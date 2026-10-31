package com.abrah.nightmare.npu

import com.abrah.nightmare.ModelCatalog
import com.abrah.nightmare.Node
import com.abrah.nightmare.NodeCtx
import com.abrah.nightmare.NodeType
import com.abrah.nightmare.Port
import com.abrah.nightmare.R
import com.abrah.nightmare.Value
import com.abrah.nightmare.Widget

/**
 * ⭐⭐ **MiniMax H3** on [ModelCatalog.MINIMAX_H3]: a prompt into a clip WITH SOUND (`docs/MINIMAX-H3.md`). Wan's node
 * shape and run ([WanVideoNode.runClip] with [WanVideoNode.H3]): size, seed, seconds, Smooth motion; what differs is
 * the prompt. H3 is trained on a STRUCTURED prompt (who is in it and how they sound, what is said, the soundscape, the
 * music), so the Prompt node opens on one ([VideoPrompts.H3]) and its hint names the sections. ⚠ ONE prompt box, the
 * Prompt node's (the user, 2026-10-30): four fields on this node left the Prompt node holding half the prompt. A plain
 * prompt still works: [H3Prompt] wraps it.
 * ⭐ A wired picture is the clip's FIRST FRAME (H3's I2VA keyframe, sd.cpp's `init_image`; framed like Wan's image to
 * video, [WanVideoNode.runClip]). ⚠ FastH3 is distilled for text to video only; the first frame is held by the base
 * model's keyframe conditioning at 4 steps (`docs/MINIMAX-H3.md` §5e).
 */
object H3Node : NodeType {

    override val appSide = false
    override val name = "h3.sample"
    override val version = "1"
    override val inputs = listOf(Port("prompt", "PROMPT"), Port("image", "IMAGE"))
    override val outputs get() = WanVideoNode.outputs
    override val category = "generate"
    override val paletteName = "MiniMax H3"
    override fun titleFor(node: Node): String =
        if (node.inputs["image"] != null) "Image to video with sound" else "Text to video with sound"
    override val defaultId = "video"
    override val about = "a prompt, or a prompt and a photo, into a clip with sound (speech, effects, music) with MiniMax H3"
    override val showsResult = false

    /**
     * ⭐ 832x480 for 2.3 s (the user, 2026-10-30: "384 output looks pretty cheap"): 6,630 video tokens, under the 8 Gen
     * 3's measured 7,100 as well as v79's 8,800, so one opening size serves both. ⚠ Nothing under 384: at 256 a frame
     * is 64 tokens, too few for H3 to hold its colours over a clip.
     */
    const val DEFAULT_W = 832
    const val DEFAULT_H = 480
    const val DEFAULT_SECONDS = 2.3

    /**
     * ⭐ Its lengths: one per 17-frame latent step, 22 to 124 frames, offered as chips in seconds (0.9 to 5.2). ⚠ Not
     * whole seconds: those skip the measured 1.6 s (39 frames), the longest 1152x640 holds, and land on 2.3 s, which it
     * does not.
     */
    val LENGTHS: List<Int> = (1..7).map { 17 * it + 5 }

    /** ⚠ Pure: a length in seconds as its chip shows it, "3.0" for 73 frames. */
    fun label(frames: Int): String = String.format(java.util.Locale.ROOT, "%.1f", frames / WanVideoNode.H3.fps.toDouble())
    val LENGTH_LABELS: List<String> get() = LENGTHS.map(::label)

    override val widgets get() = listOf(
        Widget("width", "int", DEFAULT_W.toString(), WanVideoNode.H3.minSide.toDouble(), WanVideoNode.H3.maxSide.toDouble(),
            step = WanVideoNode.H3.grid, hintRes = R.string.hint_h3_size),
        Widget("height", "int", DEFAULT_H.toString(), WanVideoNode.H3.minSide.toDouble(), WanVideoNode.H3.maxSide.toDouble(),
            step = WanVideoNode.H3.grid),
        Widget("seed", "int", "0", hintRes = R.string.hint_seed_clip),
        Widget("seconds", "string", label(framesFor(DEFAULT_SECONDS)), options = LENGTH_LABELS, hintRes = R.string.hint_h3_seconds),
        // ⭐ Every video node's (the user, 2026-10-29): the soundtrack keeps its length, so it stays in sync.
        Widget(WanVideoNode.SMOOTH, "bool", "false", hintRes = R.string.hint_wan_smooth),
        // ⭐ Parts (the user, 2026-10-30): each part starts on the last frame of the one before, as Wan's, now that a
        // wired picture works as H3's first frame; the soundtracks join with the frames. Then the framing of a wired picture.
        // ⭐ LoRAs since 1.6.269 (a tester's ask): CivitAI's H3 LoRAs bind ([WanVideoNode.runClip]).
    ) + WanVideoNode.widgets.filter { it.name in WanParts.KNOBS || it.name in FRAMING || it.name == com.abrah.nightmare.SdSampler.LORAS }

    private val FRAMING = setOf("x", "y", "w", "h")

    override fun framesTo(node: Node): Pair<Int, Int> = WanVideoNode.sizeOf(effectiveParams(node), WanVideoNode.H3)

    override fun contextKey(node: Node) = WanVideoNode.keyFor(node, ModelCatalog.MINIMAX_H3)

    override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value =
        runPorts(ctx, node, inputs).getValue("video")

    override suspend fun runPorts(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Map<String, Value> =
        WanVideoNode.runClip(ctx, node, inputs, ModelCatalog.MINIMAX_H3, null)

    /**
     * ⚠ Pure: H3's frames for [seconds] at 24 fps, on its 17k+5 grid (5 frames, then 17 a latent step), the nearest
     * of [LENGTHS]: 0.9 s 22 frames, 1.6 s 39, 3.0 s 73, 5.2 s 124; whole seconds land on 22, 56, 73, 90, 124.
     */
    fun framesFor(seconds: Double): Int {
        val k = Math.round((seconds * WanVideoNode.H3.fps - 5) / 17.0).toInt().coerceIn(1, LENGTHS.size)
        return 17 * k + 5
    }

    fun framesFor(seconds: Int): Int = framesFor(seconds.toDouble())

    /** ⭐ A node's frames: its chip ([LENGTH_LABELS]), or the default length. */
    fun framesOf(p: Map<String, String>): Int = framesFor(p["seconds"]?.toDoubleOrNull() ?: DEFAULT_SECONDS)

    /** ⚠ Pure: the longest of [LENGTHS] that fits [w] x [h] on this phone, as its label, or null when none does. */
    fun lengthThatFits(w: Int, h: Int, ramBytes: Long, arch: Int): String? =
        LENGTHS.lastOrNull { WanVideoNode.fitsPhone(w, h, it, 0, ramBytes, arch, WanVideoNode.H3) }?.let(::label)

    /** ⚠ Pure: H3's latent frames for a clip of [frames] (5 for the first five video frames' two, then 5 a step). */
    fun latentFrames(frames: Int): Int = 5 * ((frames - 5) / 17) + 2
}

/**
 * ⭐⭐ The structured prompt H3 is trained on (`docs/MINIMAX-H3.md` §5c, ClipProj's demo and ComfyUI's rewriter format).
 * The Prompt node holds it ([VideoPrompts.H3]); this sets its stated length to the clip's, and wraps a plain prompt so
 * a one-liner still gets a soundscape and music section (left open, music crept in and voices came out robotic).
 */
object H3Prompt {

    private val LASTS = Regex("""The target video lasts [0-9]+(\.[0-9]+)? seconds\.""")

    /** ⚠ Pure: [prompt] for a clip of [frames]: a structured one with its length set, a plain one wrapped. */
    fun build(prompt: String, frames: Int, fps: Int = 24): String {
        val text = prompt.trim()
        val lasts = "The target video lasts " + String.format(java.util.Locale.ROOT, "%.2f", frames.toDouble() / fps) + " seconds."
        if ("subject_definitions:" in text) return if (LASTS.containsMatchIn(text)) text.replace(LASTS, lasts) else text
        val scene = sentence(text)
        return listOf(
            "subject_definitions:\nN/A - no speaking character is defined.",
            "summary:\nText to video with audio. One continuous shot, no cut." + (if (scene.isNotEmpty()) " $scene" else ""),
            "retention_analysis:\nN/A - no reference images, video or audio are supplied; everything is generated.",
            "detailed_description:\n$lasts\n[Shot 1] $scene".trimEnd(),
            "overall_soundscape:\nDiegetic sound only: the natural sound of the scene.",
            "non_diegetic_music:\nN/A - no score, no soundtrack, no musical arrangement at any point.",
        ).joinToString("\n\n")
    }

    private val SHOT = Regex("""\[Shot 1\][^\n]*""")

    /**
     * ⚠ Pure: a later PART's prompt ([WanParts]): a structured line is its own prompt; a plain line takes the place of
     * the main prompt's `[Shot 1]` scene, so who speaks and how they sound, the soundscape and the music carry through
     * every part. A plain main prompt has nothing to carry: the line is wrapped on its own.
     */
    fun forPart(main: String, line: String, frames: Int, fps: Int = 24): String {
        val l = line.trim()
        if ("subject_definitions:" in l || "subject_definitions:" !in main || !SHOT.containsMatchIn(main)) return build(l, frames, fps)
        return build(main.trim().replaceFirst(SHOT, Regex.escapeReplacement("[Shot 1] " + sentence(l))), frames, fps)
    }

    /** A plain prompt as one sentence: trimmed, a full stop added when it has no end mark. */
    private fun sentence(s: String): String {
        val t = s.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return t
        return if (t.last() in ".!?。！？") t else "$t."
    }
}
