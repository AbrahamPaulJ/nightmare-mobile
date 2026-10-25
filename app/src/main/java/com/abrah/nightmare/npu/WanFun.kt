package com.abrah.nightmare.npu

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.abrah.nightmare.ModelCatalog
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import com.abrah.nightmare.Node
import com.abrah.nightmare.NodeCtx
import com.abrah.nightmare.NodeType
import com.abrah.nightmare.Port
import com.abrah.nightmare.R
import com.abrah.nightmare.Value
import com.abrah.nightmare.Widget
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ⭐⭐ **Wan 2.2 Fun 5B Control: motion transfer.** A driving video's motion (its pose or depth, frame by
 * frame) onto the picture wired into the Wan node, which becomes the clip's REFERENCE (identity, clothes,
 * setting) through the model's `ref_conv` (`docs/VIDEO-GGML.md` §7).
 *
 * ⭐ Its own model ([ModelCatalog.WAN22_FUN], the user's call 2026-10-15: "treat it as a new model, new
 * flow"), its own node ([WanFunNode]) and flow ("Motion transfer"), borrowing Wan 2.2 Turbo's umT5 and
 * TAEHV when Turbo is installed ([com.abrah.nightmare.ModelSpec.partsFrom]).
 *
 * ⚠ Fun Control is a fine-tune of the BASE TI2V 5B (50 steps + CFG on its own). Its
 * [ModelCatalog.FUN_LORA], Kijai's FastWan 5B LoRA, carries over to it: 3 steps at [SIGMAS], cfg 1. Measured on the S25: 480x832 x49 in
 * 84 s, 704x1280 x49 in 259 s, the reference's identity and the skeleton's motion held (2026-10-15).
 */
object WanFun {

    /** ⭐ FastWan's DMD schedule (timesteps 1000, 757, 522), as measured. */
    val SIGMAS = listOf(1.0, 0.757, 0.522, 0.0)

    /**
     * ⭐ A driving video and how its frames become the control video: the window [startMs] on, and
     * [rect] (the Video tab's crop, fractions of the frame; null = the centre at the clip's shape).
     */
    data class Driving(val uri: Uri, val mode: Mode, val startMs: Long = 0L, val rect: FloatArray? = null)

    // ⭐⭐ The Video tab's params (the user's calls, 2026-10-15): the window on the driving video,
    // two handles whose gap IS the clip, and the crop drawn on the window's first frame.
    const val START = "start"
    const val END = "end"
    const val VX = "video_x"
    const val VY = "video_y"
    const val VW = "video_w"
    const val VH = "video_h"
    val VIDEO_PARAMS = setOf(START, END, VX, VY, VW, VH)

    /** ⭐ Wan's frame step: a clip is 4n+1 frames, so its length moves in 4 frames = 1/6 s. */
    const val FRAME_STEP = 4

    /** ⭐ The shortest window: 5 frames (4n+1 with n = 1). */
    const val MIN_FRAMES = 5

    /**
     * ⚠ Pure: the frames a window [gapS] seconds long makes, snapped to Wan's 4n+1 and kept within
     * [MIN_FRAMES]..the longest clip. The seeker's label and the run read this one function.
     */
    fun framesForGap(gapS: Double, fps: Int = WanVideoNode.FPS): Int {
        val steps = Math.round(gapS * fps / FRAME_STEP).toInt()
        return (steps * FRAME_STEP + 1).coerceIn(MIN_FRAMES, WanVideoNode.framesFor(WanVideoNode.MAX_SECONDS))
    }

    /** ⚠ Pure: the window a node's params ask for, in seconds: (start, end), end after start. */
    fun windowOf(p: Map<String, String>): Pair<Double, Double> {
        val start = (p[START]?.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
        val end = p[END]?.toDoubleOrNull() ?: (start + WanVideoNode.MAX_SECONDS)
        return start to end.coerceIn(start, start + WanVideoNode.MAX_SECONDS)
    }

    /**
     * ⚠ Pure: the seeker's two handles after a drag from ([prevS], [prevE]) to ([s], [e]) on a video
     * [durS] long. The handle that MOVED gives way: the gap stays between [MIN_FRAMES] and the longest
     * clip, and both stay on the video. The other handle never jumps under the finger.
     */
    fun constrainWindow(s: Double, e: Double, prevS: Double, prevE: Double, durS: Double, fps: Int = WanVideoNode.FPS): Pair<Double, Double> {
        val minGap = (MIN_FRAMES - 1).toDouble() / fps
        val maxGap = WanVideoNode.MAX_SECONDS.toDouble()
        val dur = durS.coerceAtLeast(minGap)
        return if (s != prevS) {
            val end = e.coerceIn(minGap, dur)
            s.coerceIn(maxOf(0.0, end - maxGap), maxOf(0.0, end - minGap)) to end
        } else {
            val start = s.coerceIn(0.0, maxOf(0.0, dur - minGap))
            start to e.coerceIn(start + minGap, minOf(dur, start + maxGap))
        }
    }

    /** ⚠ Pure: the crop a node's params hold, or null for "the centre at the clip's shape". */
    fun rectOf(p: Map<String, String>): FloatArray? {
        val v = listOf(VX, VY, VW, VH).map { p[it]?.toFloatOrNull() ?: return null }
        return if (v[2] <= 0f || v[3] <= 0f) null else v.toFloatArray()
    }

    /**
     * How a driving video becomes the control video. ⚠ No Depth (the user's call, 2026-10-16): Depth Anything
     * on the CPU took 108 s for a 5 s clip against Pose's 12 s (GitHub #40's log); a saved `depth` opens as Pose.
     */
    enum class Mode(val id: String) {
        POSE("pose"), AS_IS("as_is");

        companion object {
            fun of(id: String?): Mode = entries.firstOrNull { it.id == id } ?: POSE
        }
    }

    /**
     * ⚠ Pure: how many frames a clip of [wanted] frames can take from a driving video [durationMs] long,
     * sampled at [fps]: Wan wants 4n+1, and a video shorter than the clip shortens the clip rather than
     * freezing on its last frame.
     */
    fun framesFrom(wanted: Int, durationMs: Long, fps: Int = WanVideoNode.FPS): Int {
        val available = (durationMs * fps / 1000L).toInt() + 1
        val n = minOf(wanted, available)
        return if (n < 5) 0 else (n - 1) / 4 * 4 + 1
    }

    /**
     * ⚠ Pure: the centre crop of a [srcW]x[srcH] frame that has the clip's shape [w]x[h], as
     * (left, top, width, height). The driving video's own aspect rarely matches the clip's.
     */
    fun cropFor(srcW: Int, srcH: Int, w: Int, h: Int): IntArray {
        val target = w.toDouble() / h
        return if (srcW.toDouble() / srcH > target) {
            val cw = (srcH * target).toInt().coerceIn(1, srcW)
            intArrayOf((srcW - cw) / 2, 0, cw, srcH)
        } else {
            val ch = (srcW / target).toInt().coerceIn(1, srcH)
            intArrayOf(0, (srcH - ch) / 2, srcW, ch)
        }
    }

    /**
     * ⚠ Pure: the Video tab's box [rect] (fractions of a [srcW]x[srcH] frame, and it may hang off it:
     * the box zooms out √2 past "the whole frame fits", [com.abrah.nightmare.PadRule.PAD]) at the
     * clip's shape [w]x[h], around its own centre. The box is drawn at that shape, so this only
     * corrects one left from an earlier clip size; its height is kept.
     */
    fun fitRect(rect: FloatArray, srcW: Int, srcH: Int, w: Int, h: Int): FloatArray {
        val rw = rect[3] * srcH * w / (h.toFloat() * srcW)
        val cx = rect[0] + rect[2] / 2f
        return floatArrayOf(cx - rw / 2f, rect[1], rw, rect[3])
    }

    /**
     * ⭐⭐ The control video for a clip: [uri]'s frames at 24 fps, centre-cropped to [w]x[h], each made a
     * pose skeleton or kept as it is ([mode]), written to [out] in the NMV1 layout the
     * backend reads (`"NMV1"`, int32 LE w, h, count, fps, then RGB8 frames). Returns the frame count.
     * ⚠ Blocking: ~100 ms a frame for pose (121 frames in 12 s on an SM8750, GitHub #40); [onFrame] reports it.
     */
    fun buildControl(
        context: Context,
        uri: Uri,
        mode: Mode,
        w: Int,
        h: Int,
        wanted: Int,
        out: File,
        startMs: Long = 0L,
        rect: FloatArray? = null,
        onFrame: (Int, Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): Int {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val count = framesFrom(wanted, duration - startMs.coerceIn(0L, duration))
            if (count == 0) throw IOException("the driving video is too short: it needs at least a quarter of a second")
            val rgb = ByteArray(w * h * 3)
            val px = IntArray(w * h)
            // ⚠⚠ In order, a batch at a time ([DriverFrames], Animate a portrait's reader): one exact
            // seek per frame failed partway on the S25 ("could not read frame 15") and decoded from
            // the previous keyframe every time.
            val driver = DriverFrames(r, duration)
            BufferedOutputStream(FileOutputStream(out), 1 shl 20).use { os ->
                os.write("NMV1".toByteArray(Charsets.US_ASCII))
                os.write(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(w).putInt(h).putInt(count).putInt(WanVideoNode.FPS).array())
                for (i in 0 until count) {
                    if (isCancelled()) throw IOException("cancelled")
                    val ms = startMs + i * 1000L / WanVideoNode.FPS
                    val src = driver.at(ms)
                        ?: throw IOException("could not read frame ${i + 1} of the driving video")
                    // ⭐ The Video tab's box, black past the frame (it may zoom out √2); else the centre.
                    val framed = if (rect != null) {
                        val r = fitRect(rect, src.width, src.height, w, h)
                        com.abrah.nightmare.CropNode.render(src, r[0], r[1], r[2], r[3], w, h, com.abrah.nightmare.CropNode.PAD_BLACK).first
                    } else {
                        val c = cropFor(src.width, src.height, w, h)
                        Bitmap.createScaledBitmap(Bitmap.createBitmap(src, c[0], c[1], c[2], c[3]), w, h, true)
                    }
                    val control = when (mode) {
                        Mode.POSE -> com.abrah.nightmare.pose.PoseDetector.skeleton(context, framed)
                            ?: throw com.abrah.nightmare.NeedsDownload(
                                com.abrah.nightmare.NeedsDownload.POSE, "the Pose Detector is not installed (Models, Tools)",
                            )
                        Mode.AS_IS -> framed
                    }.let { if (it.width != w || it.height != h) Bitmap.createScaledBitmap(it, w, h, true) else it }
                    control.getPixels(px, 0, w, 0, 0, w, h)
                    var o = 0
                    for (p in px) {
                        rgb[o++] = (p shr 16).toByte(); rgb[o++] = (p shr 8).toByte(); rgb[o++] = p.toByte()
                    }
                    os.write(rgb)
                    onFrame(i + 1, count)
                }
            }
            return count
        } finally {
            runCatching { r.release() }
        }
    }
}

/**
 * ⭐⭐ **Motion transfer** on [ModelCatalog.WAN22_FUN]: Wan's node ([WanVideoNode], same size, LoRAs, seed
 * and seconds) with a `control` input taking the driving clip from a Video node ([LoadVideoNode], the user's
 * call 2026-10-15). The wired `image` is the reference: who the clip shows.
 */
object WanFunNode : NodeType {

    override val appSide = false
    override val name = "wanfun.sample"
    override val version = "1"
    const val CONTROL = "control"
    override val inputs = listOf(Port("prompt", "PROMPT"), Port("image", "IMAGE"), Port(CONTROL, "VIDEO"))
    override val outputs get() = WanVideoNode.outputs
    override val category = "generate"
    override val paletteName = "Wan 2.2 Fun Control"
    override fun titleFor(node: Node): String = "Motion transfer"
    override val defaultId = "motion"
    override val about = "a picture moving like a driving video, with Wan 2.2 Fun Control"
    override val showsResult = false

    // ⚠ No `loras` widget, so no picker (the user's call, 2026-10-15; [WanVideoNode.runClip] skips tags).
    // ⚠ No `seconds` either: the Video tab's window sets the clip's length (the user's call, 2026-10-15).
    override val widgets get() = WanVideoNode.widgets.filterNot {
        it.name == com.abrah.nightmare.SdSampler.LORAS || it.name == "seconds"
    } + listOf(
        Widget(
            WanVideoNode.MOTION_MODE, "string", WanFun.Mode.POSE.id,
            options = WanFun.Mode.entries.map { it.id }, hintRes = R.string.hint_motion_mode,
        ),
        // ⭐ Drawn by the Video tab ([com.abrah.nightmare.canvas.DrivingVideoPanel]), never as knobs.
        Widget(WanFun.START, "float", "0.0", 0.0),
        Widget(WanFun.END, "float", WanVideoNode.MAX_SECONDS.toDouble().toString(), 0.0),
        Widget(WanFun.VX, "float", ""),
        Widget(WanFun.VY, "float", ""),
        Widget(WanFun.VW, "float", ""),
        Widget(WanFun.VH, "float", ""),
    )

    override fun contextKey(node: Node) = WanVideoNode.keyFor(node, ModelCatalog.WAN22_FUN)

    override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value =
        runPorts(ctx, node, inputs).getValue("video")

    override suspend fun runPorts(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Map<String, Value> {
        val control = inputs[CONTROL] as? Value.Video
            ?: throw IllegalArgumentException(
                "node \"${node.id}\": wire a Video node into \"control\": its motion is what your picture does"
            )
        if (inputs["image"] == null) {
            throw IllegalArgumentException("node \"${node.id}\": wire a picture into \"image\": it is who the clip shows")
        }
        val p = effectiveParams(node)
        val mode = WanFun.Mode.of(p[WanVideoNode.MOTION_MODE])
        val (start, _) = WanFun.windowOf(p)
        return WanVideoNode.runClip(
            ctx, node, inputs, ModelCatalog.WAN22_FUN,
            WanFun.Driving(Uri.fromFile(File(control.path)), mode, (start * 1000).toLong(), WanFun.rectOf(p)),
        )
    }
}
