package com.awaaz.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Test screen + shortcut to the phone's Text-to-speech settings. */
class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var ready = false
    private lateinit var input: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Awaaz TTS — Asad / Uzma\n\n" +
                "1) Neeche 'Open TTS settings' dabao.\n" +
                "2) Preferred engine me 'Awaaz TTS (Asad)' chuno.\n" +
                "3) Internet chahiye (Microsoft voices online hain)."
            textSize = 15f
        })

        input = EditText(this).apply {
            setText("السلام علیکم، میں اسد ہوں۔")
            textSize = 20f
            minLines = 4
            gravity = Gravity.TOP or Gravity.END
            textDirection = View.TEXT_DIRECTION_RTL
        }
        root.addView(input)

        root.addView(Button(this).apply {
            text = "Test: Asad (مرد)"
            setOnClickListener { speak(AsadTtsService.ASAD) }
        })
        root.addView(Button(this).apply {
            text = "Test: Uzma (خاتون)"
            setOnClickListener { speak(AsadTtsService.UZMA) }
        })
        root.addView(Button(this).apply {
            text = "Test: Salman (مرد، India)"
            setOnClickListener { speak(AsadTtsService.SALMAN) }
        })
        root.addView(Button(this).apply {
            text = "Test: Gul (خاتون، India)"
            setOnClickListener { speak(AsadTtsService.GUL) }
        })
        root.addView(Button(this).apply {
            text = "Open TTS settings"
            setOnClickListener {
                try {
                    startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
        })

        status = TextView(this).apply { text = "Starting engine..." }
        root.addView(status)

        setContentView(ScrollView(this).apply { addView(root) })

        tts = TextToSpeech(this, this, packageName)
    }

    override fun onInit(code: Int) {
        ready = code == TextToSpeech.SUCCESS
        status.text = if (ready) "Ready." else "Engine failed to start."
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                runOnUiThread { status.text = "Done." }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                runOnUiThread { status.text = "Error: " + EdgeTts.lastError }
            }
        })
    }

    private fun speak(voiceName: String) {
        val engine = tts
        if (!ready || engine == null) {
            status.text = "Engine not ready."
            return
        }
        val v = engine.voices?.firstOrNull { it.name == voiceName }
        if (v != null) engine.voice = v
        status.text = "Generating..."
        engine.speak(input.text.toString(), TextToSpeech.QUEUE_FLUSH, null, "test")
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}
