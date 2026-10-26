package com.abrah.nightmare.npu

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.abrah.nightmare.pose.OpenPoseRender
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ⭐⭐ **Motion transfer's driving video, read once** (the user's calls, 2026-10-27, measured in
 * `docs/VIDEO-GGML.md` §7): every frame of the window is decoded ONCE, the person found in it (MoveNet's box)
 * and, for Pose, their 133 whole-body points (DWPose, [com.abrah.nightmare.pose.WholeBodyPose]). Everything
 * after that is arithmetic on the [Track]: the crop per frame ([followWindows] or the Video tab's box), the
 * skeletons drawn in the crop ([OpenPoseRender.renderWholeBody]), one control file per chained part
 * ([writePose]), and where the wired picture's person must stand ([alignRect]).
 *
 * ⚠ Why follow: a dancer who crosses the room needs a wide fixed box, and then fills half its height. At
 * 480x832 her face was ~25 px and every arm of the test looked poor; the same clip followed (the person 1.6x
 * larger) read face, print and boots, at the same 219 s.
 */
object MotionTrack {

    /** ⭐ The crop's height over the person's tallest box: room for arms over the head. Measured 1.15. */
    const val MARGIN = 1.15f

    /** ⭐ The window's centre is a moving average over this many frames: a steady camera, not a jittery one. */
    const val SMOOTH_FRAMES = 25

    /**
     * ⭐ One driving window as the phone read it: [boxes] (x0, y0, x1, y1 in source px, null where nobody was
     * found) and [points] (133 x (x, y, score) in source px, DWPose's raw SimCC score; null without whole body).
     */
    class Track(val srcW: Int, val srcH: Int, val boxes: List<FloatArray?>, val points: List<FloatArray?>) {
        val size get() = boxes.size
    }

    /** ⚠ Pure: the [q]-quantile of [v] (sorted copy), 0 when empty. */
    internal fun quantile(v: List<Float>, q: Float): Float {
        if (v.isEmpty()) return 0f
        val s = v.sorted()
        return s[((s.size - 1) * q).toInt().coerceIn(0, s.size - 1)]
    }

    /** ⚠ Pure: [boxes] with every gap filled from the nearest found box (the one before, else after); null if none. */
    internal fun filled(boxes: List<FloatArray?>): List<FloatArray>? {
        val firstFound = boxes.indexOfFirst { it != null }
        if (firstFound < 0) return null
        var last = boxes[firstFound]!!
        return boxes.map { b -> (b ?: last).also { last = it } }
    }

    /** ⚠ Pure: a moving average of [v] over [n] values, edges held. */
    internal fun smoothed(v: FloatArray, n: Int): FloatArray {
        if (v.size <= 1 || n <= 1) return v.copyOf()
        val half = n / 2
        return FloatArray(v.size) { i ->
            var s = 0f
            for (k in i - half until i - half + n) s += v[k.coerceIn(0, v.size - 1)]
            s / n
        }
    }

    /** ⭐ A crop on a source frame, in source px. */
    data class Window(val x: Float, val y: Float, val w: Float, val h: Float)

    /**
     * ⭐⭐ Pure: the follow crop of every frame, at the clip's shape [outW] x [outH]: a fixed size ([MARGIN] x the
     * person's 95th-percentile box height, so one tall frame does not shrink everyone), its centre following the
     * person's box smoothed over [SMOOTH_FRAMES]. ⚠ It may hang off the frame: that part is black, as the Video
     * tab's box pads. Null when nobody is found in any frame.
     */
    fun followWindows(boxes: List<FloatArray?>, outW: Int, outH: Int, margin: Float = MARGIN, smooth: Int = SMOOTH_FRAMES): List<Window>? {
        val b = filled(boxes) ?: return null
        val ch = quantile(b.map { it[3] - it[1] }, 0.95f) * margin
        if (ch <= 1f) return null
        val cw = ch * outW / outH
        val cx = smoothed(FloatArray(b.size) { (b[it][0] + b[it][2]) / 2f }, smooth)
        val cy = smoothed(FloatArray(b.size) { (b[it][1] + b[it][3]) / 2f }, smooth)
        return List(b.size) { Window(cx[it] - cw / 2f, cy[it] - ch / 2f, cw, ch) }
    }

    /** ⚠ Pure: the Video tab's box (fractions, [WanFun.fitRect]) or the centre crop, the same on every frame. */
    fun fixedWindow(rect: FloatArray?, srcW: Int, srcH: Int, outW: Int, outH: Int): Window =
        if (rect != null) {
            val r = WanFun.fitRect(rect, srcW, srcH, outW, outH)
            Window(r[0] * srcW, r[1] * srcH, r[2] * srcW, r[3] * srcH)
        } else {
            val c = WanFun.cropFor(srcW, srcH, outW, outH)
            Window(c[0].toFloat(), c[1].toFloat(), c[2].toFloat(), c[3].toFloat())
        }

    /** ⚠ Pure: [p] (133 x 3, source px) in the clip's px through [win]; scores kept. */
    fun inWindow(p: FloatArray, win: Window, outW: Int, outH: Int): FloatArray {
        val sx = outW / win.w
        val sy = outH / win.h
        return FloatArray(p.size) { i ->
            when (i % 3) {
                0 -> (p[i] - win.x) * sx
                1 -> (p[i] - win.y) * sy
                else -> p[i]
            }
        }
    }

    /**
     * ⭐⭐ Pure: the crop of the wired picture (fractions of [photoW] x [photoH], for
     * [com.abrah.nightmare.CropNode.render]) that puts its person, [photoBox] in px, where the dancer starts: the
     * same height as the dancer's usual height in the clip ([personH], clip px), feet on the first frame's feet and
     * centred on its centre ([startBox], clip px). ⚠ Kept INSIDE the picture (shrunk, then slid), so the clip never
     * opens on black bars: a picture too tight for the match comes out a little larger than the dancer instead.
     */
    fun alignRect(photoW: Int, photoH: Int, photoBox: FloatArray, startBox: FloatArray, personH: Float, outW: Int, outH: Int): FloatArray {
        val ph = (photoBox[3] - photoBox[1]).coerceAtLeast(1f)
        var s = (personH / ph).coerceAtLeast(1e-3f)           // clip px per photo px
        var cw = outW / s
        var ch = outH / s
        val fit = minOf(1f, photoW / cw, photoH / ch)
        cw *= fit; ch *= fit; s /= fit
        val photoCx = (photoBox[0] + photoBox[2]) / 2f
        val startCx = (startBox[0] + startBox[2]) / 2f
        var left = photoCx - startCx / s
        var top = photoBox[3] - startBox[3] / s
        left = left.coerceIn(0f, (photoW - cw).coerceAtLeast(0f))
        top = top.coerceIn(0f, (photoH - ch).coerceAtLeast(0f))
        return floatArrayOf(left / photoW, top / photoH, cw / photoW, ch / photoH)
    }

    /** ⚠ Pure: [box] (source px) in the clip's px through [win]. */
    fun boxInWindow(box: FloatArray, win: Window, outW: Int, outH: Int): FloatArray {
        val sx = outW / win.w
        val sy = outH / win.h
        return floatArrayOf((box[0] - win.x) * sx, (box[1] - win.y) * sy, (box[2] - win.x) * sx, (box[3] - win.y) * sy)
    }

    /**
     * ⭐⭐ Reads the window: [count] frames from [startMs] at [fps], each through MoveNet for the person's box
     * and, with [wholeBody], DWPose for their points. ⚠ Blocking (~0.1-0.3 s a frame on an 8 Elite's CPU);
     * [onFrame] reports it. MoveNet must be installed (the caller checks); DWPose too when [wholeBody].
     */
    fun read(
        context: Context,
        uri: Uri,
        startMs: Long,
        count: Int,
        fps: Int,
        wholeBody: Boolean,
        onFrame: (Int, Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): Track {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val driver = DriverFrames(r, duration)
            val boxes = ArrayList<FloatArray?>(count)
            val points = ArrayList<FloatArray?>(count)
            var srcW = 0
            var srcH = 0
            for (i in 0 until count) {
                if (isCancelled()) throw IOException("cancelled")
                val src = driver.at(startMs + i * 1000L / fps)
                    ?: throw IOException("could not read frame ${i + 1} of the driving video")
                srcW = src.width; srcH = src.height
                val box = com.abrah.nightmare.pose.PoseDetector.mainBox(context, src)
                boxes += box
                points += if (wholeBody && box != null) com.abrah.nightmare.pose.WholeBodyPose.points(context, src, box) else null
                onFrame(i + 1, count)
            }
            return Track(srcW, srcH, boxes, points)
        } finally {
            runCatching { r.release() }
        }
    }

    /** ⭐ The NMV1 header the backend reads: `"NMV1"`, int32 LE w, h, count, fps. */
    private fun header(w: Int, h: Int, count: Int, fps: Int): ByteArray =
        "NMV1".toByteArray(Charsets.US_ASCII) +
            ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(w).putInt(h).putInt(count).putInt(fps).array()

    private fun rgbOf(b: Bitmap, px: IntArray, rgb: ByteArray, w: Int, h: Int): ByteArray {
        b.getPixels(px, 0, w, 0, 0, w, h)
        var o = 0
        for (p in px) {
            rgb[o++] = (p shr 16).toByte(); rgb[o++] = (p shr 8).toByte(); rgb[o++] = p.toByte()
        }
        return rgb
    }

    /**
     * ⭐ Frames [first] until [first] + [count] of [track] as whole-body skeletons, each in its window
     * ([windowAt]), written to [out] as NMV1 at [fps]. A frame with nobody in it is black (the model's "no pose").
     */
    fun writePose(track: Track, windowAt: (Int) -> Window, first: Int, count: Int, w: Int, h: Int, fps: Int, out: File) {
        val px = IntArray(w * h)
        val rgb = ByteArray(w * h * 3)
        BufferedOutputStream(FileOutputStream(out), 1 shl 20).use { os ->
            os.write(header(w, h, count, fps))
            for (i in first until first + count) {
                val people = listOfNotNull(track.points[i]?.let { inWindow(it, windowAt(i), w, h) })
                val b = OpenPoseRender.renderWholeBody(people, w, h)
                os.write(rgbOf(b, px, rgb, w, h))
                b.recycle()
            }
        }
    }

    /**
     * ⭐ Frames [first] until [first] + [count] of the driving video AS THEY ARE (Mode "as is"), each cut by its
     * window ([windowAt]: frame index, source width, height), black past the frame, written to [out] as NMV1.
     * ⚠ Decodes the frames again: an as-is control is pixels, not points.
     */
    fun writeAsIs(
        context: Context, uri: Uri, startMs: Long, fps: Int, windowAt: (Int, Int, Int) -> Window,
        first: Int, count: Int, w: Int, h: Int, out: File, isCancelled: () -> Boolean = { false },
        onFrame: (Int, Int) -> Unit = { _, _ -> },
    ) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val driver = DriverFrames(r, duration)
            val px = IntArray(w * h)
            val rgb = ByteArray(w * h * 3)
            BufferedOutputStream(FileOutputStream(out), 1 shl 20).use { os ->
                os.write(header(w, h, count, fps))
                for (i in first until first + count) {
                    if (isCancelled()) throw IOException("cancelled")
                    val src = driver.at(startMs + i * 1000L / fps)
                        ?: throw IOException("could not read frame ${i + 1} of the driving video")
                    val sw = src.width
                    val sh = src.height
                    val win = windowAt(i, sw, sh)
                    val (b, _) = com.abrah.nightmare.CropNode.render(
                        src, win.x / sw, win.y / sh, win.w / sw, win.h / sh, w, h, com.abrah.nightmare.CropNode.PAD_BLACK,
                    )
                    val sized = if (b.width != w || b.height != h) Bitmap.createScaledBitmap(b, w, h, true) else b
                    os.write(rgbOf(sized, px, rgb, w, h))
                    onFrame(i - first + 1, count)
                }
            }
        } finally {
            runCatching { r.release() }
        }
    }
}
