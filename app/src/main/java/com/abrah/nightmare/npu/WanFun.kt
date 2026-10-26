package com.abrah.nightmare.npu

import android.net.Uri
import com.abrah.nightmare.ModelCatalog
import java.io.File
import com.abrah.nightmare.Node
import com.abrah.nightmare.NodeCtx
import com.abrah.nightmare.NodeType
import com.abrah.nightmare.Port
import com.abrah.nightmare.R
import com.abrah.nightmare.Value
import com.abrah.nightmare.Widget

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
     * ⭐ [fps] samples it ([FPS_CHOICES]); [follow] crops each frame around the person instead of [rect];
     * [align] fits the wired picture's person to the first pose; [keepAudio] muxes the window's sound back in.
     */
    data class Driving(
        val uri: Uri, val mode: Mode, val startMs: Long = 0L, val rect: FloatArray? = null,
        val fps: Int = WanVideoNode.FPS, val follow: Boolean = false, val align: Boolean = false,
        val keepAudio: Boolean = false,
    )

    // ⭐⭐ The long-video knobs (the user's calls, 2026-10-27; measured in `docs/VIDEO-GGML.md` §7).
    /** ⭐ How often the driving video is sampled: 24 (Wan's own rate) or sparser and faster. */
    const val MOTION_FPS = "motion_fps"
    val FPS_CHOICES = listOf("24", "12", "8")

    /** ⭐ The crop follows the person (a dancer who crosses the room stays large), else the Video tab's box. */
    const val FOLLOW = "follow"

    /** ⭐ The wired picture's person is scaled and placed where the dancer starts (no second figure at the start). */
    const val ALIGN = "align"

    /** ⭐ The driving video's sound over the same span, muxed into the clip. */
    const val KEEP_AUDIO = "keep_audio"

    /**
     * ⭐ The longest window: 30 s (the user's ask: "converting a 30 sec dance video"), made as chained parts of at
     * most [PART_FRAMES] frames, each started from the last frame of the one before ([partsOf]).
     */
    const val MAX_SECONDS = 30

    /** ⭐ One part: at most 121 frames (5 s at 24 fps), what one pass holds at 480x832 on the S25. */
    const val PART_FRAMES = 121

    /**
     * ⚠ The tokens one pass may hold: 480x832 x 121 frames (15 x 26 x 31), which ran (219 s). 704x1280 x 121
     * asked 5.1 GB for one block against a 3.8 GB budget and failed; 704x1280 x 49 (13,200 tokens) ran in 327 s.
     */
    const val PASS_TOKENS = 15 * 26 * 31

    /** ⚠ Pure: the longest part (4n+1, at most [PART_FRAMES]) whose tokens stay within [PASS_TOKENS] at [w] x [h]. */
    fun partFramesFor(w: Int, h: Int): Int {
        val perLatent = ((w / 32) * (h / 32)).coerceAtLeast(1)
        val latents = (PASS_TOKENS / perLatent).coerceAtLeast(2)
        return ((latents - 1) * FRAME_STEP + 1).coerceIn(MIN_FRAMES, PART_FRAMES)
    }

    /** ⚠ Pure: the sampling rate a node's params ask for, one of [FPS_CHOICES]. */
    fun fpsOf(p: Map<String, String>): Int =
        p[MOTION_FPS]?.takeIf { it in FPS_CHOICES }?.toInt() ?: WanVideoNode.FPS

    /**
     * ⭐ Frames two parts share past the start picture; the new part takes over at the one where the two look most
     * alike. On the S25 (1.6.247): a part joined right at its start picture made the clip's largest frame step (12.6
     * against a median 5.9, the model's first frame after a start picture changes the texture at once); a cross-fade
     * over the shared frames ghosted (the parts put an arm a few pixels apart). A multiple of 4: every part stays 4n+1.
     */
    const val OVERLAP = 8

    /**
     * ⚠ Pure: the parts a clip of [frames] (4n+1) is made of, as (first frame, count), each at most [part]
     * long. A part after the first starts [overlap] + 1 frames before the previous one ends: on a frame of it (its
     * start picture), the next [overlap] shared. As few parts as cover the clip, of even length (the last may be
     * shorter); the joined clip is exactly [frames] long and every count is 4n+1.
     */
    fun partsOf(frames: Int, part: Int = PART_FRAMES, overlap: Int = OVERLAP): List<Pair<Int, Int>> {
        if (frames <= part) return listOf(0 to frames)
        val span = frames - 1 - overlap
        val n = (span + (part - 2 - overlap)) / (part - 1 - overlap)
        val len = (1 + ((overlap + (span + n - 1) / n + 3) / 4) * 4).coerceAtMost(part)
        val step = len - 1 - overlap
        return (0 until n).map { i -> i * step to minOf(len, frames - i * step) }.filter { it.second > 1 + overlap || it.first == 0 }
    }

    /**
     * ⚠ Pure: where a run picks up from a kept marker ("parts done, frames written"): (next part, frames), or null
     * to start over (no marker, unreadable, or nothing left to do: a finished clip is not resumed into).
     */
    fun resumeFrom(marker: String?, parts: Int): Pair<Int, Int>? {
        val v = marker?.trim()?.split(" ")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 } ?: return null
        val done = v[0]
        val made = v[1]
        return if (done in 1 until parts && made > 0) done to made else null
    }

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
        return (steps * FRAME_STEP + 1).coerceIn(MIN_FRAMES, MAX_SECONDS * fps + 1)
    }

    /** ⚠ Pure: the window a node's params ask for, in seconds: (start, end), end after start. */
    fun windowOf(p: Map<String, String>): Pair<Double, Double> {
        val start = (p[START]?.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
        val end = p[END]?.toDoubleOrNull() ?: (start + WanVideoNode.MAX_SECONDS)
        return start to end.coerceIn(start, start + MAX_SECONDS)
    }

    /**
     * ⚠ Pure: the seeker's two handles after a drag from ([prevS], [prevE]) to ([s], [e]) on a video
     * [durS] long. The handle that MOVED gives way: the gap stays between [MIN_FRAMES] and the longest
     * clip, and both stay on the video. The other handle never jumps under the finger.
     */
    fun constrainWindow(s: Double, e: Double, prevS: Double, prevE: Double, durS: Double, fps: Int = WanVideoNode.FPS): Pair<Double, Double> {
        val minGap = (MIN_FRAMES - 1).toDouble() / fps
        val maxGap = MAX_SECONDS.toDouble()
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
}

/**
 * ⭐⭐ **Motion transfer** on [ModelCatalog.WAN22_FUN]: Wan's node ([WanVideoNode], same size, LoRAs, seed
 * and seconds) with a `control` input taking the driving clip from a Video node ([LoadVideoNode], the user's
 * call 2026-10-15). The wired `image` is the reference (who the clip shows) and, since 2026-10-27, its first frame.
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
        // ⭐⭐ The long-video knobs (the user's calls, 2026-10-27; `docs/VIDEO-GGML.md` §7).
        Widget(WanFun.MOTION_FPS, "string", WanFun.FPS_CHOICES.first(), options = WanFun.FPS_CHOICES, hintRes = R.string.hint_motion_fps),
        Widget(WanFun.FOLLOW, "bool", "true", hintRes = R.string.hint_motion_follow),
        Widget(WanFun.ALIGN, "bool", "true", hintRes = R.string.hint_motion_align),
        Widget(WanFun.KEEP_AUDIO, "bool", "true", hintRes = R.string.hint_motion_audio),
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
            WanFun.Driving(
                Uri.fromFile(File(control.path)), mode, (start * 1000).toLong(), WanFun.rectOf(p),
                fps = WanFun.fpsOf(p),
                follow = p[WanFun.FOLLOW] != "false",
                align = p[WanFun.ALIGN] != "false",
                keepAudio = p[WanFun.KEEP_AUDIO] != "false",
            ),
        )
    }
}
