package com.star.client

import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WavHeaderInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val pcmDataOffset: Int,
    val pcmDataSize: Int
)

class StarTtsService : TextToSpeechService() {
    private lateinit var starManager: StarManager
    private val activeLatches = java.util.concurrent.ConcurrentHashMap.newKeySet<CountDownLatch>()

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
        activeLatches.forEach { it.countDown() }
        activeLatches.clear()
    }

    private fun parseWav(data: ByteArray): WavHeaderInfo? {
        if (data.size < 44) return null
        if (data[0] != 'R'.code.toByte() || data[1] != 'I'.code.toByte() || data[2] != 'F'.code.toByte() || data[3] != 'F'.code.toByte()) {
            return null
        }
        if (data[8] != 'W'.code.toByte() || data[9] != 'A'.code.toByte() || data[10] != 'V'.code.toByte() || data[11] != 'E'.code.toByte()) {
            return null
        }
        
        // Find "fmt " chunk
        var fmtOffset = -1
        for (i in 12 until data.size - 8) {
            if (data[i] == 'f'.code.toByte() && data[i+1] == 'm'.code.toByte() && data[i+2] == 't'.code.toByte() && data[i+3] == ' '.code.toByte()) {
                fmtOffset = i
                break
            }
        }
        if (fmtOffset == -1) return null
        
        val channels = readWord(data, fmtOffset + 8)
        val sampleRate = readDword(data, fmtOffset + 10)
        val bitsPerSample = readWord(data, fmtOffset + 22)
        
        // Find "data" chunk
        var dataOffset = -1
        for (i in (fmtOffset + 8) until data.size - 8) {
            if (data[i] == 'd'.code.toByte() && data[i+1] == 'a'.code.toByte() && data[i+2] == 't'.code.toByte() && data[i+3] == 'a'.code.toByte()) {
                dataOffset = i
                break
            }
        }
        if (dataOffset == -1) return null
        
        val pcmDataSize = readDword(data, dataOffset + 4)
        val pcmDataOffset = dataOffset + 8
        
        return WavHeaderInfo(
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = bitsPerSample,
            pcmDataOffset = pcmDataOffset,
            pcmDataSize = pcmDataSize
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

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val text = request.charSequenceText.toString()
        val voice = starManager.selectedVoice.value ?: starManager.voices.value.firstOrNull()
        
        if (voice == null) {
            callback.error()
            return
        }

        val latch = CountDownLatch(1)
        activeLatches.add(latch)
        var started = false
        var expectedBytes = -1
        var receivedBytes = 0

        try {
            starManager.requestTts(voice, text,
                onAudioReceived = { frag, data ->
                    if (data.isEmpty()) return@requestTts
                    try {
                        if (!started) {
                            val wavInfo = parseWav(data)
                            if (wavInfo != null) {
                                val sampleRate = wavInfo.sampleRate
                                val channels = wavInfo.channels
                                val encoding = if (wavInfo.bitsPerSample == 8) {
                                    android.media.AudioFormat.ENCODING_PCM_8BIT
                                } else {
                                    android.media.AudioFormat.ENCODING_PCM_16BIT
                                }
                                callback.start(sampleRate, encoding, channels)
                                started = true
                                expectedBytes = wavInfo.pcmDataSize
                                
                                val offset = wavInfo.pcmDataOffset
                                if (data.size > offset) {
                                    val pcmData = data.sliceArray(offset until data.size)
                                    callback.audioAvailable(pcmData, 0, pcmData.size)
                                    receivedBytes += pcmData.size
                                }
                            } else {
                                // Default fallback: 22050 Hz Mono 16-bit PCM
                                callback.start(22050, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)
                                started = true
                                callback.audioAvailable(data, 0, data.size)
                                receivedBytes += data.size
                            }
                        } else {
                            callback.audioAvailable(data, 0, data.size)
                            receivedBytes += data.size
                        }

                        // Complete check based on WAV header size
                        if (expectedBytes > 0 && receivedBytes >= expectedBytes) {
                            latch.countDown()
                        }
                    } catch (e: Exception) {
                        callback.error()
                        latch.countDown()
                    }
                },
                onStatusReceived = { statusMsg ->
                    if (statusMsg.abort == true || statusMsg.status?.lowercase(Locale.US)?.contains("error") == true) {
                        callback.error()
                        latch.countDown()
                    } else if (statusMsg.status?.lowercase(Locale.US)?.contains("done") == true ||
                               statusMsg.status?.lowercase(Locale.US)?.contains("completed") == true) {
                        latch.countDown()
                    }
                }
            )

            // Block synthesis thread with 30 seconds max timeout
            latch.await(30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            callback.error()
        } finally {
            activeLatches.remove(latch)
            callback.done()
        }
    }
}
