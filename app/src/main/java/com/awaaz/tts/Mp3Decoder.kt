package com.awaaz.tts

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat

/** Decodes an in-memory MP3 to 16-bit PCM using the phone's built-in decoder. */
object Mp3Decoder {

    fun decode(mp3: ByteArray, stopped: () -> Boolean, onPcm: (ByteArray) -> Unit) {
        val extractor = MediaExtractor()
        extractor.setDataSource(object : MediaDataSource() {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (position >= mp3.size) return -1
                val n = minOf(size.toLong(), mp3.size - position).toInt()
                System.arraycopy(mp3, position.toInt(), buffer, offset, n)
                return n
            }

            override fun getSize(): Long = mp3.size.toLong()
            override fun close() {}
        })

        var track = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                track = i
                format = f
                break
            }
        }
        if (track < 0 || format == null) {
            extractor.release()
            throw IllegalStateException("MP3 track not found")
        }
        extractor.selectTrack(track)

        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone && !stopped()) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        val out = codec.getOutputBuffer(outIdx)!!
                        val pcm = ByteArray(info.size)
                        out.position(info.offset)
                        out.get(pcm)
                        onPcm(pcm)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
    }
}
