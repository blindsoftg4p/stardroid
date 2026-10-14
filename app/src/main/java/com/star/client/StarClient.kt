package com.star.client

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import android.util.Log
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture

const val USER_REVISION = 6

@Serializable
data class Handshake(val user: Int)

@Serializable
data class VoiceEntry(val name: String, val provider: String = "", val engine: String = "")

@Serializable
data class VoiceList(val voices: List<VoiceEntry> = emptyList())

@Serializable
data class UserRequest(val user: Int, val request: String, val id: String)

@Serializable
data class AudioMeta(val id: String, val extension: String = "wav")

@Serializable
data class StatusMessage(
    val provider: Int? = null,
    val id: String? = null,
    val status: String? = null,
    val abort: Boolean? = null
)

/**
 * A STAR user client talking to a coagulator over a WebSocket.
 *
 * The coagulator protocol (see coagulator.py):
 *  - handshake: {"user": revision} -> answers {"voices": [...]}
 *  - synthesis: {"user": revision, "request": "<voice>: <text>", "id": <ours>}
 *  - audio reply: [2-byte LE meta length][meta][audio], where meta is either a
 *    JSON object {"id": ..., "extension": ...} or a plain request-id string.
 *  - every returned id has the shape <coagulatorClientId>_<ourId>_<fragment>,
 *    so we always extract the middle component to route audio/status back.
 */
class StarClient(private val serverUrl: String) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var voicesListener: ((List<VoiceEntry>) -> Unit)? = null
    private var audioListener: ((String, Int, ByteArray) -> Unit)? = null
    private var statusListener: ((StatusMessage) -> Unit)? = null
    private var connectedListener: (() -> Unit)? = null
    private var disconnectedListener: (() -> Unit)? = null
    private val reconnectExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "star-ws-reconnect").apply { isDaemon = true }
    }
    private var shouldReconnect = false
    private var reconnectAttempts = 0
    private var reconnectTask: ScheduledFuture<*>? = null

    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun connect() {
        if (shouldReconnect) return
        shouldReconnect = true
        openSocket()
    }

    @Synchronized
    private fun openSocket() {
        if (!shouldReconnect) return
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i("StarClient", "Connected to STAR coagulator")
                synchronized(this@StarClient) {
                    reconnectAttempts = 0
                    reconnectTask = null
                }
                webSocket.send(json.encodeToString(Handshake.serializer(), Handshake(USER_REVISION)))
                connectedListener?.invoke()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val obj = json.parseToJsonElement(text).jsonObject
                    if (obj.containsKey("voices")) {
                        val entries = mutableListOf<VoiceEntry>()
                        val arr = obj["voices"]?.jsonArray ?: return
                        for (el in arr) {
                            when (el) {
                                is JsonPrimitive -> entries += VoiceEntry(el.content)
                                is JsonElement -> {
                                    val o = el.jsonObject
                                    entries += VoiceEntry(
                                        name = o["name"]?.jsonPrimitive?.contentOrNull ?: "",
                                        provider = o["provider"]?.jsonPrimitive?.contentOrNull ?: "",
                                        engine = o["engine"]?.jsonPrimitive?.contentOrNull ?: ""
                                    )
                                }
                            }
                        }
                        if (entries.isNotEmpty()) voicesListener?.invoke(entries)
                    } else if (obj.containsKey("status")) {
                        val statusMsg = StatusMessage(
                            id = obj["id"]?.jsonPrimitive?.contentOrNull?.let { extractRequestId(it) },
                            status = obj["status"]?.jsonPrimitive?.contentOrNull,
                            abort = obj["abort"]?.jsonPrimitive?.booleanOrNull,
                            provider = obj["provider"]?.jsonPrimitive?.intOrNull
                        )
                        statusListener?.invoke(statusMsg)
                    } else if (obj.containsKey("error")) {
                        val id = obj["request_id"]?.jsonPrimitive?.contentOrNull?.let(::extractRequestId)
                        statusListener?.invoke(StatusMessage(
                            id = id,
                            status = obj["error"]?.jsonPrimitive?.contentOrNull ?: "star error",
                            abort = true
                        ))
                    } else if (obj.containsKey("warning")) {
                        val id = obj["request_id"]?.jsonPrimitive?.contentOrNull?.let(::extractRequestId)
                        statusListener?.invoke(StatusMessage(
                            id = id,
                            status = obj["warning"]?.jsonPrimitive?.contentOrNull ?: "STAR warning",
                            abort = true
                        ))
                    }
                } catch (e: Exception) {
                    // Ignore unparseable messages.
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                if (data.size < 2) return

                val metaLength = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
                if (data.size < 2 + metaLength) return

                val metaText = String(data, 2, metaLength)
                var id = metaText
                if (metaText.trimStart().startsWith("{")) {
                    try {
                        val meta = json.decodeFromString(AudioMeta.serializer(), metaText)
                        id = meta.id
                    } catch (e: Exception) {
                        // Fall through to id = metaText.
                    }
                }
                val audioData = data.sliceArray(2 + metaLength until data.size)
                audioListener?.invoke(extractRequestId(id), parseFragment(id), audioData)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("StarClient", "STAR WebSocket failed (HTTP ${response?.code ?: "no response"}): ${t.message}")
                disconnectedListener?.invoke()
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w("StarClient", "STAR WebSocket closed ($code)")
                disconnectedListener?.invoke()
                scheduleReconnect()
            }
        })
    }

    @Synchronized
    private fun scheduleReconnect() {
        if (!shouldReconnect || reconnectTask?.isDone == false) return
        val delaySeconds = (1L shl reconnectAttempts.coerceAtMost(5)).coerceAtMost(30L)
        reconnectAttempts++
        reconnectTask = reconnectExecutor.schedule({ openSocket() }, delaySeconds, TimeUnit.SECONDS)
    }

    private fun extractRequestId(full: String): String {
        val parts = full.split("_")
        return if (parts.size >= 2) parts[1] else parts[0]
    }

    private fun parseFragment(full: String): Int {
        val parts = full.split("_")
        return if (parts.size >= 3) parts[2].toIntOrNull() ?: 0 else 0
    }

    fun setOnVoicesReceived(listener: (List<VoiceEntry>) -> Unit) {
        this.voicesListener = listener
    }

    fun setOnAudioReceived(listener: (String, Int, ByteArray) -> Unit) {
        this.audioListener = listener
    }

    fun setOnStatusReceived(listener: (StatusMessage) -> Unit) {
        this.statusListener = listener
    }

    fun setOnConnected(listener: () -> Unit) {
        this.connectedListener = listener
    }

    fun setOnDisconnected(listener: () -> Unit) {
        this.disconnectedListener = listener
    }

    fun requestTts(voice: String, text: String, requestId: String): Boolean {
        val request = UserRequest(user = USER_REVISION, request = "$voice: $text", id = requestId)
        return webSocket?.send(json.encodeToString(UserRequest.serializer(), request)) == true
    }

    @Synchronized
    fun disconnect() {
        shouldReconnect = false
        reconnectTask?.cancel(false)
        reconnectTask = null
        webSocket?.close(1000, "User disconnected")
    }
}
