package com.star.client

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.*
import okio.ByteString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Serializable
data class Handshake(val user: Int, val request: List<String>? = null)

@Serializable
data class VoiceList(val voices: List<String>)

@Serializable
data class TtsRequest(val voice: String, val text: String, val id: String)

@Serializable
data class StatusMessage(
    val provider: Int? = null,
    val id: String? = null,
    val status: String? = null,
    val abort: Boolean? = null
)

class StarClient(private val serverUrl: String) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    
    private var webSocket: WebSocket? = null
    private var voicesListener: ((List<String>) -> Unit)? = null
    private var audioListener: ((String, Int, ByteArray) -> Unit)? = null
    private var statusListener: ((StatusMessage) -> Unit)? = null
    
    private val json = Json { ignoreUnknownKeys = true }

    fun connect() {
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Send handshake for voices
                val handshake = Handshake(user = 4)
                webSocket.send(json.encodeToString(Handshake.serializer(), handshake))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    if (text.contains("\"voices\"")) {
                        val voiceList = json.decodeFromString(VoiceList.serializer(), text)
                        voicesListener?.invoke(voiceList.voices)
                    } else {
                        val statusMsg = json.decodeFromString(StatusMessage.serializer(), text)
                        statusListener?.invoke(statusMsg)
                    }
                } catch (e: Exception) {
                    // Handle other JSON messages or errors
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                if (data.size < 2) return
                
                // Read 2-byte Little Endian ID Length
                val idLength = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
                if (data.size < 2 + idLength) return
                
                val idStr = String(data, 2, idLength)
                val audioData = data.sliceArray(2 + idLength until data.size)
                
                // Parse idStr: usually "clientId_fragmentId"
                val parts = idStr.split("_")
                val requestId = parts[0]
                val fragmentId = parts.getOrNull(1)?.toIntOrNull() ?: 0
                
                audioListener?.invoke(requestId, fragmentId, audioData)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Handle failure
            }
        })
    }

    fun setOnVoicesReceived(listener: (List<String>) -> Unit) {
        this.voicesListener = listener
    }

    fun setOnAudioReceived(listener: (String, Int, ByteArray) -> Unit) {
        this.audioListener = listener
    }

    fun setOnStatusReceived(listener: (StatusMessage) -> Unit) {
        this.statusListener = listener
    }

    fun requestTts(voice: String, text: String, requestId: String) {
        val request = TtsRequest(voice = voice, text = text, id = requestId)
        webSocket?.send(json.encodeToString(TtsRequest.serializer(), request))
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
    }
}
