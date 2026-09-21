package com.subarabify.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes a video's audio track to 16 kHz mono 16-bit PCM and streams it
 * chunk-by-chunk to [onPcm], so speech-to-text never holds a whole movie
 * in memory. Works with SAF Uris (no FFmpeg on Android).
 */
object AudioExtractor {

    private const val TAG = "AudioExtractor"
    const val TARGET_RATE = 16000

    data class AudioInfo(val durationUs: Long, val srcRate: Int, val channels: Int)

    /**
     * @return list of decoded PCM chunks is NOT built — samples stream via callback.
     */
    fun decode(
        context: Context,
        videoUri: Uri,
        onPcm: (shorts: ShortArray, len: Int) -> Unit,
        onProgress: ((decodedUs: Long, totalUs: Long) -> Unit)? = null,
    ): AudioInfo? {
        var pfd: ParcelFileDescriptor? = null
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            pfd = context.contentResolver.openFileDescriptor(videoUri, "r") ?: return null
            extractor = MediaExtractor()
            extractor.setDataSource(pfd.fileDescriptor)

            var trackIdx = -1
            var mime: String? = null
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (m.startsWith("audio/")) {
                    trackIdx = i
                    mime = m
                    format = f
                    break
                }
            }
            if (trackIdx < 0 || mime == null || format == null) {
                Log.w(TAG, "No audio track")
                return null
            }
            extractor.selectTrack(trackIdx)

            val srcRate = format.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44100)
            val channels = format.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2)
            val durationUs = format.getLongOrDefault(MediaFormat.KEY_DURATION, 0L)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val resampler = Resampler(srcRate, channels)
            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            var decodedUs = 0L
            var outIsFloat = false
            val pcm16 = ShortArray(8192)

            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(inBuf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, extractor.sampleFlags)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)!!
                        drainBuffer(outBuf, info, outIsFloat, resampler, pcm16, onPcm)
                        decodedUs = info.presentationTimeUs
                        onProgress?.invoke(decodedUs, durationUs)
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEos = true
                        }
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        try {
                            val outFmt = codec.outputFormat
                            outIsFloat = outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING) ==
                                android.media.AudioFormat.ENCODING_PCM_FLOAT
                        } catch (_: Exception) { }
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (sawInputEos) sawOutputEos = true // defensive: no more output coming
                    }
                }
            }
            onProgress?.invoke(durationUs, durationUs)
            return AudioInfo(durationUs, srcRate, channels)
        } catch (e: Exception) {
            Log.e(TAG, "Audio decode failed", e)
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            try { codec?.release() } catch (_: Exception) { }
            try { extractor?.release() } catch (_: Exception) { }
            try { pfd?.close() } catch (_: Exception) { }
        }
    }

    private fun drainBuffer(
        buf: ByteBuffer,
        info: MediaCodec.BufferInfo,
        isFloat: Boolean,
        resampler: Resampler,
        scratch: ShortArray,
        onPcm: (ShortArray, Int) -> Unit,
    ) {
        buf.order(ByteOrder.nativeOrder())
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        val shorts = if (isFloat) {
            val fb = buf.asFloatBuffer()
            val floats = FloatArray(info.size / 4)
            fb.get(floats)
            ShortArray(floats.size) { (floats[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
        } else {
            // Decoder outputs 16-bit PCM (most Android AAC decoders do)
            val s = ShortArray(info.size / 2)
            buf.asShortBuffer().get(s)
            s
        }
        var off = 0
        while (off < shorts.size) {
            val n = resampler.push(shorts, off, shorts.size - off, scratch)
            if (n > 0) onPcm(scratch, n)
            off += resampler.consumed
            if (resampler.consumed == 0) break
        }
        val tail = resampler.flush(scratch)
        if (tail > 0) onPcm(scratch, tail)
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, def: Int): Int {
        return try { getInteger(key) } catch (_: Exception) { def }
    }

    private fun MediaFormat.getLongOrDefault(key: String, def: Long): Long {
        return try { getLong(key) } catch (_: Exception) { def }
    }

    /** Minimal linear-interpolation resampler: any rate/channels → 16 kHz mono. */
    private class Resampler(private val srcRate: Int, private val channels: Int) {
        var consumed = 0
            private set
        private var fracPos = 0.0 // position in source frames (relative to [base, ...))
        private val pending = ArrayList<Float>(65536)
        private var base = 0 // consumed prefix (compacted lazily)

        fun push(src: ShortArray, off: Int, len: Int, out: ShortArray): Int {
            // to mono floats
            var i = off
            val end = off + len
            while (i < end) {
                var sum = 0f
                var c = 0
                while (c < channels && i + c < end) {
                    sum += src[i + c] / 32768f
                    c++
                }
                pending.add(if (c > 0) sum / c else 0f)
                i += channels
            }
            consumed = len - (len % channels)
            return emit(out)
        }

        fun flush(out: ShortArray): Int = emit(out)

        private fun emit(out: ShortArray): Int {
            val step = srcRate.toDouble() / TARGET_RATE
            var n = 0
            while (n < out.size) {
                val idx = base + fracPos.toInt()
                if (idx + 1 >= pending.size) break
                val frac = (fracPos - fracPos.toInt()).toFloat()
                val s = pending[idx] * (1 - frac) + pending[idx + 1] * frac
                out[n++] = (s.coerceIn(-1f, 1f) * 32767).toInt().toShort()
                fracPos += step
            }
            val drop = fracPos.toInt().coerceAtMost(pending.size - base)
            base += drop
            fracPos -= drop
            if (base > 32768) {
                pending.subList(0, base).clear()
                base = 0
            }
            return n
        }
    }
}
