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
    override val paletteName = "Wan video"

    override fun titleFor(node: Node): String =
        if (node.inputs["image"] != null) "Image to video" else "Text to video"

    override val defaultId = "video"
    override val about = "a prompt, or a prompt and a photo, into a clip with Wan 2.2"

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
        Widget("seconds", "int", "5", 1.0, MAX_SECONDS.toDouble(), hintRes = R.string.hint_wan_seconds),
        // ⭐ The framing, exactly as [VideoSampleNode] carries it.
        Widget("x", "float", "0.0", 0.0, 1.0, hintRes = R.string.hint_drag_frame),
        Widget("y", "float", "0.0", 0.0, 1.0),
        Widget("w", "float", "1.0", 0.0, 1.0),
        Widget("h", "float", "1.0", 0.0, 1.0),
    )

    /** ⭐ [WanFunNode]'s: how its driving video's frames become the control video. */
    const val MOTION_MODE = "motion_mode"

    /**
     * ⭐ One backend process serves every size and length: like every DiT model, the size is a
     * REQUEST field, so the key is the model's own ([com.abrah.nightmare.backendContextKey]'s rule).
     */
    override fun contextKey(node: Node): ContextKey = keyFor(node, ModelCatalog.WAN22)

    /** ⭐ The launch key for [spec] (Wan 2.2 Turbo here, Fun Control for [WanFunNode]). */
    internal fun keyFor(node: Node, spec: com.abrah.nightmare.ModelSpec): ContextKey {
        val p = effectiveParams(node)
        val (w, h) = sizeOf(p)
        val caps = com.abrah.nightmare.DeviceProbe.caps()
        val budget = memoryBudget(
            pinnedBytes(w, h, framesOf(node, p)),
            caps.ramBytes, if (caps.known) caps.arch else 0,
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
     * ⚠ v75: 2.75 GiB (under the ~3.1 GB map) only past [V75_NPU_BYTES], so 480p 2 s, the run proven on a
     * real 8 Gen 3, launches exactly as before. Unmeasured on a real one.
     */
    fun memoryBudget(pinned: Long, ramBytes: Long, arch: Int): String? = when {
        arch in 1..78 -> "2.75".takeIf { pinned > V75_NPU_BYTES }
        ramBytes in 1 until com.abrah.nightmare.Prefs.LOWRAM_BELOW_BYTES -> "4".takeIf { pinned > BUDGET_FROM }
        else -> null
    }

    private const val BUDGET_FROM = 4_608L shl 20

    /**
     * ⚠ Pure: what a clip holds on the NPU at its worst: [pinnedBytes] resident, or under a [memoryBudget]
     * one block at a time, its compute plus [BLOCK_BYTES] (704x1280 5 s: 3,410 MB of compute, and the engine
     * asked for 3,705 MB). What the run-log warnings judge, so a budgeted clip is not warned about as if whole.
     */
    fun peakBytes(w: Int, h: Int, frames: Int, budget: String?): Long =
        if (budget == null) pinnedBytes(w, h, frames) else tokens(w, h, frames) * BYTES_PER_TOKEN + BLOCK_BYTES

    private const val BLOCK_BYTES = 300L shl 20

    /** ⚠ The backend launch that served the last Wan clip ([com.abrah.nightmare.BackendProcess.processId]). */
    @Volatile private var servedBy: Int? = null

    /** ⭐ The clip's size, which a picture wired into `image` is framed to. */
    override fun framesTo(node: Node): Pair<Int, Int> = sizeOf(effectiveParams(node))

    /**
     * ⭐ A clip's frame count: its Seconds, or on Motion transfer the Video tab's window, which IS
     * the length ([WanFun.framesForGap]). The launch key and the run both read this.
     */
    internal fun framesOf(node: Node, p: Map<String, String>): Int =
        if (node.type == WanFunNode.name) WanFun.windowOf(p).let { (s, e) -> WanFun.framesForGap(e - s) }
        else framesFor(p["seconds"]?.toIntOrNull() ?: MAX_SECONDS)

    /** ⚠ Pure: the frame size the params ask for, on Wan's 32 px grid. */
    fun sizeOf(p: Map<String, String>): Pair<Int, Int> =
        snap(p["width"]?.toIntOrNull() ?: DEFAULT_W) to snap(p["height"]?.toIntOrNull() ?: DEFAULT_H)

    private fun snap(v: Int): Int = (Math.round(v / GRID.toDouble()) * GRID).toInt().coerceIn(MIN_SIDE, MAX_SIDE)

    /**
     * ⚠ Pure: [w] x [h] with the side that did NOT move ([widthMoved] says which did) shrunk until
     * the pair is within [MAX_AREA]. Dragging width to 1280 at a height of 1280 gives 1280x704.
     */
    fun fitArea(w: Int, h: Int, widthMoved: Boolean): Pair<Int, Int> {
        val sw = snap(w)
        val sh = snap(h)
        if (sw.toLong() * sh <= MAX_AREA) return sw to sh
        return if (widthMoved) sw to fitSide(sw) else fitSide(sh) to sh
    }

    /**
     * ⭐ Pure: the largest clip in the SHAPE of a [srcW] x [srcH] picture, on the grid and within
     * [MAX_AREA] (GitHub #25: a picture of another shape is cropped to the clip's, and the error that came
     * with it). Rounded DOWN, so the pair never asks for more than Wan's limit.
     */
    fun matchShape(srcW: Int, srcH: Int): Pair<Int, Int> {
        val a = srcW.toDouble() / srcH.coerceAtLeast(1)
        var h = kotlin.math.sqrt(MAX_AREA / a)
        var w = h * a
        if (w > MAX_SIDE) { w = MAX_SIDE.toDouble(); h = w / a }
        if (h > MAX_SIDE) { h = MAX_SIDE.toDouble(); w = h * a }
        fun down(v: Double) = ((v / GRID).toInt() * GRID).coerceIn(MIN_SIDE, MAX_SIDE)
        return down(w) to down(h)
    }

    /** ⚠ Pure: whether a picture's shape is more than ~4% off the clip's (a 32 px grid step is ~4% at 832). */
    fun shapeDiffers(srcW: Int, srcH: Int, w: Int, h: Int): Boolean =
        kotlin.math.abs(kotlin.math.ln((srcW.toDouble() / srcH) / (w.toDouble() / h))) > 0.04

    /** The largest grid side that keeps `other x it` within [MAX_AREA]. */
    private fun fitSide(other: Int): Int = (MAX_AREA / other / GRID * GRID).coerceIn(MIN_SIDE, MAX_SIDE)

    /** ⚠ Pure: the `width`/`height` a flow saved with the old `size`/`shape` choosers meant, or null. */
    fun fromLegacy(p: Map<String, String>): Pair<Int, Int>? {
        if ("size" !in p && "shape" !in p) return null
        val (w, h) = if (p["size"] == "704p") 1280 to 704 else 832 to 480
        return if (p["shape"] == "portrait") h to w else w to h
    }

    /** ⚠ Pure: `24·s + 1` frames for [seconds], clamped to 1..[MAX_SECONDS]. */
    fun framesFor(seconds: Int): Int = seconds.coerceIn(1, MAX_SECONDS) * FPS + 1

    /**
     * ⭐ quanhaol's Self-Forcing timesteps 1000/750/500/250 warped by shift 5, through `lcm`
     * (`docs/VIDEO-GGML.md` §3). ⚠ Turbo's schedule: a different Wan model needs its own.
     */
    val SIGMAS = listOf(1.0, 0.9375, 0.8333, 0.625, 0.0)

    /** ⚠ Pure: Wan 2.2's latent tokens — 32 px a side after VAE and patch, one latent frame per 4. */
    fun tokens(w: Int, h: Int, frames: Int): Long = (w / 32L) * (h / 32L) * ((frames - 1) / 4 + 1)

    /**
     * ⚠ Pure: what a clip PINS on the NPU — the Q4_0 DiT's 2.9 GB and ~0.125 MB of compute per
     * token (0.64 GB at 6k tokens, 3.4 GB at 27k, `docs/VIDEO-GGML.md` §4).
     * 832x480 for 5 s: 4.3 GB; 1280x704 for 5 s: 6.2 GB.
     */
    fun pinnedBytes(w: Int, h: Int, frames: Int): Long =
        DIT_BYTES + tokens(w, h, frames) * BYTES_PER_TOKEN

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

    /** The engine's words when fastrpc refused a buffer — it SIGSEGVs right after. */
    private val NPU_MAP_FAILED =
        Regex("""fastrpc_mmap failed|failed to allocate HTP0 buffer|cannot make enough memory available""")

    private const val DIT_BYTES = 2_900L shl 20
    private const val BYTES_PER_TOKEN = 128L shl 10
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
    fun vaeTileFor(w: Int, h: Int): Int = if (w.toLong() * h > SPATIAL_FROM) VAE_TILE else 0

    private const val SPATIAL_FROM = 832L * 480
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
        // lookup ([PromptLoras.resolve] in the LoRA folder), sent with the request. ⚠ Wan 2.2 TI2V-5B
        // LoRAs only; a 14B one does not fit this model's layers (the engine logs what it bound).
        val pos = PromptLoras.extract(wired.positive)
        val neg = PromptLoras.extract(wired.negative)
        val loraDir = com.abrah.nightmare.BackendProcess.lorasDir(android)
        val installed = loraDir.list()?.toList().orEmpty()
        // ⭐⭐ Motion transfer takes NO LoRAs (the user's call, 2026-10-15): it has no picker, and its
        // prompt's tags are skipped out loud rather than reaching Fun by the back door. Its FastWan
        // speed LoRA is part of the model ([ModelCatalog.FUN_LORA]), not this list.
        val takesLoras = driving == null
        if (!takesLoras) {
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
        val loras = picked.filter { (f, _) -> tagged.none { it.first == f } } + tagged
        if (loras.isNotEmpty()) {
            ctx.say("  LoRAs: " + loras.joinToString { (f, s) -> "${File(f).nameWithoutExtension} $s" })
        }
        val p = effectiveParams(node)
        val (w, h) = sizeOf(p)
        // ⚠ The sliders cannot get here ([fitArea]); a hand-edited or plugin-written graph can.
        if (w.toLong() * h > MAX_AREA) {
            throw IllegalArgumentException(
                "node \"${node.id}\": ${w}x$h is larger than Wan's limit of 1280x704 worth of pixels. Make it smaller"
            )
        }
        val frames = framesOf(node, p)
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
        val need = peakBytes(w, h, frames, memoryBudget(pinnedBytes(w, h, frames), ram, arch))
        if (ram > 0 && !fits(need, ram)) {
            ctx.warn(ctx.text(R.string.run_wan_ram_warning,
                "%1\$s for %2\$s s needs about %3\$s GB on the NPU, more than this phone's %4\$s GB of RAM comfortably holds. It may stop or restart the app. 480p or fewer seconds is safer",
                "${w}x$h", (frames / FPS).toString(), gb(need), gb(ram)))
        }
        if (!fitsNpuMap(need, arch)) {
            ctx.warn(ctx.text(R.string.run_wan_npu_warning,
                "%1\$s for %2\$s s needs about %3\$s GB on the NPU at once. A Snapdragon 8 Gen 3 holds about 3.5 GB, so it will probably fail. Trying anyway",
                "${w}x$h", (frames / FPS).toString(), gb(need)))
        }

        val source = inputs["image"] as? Value.Image
        // ⭐⭐ Motion transfer: the wired picture is the REFERENCE, not the start frame (the model's
        // `ref_conv`, VideoX-Fun's 5B example), and the driving video gives the motion.
        val motion = driving
        val framedPng: ByteArray? = source?.let { img ->
            val src = ctx.images.get(img.id)
                ?: throw IllegalStateException("node \"${node.id}\": image ${img.id} is no longer in the store")
            fun f(k: String, d: Float) = p[k]?.toFloatOrNull() ?: d
            val framed = com.abrah.nightmare.CropNode.render(
                src, f("x", 0f), f("y", 0f), f("w", 1f), f("h", 1f), w, h,
                com.abrah.nightmare.CropNode.PAD_BLACK,
            ).first
            ByteArrayOutputStream().also { framed.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
        val startPng = framedPng.takeIf { motion == null }
        val refPng = framedPng.takeIf { motion != null }

        // ⚠ The decode's spatial tile changes the frames (a little), so it is part of the clip's key.
        val vaeTile = com.abrah.nightmare.BackendProcess.readWanVaeTileOverride(android) ?: vaeTileFor(w, h)
        // ⚠ A content address of everything the clip is made from, as [VideoSampleNode] does.
        val key = sha1(
            (listOf(
                pos.text, neg.text, used.toString(), "${w}x$h", frames.toString(), source?.address().orEmpty(),
                loras.joinToString(";") { (f, s) -> "$f@$s" },
            ) + listOfNotNull(
                vaeTile.takeIf { it > 0 }?.let { "vae_tile=$it" },
                motion?.let { m -> "motion=${m.uri}/${m.mode.id}/${m.startMs}/${m.rect?.joinToString(",").orEmpty()}" },
            ))
                .joinToString("\u0000")
        )
        val dir = File(android.cacheDir, "video").apply { mkdirs() }
        val raw = File(dir, "wan_$key.nmv")
        val out = File(dir, "clip_wan_$key.mp4")
        // ⭐ The control video, made on the phone from the driving one (pose: MoveNet, depth: Depth Anything).
        val controlFile = File(dir, "control_$key.nmv")
        var clipFrames = frames
        if (motion != null) {
            ctx.say("  motion: ${motion.mode.id} from the driving video")
            clipFrames = try {
                WanFun.buildControl(
                    android, motion.uri, motion.mode, w, h, frames, controlFile,
                    startMs = motion.startMs, rect = motion.rect,
                    // ⭐ Its own bar in the run log (the user's ask, 2026-10-16: pose is a good
                    // share of the run): "pose 12/49", until the sampler's own steps take over.
                    onFrame = { i, n ->
                        ctx.onProgress(Ops.Progress(i, n, 0L, phase = motion.mode.id.replace('_', ' ')))
                        if (i == n) ctx.say("  control: $n frames")
                    },
                    isCancelled = { coroutineContext[kotlinx.coroutines.Job]?.isActive == false },
                )
            } catch (e: Exception) {
                controlFile.delete()
                throw IllegalStateException("node \"${node.id}\": ${e.message ?: e.javaClass.simpleName}")
            }
            // ⚠ The control bar ends here: no count until the sampler reports its first step.
            ctx.onProgress(Ops.Progress(0, 0, 0L))
        }
        ctx.say("seed $used, ${w}x$h, $clipFrames frames" + when {
            motion != null -> ", your picture moving like the driving video"
            startPng != null -> ", animating your picture"
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
        // ⭐ Fun Control: FastWan's LoRA (it stands in for the base model's 50 steps + CFG) and its 3-step
        // schedule, measured (`docs/VIDEO-GGML.md` §7). Turbo keeps its own 4.
        val control = motion != null
        val r = try {
            ctx.host.video(
                prompt = pos.text, negative = neg.text, width = w, height = h, frames = clipFrames, fps = FPS,
                seed = used,
                steps = (if (control) WanFun.SIGMAS else SIGMAS).size - 1,
                sampleMethod = if (control) "euler" else "lcm",
                sigmas = if (control) WanFun.SIGMAS else SIGMAS, tilingArgs = TILING,
                out = raw.absolutePath, startPng = startPng,
                loras = if (control) loras + (File(spec.dir(android), ModelCatalog.FUN_LORA).absolutePath to 1.0) else loras,
                onProgress = { pr -> ctx.onProgress(pr) },
                vaeTileSize = vaeTile,
                controlVideo = controlFile.absolutePath.takeIf { control },
                refPng = refPng,
            )
        } finally {
            controlFile.delete()
        }
        val clip = when (r) {
            is Ops.Result.Ok -> r.value
            is Ops.Result.Err -> {
                raw.delete()
                // ⭐ An NPU that could not map the clip's working buffer, said as such: the engine
                // crashes right after (SIGSEGV), and testers saw only "EOFException" (GitHub #20).
                val log = com.abrah.nightmare.BackendProcess.newestLaunch()
                if (r.code == -1 && log.any { NPU_MAP_FAILED.containsMatchIn(it) }) {
                    throw IllegalStateException(
                        "node \"${node.id}\": the NPU could not hold this clip's working memory " +
                            "(${w}x$h, ${frames / FPS} s). Choose fewer seconds or 480p [${r.body}]"
                    )
                }
                throw IllegalStateException("node \"${node.id}\": ${com.abrah.nightmare.explainOpFailure(r.code, r.body)}")
            }
        }
        ctx.say("${clip.frames} frames in ${"%.1f".format(clip.serverMs / 1000.0)} s. Encoding")
        try {
            // ⭐ The encoder's lines go to the run log too: GitHub #26 sat at "Encoding" with nothing
            // saying how far it got.
            val (poster, last) = encodeClip(raw, out, VideoWriter.cancelledWith(coroutineContext[kotlinx.coroutines.Job])) {
                Log.i(TAG, it)
                ctx.say(it.trim())
            }
            val posterId = ctx.images.put(poster, id = VideoWriter.posterIdFor(out))
            // ⭐⭐ A CHAIN (GitHub #29, the user's call 2026-10-15): started from a video node's last
            // frame, this clip CONTINUES that node's film, so `video` is the film so far and the last
            // node of a chain hands the output all of it. ⚠ Not Motion transfer: its picture is a
            // reference, not a start frame.
            val prevFilm = source?.film?.let { File(it) }?.takeIf { it.isFile && motion == null }
            var filmFile = out
            var filmFrames = clip.frames
            var filmPoster = posterId
            if (prevFilm != null) {
                val film = File(dir, "film_" + sha1(prevFilm.name + "\u0000" + out.name) + ".mp4")
                filmFrames = if (film.isFile) FilmJoin.frames(film) else {
                    val j = FilmJoin.join(prevFilm, out, film, FPS) { Log.i(TAG, it); ctx.say(it.trim()) }
                    ctx.say("  joined onto the film so far: ${j.frames} frames (${"%.1f".format(j.frames.toDouble() / FPS)} s)" +
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
            raw.delete()
        }
    }

    /**
     * ⭐ The backend's frame file ([Ops.video]) into [out] as MP4, a frame at a time, with the
     * node's loop ([com.abrah.nightmare.ClipStore]) built on the way. Returns the first and last frames.
     */
    internal fun encodeClip(
        raw: File,
        out: File,
        isCancelled: () -> Boolean = { false },
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
            VideoWriter.encode(count, w, h, fps, out, log, isCancelled) { i ->
                // ⚠ Frame 0 again = [VideoWriter.encode] retrying on the software encoder: start over.
                if (i == 0) thumbs.clear()
                f.seek(HEADER_BYTES + i.toLong() * rgb.size)
                f.readFully(rgb)
                rgba.clear()
                var s = 0
                while (s < rgb.size) {
                    rgba.put(rgb[s]).put(rgb[s + 1]).put(rgb[s + 2]).put(0xFF.toByte())
                    s += 3
                }
                rgba.flip()
                frame.copyPixelsFromBuffer(rgba)
                if (i == 0) poster = frame.copy(Bitmap.Config.ARGB_8888, false)
                if (i == count - 1) last = frame.copy(Bitmap.Config.ARGB_8888, false)
                if (i % com.abrah.nightmare.ClipStore.EVERY == 0) thumbs.add(com.abrah.nightmare.ClipStore.thumb(frame))
                frame
            }
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
