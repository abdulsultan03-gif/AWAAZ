package com.awaaz.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

/** Tells the system which voices this engine has (Urdu / Pakistan). */
class CheckVoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = Intent()
        result.putStringArrayListExtra(
            TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, arrayListOf("urd-PAK")
        )
        result.putStringArrayListExtra(
            TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, arrayListOf()
        )
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, result)
        finish()
    }
}
