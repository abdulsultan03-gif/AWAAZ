package com.awaaz.tts

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Blocking client for Microsoft Edge's read-aloud TTS WebSocket. Output: 24 kHz, 16-bit, mono PCM. */
object EdgeTts {
    private const val TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val CHROMIUM = "143.0.3650.75"
    private const val WSS =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"

    /** Last failure reason, shown in the test screen. */
    @Volatile
    var lastError: String = ""

    const val SAMPLE_RATE = 24000

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** Server clock minus phone clock, learned from a 403 response (Microsoft rejects skewed clocks). */
    @Volatile
    private var skewSec = 0L

    private class SkewException : IOException("Clock skew, retry")

    private val END = Any()

    private fun uuid() = UUID.randomUUID().toString().replace("-", "")

    private fun gec(): String = Drm.secMsGec(System.currentTimeMillis() / 1000 + skewSec)

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private fun timestamp(): String {
        val f = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(java.util.Date())
    }

    /**
     * Synthesizes [text] and passes raw PCM pieces to [onPcm] as they arrive.
     * [rate] like "+0%", [pitch] like "+0Hz". Blocks until finished, stopped or failed.
     */
    @Throws(IOException::class)
    fun stream(
        text: String,
        voice: String,
        rate: String,
        pitch: String,
        stopped: () -> Boolean,
        onPcm: (ByteArray) -> Unit,
    ) {
        var attempt = 0
        while (true) {
            try {
                streamOnce(text, voice, rate, pitch, stopped, onPcm)
                lastError = ""
                return
            } catch (e: SkewException) {
                if (attempt++ >= 1) {
                    lastError = "HTTP 403 - Microsoft ne connection reject kiya (phone ka date/time check karo)"
                    throw IOException(lastError)
                }
            } catch (e: IOException) {
                lastError = e.message ?: "unknown error"
                throw e
            }
        }
    }

    private fun streamOnce(
        text: String,
        voice: String,
        rate: String,
        pitch: String,
        stopped: () -> Boolean,
        onPcm: (ByteArray) -> Unit,
    ) {
        val queue = LinkedBlockingQueue<Any>()
        val mp3 = java.io.ByteArrayOutputStream()
        val trace = StringBuilder()
        fun note(s: String) {
            synchronized(trace) { if (trace.length < 400) trace.append(s).append("; ") }
        }
        val url = "$WSS?TrustedClientToken=$TOKEN&ConnectionId=${uuid()}" +
            "&Sec-MS-GEC=${gec()}&Sec-MS-GEC-Version=2-$CHROMIUM"
        val request = Request.Builder()
            .url(url)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("User-Agent", UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=${uuid().uppercase()};")
            .build()

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                note("open")
                val ts = timestamp()
                webSocket.send(
                    "X-Timestamp:$ts\r\nContent-Type:application/json; charset=utf-8\r\n" +
                        "Path:speech.config\r\n\r\n" +
                        "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
                        "{\"sentenceBoundaryEnabled\":\"true\",\"wordBoundaryEnabled\":\"false\"}," +
                        "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"
                )
                val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
                    "<voice name='$voice'><prosody pitch='$pitch' rate='$rate' volume='+0%'>" +
                    esc(text) + "</prosody></voice></speak>"
                webSocket.send(
                    "X-RequestId:${uuid()}\r\nContent-Type:application/ssml+xml\r\n" +
                        "X-Timestamp:${ts}Z\r\nPath:ssml\r\n\r\n$ssml"
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val path = Regex("Path:([\\w.]+)").find(text)?.groupValues?.get(1) ?: text.take(40)
                note("text:$path")
                if (text.contains("Path:turn.end")) {
                    queue.put(END)
                    webSocket.close(1000, null)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val b = bytes.toByteArray()
                if (b.size < 2) return
                val headLen = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
                if (b.size <= 2 + headLen) return
                val head = String(b, 2, headLen, Charsets.UTF_8)
                if (head.contains("Path:audio")) {
                    queue.put(b.copyOfRange(2 + headLen, b.size))
                } else {
                    note("bin:" + head.take(30).replace("\r\n", " "))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (response?.code == 403) {
                    val date = response.header("Date")
                    if (date != null) {
                        try {
                            val f = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                            val server = f.parse(date)?.time ?: 0L
                            if (server > 0) skewSec = server / 1000 - System.currentTimeMillis() / 1000
                        } catch (e: Exception) {
                        }
                    }
                    queue.put(SkewException())
                } else {
                    queue.put(IOException("HTTP ${response?.code ?: "-"}: ${t.message}", t))
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                note("closed:$code $reason")
                queue.put(END)
            }
        })

        var lastActivity = System.currentTimeMillis()
        try {
            while (true) {
                if (stopped()) return
                val item = queue.poll(200, TimeUnit.MILLISECONDS)
                if (item == null) {
                    if (System.currentTimeMillis() - lastActivity > 20_000) {
                        throw IOException("Timeout | " + synchronized(trace) { trace.toString() })
                    }
                    continue
                }
                lastActivity = System.currentTimeMillis()
                when (item) {
                    is ByteArray -> mp3.write(item)
                    is SkewException -> throw item
                    is Throwable -> throw IOException(item.message ?: "Connection failed", item)
                    else -> {
                        if (mp3.size() == 0) {
                            throw IOException("Awaaz nahi aayi | " + synchronized(trace) { trace.toString() })
                        }
                        try {
                            Mp3Decoder.decode(mp3.toByteArray(), stopped, onPcm)
                        } catch (e: Exception) {
                            throw IOException("Decode error: ${e.message}", e)
                        }
                        return
                    }
                }
            }
        } finally {
            ws.cancel()
        }
    }
}
