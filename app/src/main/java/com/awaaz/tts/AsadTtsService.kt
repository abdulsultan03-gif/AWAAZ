package com.awaaz.tts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import java.io.IOException
import java.util.Locale

class AsadTtsService : TextToSpeechService() {

    companion object {
        const val ASAD = "ur-PK-AsadNeural"
        const val UZMA = "ur-PK-UzmaNeural"
        const val SALMAN = "ur-IN-SalmanNeural"
        const val GUL = "ur-IN-GulNeural"
        private val NAMES = setOf(ASAD, UZMA, SALMAN, GUL)
        private const val MAX_CHUNK = 600

        private fun isUrdu(lang: String?) =
            lang.equals("urd", true) || lang.equals("ur", true)

        private fun fmt(v: Int, unit: String) = (if (v >= 0) "+" else "") + v + unit

        fun chunkText(text: String): List<String> {
            val out = ArrayList<String>()
            val cur = StringBuilder()
            for (part in text.split(Regex("(?<=[۔؟!?.\\n])"))) {
                if (cur.length + part.length > MAX_CHUNK && cur.isNotEmpty()) {
                    out.add(cur.toString()); cur.setLength(0)
                }
                cur.append(part)
                while (cur.length > MAX_CHUNK) {
                    out.add(cur.substring(0, MAX_CHUNK)); cur.delete(0, MAX_CHUNK)
                }
            }
            if (cur.isNotBlank()) out.add(cur.toString())
            return out.filter { it.isNotBlank() }
        }
    }

    @Volatile
    private var stopFlag = false

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        if (!isUrdu(lang)) return TextToSpeech.LANG_NOT_SUPPORTED
        return if (country.equals("PAK", true) || country.equals("PK", true) ||
            country.equals("IND", true) || country.equals("IN", true))
            TextToSpeech.LANG_COUNTRY_AVAILABLE else TextToSpeech.LANG_AVAILABLE
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int =
        onIsLanguageAvailable(lang, country, variant)

    override fun onGetLanguage(): Array<String> = arrayOf("urd", "PAK", "")

    override fun onGetVoices(): MutableList<Voice> = mutableListOf(
        Voice(ASAD, Locale("ur", "PK"), Voice.QUALITY_VERY_HIGH, Voice.LATENCY_NORMAL, true, emptySet()),
        Voice(UZMA, Locale("ur", "PK"), Voice.QUALITY_VERY_HIGH, Voice.LATENCY_NORMAL, true, emptySet()),
        Voice(SALMAN, Locale("ur", "IN"), Voice.QUALITY_VERY_HIGH, Voice.LATENCY_NORMAL, true, emptySet()),
        Voice(GUL, Locale("ur", "IN"), Voice.QUALITY_VERY_HIGH, Voice.LATENCY_NORMAL, true, emptySet()),
    )

    override fun onIsValidVoiceName(name: String?): Int =
        if (name in NAMES) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(name: String?): Int = onIsValidVoiceName(name)

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String =
        if (isUrdu(lang)) ASAD else ""

    override fun onStop() {
        stopFlag = true
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        stopFlag = false
        val text = (request.charSequenceText ?: "").toString()
        callback.start(EdgeTts.SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
        if (text.isBlank()) {
            callback.done()
            return
        }

        val voice = if (request.voiceName in NAMES) request.voiceName else ASAD
        val rate = fmt((request.speechRate - 100).coerceIn(-50, 100), "%")
        val pitch = fmt(((request.pitch - 100) / 5).coerceIn(-30, 30), "Hz")
        val maxBuf = callback.maxBufferSize

        // Keep PCM 16-bit aligned if a network frame ends mid-sample.
        var carry: Byte? = null

        try {
            for (piece in chunkText(text)) {
                if (stopFlag) return
                EdgeTts.stream(piece, voice, rate, pitch, { stopFlag }) { incoming ->
                    var data = incoming
                    if (carry != null) {
                        data = byteArrayOf(carry!!) + data
                        carry = null
                    }
                    if (data.size % 2 != 0) {
                        carry = data[data.size - 1]
                        data = data.copyOf(data.size - 1)
                    }
                    var off = 0
                    while (off < data.size && !stopFlag) {
                        val n = minOf(maxBuf, data.size - off)
                        if (callback.audioAvailable(data, off, n) != TextToSpeech.SUCCESS) {
                            stopFlag = true
                            break
                        }
                        off += n
                    }
                }
            }
            if (!stopFlag) callback.done()
        } catch (e: IOException) {
            callback.error()
        }
    }
}
