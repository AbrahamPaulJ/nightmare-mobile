package com.abrah.nightmare.npu

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever

/**
 * ⭐ A driving video's frame at a time, decoded IN ORDER a batch at a time (`getFramesAtIndex`), each
 * source frame read once. Animate a portrait's reader, shared with Motion transfer's control video
 * ([WanFun.buildControl]) so the two cannot drift.
 *
 * ⚠⚠ Never one exact seek per frame: on the S25 (2026-10-21) `getFrameAtTime` CLOSEST failed at
 * frame 14 of a 2 s 1080x1920 clip, and the keyframe fallback left 2 or 3 moves in the whole clip.
 * Each exact seek also decodes from the previous keyframe, so a 121-frame read cost O(n x GOP).
 * ⚠ RGB_565 halves a batch (8 x 4 MB at 1080x1920); `getPixels` still gives ARGB.
 * ⚠ A frame from a batch is valid until the [at] that loads the next batch (the old one is
 * recycled then): use it before asking for another. Without a frame count (some containers),
 * one exact seek per frame, as before, and the caller owns that bitmap.
 */
internal class DriverFrames(private val r: MediaMetadataRetriever, private val durMs: Long, private val batch: Int = 8) {
    private val srcCount = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0
    private val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.RGB_565 }
    private var start = -1
    private var held: List<Bitmap> = emptyList()
    private val distinct = HashSet<Int>()
    private var seeks = 0

    fun at(ms: Long): Bitmap? {
        if (srcCount <= 0 || durMs <= 0) {
            seeks++
            return r.getFrameAtTime(ms * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
        }
        val i = (ms * srcCount / durMs).toInt().coerceIn(0, srcCount - 1)
        if (i !in start until start + held.size) {
            held.forEach { it.recycle() }
            start = i
            held = runCatching { r.getFramesAtIndex(i, minOf(batch, srcCount - i), params) }.getOrDefault(emptyList())
            if (held.isEmpty()) {
                seeks++
                return r.getFrameAtTime(ms * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
            }
        }
        distinct += i
        return held[i - start]
    }

    /** For the run log: how the driver was read. */
    fun describe(): String =
        if (srcCount <= 0) "driver read by seeking ($seeks)"
        else "driver: ${distinct.size} of its $srcCount frames" + (if (seeks > 0) ", $seeks by seeking" else "")
}
