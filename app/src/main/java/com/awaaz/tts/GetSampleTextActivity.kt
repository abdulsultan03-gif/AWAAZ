package com.awaaz.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

/** Provides the sample sentence shown by "Listen to an example" in system TTS settings. */
class GetSampleTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = Intent()
        result.putExtra("sampleText", "السلام علیکم، یہ اردو آواز کا نمونہ ہے۔")
        setResult(TextToSpeech.LANG_AVAILABLE, result)
        finish()
    }
}
