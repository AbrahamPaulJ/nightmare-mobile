package com.abrah.nightmare.npu

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * ⭐⭐ **A chain of clips becomes one film** (GitHub #29, the user's call 2026-10-15: "the chain carries
 * it"). A video node started from the previous node's `last_frame` joins that node's film and its own
 * part, so the last node of a chain hands the output the whole film.
 *
 * ⭐ The join frame appears ONCE: the part's first frame IS the film's last (it was the start
 * picture), so the film's last frame is dropped. Dropping the last sample is safe in a stream
 * with no B-frames (nothing refers to it); dropping the part's first would break its IDR.
 *
 * ⭐ Lossless when it can be: both files come from the same encoder at the same size, so the
 * samples are copied as they are. ⚠ Only when their codec headers (`csd-0`, `csd-1`) match and
 * neither stream reorders frames; otherwise the film is RE-ENCODED from decoded frames
 * ([VideoWriter.encode]), never muxed into a stream a player would misdecode.
 */
object FilmJoin {

    private const val TAG = "FilmJoin"

    /** ⚠ Frames decoded at a time on the re-encode path: 8 at 1280x704 is ~29 MB. */
    private const val BATCH = 8

    /** ⭐ A film's frame count, for one made by an earlier Run. */
    fun frames(f: File): Int = MediaMetadataRetriever().let { r ->
        try {
            r.setDataSource(f.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0
        } finally {
            runCatching { r.release() }
        }
    }

    /** ⭐ A film's first frame: its poster. */
    fun firstFrame(f: File): Bitmap? = MediaMetadataRetriever().let { r ->
        try {
            r.setDataSource(f.absolutePath)
            r.getFrameAtIndex(0)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /** ⭐ What a join made: the film's frame count, and whether the samples were copied. */
    data class Joined(val frames: Int, val copied: Boolean)

    /** Joins [film] and [part] into [out]: the film without its last frame, then all of the part. */
    fun join(film: File, part: File, out: File, fps: Int, log: (String) -> Unit = { Log.i(TAG, it) }): Joined {
        val tmp = File(out.parentFile, out.name + ".part")
        tmp.delete()
        val copied = runCatching { copySamples(film, part, tmp, fps) }.getOrElse { e ->
            log("  join: copying failed (${e.message}); re-encoding")
            null
        }
        val joined = copied ?: reencode(film, part, tmp, fps, log)
        check(tmp.renameTo(out) || (tmp.copyTo(out, overwrite = true).exists() && tmp.delete())) { "could not write ${out.name}" }
        return joined
    }

    private class Track(val extractor: MediaExtractor, val format: MediaFormat) {
        fun csd(key: String): ByteBuffer? = if (format.containsKey(key)) format.getByteBuffer(key) else null
    }

    private fun open(f: File): Track {
        val ex = MediaExtractor()
        ex.setDataSource(f.absolutePath)
        val i = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: run { ex.release(); error("no video track in ${f.name}") }
        ex.selectTrack(i)
        return Track(ex, ex.getTrackFormat(i))
    }

    /** ⚠ Every sample's time, in DECODE order; a stream that is not increasing has B-frames. */
    private fun times(t: Track): List<Long> {
        val out = ArrayList<Long>()
        t.extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        while (true) {
            val ts = t.extractor.sampleTime
            if (ts < 0) break
            out += ts
            t.extractor.advance()
        }
        t.extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        return out
    }

    private fun copySamples(film: File, part: File, out: File, fps: Int): Joined? {
        val a = open(film)
        val b = open(part)
        try {
            if (a.format.getString(MediaFormat.KEY_MIME) != b.format.getString(MediaFormat.KEY_MIME)) return null
            if (a.csd("csd-0") != b.csd("csd-0") || a.csd("csd-1") != b.csd("csd-1")) return null
            if (a.format.getInteger(MediaFormat.KEY_WIDTH) != b.format.getInteger(MediaFormat.KEY_WIDTH) ||
                a.format.getInteger(MediaFormat.KEY_HEIGHT) != b.format.getInteger(MediaFormat.KEY_HEIGHT)
            ) return null
            val ta = times(a)
            val tb = times(b)
            if (ta.size < 2 || tb.isEmpty()) return null
            if (ta.zipWithNext().any { (x, y) -> y <= x } || tb.zipWithNext().any { (x, y) -> y <= x }) return null
            val frameUs = 1_000_000L / fps
            val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val track = muxer.addTrack(a.format)
                muxer.start()
                val size = maxOf(
                    if (a.format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) a.format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0,
                    if (b.format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) b.format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0,
                    4 shl 20,
                )
                val buf = ByteBuffer.allocate(size)
                val info = MediaCodec.BufferInfo()
                fun copy(t: Track, count: Int, shiftUs: Long) {
                    var n = 0
                    while (n < count) {
                        val len = t.extractor.readSampleData(buf, 0)
                        if (len < 0) break
                        val sync = t.extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                        info.set(0, len, t.extractor.sampleTime + shiftUs, if (sync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer.writeSampleData(track, buf, info)
                        t.extractor.advance()
                        n++
                    }
                }
                copy(a, ta.size - 1, 0L)
                // The part starts one frame after the film's last KEPT frame.
                copy(b, tb.size, ta[ta.size - 2] + frameUs - tb[0])
                muxer.stop()
            } finally {
                runCatching { muxer.release() }
            }
            return Joined(ta.size - 1 + tb.size, copied = true)
        } finally {
            a.extractor.release()
            b.extractor.release()
        }
    }

    /** ⭐ Frames [from] onward of a clip, a few at a time, in order ([BATCH]). */
    private class Frames(f: File) : AutoCloseable {
        private val r = MediaMetadataRetriever().apply { setDataSource(f.absolutePath) }
        val count = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0
        private var start = -1
        private var held: List<Bitmap> = emptyList()
        fun at(i: Int): Bitmap {
            if (i !in start until start + held.size) {
                start = i
                held = r.getFramesAtIndex(i, minOf(BATCH, count - i))
            }
            return held[i - start]
        }
        override fun close() { runCatching { r.release() } }
    }

    private fun reencode(film: File, part: File, out: File, fps: Int, log: (String) -> Unit): Joined {
        Frames(film).use { a ->
            Frames(part).use { b ->
                check(a.count >= 2 && b.count >= 1) { "cannot read the frames to join" }
                val keep = a.count - 1
                val total = keep + b.count
                val first = a.at(0)
                VideoWriter.encode(total, first.width, first.height, fps, out, log) { i ->
                    if (i < keep) a.at(i) else b.at(i - keep)
                }
                return Joined(total, copied = false)
            }
        }
    }
}
