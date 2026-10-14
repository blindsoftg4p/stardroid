package com.star.client

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

/** Handles the system's TTS engine checks; STAR fetches speech remotely. */
class TtsEngineCheckActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val result = Intent()
        when (intent?.action) {
            TextToSpeech.Engine.ACTION_CHECK_TTS_DATA -> {
                val requested = intent.getStringArrayListExtra(
                    TextToSpeech.Engine.EXTRA_CHECK_VOICE_DATA_FOR
                )?.filter { it.isNotBlank() }.orEmpty().ifEmpty { listOf("eng-USA") }
                val available = requested.filter { it == "eng-USA" }
                val unavailable = requested.filterNot { it == "eng-USA" }
                result.putStringArrayListExtra(
                    TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES,
                    ArrayList(available)
                )
                result.putStringArrayListExtra(
                    TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES,
                    ArrayList(unavailable)
                )
                val resultCode = when {
                    unavailable.isEmpty() -> TextToSpeech.Engine.CHECK_VOICE_DATA_PASS
                    available.isEmpty() -> TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL
                    else -> TextToSpeech.Engine.CHECK_VOICE_DATA_MISSING_DATA
                }
                setResult(resultCode, result)
            }

            TextToSpeech.Engine.ACTION_GET_SAMPLE_TEXT -> {
                result.putExtra(
                    TextToSpeech.Engine.EXTRA_SAMPLE_TEXT,
                    getString(R.string.tts_sample_text)
                )
                setResult(RESULT_OK, result)
            }

            else -> setResult(RESULT_CANCELED)
        }
        finish()
    }
}
