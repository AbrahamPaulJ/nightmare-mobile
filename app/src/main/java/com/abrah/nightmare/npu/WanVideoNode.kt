package com.abrah.nightmare.npu

import android.graphics.Bitmap
import android.util.Log
import com.abrah.nightmare.ContextKey
import com.abrah.nightmare.ModelCatalog
import com.abrah.nightmare.Node
import com.abrah.nightmare.NodeCtx
import com.abrah.nightmare.NodeType
import com.abrah.nightmare.Ops
import com.abrah.nightmare.Port
import com.abrah.nightmare.PromptLoras
import com.abrah.nightmare.R
import com.abrah.nightmare.Value
import com.abrah.nightmare.Widget
import com.abrah.nightmare.text
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.ensureActive

/**
 * ⭐⭐ **Wan 2.2 video** — a prompt, or a prompt and a photo, into a clip, on the DiT engine
 * (`docs/VIDEO-GGML.md`). The second video sampler beside Neodragon's [VideoSampleNode]: slower
 * (about 2.5 minutes for 5 s at 832x480 on an 8 Elite) and much better.
 *
 * ⚠⚠ **Its sibling is [VideoSampleNode] and it keeps that node's shape**: `prompt` + an optional
 * `image` (wired = image to video), the framing params, a seed that rolls at 0, one VIDEO out,
 * the result drawn by `core.output`. What differs is WHERE it runs: through the backend with
 * `--type wan` ([contextKey]), so it binds a context key like a DiT sampler and the executor
 * relaunches for it like for any other model.
 *
 * ⚠ The clip leaves the backend as a FILE ([Ops.video]), and is encoded to MP4 one frame at a
 * time ([VideoWriter.encode]'s frame-provider form): 121 frames at 1280x704 are 436 MB as Bitmaps.
 */
object WanVideoNode : NodeType {

    /** ⚠ Minutes on the NPU. Never run for a preview. */
    override val appSide = false

    override val name = "wan.sample"
    override val version = "1"
    override val inputs = listOf(Port("prompt", "PROMPT"), Port("image", "IMAGE"))

    /**
     * ⭐ `last_frame` is the clip's final frame as a picture (GitHub #23, the user's call 2026-10-14):
     * wired into another video node's `image`, with its own prompt, it continues the clip, as many
     * times as the canvas holds. Joining the clips into one file is not done here.
     */
    override val outputs = listOf(Port("video", "VIDEO"), Port(LAST_FRAME, "IMAGE"))
    const val LAST_FRAME = "last_frame"
    override val category = "generate"
    override val paletteName = "Wan 2.2 Turbo"

    override fun titleFor(node: Node): String =
        if (node.inputs["image"] != null) "Image to video" else "Text to video"

    override val defaultId = "video"
    override val about = "a prompt, or a prompt and a photo, into a clip with Wan 2.2 Turbo"

    /** ⚠ The clip belongs to `core.output`, like every other result. */
    override val showsResult = false

    /**
     * ⭐⭐ The clip's size is two sliders, as on the FLUX nodes (GitHub #23 asked for 1:1, 4:3, 21:9 and
     * phone-screen clips; the user's call 2026-10-14). Wan's grid is 32 px. ⚠ The AREA is capped at
     * 1280x704, the largest size run here (and Wan 2.2 TI2V's native one): [fitArea] keeps a pair
     * under it, so any shape costs at most what 704p costs, and [pinnedBytes] / [fitsNpuMap] still
     * gate it by its real token count. They replaced a `shape` (landscape/portrait) and a `size`
     * (480p/704p) chooser; [fromLegacy] reads a flow saved with those.
     */
    const val MIN_SIDE = 320
    const val MAX_SIDE = 1280
    const val GRID = 32
    const val MAX_AREA = 1280 * 704
    const val DEFAULT_W = 832
    const val DEFAULT_H = 480

    /** ⚠ 24, the rate Wan 2.2 was trained at; a clip is `24·s + 1` frames (Wan wants 4n+1). */
    const val FPS = 24
    const val MAX_SECONDS = 5

    /**
     * ⭐ `width` and `height` are drawn as one pair at the top of the sheet (`NodeInspector`'s size
     * panel, committed when the finger lifts); the rest in this order UNDER the crop.
     */
    override val widgets get() = listOf(
        Widget("width", "int", DEFAULT_W.toString(), MIN_SIDE.toDouble(), MAX_SIDE.toDouble(), step = GRID,
            hintRes = R.string.hint_wan_size),
        Widget("height", "int", DEFAULT_H.toString(), MIN_SIDE.toDouble(), MAX_SIDE.toDouble(), step = GRID),
        // ⭐ The image nodes' LoRA picker (`NodeInspector`'s LoRA row, drawn at the top, never in the
        // knob loop); merged with the prompt's `<lora:…>` tags in [runPorts].
        Widget(com.abrah.nightmare.SdSampler.LORAS, "string", ""),
        Widget("seed", "int", "0", hintRes = R.string.hint_seed_clip),
        // ⭐ 0 = ONE frame, a still picture (a tester's wish, 2026-10-19: "1 frame Wan 2.2, basically a txt to img").
        Widget("seconds", "int", "5", 0.0, MAX_SECONDS.toDouble(), hintRes = R.string.hint_wan_seconds),
        // ⭐ Twice the frames, a new one between every pair ([FrameInterp], the user's call 2026-10-26). Every Wan node.
        Widget(SMOOTH, "bool", "false", hintRes = R.string.hint_wan_smooth),
        // ⭐ Up to 10 parts, each starting on the last frame of the one before ([WanParts], the user's call 2026-10-27).
        Widget(WanParts.PARTS, "int", "1", 1.0, WanParts.MAX.toDouble(), hintRes = R.string.hint_wan_parts),
        Widget(WanParts.PART_PROMPTS, "string", "", hintRes = R.string.hint_wan_part_prompts),
        Widget(WanParts.KEEP_COLOURS, "bool", "true", hintRes = R.string.hint_wan_keep_colours),
        // ⭐ The framing, exactly as [VideoSampleNode] carries it.
        Widget("x", "float", "0.0", 0.0, 1.0, hintRes = R.string.hint_drag_frame),
        Widget("y", "float", "0.0", 0.0, 1.0),
        Widget("w", "float", "1.0", 0.0, 1.0),
        Widget("h", "float", "1.0", 0.0, 1.0),
    )

    /** ⭐ [WanFunNode]'s: how its driving video's frames become the control video. */
    const val MOTION_MODE = "motion_mode"

    /** ⭐ Smooth motion: the clip at twice its frame rate, IFRNet's frames between the model's ([FrameInterp]). */
    const val SMOOTH = "smooth"

    /** ⭐ The clip takes the wired picture's shape (Text to video (14B), `WanA14Node`): a hidden knob the flow sets. */
    const val FOLLOW_PICTURE = "follow_picture"

    /**
     * ⭐ One backend process serves every size and length: like every DiT model, the size is a
     * REQUEST field, so the key is the model's own ([com.abrah.nightmare.backendContextKey]'s rule).
     */
    override fun contextKey(node: Node): ContextKey = keyFor(node, ModelCatalog.WAN22)

    /**
     * ⭐⭐ The model a Wan node runs: its node IS its model (no `model` param), except the 14B's, whose wired picture
     * picks the I2V experts. THE table: the pre-run download check, the API's and the harness's all read it.
     */
    fun specOf(node: Node): com.abrah.nightmare.ModelSpec? = when (node.type) {
        name -> ModelCatalog.WAN22
        WanFunNode.name -> ModelCatalog.WAN22_FUN
        WanA14Node.name -> WanA14Node.specFor(node)
        WanNsfw13Node.name -> ModelCatalog.WAN21_NSFW13
        H3Node.name -> ModelCatalog.MINIMAX_H3
        else -> null
    }

    /** ⭐ The launch key for [spec] (Wan 2.2 Turbo here, Fun Control for [WanFunNode]). */
    internal fun keyFor(node: Node, spec: com.abrah.nightmare.ModelSpec): ContextKey {
        val p = effectiveParams(node)
        val prof = profileOf(node.type, deviceArch())
        val (w, h) = sizeOf(p, prof)
        val caps = com.abrah.nightmare.DeviceProbe.caps()
        val budget = memoryBudget(
            pinnedBytes(w, h, passFrames(node, p), prof),
            caps.ramBytes, if (caps.known) caps.arch else 0, prof = prof,
        )
        return ContextKey(spec.backendType, spec.id, spec.native.width, spec.native.height, budget)
    }

    /**
     * ⭐⭐ Pure: the engine's memory budget (GiB) for a clip that [pinned] too much to hold resident, or null.
     * With it (and `all=disk`) the DiT streams one block at a time instead of holding its 2.9 GB of weights
     * plus the whole compute: 704x1280 for 5 s rendered on the 12 GB S25 with apps open in 750 s, min 2.2 GB
     * free, where unbudgeted it was killed ~15 s in (2026-10-15). It costs ~30% (480p 5 s: DiT 196 s vs
     * 152 s), so only clips over [BUDGET_FROM] get it, below the 16 GB class. ⚠ One block at 704p 5 s needs
     * 3.7 GB by itself (attention), so 4 GiB is the floor there.
     * ⚠⚠ v75: 2.75 GiB (under the ~3.1 GB map) for EVERY clip. A real 8 Gen 3 (a tester, 1.6.166, 2026-10-16)
     * rendered NOISE at 832x480 2 s, which fit [V75_NPU_BYTES] and so ran resident, and a clean clip at 1280x704,
     * which ran budgeted; a fresh backend per clip did not help. The same tester's LoRA put the resident DiT at
     * 3.0 GB and fastrpc refused the compute. Budgeted, the weights stream and a LoRA's add little.
     * ⚠⚠ [stream] (Settings → Memory → Video: stream the model, GitHub #48/#59) budgets EVERY clip, and the budget
     * must sit BELOW the weights: the engine keeps the whole graph resident whenever weights plus compute fit, so
     * the switch's first fixed 4 GiB still ran a 2 s 480p clip resident (2,892 + 528 MB) and a 16 GB 8 Elite drew
     * its colour grid there, while 5 s (compute 1.46 GB) streamed and came out clean. 2.75 when one block fits it,
     * else 4: such a clip is over 4 GiB whole, so it streams there too.
     */
    fun memoryBudget(
        pinned: Long, ramBytes: Long, arch: Int, stream: Boolean = com.abrah.nightmare.Prefs.wanStream,
        prof: WanProfile = TURBO,
    ): String? = when {
        // ⭐ MiniMax H3 ([H3]): 11.6 GB of DiT, so every clip streams; 4.5 GiB holds one block of every size it offers on
        // v79 (832x480 3 s: block 0 needs 4,416 MB with a structured prompt), 2.75 below (`docs/MINIMAX-H3.md` §5d).
        prof.sound -> if (arch in 1..78) "2.75" else H3_BUDGET
        // ⭐ Wan 2.2 A14B: 8.6 GB an expert, held by no phone, so every clip streams ([WanProfile.alwaysStream]).
        // ⭐ Below v79 one FastRPC session maps ~3.1 GB, so the 14B streams there at 2.75 like everything else (the S25
        // forced to v75, 480x832 1 s, `docs/DEVICES.md` §3e).
        prof.alwaysStream -> if (arch in 1..78) "2.75" else if (pinned - prof.ditBytes > A14B_SMALL_COMPUTE) A14B_BIG_BUDGET else A14B_BUDGET
        arch in 1..78 -> "2.75"
        stream -> if (pinned - prof.ditBytes + BLOCK_BYTES <= STREAM_FLOOR) "2.75" else "4"
        // ⭐ Compute that one NPU session cannot map streams on EVERY phone (Saved #7101, a 16 GB 8 Elite, 1.6.216):
        // 704x1280 5 s holds 3.4 GB of compute, over the ~3.1 GB a batch maps (`vmem 3311845376`), and resident it
        // aborted at the first DiT graph (`dspqueue_read failed`) where its 2 s clips rendered.
        pinned - prof.ditBytes > RESIDENT_COMPUTE_MAX -> "4"
        ramBytes in 1 until com.abrah.nightmare.Prefs.LOWRAM_BELOW_BYTES -> "4".takeIf { pinned > BUDGET_FROM }
        else -> null
    }

    private const val H3_BUDGET = "4.5"

    private const val STREAM_FLOOR = 2_816L shl 20

    /** ⚠ The most compute a clip may hold resident: under the ~3.1 GB one NPU batch maps, with room for the rest. */
    private const val RESIDENT_COMPUTE_MAX = 2_816L shl 20

    private const val BUDGET_FROM = 4_608L shl 20

    /**
     * ⚠ Pure: what a clip holds on the NPU at its worst: [pinnedBytes] resident, or under a [memoryBudget]
     * one block at a time, its compute plus [BLOCK_BYTES] (704x1280 5 s: 3,410 MB of compute, and the engine
     * asked for 3,705 MB). What the run-log warnings judge, so a budgeted clip is not warned about as if whole.
     */
    fun peakBytes(w: Int, h: Int, frames: Int, budget: String?, loras: Int = 0, prof: WanProfile = TURBO): Long =
        if (budget == null) pinnedBytes(w, h, frames, prof)
        else tokens(w, h, frames, prof) * (prof.bytesPerToken + loras * LORA_BYTES_PER_TOKEN) + prof.blockBytes

    private const val BLOCK_BYTES = 300L shl 20

    /**
     * ⚠ A runtime LoRA's share of a budgeted block, per token: a tester's 8 Gen 3 with two LoRAs
     * (2026-10-16) needed 4,848 MB for one block at 928x928 121 frames (26,071 tokens, 186 KB a token;
     * 124 without, 704p's 3,385 MB at 27,280), and rendered 49 frames (10,933 tokens) under 2.75 GiB.
     */
    private const val LORA_BYTES_PER_TOKEN = 32L shl 10

    /**
     * ⭐ Which of Wan 2.2 A14B's experts a LoRA file is for, from its name, as engine `dit/068` reads the path: a Wan 2.2
     * pair names its halves ("..._high_noise", "HighNoise", "..._H.safetensors"), and anything else is a Wan 2.1 14B
     * LoRA, one file for both experts (Joachim's, TG #7454, is one).
     */
    enum class Expert(val prefix: String) { HIGH("high:"), LOW(""), BOTH("both:") }

    fun expertOf(fileName: String): Expert {
        val n = fileName.substringBeforeLast('.').lowercase()
        return when {
            Regex("high[ _-]?noise|(^|[^a-z])high([^a-z]|$)|_h$|-h$").containsMatchIn(n) -> Expert.HIGH
            Regex("low[ _-]?noise|(^|[^a-z])low([^a-z]|$)|_l$|-l$").containsMatchIn(n) -> Expert.LOW
            else -> Expert.BOTH
        }
    }

    /**
     * ⭐ Pure: whether [need] fits the chip. Under a [budget] the DiT streams, so ONE block must fit
     * the budget (the tester above: `need 4847.66 MB device / 2816 MB budget`); resident, the v75 map.
     */
    fun fitsChip(need: Long, arch: Int, budget: String?): Boolean =
        budget?.toDoubleOrNull()?.let { need <= (it * (1L shl 30)).toLong() } ?: fitsNpuMap(need, arch)

    /**
     * ⭐ Pure: the longest clip, in seconds, that fits at [w] x [h] with [loras] LoRAs on a phone with
     * [ramBytes] and [arch]; 0 when not even 1 s does. What the slider warning offers instead.
     */
    fun secondsThatFit(w: Int, h: Int, loras: Int, ramBytes: Long, arch: Int, prof: WanProfile = TURBO): Int =
        (prof.maxSeconds downTo 1).firstOrNull { s -> fitsPhone(w, h, framesFor(s, prof), loras, ramBytes, arch, prof) } ?: 0

    /** ⚠ Pure: both tests the run log warns on, as one answer. */
    fun fitsPhone(w: Int, h: Int, frames: Int, loras: Int, ramBytes: Long, arch: Int, prof: WanProfile = TURBO): Boolean {
        val budget = memoryBudget(pinnedBytes(w, h, frames, prof), ramBytes, arch, prof = prof)
        val need = peakBytes(w, h, frames, budget, loras, prof)
        return fitsNpu(w, h, frames, need, arch, budget, prof) && (ramBytes <= 0 || fits(need, ramBytes))
    }

    /**
     * ⚠ Pure: a profile with a measured token ceiling ([WanProfile.maxTokens], MiniMax H3) holds the clip. H3's
     * ceilings are where its runs stopped fitting, not a model: v79 ran 8,640 tokens (1152x640 1.6 s, 1.69 GB left)
     * and refused nothing under it; the S25 forced to v75 ran 7,056 (768x1344 0.9 s) and failed at 8,580 and 8,670.
     */
    fun fitsTokens(w: Int, h: Int, frames: Int, arch: Int, prof: WanProfile = TURBO): Boolean =
        prof.maxTokens <= 0 || tokens(w, h, frames, prof) <= if (arch in 1..78) prof.lowArchMaxTokens else prof.maxTokens

    /**
     * ⚠ Pure: the NPU holds the clip: by the measured token ceiling where a profile has one ([fitsTokens]; the byte model
     * would refuse H3's 768x1344 0.9 s at v75, which rendered), else by [fitsChip].
     */
    fun fitsNpu(w: Int, h: Int, frames: Int, need: Long, arch: Int, budget: String?, prof: WanProfile = TURBO): Boolean =
        if (prof.maxTokens > 0) fitsTokens(w, h, frames, arch, prof) else fitsChip(need, arch, budget)

    /** ⚠ The backend launch that served the last Wan clip ([com.abrah.nightmare.BackendProcess.processId]). */
    @Volatile private var servedBy: Int? = null

    /** ⭐ The clip's size, which a picture wired into `image` is framed to. */
    override fun framesTo(node: Node): Pair<Int, Int> = sizeOf(effectiveParams(node), profileOf(node.type, deviceArch()))

    /**
     * ⭐ A clip's frame count: its Seconds, or on Motion transfer the Video tab's window, which IS
     * the length ([WanFun.framesForGap]). The launch key and the run both read this.
     */
    internal fun framesOf(node: Node, p: Map<String, String>): Int =
        if (node.type == WanFunNode.name) WanFun.windowOf(p).let { (s, e) -> WanFun.framesForGap(e - s, WanFun.fpsOf(p)) }
        // ⭐ MiniMax H3: its length chips ([H3Node.LENGTHS]), not whole seconds.
        else if (node.type == H3Node.name) H3Node.framesOf(p)
        else profileOf(node.type).let { prof ->
            framesFor((p["seconds"]?.toIntOrNull() ?: prof.maxSeconds).coerceAtMost(prof.maxSeconds), prof)
        }

    /**
     * ⭐ The frames ONE pass of the engine holds: the clip's, or for Motion transfer its longest part
     * ([WanFun.partsOf]). What the memory budget and the size warnings judge: a 30 s clip is six 5 s passes.
     */
    internal fun passFrames(node: Node, p: Map<String, String>): Int =
        framesOf(node, p).let { f ->
            if (node.type == WanFunNode.name) sizeOf(p).let { (w, h) -> minOf(f, WanFun.partFramesFor(w, h)) } else f
        }

    /** ⚠ Pure: the frame size the params ask for, on [prof]'s grid (32 px; the 14B's 16). */
    fun sizeOf(p: Map<String, String>, prof: WanProfile = TURBO): Pair<Int, Int> =
        snap(p["width"]?.toIntOrNull() ?: DEFAULT_W, prof) to snap(p["height"]?.toIntOrNull() ?: DEFAULT_H, prof)

    private fun snap(v: Int, prof: WanProfile): Int =
        (Math.round(v / prof.grid.toDouble()) * prof.grid).toInt().coerceIn(prof.minSide, prof.maxSide)

    /**
     * ⚠ Pure: [w] x [h] with the side that did NOT move ([widthMoved] says which did) shrunk until
     * the pair is within [prof]'s area. Dragging width to 1280 at a height of 1280 gives 1280x704 (the 14B: 1280x720).
     */
    fun fitArea(w: Int, h: Int, widthMoved: Boolean, prof: WanProfile = TURBO): Pair<Int, Int> {
        val sw = snap(w, prof)
        val sh = snap(h, prof)
        if (sw.toLong() * sh <= prof.maxArea) return sw to sh
        return if (widthMoved) sw to fitSide(sw, prof) else fitSide(sh, prof) to sh
    }

    /**
     * ⭐ Pure: the largest clip in the SHAPE of a [srcW] x [srcH] picture, on the grid and within
     * [MAX_AREA] (GitHub #25: a picture of another shape is cropped to the clip's, and the error that came
     * with it). Rounded DOWN, so the pair never asks for more than Wan's limit.
     */
    fun matchShape(srcW: Int, srcH: Int, prof: WanProfile = TURBO, area: Int = prof.maxArea): Pair<Int, Int> {
        val a = srcW.toDouble() / srcH.coerceAtLeast(1)
        var h = kotlin.math.sqrt(area.coerceAtMost(prof.maxArea) / a)
        var w = h * a
        if (w > MAX_SIDE) { w = MAX_SIDE.toDouble(); h = w / a }
        if (h > MAX_SIDE) { h = MAX_SIDE.toDouble(); w = h * a }
        fun down(v: Double) = ((v / prof.grid).toInt() * prof.grid).coerceIn(MIN_SIDE, MAX_SIDE)
        return down(w) to down(h)
    }

    /** ⚠ Pure: whether a picture's shape is more than ~4% off the clip's (a 32 px grid step is ~4% at 832). */
    fun shapeDiffers(srcW: Int, srcH: Int, w: Int, h: Int): Boolean =
        kotlin.math.abs(kotlin.math.ln((srcW.toDouble() / srcH) / (w.toDouble() / h))) > 0.04

    /**
     * ⭐ Pure: the largest clip of EXACTLY the picture's shape on [prof]'s grid within [area], or null when that shape
     * has no grid size near [area] (an odd shape: then [matchShape] and [centredFrame] trim a few edge pixels). 512x768
     * is 512x768 at 832x480 worth and 768x1152 at 1280x720 worth, so nothing is stretched or trimmed.
     */
    fun exactShape(srcW: Int, srcH: Int, prof: WanProfile, area: Int): Pair<Int, Int>? {
        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
        if (srcW <= 0 || srcH <= 0) return null
        val g = gcd(srcW, srcH)
        val a = srcW / g
        val b = srcH / g
        val ma = prof.grid / gcd(a, prof.grid)
        val mb = prof.grid / gcd(b, prof.grid)
        val m = ma / gcd(ma, mb) * mb
        val ua = a.toLong() * m
        val ub = b.toLong() * m
        val k = kotlin.math.sqrt(area.coerceAtMost(prof.maxArea).toDouble() / (ua * ub)).toLong()
        if (k < 1) return null
        val w = (ua * k).toInt()
        val h = (ub * k).toInt()
        // ⚠ Only when it is close to the asked pixels: a shape whose grid step is coarse would drop far below them.
        if (w < MIN_SIDE || h < MIN_SIDE || w > MAX_SIDE || h > MAX_SIDE || w.toLong() * h < area * 85L / 100) return null
        return w to h
    }

    /**
     * ⚠ Pure: the largest centred frame of a [srcW] x [srcH] picture in the shape of a [w] x [h] clip, as normalised
     * `x, y, w, h`: what is trimmed is the sliver the grid's rounding left, so nothing is ever stretched.
     */
    fun centredFrame(srcW: Int, srcH: Int, w: Int, h: Int): List<Float> {
        val pa = srcW.toFloat() / srcH.coerceAtLeast(1)
        val ca = w.toFloat() / h.coerceAtLeast(1)
        return if (pa > ca) (ca / pa).let { fw -> listOf((1f - fw) / 2f, 0f, fw, 1f) }
        else (pa / ca).let { fh -> listOf(0f, (1f - fh) / 2f, 1f, fh) }
    }

    /** The largest grid side that keeps `other x it` within [prof]'s area. */
    private fun fitSide(other: Int, prof: WanProfile): Int =
        (prof.maxArea / other / prof.grid * prof.grid).coerceIn(prof.minSide, prof.maxSide)

    /** ⚠ Pure: the `width`/`height` a flow saved with the old `size`/`shape` choosers meant, or null. */
    fun fromLegacy(p: Map<String, String>): Pair<Int, Int>? {
        if ("size" !in p && "shape" !in p) return null
        val (w, h) = if (p["size"] == "704p") 1280 to 704 else 832 to 480
        return if (p["shape"] == "portrait") h to w else w to h
    }

    /** ⚠ Pure: `24·s + 1` frames for [seconds], clamped to 0..[MAX_SECONDS]: 0 is one frame, a still. */
    fun framesFor(seconds: Int, fps: Int = FPS): Int = seconds.coerceIn(0, MAX_SECONDS) * fps + 1

    /** ⚠ Pure: [framesFor] on [prof]'s own grid: Wan's `fps·s + 1`, MiniMax H3's 17k+5 ([H3Node.framesFor]). */
    fun framesFor(seconds: Int, prof: WanProfile): Int = if (prof.sound) H3Node.framesFor(seconds) else framesFor(seconds, prof.fps)

    /**
     * ⭐ quanhaol's Self-Forcing timesteps 1000/750/500/250 warped by shift 5, through `lcm`
     * (`docs/VIDEO-GGML.md` §3). ⚠ Turbo's schedule: a different Wan model needs its own.
     */
    val SIGMAS = listOf(1.0, 0.9375, 0.8333, 0.625, 0.0)

    /** ⚠ Pure: Wan 2.2's latent tokens — 32 px a side after VAE and patch, one latent frame per 4. */
    fun tokens(w: Int, h: Int, frames: Int, prof: WanProfile = TURBO): Long =
        (w.toLong() / prof.pxPerToken) * (h.toLong() / prof.pxPerToken) *
            (if (prof.sound) H3Node.latentFrames(frames) else (frames - 1) / 4 + 1)

    /**
     * ⚠ Pure: what a clip PINS on the NPU — the Q4_0 DiT's 2.9 GB and ~0.125 MB of compute per
     * token (0.64 GB at 6k tokens, 3.4 GB at 27k, `docs/VIDEO-GGML.md` §4).
     * 832x480 for 5 s: 4.3 GB; 1280x704 for 5 s: 6.2 GB.
     */
    fun pinnedBytes(w: Int, h: Int, frames: Int, prof: WanProfile = TURBO): Long =
        prof.ditBytes + tokens(w, h, frames, prof) * prof.bytesPerToken

    /**
     * ⚠ Pure: whether [pinned] fits a phone with [ramBytes]: at most [RAM_SHARE] of it (the S25: 6.3 GB of 10.9). Every size
     * the 12 GB S25 Ultra ran (up to 1280x704 for 5 s, 0.9 GB left at the worst) passes; an 8 GB
     * phone gets 480p up to about 3 s.
     */
    fun fits(pinned: Long, ramBytes: Long): Boolean = pinned <= (ramBytes * RAM_SHARE).toLong()

    /**
     * ⚠ Pure: whether [pinned] fits what an 8 Gen 3's (v75) NPU will MAP for one process. Measured
     * on real SM8650s (GitHub #20 and a tester's log, 2026-10-14, 1.6.153): 832x480 for 2 s
     * (3.5 GB by [pinnedBytes]) rendered in 85 s, every time; 5 s (4.3 GB) failed at once, its
     * 1.46 GB compute buffer refused by fastrpc after the 2.9 GB of weights. The cap sits just above
     * the size that worked; 3 s (3.7 GB) and 704p are unmeasured, so a run past it is WARNED (not refused).
     * ⚠ [arch] 0 (unknown) and v79+ are not capped: the 8 Elite mapped 6.2 GB.
     */
    fun fitsNpuMap(pinned: Long, arch: Int): Boolean = arch !in 1..78 || pinned <= V75_NPU_BYTES

    private const val V75_NPU_BYTES = 3_600L shl 20

    /** ⭐ The seconds a new Wan flow opens on: [MAX_SECONDS], or 2 on an 8 Gen 3 ([fitsNpuMap]). */
    fun openingSeconds(): Int {
        val caps = com.abrah.nightmare.DeviceProbe.caps()
        return if (caps.known && caps.arch in 1..78) V75_SECONDS else MAX_SECONDS
    }

    private const val V75_SECONDS = 2

    /**
     * ⚠ Pure: what to try after the NPU refused a clip's memory: never "480p" to a clip already that small
     * (a tester at 832x480 was told to choose 480p, 2026-10-16), and LoRAs first, since they ride on the DiT.
     */
    fun mapAdvice(w: Int, h: Int, hasLoras: Boolean): String {
        val small = w.toLong() * h <= 832L * 480
        return when {
            hasLoras && small -> "Remove a LoRA or choose fewer seconds"
            hasLoras -> "Remove a LoRA, choose fewer seconds or 480p"
            small -> "Choose fewer seconds"
            else -> "Choose fewer seconds or 480p"
        }
    }

    /** The engine's words when fastrpc refused a buffer — it SIGSEGVs right after. */
    private val NPU_MAP_FAILED =
        Regex("""fastrpc_mmap failed|failed to allocate HTP0 buffer|cannot make enough memory available""")

    private const val DIT_BYTES = 2_900L shl 20
    private const val BYTES_PER_TOKEN = 128L shl 10

    /**
     * ⭐⭐ What differs between the Wan models this node shape serves: Turbo 5B (and Fun Control, its sibling) and
     * Wan 2.2 A14B ([WanA14Node]). [pxPerToken]: image px a side per DiT token (the 5B's VAE is 16x16 and its patch
     * 2, so 32; the 14B's Wan 2.1 VAE is 8x8, so 16: four times the tokens at the same size). [ditBytes] and
     * [bytesPerToken] model what a clip pins resident ([pinnedBytes]), [blockBytes] one streamed block.
     */
    data class WanProfile(
        val fps: Int,
        val sampler: String,
        val pxPerToken: Int,
        val ditBytes: Long,
        val bytesPerToken: Long,
        val blockBytes: Long,
        val alwaysStream: Boolean,
        /** ⭐ The size grid (px) and the most pixels a clip may have: the size picker, Match picture and the run. */
        val grid: Int = GRID,
        val maxArea: Int = MAX_AREA,
        /** ⭐ The longest clip it offers, in seconds. */
        val maxSeconds: Int = MAX_SECONDS,
        /** ⭐ Its sampling schedule ([SIGMAS] is Turbo's and the 14B's 4 steps). */
        val sigmas: List<Double> = SIGMAS,
        /** ⚠ The most pixels a frame decodes WHOLE; above it [vaeTileFor] tiles. Its decoder's cost decides it. */
        val decodeWholeArea: Long = SPATIAL_FROM,
        /** ⭐ Its steps: [sigmas]' count, or the engine's own schedule's when it sends none (MiniMax H3). */
        val steps: Int = sigmas.size - 1,
        /** ⭐ The size sliders' range. */
        val minSide: Int = MIN_SIDE,
        val maxSide: Int = MAX_SIDE,
        /** ⚠ A measured token ceiling ([fitsTokens]) on v79 and below it, or 0 for none. */
        val maxTokens: Long = 0L,
        val lowArchMaxTokens: Long = 0L,
        /**
         * ⭐ MiniMax H3: the clip has a SOUNDTRACK (muxed into the MP4), 17k+5 frames ([H3Node.framesFor]), its own
         * latent frames ([H3Node.latentFrames]), a structured prompt ([H3Prompt]), no LoRAs and no temporal tiling.
         */
        val sound: Boolean = false,
    )

    /** ⭐ Wan 2.2 Turbo 5B (and Fun Control): `docs/VIDEO-GGML.md` §1-4. */
    val TURBO = WanProfile(FPS, "lcm", 32, DIT_BYTES, BYTES_PER_TOKEN, 300L shl 20, alwaysStream = false)

    /**
     * ⭐ Wan 2.2 A14B with Lightning (`docs/VIDEO-GGML.md` §8): 16 fps, euler (lightx2v's workflow), Wan 2.1's
     * 8x8 VAE, and ALWAYS streamed: an expert is 8.6 GB. The engine runs its blocks in 4096-token chunks (`dit/046`):
     * measured on the S25, 2.27 GB of compute peak at 832x480 5 s (32,760 tokens, ~70 KB a token), 305 MB at 480x272 1 s.
     */
    val A14B = WanProfile(
        16, "euler", 16, 8_160L shl 20, 72L shl 10, 420L shl 20, alwaysStream = true,
        // ⭐ Its native 720p on its own 16 px tokens: 1280x720 (the 5B's 32 px grid stopped at 704, the user's call).
        grid = 16, maxArea = 1280 * 720,
        // ⚠ 3 s at most (the user, 2026-10-25: "isnt that hours"): 1 s at 720p is ~12 min measured, and attention grows
        // with the square of the length, so ~30 min for 2 s, ~1 h for 3 s and ~2 h for 5 s (estimates from the profile).
        maxSeconds = 3,
    )

    /**
     * ⚠ The 14B's budget (GiB): one block's chunked compute plus its weights must fit, the weights stream. 832x480 5 s
     * needs 4.19 GB with the engine's own reserve (S25, `dit/046`), over 4 GiB, so long clips get 4.5 ([A14B_BIG_BUDGET])
     * from [A14B_SMALL_COMPUTE] of compute (~20k tokens; 832x480 2 s is 14k and ran at 4).
     */
    private const val A14B_BUDGET = "4"
    private const val A14B_BIG_BUDGET = "4.5"
    private const val A14B_SMALL_COMPUTE = 1_400L shl 20

    /**
     * ⭐ The 14B below v79 (an 8 Gen 3, v75): the S25 forced to the v75 skel rendered 480x832 1 s at 2.75 GiB in 282 s,
     * 35.3-40.8 dB from v79's 259 s (2026-10-26, `docs/DEVICES.md` §3e), so it is offered up to that area. ⚠ 720p there
     * is not measured: the forced-v75 run sampled but its untiled decode failed (sd-cli, no `vae_tile`).
     */
    val A14B_LOW_ARCH = A14B.copy(maxArea = 832 * 480)

    /**
     * ⭐ The NSFW 1.3B ([WanNsfw13Node]): Wan 2.1's 8x8 VAE (16 px tokens, as the 14B), 16 fps, euler, its native 480p
     * at most. CausVid's 6 steps at shift 5 as sd.cpp schedules them, minus their last: sd.cpp's discrete list ends
     * 0, 0, a step from 0 to 0 that runs the DiT (~20 s at 832x480 2 s on the S25) and changes nothing. 1.5 GB Q8_0
     * DiT held resident; ~96 KB of compute a token (1,316 MB at 832x480 x33, 14,040 tokens: the S25, sd-cli).
     */
    val NSFW13 = WanProfile(
        16, "euler", 16, 1_442L shl 20, 96L shl 10, 60L shl 20, alwaysStream = false,
        grid = 16, maxArea = 832 * 480,
        sigmas = listOf(1.0, 0.9522, 0.8821, 0.7689, 0.5552, 0.0),
        decodeWholeArea = NSFW13_WHOLE_AREA,
    )

    /**
     * ⭐ MiniMax H3 ([H3Node], `docs/MINIMAX-H3.md` §5b and §5d): 24 fps, euler on the engine's own FastH3 schedule
     * (`dit/072`'s knots: no sigmas sent), 32 px tokens (16x VAE, patch 2), 384 to 1344 a side within ComfyUI's
     * 768x1344 cap, 5 s at most (124 frames, 384x384 measured at 716 s). Always streamed at a budget ([memoryBudget]).
     * ~300 KB of compute a token: 2,482 MB at 1152x640 1.6 s (8,640 tokens), 1,811 MB at 768x1344 0.9 s (7,056).
     * Its video VAE decodes whole frames in its own temporal chunks (`#2103`), so no spatial tile.
     */
    val H3 = WanProfile(
        24, "euler", 32, 11_583_540_608L, 300L shl 10, 1_200L shl 20, alwaysStream = true,
        grid = 32, maxArea = 768 * 1344, maxSeconds = 5,
        sigmas = emptyList(), steps = 4,
        decodeWholeArea = Long.MAX_VALUE,
        minSide = 384, maxSide = 1344,
        maxTokens = 8_800L, lowArchMaxTokens = 7_100L,
        sound = true,
    )

    /** ⭐ The profile a node type renders with on a chip of [arch] (0 = unknown, the full profile; pure for tests). */
    fun profileOf(type: String, arch: Int = 0): WanProfile = when (type) {
        WanA14Node.name -> if (arch in 1 until com.abrah.nightmare.ModelCatalog.DIT_MIN_ARCH) A14B_LOW_ARCH else A14B
        WanNsfw13Node.name -> NSFW13
        H3Node.name -> H3
        else -> TURBO
    }

    /** ⭐ This phone's arch for [profileOf] and [memoryBudget], 0 when the chip table does not know it. */
    fun deviceArch(): Int = com.abrah.nightmare.DeviceProbe.caps().let { if (it.known) it.arch else 0 }
    private const val RAM_SHARE = 0.58

    private fun gb(b: Long) = String.format(java.util.Locale.ROOT, "%.1f", b / (1024.0 * 1024 * 1024))

    /**
     * ⭐ TAEHV decodes in temporal tiles, and each tile starts cold: blended in, that cold start was a
     * brightness bump every 16 frames (a tester's 704p clip, 2026-10-14). `temporal_tile_cut` (engine
     * `dit/018`) gives a later tile's overlap no weight; overlap 3 lets it warm up for 9 frames first.
     * Same tile size, so the same decode memory. `docs/VIDEO-GGML.md` §3.
     */
    const val TILING = "temporal_tile_frames=6,temporal_tile_overlap=3,temporal_tile_cut=1"

    /**
     * ⚠ Pure: the decode's SPATIAL tile, in latent px (16 image px each), or 0 for the whole frame.
     * ⚠⚠ 704p died at the decode (~80%) on 12 GB phones with apps open: TAEHV's temporal tiles do
     * not shrink with the frame, ~1.1 to 1.5 GB each at 1280x704 (`docs/VIDEO-GGML.md` §4). Spatial
     * tiles (backend 031) cut that; frames up to [SPATIAL_FROM] decode whole, as before.
     */
    fun vaeTileFor(w: Int, h: Int, prof: WanProfile = TURBO): Int = if (w.toLong() * h > prof.decodeWholeArea) VAE_TILE else 0

    private const val SPATIAL_FROM = 832L * 480

    /**
     * ⚠ The NSFW 1.3B never decodes whole: at 832x480 x33 its `taew2_1` decode took MemAvailable from 4.9 GB to 0.84 GB
     * in 3 s and lmkd killed the app (S25, 3 of 3 runs, sd-cli too). Tile 32: 9.3 s, never under 3.85 GB free, no seam
     * (2026-10-29). ⚠ The 14B shares the decoder but keeps [SPATIAL_FROM]: it decoded 480x832 whole in 18 s on the S25
     * and its process streams, so nothing measured says it needs this.
     */
    private const val NSFW13_WHOLE_AREA = 0L
    private const val VAE_TILE = 32

    override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value =
        runPorts(ctx, node, inputs).getValue("video")

    override suspend fun runPorts(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Map<String, Value> =
        runClip(ctx, node, inputs, ModelCatalog.WAN22, null)

    /**
     * ⭐⭐ One clip on [spec]: Wan 2.2 Turbo, or Fun Control with a [driving] video ([WanFunNode]), whose
     * wired picture is then the REFERENCE (the model's `ref_conv`) rather than the start frame.
     */
    internal suspend fun runClip(
        ctx: NodeCtx, node: Node, inputs: Map<String, Value>,
        spec: com.abrah.nightmare.ModelSpec, driving: WanFun.Driving?,
    ): Map<String, Value> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val android = ctx.android
            ?: throw IllegalStateException(
                "node \"${node.id}\": the video path needs a platform context and this executor has none"
            )
        val wired = inputs["prompt"] as? Value.Prompt
            ?: throw IllegalArgumentException(
                "node \"${node.id}\": nothing is wired into \"prompt\". " +
                    "drag a Prompt node out of the palette and connect it"
            )
        // ⭐ `<lora:name:0.8>` tags become Wan LoRAs (a tester's ask, 2026-10-14): the image nodes'
        // lookup ([PromptLoras.resolve] in the LoRA folder), sent with the request. ⚠ A LoRA fits its own model: a 5B
        // one on the 5B, a 14B one (Wan 2.1 or 2.2) on the 14B (the engine logs what it bound).
        val pos = PromptLoras.extract(wired.positive)
        val neg = PromptLoras.extract(wired.negative)
        val loraDir = com.abrah.nightmare.BackendProcess.lorasDir(android)
        val installed = loraDir.list()?.toList().orEmpty()
        // ⭐⭐ Motion transfer takes NO LoRAs (the user's call, 2026-10-15): it has no picker, and its
        // prompt's tags are skipped out loud rather than reaching Fun by the back door. Its FastWan
        // speed LoRA is part of the model ([ModelCatalog.FUN_LORA]), not this list.
        // ⭐ The 14B takes them since 1.6.259 (Joachim, TG #7454): engine `dit/068` binds each to its expert ([expertOf]).
        // ⭐ MiniMax H3 takes none either: nothing is trained for it, and its tags are skipped out loud the same way.
        val sound = profileOf(node.type).sound
        val takesLoras = driving == null && !sound
        if (sound) {
            (pos.tags + neg.tags).forEach { t ->
                ctx.warn(ctx.text(R.string.run_lora_h3_skipped, "MiniMax H3 takes no LoRAs: \"%1\$s\" from the prompt was skipped", t.name))
            }
        } else if (!takesLoras) {
            (pos.tags + neg.tags).forEach { t ->
                ctx.warn(ctx.text(R.string.run_lora_motion_skipped, "Motion transfer takes no LoRAs: \"%1\$s\" from the prompt was skipped", t.name))
            }
        }
        // ⭐ The picker's LoRAs first. ⚠⚠ A picked file that is missing is REFUSED by name, the image
        // nodes' rule (`SdSampler.parseLoras`): the engine would log it and render without it.
        val picked = if (!takesLoras) emptyList() else com.abrah.nightmare.LoraSpec.parse(effectiveParams(node)[com.abrah.nightmare.SdSampler.LORAS]).map { e ->
            val f = File(loraDir, e.name)
            if (!f.isFile) throw IllegalStateException("LoRA \"${e.name}\" is not in ${loraDir.name}. Import it first")
            f.absolutePath to e.strength
        }
        val tagged = (if (takesLoras) pos.tags + neg.tags else emptyList()).mapNotNull { t ->
            PromptLoras.resolve(t.name, installed)?.let { File(loraDir, it).absolutePath to t.strength }
                ?: run {
                    ctx.warn(ctx.text(R.string.run_lora_tag_missing, "LoRA \"%1\$s\" from the prompt is not installed. Skipped", t.name))
                    null
                }
        }
        // ⚠ A tag naming a file the picker already has replaces the picker's strength, as on the image nodes.
        // ⭐ On the 14B each path carries its expert ([expertOf]); the cache key below includes it.
        val loras = (picked.filter { (f, _) -> tagged.none { it.first == f } } + tagged)
            .map { (f, s) -> if (node.type == WanA14Node.name) expertOf(File(f).name).prefix + f to s else f to s }
        if (loras.isNotEmpty()) {
            ctx.say("  LoRAs: " + loras.joinToString { (f, s) -> "${File(f).nameWithoutExtension} $s" })
        }
        // ⭐ The NSFW 1.3B's CausVid LoRA rides on every clip ([ModelCatalog.CAUSVID_LORA]): part of the model, as Fun's
        // FastWan is, so it is sent and counted but never shown or advised against as the user's.
        val clipLoras = loras + listOfNotNull(
            (File(spec.dir(android), ModelCatalog.CAUSVID_LORA).absolutePath to 1.0).takeIf { node.type == WanNsfw13Node.name },
        )
        val p = effectiveParams(node)
        val sizeProf = profileOf(node.type, deviceArch())
        // ⭐⭐ [FOLLOW_PICTURE] (Text to video (14B)): the clip takes the wired picture's SHAPE at the pixel count the size
        // asks for, uncropped. A picture model makes its own shape (AbsoluteReality 512x768), and cropping it to the
        // default 832x480 kept a strip (the user, 2026-10-25). 512x768 at 832x480 worth is 512x768; at 720p, 768x1168.
        val follow = p[FOLLOW_PICTURE] == "true" && inputs["image"] != null
        val followSrc = if (follow) (inputs["image"] as? Value.Image)?.let { ctx.images.get(it.id) } else null
        val (w, h) = sizeOf(p, sizeProf).let { (sw, sh) ->
            followSrc?.let { exactShape(it.width, it.height, sizeProf, area = sw * sh) ?: matchShape(it.width, it.height, sizeProf, area = sw * sh) }
                ?: (sw to sh)
        }
        // ⚠ The sliders cannot get here ([fitArea]); a hand-edited or plugin-written graph can.
        if (w.toLong() * h > sizeProf.maxArea) {
            throw IllegalArgumentException(
                "node \"${node.id}\": ${w}x$h is larger than this Wan's limit of 1280x${sizeProf.maxArea / 1280} worth of pixels. Make it smaller"
            )
        }
        val frames = framesOf(node, p)
        // ⭐ MiniMax H3 reads a STRUCTURED prompt: its stated length set to the clip's, a plain one wrapped ([H3Prompt]).
        val promptText = if (!sound) pos.text else H3Prompt.build(pos.text, frames, H3.fps)
        val seed = p["seed"]?.toLongOrNull() ?: 0L
        // ⚠ A fallback, not the roll: `HarnessOps.runRolled` rolls a 0 before the graph runs
        // ([com.abrah.nightmare.SAMPLER_TYPES]), for the reason [VideoSampleNode] gives.
        val used = if (seed == 0L) System.currentTimeMillis() and 0x7fffffffL else seed

        // ⚠⚠ WARNED, not refused (the user's call, 2026-10-15: "users dont like if we gate things from
        // them"; a tester on an 8 Gen 3 asked for a warning). A DMA-BUF overshoot can close the app or
        // reboot the phone (`docs/VIDEO-GGML.md` §4), so the run log says so first. ⚠ Against the phone's
        // RAM, NOT `MemAvailable`: that read 3.8 GB on the S25 right after a clip that ran from 6.3.
        val caps = com.abrah.nightmare.DeviceProbe.caps()
        val ram = caps.ramBytes
        val arch = if (caps.known) caps.arch else 0
        val prof = profileOf(node.type, arch)
        // ⭐ Motion transfer samples its driving video at the rate it asks for ([WanFun.MOTION_FPS]); the model has none.
        val fps = driving?.fps ?: prof.fps
        // ⚠ One PASS is what the NPU holds at once: Motion transfer runs a long clip as parts ([passFrames]).
        val pass = if (driving != null) minOf(frames, WanFun.partFramesFor(w, h)) else frames
        val budget = memoryBudget(pinnedBytes(w, h, pass, prof), ram, arch, prof = prof)
        val need = peakBytes(w, h, pass, budget, clipLoras.size, prof)
        if (ram > 0 && !fits(need, ram)) {
            ctx.warn(ctx.text(R.string.run_wan_ram_warning,
                "%1\$s for %2\$s s needs about %3\$s GB on the NPU, more than this phone's %4\$s GB of RAM comfortably holds. It may stop or restart the app. 480p or fewer seconds is safer",
                "${w}x$h", if (sound) H3Node.label(pass) else (pass / fps).toString(), gb(need), gb(ram)))
        }
        if (!fitsNpu(w, h, pass, need, arch, budget, prof)) {
            ctx.warn(ctx.text(R.string.run_wan_npu_warning,
                "%1\$s for %2\$s s needs about %3\$s GB of NPU memory at once, more than this phone's NPU takes, so it will probably fail. Trying anyway",
                "${w}x$h", if (sound) H3Node.label(pass) else (pass / fps).toString(), gb(need)))
        }

        val source = inputs["image"] as? Value.Image
        val motion = driving
        val cancelled = VideoWriter.cancelledWith(coroutineContext[kotlinx.coroutines.Job])
        // ⭐⭐ Motion transfer reads its driving window FIRST ([MotionTrack]): the crop follows the person, and the
        // picture is lined up with where they start.
        val plan = motion?.let { m -> readMotion(ctx, node, android, m, w, h, frames, cancelled) }
        val framedPng: ByteArray? = source?.let { img ->
            val src = ctx.images.get(img.id)
                ?: throw IllegalStateException("node \"${node.id}\": image ${img.id} is no longer in the store")
            fun f(k: String, d: Float) = p[k]?.toFloatOrNull() ?: d
            val aligned = if (motion?.align == true && plan != null) alignedFrame(ctx, android, src, plan, w, h) else null
            // ⚠ Following the picture: never stretched (the user: "I dont want that"). The clip is the picture's exact
            // shape ([exactShape]), so this frame is the whole picture; only an odd shape no grid size matches loses a
            // few edge pixels, cut equally from both sides ([centredFrame]). [CropNode.render] scales x and y apart.
            val (fx, fy, fw, fh) = when {
                aligned != null -> aligned.toList()
                follow -> centredFrame(src.width, src.height, w, h)
                else -> listOf(f("x", 0f), f("y", 0f), f("w", 1f), f("h", 1f))
            }
            val framed = com.abrah.nightmare.CropNode.render(
                src, fx, fy, fw, fh, w, h,
                com.abrah.nightmare.CropNode.PAD_BLACK,
            ).first
            ByteArrayOutputStream().also { framed.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
        // ⭐⭐ The picture starts the clip. On Motion transfer it is ALSO the reference (identity, the model's `ref_conv`):
        // as the first frame too it kept the strap and brooch the reference alone lost (the user's "A", 2026-10-27).
        val startPng = framedPng
        val refPng = framedPng.takeIf { motion != null }

        // ⭐⭐ Smooth motion ([SMOOTH]). ⚠ A clip CONTINUING a film takes the film's rate, whatever its switch says:
        // [FilmJoin] times both pieces at one fps, so a 32 fps part on a 16 fps film played the film at double speed.
        val prevFilm = source?.film?.let { File(it) }?.takeIf { it.isFile && motion == null }
        val asked = p[SMOOTH] == "true"
        var smooth = prevFilm?.let { (FilmJoin.fpsOf(it) ?: fps) > fps } ?: asked
        if (prevFilm != null && smooth != asked) {
            ctx.say(if (smooth) "  smooth motion on: the film this clip continues is smoothed"
                else "  smooth motion off: the film this clip continues is not smoothed")
        }
        if (smooth && FrameInterp.tierFor() == null) {
            ctx.warn(ctx.text(R.string.run_smooth_chip, "Smooth motion needs a Snapdragon 8 Gen 3 or newer. The clip stays at %1\$s fps", fps.toString()))
            smooth = false
        }
        if (smooth && FrameInterp.pick(w, h) == null) {
            ctx.warn(ctx.text(R.string.run_smooth_size, "Smooth motion works up to 1280x736 or 736x1280; %1\$s is larger. The clip stays at %2\$s fps", "${w}x$h", fps.toString()))
            smooth = false
        }
        // ⚠ Asked before the render, not after it: minutes of NPU for a clip that then could not be smoothed.
        if (smooth && !FrameInterp.isInstalled(android)) {
            throw com.abrah.nightmare.NeedsDownload(
                com.abrah.nightmare.NeedsDownload.INTERP, "node \"${node.id}\": ${FrameInterp.LABEL} is not installed (Models, Tools)",
            )
        }

        // ⭐⭐ Parts ([WanParts]): not Motion transfer (its own parts), not a one-frame still.
        val partCount = if (driving == null && frames > 1) WanParts.countOf(p) else 1
        val partPrompts = WanParts.promptsFor(
            pos.text, p[WanParts.PART_PROMPTS]?.lines()?.joinToString("\n") { PromptLoras.extract(it).text }, partCount,
        ).let { ps ->
            // ⭐ MiniMax H3: part 1 is the structured prompt; a later part's plain line takes its scene ([H3Prompt.forPart]).
            if (!sound) ps else ps.mapIndexed { k, pr -> if (k == 0) promptText else H3Prompt.forPart(pos.text, pr, frames, H3.fps) }
        }
        val keepColours = p[WanParts.KEEP_COLOURS] != "false"
        // ⚠ The decode's spatial tile changes the frames (a little), so it is part of the clip's key.
        val vaeTile = com.abrah.nightmare.BackendProcess.readWanVaeTileOverride(android) ?: vaeTileFor(w, h, prof)
        // ⚠ A content address of everything the clip is made from, as [VideoSampleNode] does.
        val key = sha1(
            (listOf(
                promptText, neg.text, used.toString(), "${w}x$h", frames.toString(), source?.address().orEmpty(),
                loras.joinToString(";") { (f, s) -> "$f@$s" },
            ) + listOfNotNull(
                // ⚠ The MODEL, for the 14B: a 5B 2 s clip and a 14B 3 s clip are both 49 frames, so the same prompt,
                // seed and size named one file. Turbo's and Fun's keys stay as they were (their cached clips).
                spec.id.takeIf { profileOf(node.type) != TURBO }?.let { "model=$it" },
                vaeTile.takeIf { it > 0 }?.let { "vae_tile=$it" },
                "smooth=2x".takeIf { smooth },
                // ⭐ Parts: their count, prompts and colour lock make different frames.
                partCount.takeIf { it > 1 }?.let { "parts=$it/${partPrompts.joinToString("\u0001")}/lock=$keepColours" },
                // ⚠⚠ The FRAMING too: `source.address()` is the unframed photo, so dragging the crop at
                // the same seed wrote the old clip's name (GitHub #27 again), and a chain served the old
                // film ([FilmJoin], keyed on this file's name).
                source?.let { "frame=" + listOf("x", "y", "w", "h").joinToString(",") { k -> p[k].orEmpty() } },
                motion?.let { m -> "motion=${m.uri}/${m.mode.id}/${m.startMs}/${m.rect?.joinToString(",").orEmpty()}" },
                // ⚠ The long-video knobs change the frames (rate, crop, start picture) or the file (sound).
                motion?.let { m -> "motion2=${m.fps}/${m.follow}/${m.align}/${m.keepAudio}" },
            ))
                .joinToString("\u0000")
        )
        val dir = File(android.cacheDir, "video").apply { mkdirs() }
        val raw = File(dir, "wan_$key.nmv")
        val out = File(dir, "clip_wan_$key.mp4")
        val clipFrames = plan?.frames ?: frames
        if (partCount > 1) ctx.say("$partCount parts of $clipFrames frames, each starting on the last frame of the one before" +
            if (keepColours) ", colours held to the first frame" else "")
        ctx.say("seed $used, ${w}x$h, $clipFrames frames" + when {
            motion != null -> ", your picture moving like the driving video"
            startPng != null -> ", animating your picture"
            sound -> ", with sound"
            else -> ""
        })
        Log.i(TAG, "wan: seed $used ${w}x$h x$clipFrames i2v=${startPng != null} motion=${motion != null}")

        // ⭐⭐ 8 Gen 3: every clip from a FRESH backend (the user's call, 2026-10-15). Real SM8650s turn
        // most clips to noise with no error, and the first clip after a restart is the clean one (#23;
        // #29's five-clip chain: clips 1-2 clean, 3+ noise); never reproduced on the S25 (VIDEO-GGML §6).
        // With `all=disk` the weights reload for every clip anyway, so a relaunch costs ~3 s. ⚠ Only when
        // THIS process already served a clip: the first after a launch goes straight through.
        if (arch in 1..78 && com.abrah.nightmare.BackendProcess.processId.let { it != null && it == servedBy }) {
            ctx.say("  a fresh backend for this clip (8 Gen 3)")
            com.abrah.nightmare.BackendProcess.stop()
            if (ctx.switchKey?.invoke(keyFor(node, spec)) != true) {
                throw IllegalStateException("node \"${node.id}\": the backend did not restart for this clip")
            }
        }
        servedBy = com.abrah.nightmare.BackendProcess.processId
        fun unwrap(r: Ops.Result<Ops.Clip>, file: File): Ops.Clip = when (r) {
            is Ops.Result.Ok -> r.value
            is Ops.Result.Err -> {
                file.delete()
                // ⭐ An NPU that could not map the clip's working buffer, said as such: the engine
                // crashes right after (SIGSEGV), and testers saw only "EOFException" (GitHub #20).
                val log = com.abrah.nightmare.BackendProcess.newestLaunch()
                // ⚠ The engine can also refuse and LIVE: `cannot make enough memory available … need 4847 MB
                // device / 2816 MB budget`, then a 200 stream error "generate_video failed" (a tester's
                // 928x928, 121 frames on an 8 Gen 3, 2026-10-16), which the -1 test missed.
                if ((r.code == -1 || "generate_video failed" in r.body) && log.any { NPU_MAP_FAILED.containsMatchIn(it) }) {
                    throw IllegalStateException(
                        "node \"${node.id}\": the NPU could not hold this clip's working memory " +
                            "(${w}x$h, ${pass / fps} s). " + mapAdvice(w, h, loras.isNotEmpty()) + " [${r.body}]"
                    )
                }
                throw IllegalStateException("node \"${node.id}\": ${com.abrah.nightmare.explainOpFailure(r.code, r.body)}")
            }
        }
        // ⭐ The battery's heat around the whole clip, every part ([com.abrah.nightmare.BatteryHeat]): a warning when it is
        // warm, and with Settings' pause on, the render waits between steps while it is hot.
        val clip = com.abrah.nightmare.BatteryHeat.watch(android, com.abrah.nightmare.Prefs.heatPause, ctx, { Ops.pauseVideo(it) }) {
        if ((motion == null || plan == null) && partCount > 1) runParts(
            ctx, node, spec, arch, partCount, partPrompts, keepColours, neg.text, used, w, h, clipFrames, fps, prof,
            startPng, clipLoras, vaeTile, dir, key, raw, ::unwrap,
        ) else if (motion == null || plan == null) unwrap(
            ctx.host.video(
                prompt = promptText, negative = neg.text, width = w, height = h, frames = clipFrames, fps = fps,
                // ⚠ No tiling args = no temporal tiling: H3's VAE decodes in its own chunks ([WanProfile.sound]).
                seed = used, steps = prof.steps, sampleMethod = prof.sampler, sigmas = prof.sigmas, tilingArgs = if (sound) "" else TILING,
                out = raw.absolutePath, startPng = startPng, loras = clipLoras,
                onProgress = { pr -> ctx.onProgress(pr) },
                vaeTileSize = vaeTile,
            ),
            raw,
        ) else {
            // ⭐⭐ Motion transfer in PARTS ([WanFun.partsOf], the user's ask 2026-10-27: 30 s dance videos): each part
            // starts on a frame near the end of the one before, its poses continuing from there, the picture still the
            // reference, joined at the shared frame where the two match best ([WanFun.OVERLAP], `docs/VIDEO-GGML.md` §7).
            // ⭐ Fun Control: FastWan's LoRA (it stands in for the base model's 50 steps + CFG) and its 3-step
            // schedule, measured (`docs/VIDEO-GGML.md` §7). Turbo keeps its own 4.
            val funLoras = loras + (File(spec.dir(android), ModelCatalog.FUN_LORA).absolutePath to 1.0)
            val parts = WanFun.partsOf(plan.frames, WanFun.partFramesFor(w, h))
            if (parts.size > 1) ctx.say("  ${parts.size} parts, each starting on the last frame of the one before")
            var start = startPng
            var total = 0
            var ms = 0L
            var ranPart = false
            // ⭐⭐ RESUMABLE (2026-10-27: the S25's low-memory killer took the app in part 3 of a 10 s clip while
            // other apps were open): after each part the clip so far is kept with a marker, so running the same
            // node again (same seed, picture, video and knobs: [key]) carries on from the next part.
            val marker = File(dir, "wan_$key.parts")
            val resume = WanFun.resumeFrom(marker.takeIf { raw.isFile }?.let { runCatching { it.readText() }.getOrNull() }, parts.size)
            RandomAccessFile(raw, "rw").use { all ->
                if (resume == null) {
                    all.setLength(0)
                    all.write(ByteArray(HEADER_BYTES))
                } else {
                    total = resume.second
                    ctx.say("  resuming: parts 1 to ${resume.first} were made by an earlier run")
                }
                for ((pi, part) in parts.withIndex()) {
                    val (first, count) = part
                    if (resume != null && pi < resume.first) continue
                    coroutineContext.ensureActive()
                    val bytes = w * h * 3
                    // ⭐ A later part starts ON a frame the clip already has (the overlap's start, [WanFun.partsOf]).
                    if (pi > 0) {
                        val b = ByteArray(bytes)
                        all.seek(HEADER_BYTES + first.toLong() * bytes)
                        all.readFully(b)
                        start = pngOfRgb(b, w, h)
                    }
                    if (parts.size > 1) ctx.say("  part ${pi + 1} of ${parts.size}: frames ${first + 1} to ${first + count}")
                    val controlFile = File(dir, "control_${key}_$pi.nmv")
                    val partRaw = File(dir, "wan_${key}_$pi.nmv")
                    // ⭐⭐ Every part after the one that started this backend gets a FRESH backend. A warm one still holds
                    // the DiT (2.9 GB) while it encodes the next part's control video, and that stack (5.2 to 5.6 GB of
                    // DMA-BUF) is where the S25's low-memory killer took the app three times out of three, ~30 s into
                    // part 2 or 3 with other apps open (2026-10-27). A fresh one encodes first and loads the DiT after,
                    // as part 1 did. The 8 Gen 3 rule above, for the same reason in another form.
                    if (ranPart) {
                        ctx.say("  a fresh backend for part ${pi + 1}")
                        com.abrah.nightmare.BackendProcess.stop()
                        if (ctx.switchKey?.invoke(keyFor(node, spec)) != true) {
                            throw IllegalStateException("node \"${node.id}\": the backend did not restart for part ${pi + 1}")
                        }
                        servedBy = com.abrah.nightmare.BackendProcess.processId
                    }
                    try {
                        writeControl(android, motion, plan, first, count, w, h, controlFile, cancelled) { i, n ->
                            ctx.onProgress(Ops.Progress(i, n, 0L, phase = motion.mode.id.replace('_', ' ')))
                        }
                        ctx.onProgress(Ops.Progress(0, 0, 0L))
                        val c = unwrap(
                            ctx.host.video(
                                prompt = pos.text, negative = neg.text, width = w, height = h, frames = count, fps = fps,
                                seed = used, steps = WanFun.SIGMAS.size - 1, sampleMethod = "euler",
                                sigmas = WanFun.SIGMAS, tilingArgs = TILING,
                                out = partRaw.absolutePath, startPng = start, loras = funLoras,
                                onProgress = { pr -> ctx.onProgress(pr) },
                                vaeTileSize = vaeTile,
                                controlVideo = controlFile.absolutePath,
                                // ⚠ The PHOTO for every part, never the part's start frame: tried (2026-10-27), the joins
                                // were ~10% smaller (11.4 vs 12.7) and the face drifted further by 10 s.
                                refPng = refPng,
                            ),
                            // ⚠ The PART's file, not the clip so far: a failed part leaves the finished ones to resume.
                            partRaw,
                        )
                        ms += c.serverMs
                        check(c.width == w && c.height == h) { "part ${pi + 1} came back ${c.width}x${c.height}, not ${w}x$h" }
                        val buf = ByteArray(bytes)
                        val old = ByteArray(bytes)
                        RandomAccessFile(partRaw, "r").use { f ->
                            // ⚠ A later part's frame 0 IS its start picture, a frame the clip has. Its next
                            // [WanFun.OVERLAP] frames the clip has too: the new part takes over at the frame k whose
                            // STEP from the clip's frame k - 1 is smallest ([seamDiff]), a cut, not a blend. ⚠ The step,
                            // not new k against old k: that picked k = 1 every time, the model's first frame after a
                            // start picture, the largest step in the clip (13.2, median 6.2). A blend ghosted: the parts
                            // put an arm a few pixels apart (S25, 2026-10-27).
                            var from = if (pi == 0) 0 else 1
                            if (pi > 0) {
                                var best = Double.MAX_VALUE
                                for (k in 1..minOf(WanFun.OVERLAP, c.frames - 1)) {
                                    if (first + k >= total) break
                                    f.seek(HEADER_BYTES + k.toLong() * bytes)
                                    f.readFully(buf)
                                    all.seek(HEADER_BYTES + (first + k - 1).toLong() * bytes)
                                    all.readFully(old)
                                    val d = seamDiff(old, buf)
                                    if (d < best) { best = d; from = k }
                                }
                                ctx.say("  joined at frame ${first + from + 1}")
                            }
                            for (k in from until c.frames) {
                                f.seek(HEADER_BYTES + k.toLong() * bytes)
                                f.readFully(buf)
                                val g = first + k
                                all.seek(HEADER_BYTES + g.toLong() * bytes)
                                all.write(buf)
                                total = maxOf(total, g + 1)
                            }
                        }
                        all.fd.sync()
                        marker.writeText("${pi + 1} $total")
                        ranPart = true
                    } finally {
                        controlFile.delete()
                        partRaw.delete()
                    }
                }
                all.seek(0)
                all.write(nmvHeader(w, h, total, fps))
            }
            marker.delete()
            Ops.Clip(raw.absolutePath, total, w, h, fps, ms)
        }
        }
        ctx.say("${clip.frames} frames in ${"%.1f".format(clip.serverMs / 1000.0)} s. Encoding")
        // ⚠ Opened only now: ~25 MB on the NPU, beside a backend that has finished its clip.
        val session = try {
            if (smooth && clip.frames > 1) FrameInterp.Session(android, w, h).also {
                ctx.say("  smooth motion: ${clip.frames - 1} new frames (IFRNet ${it.size})")
            } else null
        } catch (e: Exception) {
            raw.delete()
            clip.audioPath?.let { File(it).delete() }
            throw IllegalStateException("node \"${node.id}\": ${e.message ?: e.javaClass.simpleName}")
        }
        val outFps = if (session != null) fps * 2 else fps
        try {
            // ⭐ The encoder's lines go to the run log too: GitHub #26 sat at "Encoding" with nothing
            // saying how far it got.
            val (poster, last) = encodeClip(raw, out, VideoWriter.cancelledWith(coroutineContext[kotlinx.coroutines.Job]), smooth = session) {
                Log.i(TAG, it)
                ctx.say(it.trim())
            }
            // ⭐ Keep audio: the driving video's sound over exactly the span the clip shows ([AudioMux]).
            if (motion?.keepAudio == true) AudioMux.mux(android, out, motion.uri, motion.startMs * 1000L) {
                Log.i(TAG, it)
                ctx.say(it.trim())
            }
            // ⭐ MiniMax H3's own soundtrack (backend 050's `<out>.pcm`), encoded to AAC beside the frames ([AudioMux.muxPcm]).
            // ⚠ Smooth motion doubles the frames at double the rate: the same length, so the sound stays in sync.
            clip.audioPath?.let { File(it) }?.let { pcm ->
                AudioMux.muxPcm(out, pcm) {
                    Log.i(TAG, it)
                    ctx.say(it.trim())
                }
                pcm.delete()
            }
            val posterId = ctx.images.put(poster, id = VideoWriter.posterIdFor(out))
            // ⭐⭐ A CHAIN (GitHub #29, the user's call 2026-10-15): started from a video node's last
            // frame, this clip CONTINUES that node's film, so `video` is the film so far and the last
            // node of a chain hands the output all of it. ⚠ Not Motion transfer: its picture is a
            // reference, not a start frame. [prevFilm] is read above, where it sets the clip's rate.
            var filmFile = out
            var filmFrames = if (session != null) FrameInterp.doubled(clip.frames) else clip.frames
            var filmPoster = posterId
            if (prevFilm != null) {
                val film = File(dir, "film_" + sha1(prevFilm.name + "\u0000" + out.name) + ".mp4")
                filmFrames = if (film.isFile) FilmJoin.frames(film) else {
                    val j = FilmJoin.join(
                        prevFilm, out, film, outFps, { Log.i(TAG, it); ctx.say(it.trim()) },
                        VideoWriter.cancelledWith(coroutineContext[kotlinx.coroutines.Job]),
                    )
                    ctx.say("  joined onto the film so far: ${j.frames} frames (${"%.1f".format(j.frames.toDouble() / outFps)} s)" +
                        if (j.copied) "" else ", re-encoded")
                    j.frames
                }
                filmFile = film
                // ⭐ The film's loop: the film so far's, then this part's ([com.abrah.nightmare.ClipStore.join]).
                com.abrah.nightmare.ClipStore.join(film.absolutePath, prevFilm.absolutePath, out.absolutePath)
                filmPoster = FilmJoin.firstFrame(film)?.let { ctx.images.put(it, id = VideoWriter.posterIdFor(film)) } ?: posterId
            }
            mapOf(
                "video" to Value.Video(
                    filmFile.absolutePath, filmFrames, clip.width, clip.height, filmPoster,
                    part = out.absolutePath, startId = source?.id.takeIf { motion == null },
                ),
                LAST_FRAME to Value.Image(ctx.images.put(last), last.width, last.height, film = filmFile.absolutePath),
            )
        } finally {
            session?.close()
            raw.delete()
            clip.audioPath?.let { File(it).delete() }
        }
    }

    /**
     * ⭐⭐ A clip in [n] parts ([WanParts]): part k on [prompts]'s k-th line, seed [used] + k, starting on the last frame
     * of part k - 1 (whose duplicate first frame is dropped), all written into [raw]. With [keepColours] every frame is
     * held to the first frame's colours ([WanParts.Lock]) BEFORE the next part starts from it, so the drift cannot
     * compound. Resumable as Motion transfer's parts are: a marker after each part, the same node run again carries on.
     */
    private suspend fun runParts(
        ctx: NodeCtx, node: Node, spec: com.abrah.nightmare.ModelSpec, arch: Int, n: Int, prompts: List<String>,
        keepColours: Boolean, negative: String, used: Long, w: Int, h: Int, frames: Int, fps: Int, prof: WanProfile,
        startPng: ByteArray?, loras: List<Pair<String, Double>>, vaeTile: Int, dir: File, key: String, raw: File,
        unwrap: (Ops.Result<Ops.Clip>, File) -> Ops.Clip,
    ): Ops.Clip {
        val bytes = w * h * 3
        val marker = File(dir, "wan_$key.parts")
        val resume = WanFun.resumeFrom(marker.takeIf { raw.isFile }?.let { runCatching { it.readText() }.getOrNull() }, n)
        // ⭐ MiniMax H3's parts each have a soundtrack: joined beside the frames ([AudioMux.appendPart]).
        val audioRaw = File(dir, "wan_$key.audio")
        val audioFmt = File(dir, "wan_$key.audio.fmt")
        if (resume == null) { audioRaw.delete(); audioFmt.delete() }
        var total = 0
        var ms = 0L
        var start = startPng
        var lock: WanParts.Lock? = null
        val buf = ByteArray(bytes)
        RandomAccessFile(raw, "rw").use { all ->
            if (resume == null) {
                all.setLength(0)
                all.write(ByteArray(HEADER_BYTES))
            } else {
                total = resume.second
                ctx.say("  resuming: parts 1 to ${resume.first} were made by an earlier run")
                // ⚠ The anchor is frame 0, and the smoothing restarts from the last frame the clip has.
                if (keepColours) {
                    all.seek(HEADER_BYTES.toLong()); all.readFully(buf)
                    lock = WanParts.Lock(WanParts.stats(buf))
                    all.seek(HEADER_BYTES + (total - 1).toLong() * bytes); all.readFully(buf)
                    lock.apply(buf.copyOf())
                }
            }
            for (pi in 0 until n) {
                if (resume != null && pi < resume.first) continue
                kotlin.coroutines.coroutineContext.ensureActive()
                if (pi > 0) {
                    all.seek(HEADER_BYTES + (total - 1).toLong() * bytes)
                    all.readFully(buf)
                    start = pngOfRgb(buf, w, h)
                    // ⭐ 8 Gen 3: every clip from a fresh backend, and a part is a clip (the rule in [runClip]).
                    if (arch in 1..78) {
                        ctx.say("  a fresh backend for part ${pi + 1} (8 Gen 3)")
                        com.abrah.nightmare.BackendProcess.stop()
                        if (ctx.switchKey?.invoke(keyFor(node, spec)) != true) {
                            throw IllegalStateException("node \"${node.id}\": the backend did not restart for part ${pi + 1}")
                        }
                        servedBy = com.abrah.nightmare.BackendProcess.processId
                    }
                }
                ctx.say("  part ${pi + 1} of $n" + if (pi > 0 && prompts[pi] != prompts[pi - 1]) ": ${prompts[pi].take(60)}" else "")
                val partRaw = File(dir, "wan_${key}_p$pi.nmv")
                try {
                    val c = unwrap(
                        ctx.host.video(
                            prompt = prompts[pi], negative = negative, width = w, height = h, frames = frames, fps = fps,
                            seed = used + pi, steps = prof.steps, sampleMethod = prof.sampler, sigmas = prof.sigmas,
                            tilingArgs = if (prof.sound) "" else TILING, out = partRaw.absolutePath, startPng = start, loras = loras,
                            onProgress = { pr -> ctx.onProgress(pr) },
                            vaeTileSize = vaeTile,
                        ),
                        partRaw,
                    )
                    ms += c.serverMs
                    check(c.width == w && c.height == h) { "part ${pi + 1} came back ${c.width}x${c.height}, not ${w}x$h" }
                    // ⭐ No smoothing across the join ([WanParts.Lock.restart]).
                    if (pi > 0) lock?.restart()
                    RandomAccessFile(partRaw, "r").use { f ->
                        // ⚠ A later part's frame 0 IS its start picture, the clip's last frame: dropped, not shown twice.
                        for (k in (if (pi == 0) 0 else 1) until c.frames) {
                            f.seek(HEADER_BYTES + k.toLong() * bytes)
                            f.readFully(buf)
                            if (keepColours) {
                                val l = lock ?: WanParts.Lock(WanParts.stats(buf)).also { lock = it }
                                l.apply(buf)
                            }
                            all.seek(HEADER_BYTES + total.toLong() * bytes)
                            all.write(buf)
                            total++
                        }
                    }
                    all.fd.sync()
                    c.audioPath?.let { File(it) }?.let { pcm ->
                        AudioMux.appendPart(pcm, audioRaw, audioFmt, dropFrames = if (pi == 0) 0 else 1, fps = fps)
                        pcm.delete()
                    }
                    marker.writeText("${pi + 1} $total")
                } finally {
                    partRaw.delete()
                    File(partRaw.path + ".pcm").delete()
                }
            }
            all.seek(0)
            all.write(nmvHeader(w, h, total, fps))
        }
        marker.delete()
        val sound = AudioMux.finishParts(audioRaw, audioFmt, File(raw.path + ".pcm"))
        return Ops.Clip(raw.absolutePath, total, w, h, fps, ms, audioPath = sound?.absolutePath)
    }

    /**
     * ⭐ Motion transfer's driving window, read and planned ([MotionTrack]): its [frames] (the window, shortened to
     * the video), the [track] (null when nothing needs the person: As is with a still crop and no lining up), each
     * frame's crop ([windowAt]: index, source width, height), and where the person starts and how tall they
     * usually are in the clip's pixels ([startBox], [personH]: what the picture is lined up with).
     */
    private class MotionPlan(
        val frames: Int,
        val track: MotionTrack.Track?,
        val windowAt: (Int, Int, Int) -> MotionTrack.Window,
        val startBox: FloatArray?,
        val personH: Float,
    )

    private fun readMotion(
        ctx: NodeCtx, node: Node, app: android.content.Context, m: WanFun.Driving, w: Int, h: Int, wanted: Int,
        cancelled: () -> Boolean,
    ): MotionPlan {
        val durationMs = android.media.MediaMetadataRetriever().let { r ->
            try {
                r.setDataSource(app, m.uri)
                r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } catch (e: Exception) {
                throw IllegalStateException("node \"${node.id}\": cannot read the driving video (${e.message ?: e.javaClass.simpleName})")
            } finally {
                runCatching { r.release() }
            }
        }
        val count = WanFun.framesFrom(wanted, durationMs - m.startMs.coerceIn(0L, durationMs), m.fps)
        if (count == 0) throw IllegalStateException("node \"${node.id}\": the driving video is too short: it needs at least a quarter of a second")
        ctx.say("  motion: ${m.mode.id} from the driving video at ${m.fps} fps" +
            (if (m.follow) ", the crop following the person" else "") + (if (m.align) ", your picture lined up" else ""))
        val pose = m.mode == WanFun.Mode.POSE
        if (!pose && !m.follow && !m.align) {
            return MotionPlan(count, null, { _, sw, sh -> MotionTrack.fixedWindow(m.rect, sw, sh, w, h) }, null, 0f)
        }
        if (!com.abrah.nightmare.pose.PoseDetector.isInstalled(app)) {
            throw com.abrah.nightmare.NeedsDownload(
                com.abrah.nightmare.NeedsDownload.POSE, "node \"${node.id}\": the Pose Detector is not installed (Models, Tools)",
            )
        }
        if (pose && !com.abrah.nightmare.pose.WholeBodyPose.isInstalled(app)) {
            throw com.abrah.nightmare.NeedsDownload(
                com.abrah.nightmare.NeedsDownload.WHOLE_BODY,
                "node \"${node.id}\": ${com.abrah.nightmare.pose.WholeBodyPose.LABEL} is not installed (Models, Tools)",
            )
        }
        val t0 = System.currentTimeMillis()
        val track = try {
            // ⭐ Its own bar in the run log (the user's ask, 2026-10-16: pose is a good share of the run).
            MotionTrack.read(
                app, m.uri, m.startMs, count, m.fps, wholeBody = pose,
                onFrame = { i, n -> ctx.onProgress(Ops.Progress(i, n, 0L, phase = "pose")) },
                isCancelled = cancelled,
            )
        } catch (e: Exception) {
            throw IllegalStateException("node \"${node.id}\": ${e.message ?: e.javaClass.simpleName}")
        }
        ctx.onProgress(Ops.Progress(0, 0, 0L))
        val found = track.boxes.count { it != null }
        ctx.say("  read the person in $found of $count frames in ${"%.1f".format((System.currentTimeMillis() - t0) / 1000.0)} s")
        val followed = if (m.follow) MotionTrack.followWindows(track.boxes, w, h) else null
        if (m.follow && followed == null) {
            ctx.warn(ctx.text(R.string.run_motion_nobody, "No person was found in the driving video, so the crop stays still"))
        }
        val fixed = MotionTrack.fixedWindow(m.rect, track.srcW, track.srcH, w, h)
        val windows = followed ?: List(track.size) { fixed }
        val first = track.boxes.indexOfFirst { it != null }
        val startBox = if (first >= 0) MotionTrack.boxInWindow(track.boxes[first]!!, windows[first], w, h) else null
        val heights = track.boxes.mapIndexedNotNull { i, b -> b?.let { (it[3] - it[1]) * h / windows[i].h } }
        return MotionPlan(count, track, { i, _, _ -> windows[i] }, startBox, MotionTrack.quantile(heights, 0.95f))
    }

    /**
     * ⭐ Line up my picture ([WanFun.ALIGN]): the crop of [src] that puts its person where the dancer starts, as
     * fractions for [com.abrah.nightmare.CropNode.render], or null (said in the run log) when either has nobody.
     */
    private fun alignedFrame(ctx: NodeCtx, app: android.content.Context, src: Bitmap, plan: MotionPlan, w: Int, h: Int): FloatArray? {
        val start = plan.startBox ?: return null
        val box = com.abrah.nightmare.pose.PoseDetector.mainBox(app, src)
        if (box == null) {
            ctx.warn(ctx.text(R.string.run_motion_align_none, "No person was found in your picture, so it is used as framed"))
            return null
        }
        return MotionTrack.alignRect(src.width, src.height, box, start, plan.personH, w, h)
    }

    /** ⭐ One part's control video: whole-body skeletons (Pose) or the frames as they are (As is), in their crops. */
    private fun writeControl(
        app: android.content.Context, m: WanFun.Driving, plan: MotionPlan, first: Int, count: Int, w: Int, h: Int,
        out: File, cancelled: () -> Boolean, onFrame: (Int, Int) -> Unit,
    ) {
        val track = plan.track
        if (m.mode == WanFun.Mode.POSE && track != null) {
            MotionTrack.writePose(track, { i -> plan.windowAt(i, track.srcW, track.srcH) }, first, count, w, h, m.fps, out)
        } else {
            MotionTrack.writeAsIs(app, m.uri, m.startMs, m.fps, plan.windowAt, first, count, w, h, out, cancelled, onFrame)
        }
    }

    /** ⚠ Pure: the mean absolute difference of two RGB8 frames of one size, every 7th byte (a cut's cost). */
    internal fun seamDiff(a: ByteArray, b: ByteArray): Double {
        var s = 0L
        var n = 0
        var i = 0
        while (i < a.size) {
            s += kotlin.math.abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
            n++
            i += 7
        }
        return if (n == 0) 0.0 else s.toDouble() / n
    }

    /** ⚠ RGB8 frame bytes as a PNG: a frame of the clip, the next part's start picture. */
    private fun pngOfRgb(rgb: ByteArray, w: Int, h: Int): ByteArray {
        val px = IntArray(w * h) { i ->
            0xFF000000.toInt() or ((rgb[i * 3].toInt() and 0xFF) shl 16) or
                ((rgb[i * 3 + 1].toInt() and 0xFF) shl 8) or (rgb[i * 3 + 2].toInt() and 0xFF)
        }
        val b = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it); b.recycle() }.toByteArray()
    }

    /** ⚠ [MAGIC]'s 20-byte header. */
    private fun nmvHeader(w: Int, h: Int, count: Int, fps: Int): ByteArray =
        MAGIC.toByteArray(Charsets.US_ASCII) +
            ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(w).putInt(h).putInt(count).putInt(fps).array()

    /**
     * ⭐ The backend's frame file ([Ops.video]) into [out] as MP4, a frame at a time, with the
     * node's loop ([com.abrah.nightmare.ClipStore]) built on the way. Returns the first and last frames.
     * ⭐ With [smooth], twice the rate: frame 2k is the model's frame k, 2k + 1 IFRNet's midpoint of k and k + 1.
     */
    internal fun encodeClip(
        raw: File,
        out: File,
        isCancelled: () -> Boolean = { false },
        smooth: FrameInterp.Session? = null,
        log: (String) -> Unit = { Log.i(TAG, it) },
    ): Pair<Bitmap, Bitmap> {
        RandomAccessFile(raw, "r").use { f ->
            val head = ByteArray(HEADER_BYTES).also { f.readFully(it) }
            check(String(head, 0, 4, Charsets.US_ASCII) == MAGIC) { "not a $MAGIC frame file: ${raw.name}" }
            val ints = ByteBuffer.wrap(head, 4, 16).order(ByteOrder.LITTLE_ENDIAN)
            val w = ints.int
            val h = ints.int
            val count = ints.int
            val fps = ints.int
            val rgb = ByteArray(w * h * 3)
            val rgba = ByteBuffer.allocateDirect(w * h * 4)
            val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            var poster: Bitmap? = null
            var last: Bitmap? = null
            val thumbs = ArrayList<Bitmap>()
            fun read(k: Int, into: ByteArray) {
                f.seek(HEADER_BYTES + k.toLong() * into.size)
                f.readFully(into)
            }
            val doubling = smooth != null && count > 1
            val outCount = if (doubling) FrameInterp.doubled(count) else count
            val mid = if (doubling) ByteArray(rgb.size) else rgb
            var midMs = 0L
            VideoWriter.encode(outCount, w, h, if (doubling) fps * 2 else fps, out, log, isCancelled) { i ->
                // ⚠ Frame 0 again = [VideoWriter.encode] retrying on the software encoder: start over.
                if (i == 0) thumbs.clear()
                val (k, between) = if (doubling) FrameInterp.sourceOf(i) else i to false
                val src = if (between) {
                    val t0 = System.currentTimeMillis()
                    smooth!!.midpoint(k, ::read, mid)
                    midMs += System.currentTimeMillis() - t0
                    mid
                } else {
                    read(k, rgb)
                    rgb
                }
                rgba.clear()
                var s = 0
                while (s < src.size) {
                    rgba.put(src[s]).put(src[s + 1]).put(src[s + 2]).put(0xFF.toByte())
                    s += 3
                }
                rgba.flip()
                frame.copyPixelsFromBuffer(rgba)
                if (i == 0) poster = frame.copy(Bitmap.Config.ARGB_8888, false)
                if (i == outCount - 1) last = frame.copy(Bitmap.Config.ARGB_8888, false)
                // ⚠ The loop from the model's frames only: [com.abrah.nightmare.ClipStore.FPS] is paired with its
                // thinning, so a smoothed clip's loop runs at the same speed as an unsmoothed one.
                if (!between && k % com.abrah.nightmare.ClipStore.EVERY == 0) thumbs.add(com.abrah.nightmare.ClipStore.thumb(frame))
                frame
            }
            if (doubling) log("  smooth motion: ${count - 1} frames in ${"%.1f".format(midMs / 1000.0)} s")
            com.abrah.nightmare.ClipStore.putThinned(out.absolutePath, thumbs)
            return (poster ?: error("no frames in ${raw.name}")) to (last ?: error("no last frame in ${raw.name}"))
        }
    }

    /** ⚠ backend 028's `/video` file: "NMV1", then int32 LE width, height, count, fps. */
    private const val MAGIC = "NMV1"
    private const val HEADER_BYTES = 20

    private const val TAG = "WanVideo"

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
}
