package com.abrah.nightmare.npu

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ⭐⭐ **Keep audio** on Motion transfer (the user's ask, 2026-10-27: "keep original clip's audio, you can match by
 * frames"): the clip covers exactly [startUs] to [startUs] + its own length of the driving video, at any frame
 * rate and with Smooth motion too (2x the frames at 2x the rate is the same span). So the driving video's audio
 * samples over that span are copied into the clip as they are, no re-encode, next to its video samples.
 *
 * ⚠ To the audio frame (AAC: ~21 ms): a sample is kept when it STARTS inside the span.
 */
object AudioMux {

    /** ⚠ Pure: whether an audio sample at [sampleUs] belongs to a span starting at [startUs] and [lengthUs] long. */
    fun inSpan(sampleUs: Long, startUs: Long, lengthUs: Long): Boolean = sampleUs >= startUs && sampleUs < startUs + lengthUs

    /**
     * ⭐ [clip] with [source]'s audio over [startUs] for the clip's length, written over [clip]. False (and
     * [clip] untouched) when the source has no audio track or anything fails: a clip without sound beats no clip.
     */
    fun mux(context: Context, clip: File, source: Uri, startUs: Long, log: (String) -> Unit): Boolean {
        val tmp = File(clip.parentFile, clip.name + ".audio")
        val audio = MediaExtractor()
        val video = MediaExtractor()
        return try {
            audio.setDataSource(context, source, null)
            val at = (0 until audio.trackCount).firstOrNull {
                audio.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: run { log("  keep audio: the driving video has no sound"); return false }
            video.setDataSource(clip.absolutePath)
            val vt = (0 until video.trackCount).first {
                video.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            audio.selectTrack(at)
            video.selectTrack(vt)
            val vf = video.getTrackFormat(vt)
            val af = audio.getTrackFormat(at)
            val lengthUs = if (vf.containsKey(MediaFormat.KEY_DURATION)) vf.getLong(MediaFormat.KEY_DURATION) else 0L
            val muxer = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var copied = 0
            try {
                val v = muxer.addTrack(vf)
                val a = muxer.addTrack(af)
                muxer.start()
                val buf = ByteBuffer.allocate(4 shl 20)
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val len = video.readSampleData(buf, 0)
                    if (len < 0) break
                    val key = video.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                    info.set(0, len, video.sampleTime, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(v, buf, info)
                    video.advance()
                }
                audio.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                while (true) {
                    val len = audio.readSampleData(buf, 0)
                    if (len < 0) break
                    val t = audio.sampleTime
                    if (t >= startUs + lengthUs) break
                    if (inSpan(t, startUs, lengthUs)) {
                        info.set(0, len, t - startUs, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                        muxer.writeSampleData(a, buf, info)
                        copied++
                    }
                    audio.advance()
                }
                muxer.stop()
            } finally {
                runCatching { muxer.release() }
            }
            check(tmp.renameTo(clip) || (tmp.copyTo(clip, overwrite = true).exists() && tmp.delete())) { "could not write ${clip.name}" }
            log("  keep audio: $copied sound frames over ${"%.1f".format(lengthUs / 1e6)} s")
            true
        } catch (e: Exception) {
            tmp.delete()
            log("  keep audio failed (${e.message ?: e.javaClass.simpleName}); the clip has no sound")
            false
        } finally {
            audio.release()
            video.release()
        }
    }
    /** ⭐ A soundtrack as 16-bit PCM: [samples] interleaved by channel. */
    class Pcm(val rate: Int, val channels: Int, val samples: ShortArray) {
        val seconds: Double get() = samples.size.toDouble() / channels.coerceAtLeast(1) / rate.coerceAtLeast(1)
    }

    /**
     * ⚠ Pure: backend 050's soundtrack file ("NMA1", int32 LE sample rate and channels, int64 sample frames, then
     * float32 samples interleaved by channel) as 16-bit PCM, clipped to full scale. Null when it is not one.
     */
    fun readNma1(bytes: ByteArray): Pcm? {
        if (bytes.size < 20 || String(bytes, 0, 4, Charsets.US_ASCII) != "NMA1") return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.position(4)
        val rate = b.int
        val channels = b.int
        val frames = b.long
        if (rate <= 0 || channels !in 1..8 || frames < 0) return null
        val n = minOf(frames * channels, ((bytes.size - 20) / 4).toLong()).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) out[i] = (b.float.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        return Pcm(rate, channels, out)
    }

    /**
     * ⭐⭐ MiniMax H3's soundtrack into [clip]: [pcm] (backend 050's NMA1) encoded to AAC-LC by the phone's encoder and
     * muxed beside the clip's video samples, which are copied as they are, written over [clip]. False (and [clip]
     * untouched) when anything fails: a clip without sound beats no clip, and the log says so.
     */
    fun muxPcm(clip: File, pcm: File, log: (String) -> Unit): Boolean {
        val tmp = File(clip.parentFile, clip.name + ".audio")
        val video = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            val sound = readNma1(pcm.readBytes()) ?: run { log("  sound: the soundtrack file is not one"); return false }
            video.setDataSource(clip.absolutePath)
            val vt = (0 until video.trackCount).first {
                video.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            video.selectTrack(vt)
            val vf = video.getTrackFormat(vt)
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sound.rate, sound.channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 shl 10)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also { codec = it }
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            // ⚠ All of it encoded before the muxer starts: the muxer needs the encoder's output format first.
            val packets = ArrayList<Pair<ByteArray, Long>>()
            var outFormat: MediaFormat? = null
            val info = MediaCodec.BufferInfo()
            var fed = 0
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val ii = c.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = c.getInputBuffer(ii)!!
                        buf.clear()
                        val n = minOf(buf.capacity() / 2 / sound.channels * sound.channels, sound.samples.size - fed)
                        val pts = (fed / sound.channels).toLong() * 1_000_000L / sound.rate
                        if (n <= 0) {
                            c.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.order(ByteOrder.nativeOrder()).asShortBuffer().put(sound.samples, fed, n)
                            c.queueInputBuffer(ii, 0, n * 2, pts, 0)
                            fed += n
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) outFormat = c.outputFormat
                else if (oi >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val ob = c.getOutputBuffer(oi)!!
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        packets += ByteArray(info.size).also { ob.get(it) } to info.presentationTimeUs
                    }
                    c.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            val af = checkNotNull(outFormat) { "the AAC encoder gave no format" }
            val muxer = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val v = muxer.addTrack(vf)
                val a = muxer.addTrack(af)
                muxer.start()
                val buf = ByteBuffer.allocate(4 shl 20)
                val vi = MediaCodec.BufferInfo()
                while (true) {
                    val len = video.readSampleData(buf, 0)
                    if (len < 0) break
                    val key = video.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                    vi.set(0, len, video.sampleTime, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(v, buf, vi)
                    video.advance()
                }
                for ((bytes, us) in packets) {
                    buf.clear()
                    buf.put(bytes)
                    buf.flip()
                    vi.set(0, bytes.size, us, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    muxer.writeSampleData(a, buf, vi)
                }
                muxer.stop()
            } finally {
                runCatching { muxer.release() }
            }
            check(tmp.renameTo(clip) || (tmp.copyTo(clip, overwrite = true).exists() && tmp.delete())) { "could not write ${clip.name}" }
            log("  sound: ${"%.1f".format(sound.seconds)} s at ${sound.rate / 1000} kHz, ${if (sound.channels == 2) "stereo" else "${sound.channels} channels"}")
            true
        } catch (e: Exception) {
            tmp.delete()
            log("  sound failed (${e.message ?: e.javaClass.simpleName}); the clip has no sound")
            false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            video.release()
        }
    }

    private const val AAC_BITRATE = 192_000
    /**
     * ⭐ A PART's soundtrack ([pcm], backend 050's NMA1) appended to the film's ([into], float32 interleaved), its first
     * [dropFrames] video frames' worth of samples left out: a later part's frame 0 repeats the clip's last frame and is
     * dropped ([WanVideoNode]'s parts), so its sound goes too and the joins stay in sync. [fmt] holds "rate channels
     * frames" of what [into] holds, rewritten after each part, so a resumed run trims [into] back to it first.
     */
    fun appendPart(pcm: File, into: File, fmt: File, dropFrames: Int, fps: Int) {
        val bytes = pcm.readBytes()
        if (bytes.size < 20 || String(bytes, 0, 4, Charsets.US_ASCII) != "NMA1") return
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.position(4)
        val rate = b.int
        val channels = b.int
        val frames = b.long
        val have = ((bytes.size - 20) / 4 / channels.coerceAtLeast(1)).toLong()
        val drop = (rate.toLong() * dropFrames / fps.coerceAtLeast(1)).coerceAtMost(minOf(frames, have))
        val keep = minOf(frames, have) - drop
        val held = partFrames(fmt)
        java.io.RandomAccessFile(into, "rw").use { f ->
            f.setLength(held * channels * 4)
            f.seek(f.length())
            f.write(bytes, 20 + (drop * channels * 4).toInt(), (keep * channels * 4).toInt())
        }
        fmt.writeText("$rate $channels ${held + keep}")
    }

    private fun partFrames(fmt: File): Long =
        runCatching { fmt.readText().trim().split(' ')[2].toLong() }.getOrDefault(0L)

    /** ⭐ The joined parts' soundtrack ([appendPart]) as one NMA1 file at [out], or null when no part had sound. */
    fun finishParts(into: File, fmt: File, out: File): File? {
        val f = runCatching { fmt.readText().trim().split(' ').map { it.toLong() } }.getOrNull() ?: return null
        if (f.size < 3 || !into.isFile) return null
        val (rate, channels, frames) = f
        val head = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        head.put("NMA1".toByteArray(Charsets.US_ASCII)).putInt(rate.toInt()).putInt(channels.toInt()).putLong(frames)
        out.outputStream().use { o ->
            o.write(head.array())
            into.inputStream().use { i -> i.copyTo(o) }
        }
        into.delete()
        fmt.delete()
        return out
    }
}
