package com.example.ytsaver

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import kotlin.math.min

/** Interleaved 16-bit audio plus what the fingerprint lookup needs to know about it. */
class Pcm(val samples: ShortArray, val sampleRate: Int, val channels: Int, val durationSec: Int)

object AudioCapture {
    /** AcoustID indexes the first two minutes of a track, so that is all a file needs to be fingerprinted. */
    const val FILE_SECONDS = 120

    /**
     * Blocking: decodes the first [maxSeconds] of any audio or video file (or content link) to PCM.
     * Returns null when the file has no readable audio.
     */
    fun decode(ctx: Context, uri: Uri, maxSeconds: Int = FILE_SECONDS): Pcm? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) return null
            ex.selectTrack(track)
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durSec = if (fmt.containsKey(MediaFormat.KEY_DURATION)) (fmt.getLong(MediaFormat.KEY_DURATION) / 1_000_000L).toInt() else 0

            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME) ?: return null)
            codec.configure(fmt, null, null, 0)
            codec.start()

            var out = ShortArray(rate * ch * min(maxSeconds, 30))
            var n = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii) ?: throw IllegalStateException("decoder gave no input buffer")
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    oi >= 0 -> {
                        if (info.size > 0) {
                            val bb = codec.getOutputBuffer(oi) ?: throw IllegalStateException("decoder gave no output buffer")
                            bb.position(info.offset)
                            bb.limit(info.offset + info.size)
                            val sb = bb.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                            val count = sb.remaining()
                            val limit = rate * ch * maxSeconds
                            val take = min(count, limit - n)
                            if (n + take > out.size) out = out.copyOf(min(limit, maxOf(out.size * 2, n + take)))
                            sb.get(out, n, take)
                            n += take
                            if (n >= limit) outputDone = true
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val nf = codec.outputFormat
                        rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }
            if (n == 0) return null
            return Pcm(out.copyOf(n), rate, ch, if (durSec > 0) durSec else n / (rate * ch))
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { ex.release() }
        }
    }

    /**
     * Blocking: records [seconds] from the microphone (needs the RECORD_AUDIO permission).
     * [onProgress] gets 0..1; [cancelled] is polled so the Stop button (or a match) ends the recording early.
     * [onCheckpoint] gets a copy of everything heard so far each time a second count in [checkpoints] is passed,
     * while the recording goes on.
     */
    @SuppressLint("MissingPermission")
    fun record(
        seconds: Int, onProgress: (Float) -> Unit, cancelled: () -> Boolean,
        checkpoints: IntArray = IntArray(0), onCheckpoint: ((Pcm) -> Unit)? = null,
    ): Pcm? {
        val rate = 44_100
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, rate),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return null }
        val total = rate * seconds
        val out = ShortArray(total)
        var n = 0
        var cp = 0
        try {
            rec.startRecording()
            val chunk = ShortArray(4096)
            while (n < total && !cancelled()) {
                val r = rec.read(chunk, 0, min(chunk.size, total - n))
                if (r <= 0) break
                System.arraycopy(chunk, 0, out, n, r)
                n += r
                onProgress(n.toFloat() / total)
                if (onCheckpoint != null && cp < checkpoints.size && n >= rate * checkpoints[cp]) {
                    onCheckpoint(Pcm(out.copyOf(n), rate, 1, n / rate))
                    cp++
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        if (n < rate * 5) return null          // less than five seconds is not enough to fingerprint
        return Pcm(out.copyOf(n), rate, 1, n / rate)
    }
}
