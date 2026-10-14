package com.star.client

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.*
import okio.ByteString
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

const val PROVIDER_REVISION = 6
const val PROVIDER_AUDIO_EXTENSION = "wav"

@Serializable
data class ProviderPacket(
    val provider: Int,
    val provider_name: String,
    val provider_id: String,
    val voices: List<ProviderVoiceEntry>
)

@Serializable
data class ProviderVoiceEntry(val name: String, val engine: String? = null)

@Serializable
data class ProviderError(val provider: Int, val id: String, val status: String, val abort: Boolean = true)

@Serializable
data class ProviderAudioMeta(val id: String, val extension: String = PROVIDER_AUDIO_EXTENSION)

/**
 * A STAR speech provider.
 *
 * This device registers all of its local Android Text-to-Speech voices with a
 * coagulator and synthesizes incoming requests locally, returning standard WAV
 * audio. Advertised voices carry the host engine so client UIs can group them.
 */
class StarProvider(
    private val context: Context,
    private val providerName: String,
    private val coagulatorUrl: String,
    private val onStatus: (String) -> Unit
) {
    private data class ProviderVoice(
        val voice: Voice,
        val enginePackage: String,
        val engineLabel: String,
        val localeLabel: String
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val providerId: String
    private val voices = mutableMapOf<String, ProviderVoice>()
    private val ttsCache = mutableMapOf<String, TextToSpeech>()
    private val canceledIds = ConcurrentHashMap.newKeySet<String>()

    private val prefs = context.getSharedPreferences("star_prefs", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()
    private val synthExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var webSocket: WebSocket? = null

    private val myself = context.packageName

    init {
        var id = prefs.getString("provider_id", null)
        if (id == null) {
            id = java.util.UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString("provider_id", id).apply()
        }
        providerId = id
    }

    fun start() {
        executor.execute {
            buildVoiceMap()
            if (voices.isEmpty()) {
                onStatus("Provider: no local TTS voices found")
                return@execute
            }
            sendStatus("Provider: advertising ${voices.size} voice(s)")
            try {
                val request = Request.Builder().url(coagulatorUrl).build()
                webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        sendVoicePacket()
                        sendStatus("Provider: connected to $coagulatorUrl")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        handleMessage(text)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        webSocket.close(1011, "connection failed")
                        sendStatus("Provider: connection lost - $coagulatorUrl")
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        sendStatus("Provider: disconnected from $coagulatorUrl")
                    }
                })
            } catch (e: Exception) {
                sendStatus("Provider: unable to start: ${e.message}")
            }
        }
    }

    private fun buildVoiceMap() {
        voices.clear()
        val engines: List<TextToSpeech.EngineInfo>
        try {
            val probe = TextToSpeech(context.applicationContext, null)
            engines = probe.engines
            probe.shutdown()
        } catch (e: Exception) {
            return
        }
        var candidates = engines.filter { it.name != myself }
        if (candidates.isEmpty()) candidates = engines

        for (engine in candidates) {
            val tts = initTts(engine.name) ?: continue
            try {
                val engineVoices = tts.voices ?: emptyList<Voice>()
                for (v in engineVoices) {
                    val label = v.name
                    val uniqueName = if (voices.containsKey(label)) {
                        "${label} (${engine.label})"
                    } else {
                        label
                    }
                    val locale = v.locale?.toLanguageTag() ?: "und"
                    voices[uniqueName] = ProviderVoice(v, engine.name, engine.label, locale)
                }
            } catch (e: Exception) {
                // Skip engine failure.
            } finally {
                tts.shutdown()
            }
            if (voices.isNotEmpty()) break
        }
    }

    private fun initTts(enginePackage: String): TextToSpeech? {
        val latch = CountDownLatch(1)
        var success = false
        var tts: TextToSpeech? = null
        try {
            tts = TextToSpeech(context.applicationContext, { status ->
                success = status == TextToSpeech.SUCCESS
                latch.countDown()
            }, enginePackage)
            if (!latch.await(4, TimeUnit.SECONDS)) {
                tts?.shutdown()
                return null
            }
            return if (success) tts else {
                tts.shutdown()
                null
            }
        } catch (e: Exception) {
            tts?.shutdown()
            return null
        }
    }

    private fun ttsFor(enginePackage: String): TextToSpeech? {
        ttsCache[enginePackage]?.let { return it }
        val tts = initTts(enginePackage) ?: return null
        ttsCache[enginePackage] = tts
        return tts
    }

    private fun sendVoicePacket() {
        val packet = ProviderPacket(
            provider = PROVIDER_REVISION,
            provider_name = providerName,
            provider_id = providerId,
            voices = voices.map { ProviderVoiceEntry(name = it.key, engine = it.value.engineLabel) }
        )
        webSocket?.send(json.encodeToString(ProviderPacket.serializer(), packet))
    }

    private fun handleMessage(text: String) {
        try {
            val obj = json.parseToJsonElement(text).jsonObject
            val abort = obj["abort"]?.jsonPrimitive?.booleanOrNull
            if (abort == true) {
                obj["id"]?.jsonPrimitive?.contentOrNull?.let { canceledIds.add(it) }
                return
            }
            val voice = obj["voice"]?.jsonPrimitive?.contentOrNull
            val textToSpeak = obj["text"]?.jsonPrimitive?.contentOrNull
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return
            if (voice != null && textToSpeak != null) {
                val rate = obj["rate"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                val pitch = obj["pitch"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                synthExecutor.execute { synthesize(voice, textToSpeak, id, rate, pitch) }
            }
        } catch (e: Exception) {
            // Ignore unparseable messages.
        }
    }

    private fun synthesize(voiceName: String, text: String, id: String, rate: Int?, pitch: Int?) {
        if (id in canceledIds) {
            canceledIds.remove(id)
            return
        }
        val pv = voices[voiceName]
        if (pv == null) {
            sendError(id, "cannot find voice $voiceName")
            return
        }
        val tts = ttsFor(pv.enginePackage)
        if (tts == null) {
            sendError(id, "no TTS engine available for $voiceName")
            return
        }

        val outFile = try {
            File(context.cacheDir, "star_provider_${System.currentTimeMillis()}_$id.wav")
        } catch (e: Exception) {
            sendError(id, "cannot create output file")
            return
        }
        val done = CountDownLatch(1)
        val latchHolder = arrayOfNulls<CountDownLatch>(1)
        latchHolder[0] = done
        var failed = false

        try {
            tts.voice = pv.voice
            tts.setSpeechRate((1.0 + (rate ?: 0) * 0.1).coerceIn(0.5, 2.0).toFloat())
            tts.setPitch((1.0 + (pitch ?: 0) * 0.05).coerceIn(0.5, 2.0).toFloat())
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    latchHolder[0]?.countDown()
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    failed = true
                    latchHolder[0]?.countDown()
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    failed = true
                    latchHolder[0]?.countDown()
                }
            })
            val result = tts.synthesizeToFile(text, null, outFile, id)
            if (result != TextToSpeech.ERROR) {
                if (!done.await(30, TimeUnit.SECONDS)) failed = true
            } else {
                failed = true
                done.countDown()
            }
        } catch (e: Exception) {
            failed = true
            done.countDown()
        }

        if (failed) {
            sendError(id, "synthesis failed for $voiceName")
        } else {
            try {
                val audio = outFile.readBytes()
                if (audio.isEmpty()) {
                    sendError(id, "empty audio for $voiceName")
                } else {
                    sendAudio(id, audio)
                }
            } catch (e: Exception) {
                sendError(id, "cannot read audio for $voiceName")
            }
        }
        runCatching { outFile.delete() }
    }

    private fun sendAudio(id: String, data: ByteArray) {
        val meta = json.encodeToString(ProviderAudioMeta.serializer(), ProviderAudioMeta(id = id))
        val metaBytes = meta.toByteArray()
        val payload = ByteArray(2 + metaBytes.size + data.size)
        payload[0] = (metaBytes.size and 0xFF).toByte()
        payload[1] = ((metaBytes.size shr 8) and 0xFF).toByte()
        metaBytes.copyInto(payload, 2)
        data.copyInto(payload, 2 + metaBytes.size)
        webSocket?.send(ByteString.of(*payload))
    }

    private fun sendError(id: String, message: String) {
        runCatching {
            webSocket?.send(
                json.encodeToString(
                    ProviderError.serializer(),
                    ProviderError(provider = PROVIDER_REVISION, id = id, status = message)
                )
            )
        }
    }

    private fun sendStatus(message: String) {
        onStatus(message)
    }

    fun stop() {
        executor.execute {
            runCatching { webSocket?.close(1000, "provider stopped") }
            webSocket = null
            ttsCache.values.forEach { runCatching { it.shutdown() } }
            ttsCache.clear()
        }
    }
}
