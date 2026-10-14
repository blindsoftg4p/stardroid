package com.star.client

import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.TimeUnit

class WavHeaderInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val pcmDataOffset: Int,
    val pcmDataSize: Long
)

class StarTtsService : TextToSpeechService() {
    private val logTag = "StarTtsService"
    private lateinit var starManager: StarManager
    override fun onCreate() {
        super.onCreate()
        starManager = StarManager.getInstance(this)
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        return TextToSpeech.LANG_AVAILABLE
    }

    override fun onGetLanguage(): Array<String> {
        return arrayOf("eng", "USA", "")
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        return TextToSpeech.LANG_AVAILABLE
    }

    override fun onStop() {
        activeEvents.forEach { it.offer(SynthesisEvent.Cancelled) }
        activeEvents.clear()
    }

    private fun parseWav(data: ByteArray): WavHeaderInfo? {
        if (data.size < 44) return null
        // RIFF magic
        if (data[0] != 'R'.code.toByte() || data[1] != 'I'.code.toByte() ||
            data[2] != 'F'.code.toByte() || data[3] != 'F'.code.toByte()) return null
        // WAVE magic
        if (data[8] != 'W'.code.toByte() || data[9] != 'A'.code.toByte() ||
            data[10] != 'V'.code.toByte() || data[11] != 'E'.code.toByte()) return null

        // Scan for "fmt " chunk
        var fmtOffset = -1
        var i = 12
        while (i <= data.size - 8) {
            if (data[i]   == 'f'.code.toByte() && data[i+1] == 'm'.code.toByte() &&
                data[i+2] == 't'.code.toByte() && data[i+3] == ' '.code.toByte()) {
                fmtOffset = i
                break
            }
            i++
        }
        if (fmtOffset == -1) return null

        // fmt chunk layout (offsets relative to chunk start):
        //  +0  "fmt "  (4 bytes)
        //  +4  chunk size (4 bytes LE)
        //  +8  audio format (2 bytes)  — 1 = PCM
        //  +10 num channels (2 bytes)
        //  +12 sample rate  (4 bytes)
        //  +16 byte rate    (4 bytes)
        //  +18 block align  (2 bytes)
        //  +22 bits/sample  (2 bytes)
        val channels      = readWord (data, fmtOffset + 10)
        val sampleRate    = readDword(data, fmtOffset + 12)
        val bitsPerSample = readWord (data, fmtOffset + 22)

        // Scan for "data" chunk
        var dataOffset = -1
        var j = fmtOffset + 8
        while (j <= data.size - 8) {
            if (data[j]   == 'd'.code.toByte() && data[j+1] == 'a'.code.toByte() &&
                data[j+2] == 't'.code.toByte() && data[j+3] == 'a'.code.toByte()) {
                dataOffset = j
                break
            }
            j++
        }
        if (dataOffset == -1) return null

        val pcmDataSize   = readDword(data, dataOffset + 4).toLong()
        val pcmDataOffset = dataOffset + 8

        return WavHeaderInfo(
            sampleRate    = sampleRate,
            channels      = channels,
            bitsPerSample = bitsPerSample,
            pcmDataOffset = pcmDataOffset,
            pcmDataSize   = pcmDataSize
        )
    }


    private fun readWord(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readDword(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
               ((data[offset + 1].toInt() and 0xFF) shl 8) or
               ((data[offset + 2].toInt() and 0xFF) shl 16) or
               ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    private sealed class SynthesisEvent {
        data class Audio(val bytes: ByteArray) : SynthesisEvent()
        data class Status(val value: StatusMessage) : SynthesisEvent()
        object Cancelled : SynthesisEvent()
    }

    private val activeEvents = java.util.concurrent.ConcurrentHashMap.newKeySet<java.util.concurrent.BlockingQueue<SynthesisEvent>>()

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val text = request.charSequenceText.toString()
        val preferredVoice = starManager.selectedVoice.value
        val voiceDeadline = System.currentTimeMillis() + 30_000L
        var voice: StarVoice? = null

        while (System.currentTimeMillis() < voiceDeadline) {
            val states = starManager.connectionStates.value
            voice = when {
                preferredVoice != null && states[preferredVoice.serverUrl] == ConnState.CONNECTED -> preferredVoice
                preferredVoice == null -> starManager.voices.value.firstOrNull { states[it.serverUrl] == ConnState.CONNECTED }
                else -> starManager.voices.value.firstOrNull {
                    it.name == preferredVoice.name && states[it.serverUrl] == ConnState.CONNECTED
                }
            }
            if (voice != null) break
            try { Thread.sleep(200) } catch (_: InterruptedException) {
                callback.error()
                return
            }
        }

        if (voice == null) {
            Log.e(logTag, "No connected server has the selected voice")
            callback.error()
            return
        }

        val events = java.util.concurrent.LinkedBlockingQueue<SynthesisEvent>()
        activeEvents.add(events)
        var started = false
        var expectedBytes = -1L
        var receivedBytes = 0L
        val initialAudio = ByteArrayOutputStream()
        var pcmFrameBytes = 2
        var requestId: String? = null

        fun startAudio(sampleRate: Int, encoding: Int, channels: Int) {
            val result = callback.start(sampleRate, encoding, channels)
            if (result != TextToSpeech.SUCCESS) throw IllegalStateException("TTS callback rejected audio start: $result")
            pcmFrameBytes = (if (encoding == android.media.AudioFormat.ENCODING_PCM_8BIT) 1 else 2) * channels
            started = true
        }

        fun writeAudio(bytes: ByteArray, offset: Int, length: Int) {
            if (length <= 0) return
            val maxBufferSize = callback.maxBufferSize
            val chunkLimit = maxBufferSize - (maxBufferSize % pcmFrameBytes)
            if (chunkLimit <= 0) throw IllegalStateException("TTS callback buffer is too small")
            var position = offset
            val end = offset + length
            while (position < end) {
                val count = minOf(chunkLimit, end - position)
                val result = callback.audioAvailable(bytes, position, count)
                if (result != TextToSpeech.SUCCESS) throw IllegalStateException("TTS callback rejected audio: $result")
                position += count
            }
            receivedBytes += length
        }

        try {
            requestId = starManager.requestTts(voice, text,
                onAudioReceived = { _, data -> events.offer(SynthesisEvent.Audio(data)) },
                onStatusReceived = { status -> events.offer(SynthesisEvent.Status(status)) }
            )
            if (requestId == null) {
                Log.e(logTag, "Could not dispatch synthesis request")
                callback.error()
                return
            }
            Log.i(logTag, "Dispatched synthesis request ${requestId!!}")

            val deadline = System.currentTimeMillis() + 20_000L
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    Log.e(logTag, "Synthesis request timed out")
                    callback.error()
                    return
                }
                when (val event = events.poll(remaining, TimeUnit.MILLISECONDS)) {
                    null -> {
                        Log.e(logTag, "Synthesis request timed out")
                        callback.error()
                        return
                    }
                    is SynthesisEvent.Cancelled -> return
                    is SynthesisEvent.Status -> {
                        val status = event.value
                        Log.w(logTag, "Server status for request ${requestId!!}: ${status.status ?: "unspecified"}")
                        if (status.abort == true || status.status?.lowercase(Locale.US)?.contains("error") == true) {
                            Log.e(logTag, "Synthesis request failed: ${status.status ?: "aborted"}")
                            callback.error()
                            return
                        }
                        if (status.status?.lowercase(Locale.US)?.let { it.contains("done") || it.contains("completed") } == true) {
                            if (started) callback.done() else callback.error()
                            return
                        }
                    }
                    is SynthesisEvent.Audio -> {
                        val data = event.bytes
                        if (data.isEmpty()) continue
                        Log.d(logTag, "Received ${data.size} audio bytes for request ${requestId!!}")
                        if (!started) {
                            initialAudio.write(data)
                            val buffered = initialAudio.toByteArray()
                            val isWav = buffered.size >= 4 && buffered[0] == 'R'.code.toByte() &&
                                buffered[1] == 'I'.code.toByte() && buffered[2] == 'F'.code.toByte() && buffered[3] == 'F'.code.toByte()
                            val wav = parseWav(buffered)
                            if (wav == null && isWav) continue
                            if (wav != null) {
                                val encoding = if (wav.bitsPerSample == 8) android.media.AudioFormat.ENCODING_PCM_8BIT
                                    else android.media.AudioFormat.ENCODING_PCM_16BIT
                                startAudio(wav.sampleRate, encoding, wav.channels)
                                expectedBytes = wav.pcmDataSize
                                writeAudio(buffered, wav.pcmDataOffset, buffered.size - wav.pcmDataOffset)
                                initialAudio.reset()
                            } else {
                                Log.w(logTag, "Audio has no WAV header; treating it as raw 22050 Hz mono PCM")
                                startAudio(22050, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)
                                writeAudio(buffered, 0, buffered.size)
                                initialAudio.reset()
                            }
                        } else {
                            writeAudio(data, 0, data.size)
                        }
                        if (expectedBytes >= 0 && receivedBytes >= expectedBytes) {
                            callback.done()
                            return
                        }
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            callback.error()
        } catch (e: Exception) {
            Log.e(logTag, "Failed to deliver synthesized audio", e)
            callback.error()
        } finally {
            requestId?.let(starManager::clearTtsRequest)
            activeEvents.remove(events)
        }
    }
}
