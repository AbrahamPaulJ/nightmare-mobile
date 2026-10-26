package com.abrah.nightmare.npu

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

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
}
