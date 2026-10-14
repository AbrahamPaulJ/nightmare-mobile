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
    override val outputs = listOf(Port("video", "VIDEO"))
    override val category = "generate"
    override val paletteName = "Wan video"

    override fun titleFor(node: Node): String =
        if (node.inputs["image"] != null) "Image to video" else "Text to video"

    override val defaultId = "video"
    override val about = "a prompt, or a prompt and a photo, into a clip with Wan 2.2"

    /** ⚠ The clip belongs to `core.output`, like every other result. */
    override val showsResult = false

    /** ⭐ The two sizes Wan 2.2 TI2V was trained around: 480p (the default) and its native 704p. */
    const val SIZE_480 = "480p"
    const val SIZE_704 = "704p"
    const val LANDSCAPE = "landscape"
    const val PORTRAIT = "portrait"

    /** ⚠ 24, the rate Wan 2.2 was trained at; a clip is `24·s + 1` frames (Wan wants 4n+1). */
    const val FPS = 24
    const val MAX_SECONDS = 5

    /**
     * ⭐ The sheet draws these in this order UNDER the crop (`NodeInspector`'s widget loop),
     * and the order is the user's (2026-10-14): shape first, since it reshapes the crop above
     * it, then size, then seed.
     */
    override val widgets get() = listOf(
        Widget("shape", "string", LANDSCAPE, options = listOf(LANDSCAPE, PORTRAIT)),
        Widget("size", "string", SIZE_480, options = listOf(SIZE_480, SIZE_704), hintRes = R.string.hint_wan_size),
        Widget("seed", "int", "0", hintRes = R.string.hint_seed_clip),
        Widget("seconds", "int", "5", 1.0, MAX_SECONDS.toDouble(), hintRes = R.string.hint_wan_seconds),
        // ⭐ The framing, exactly as [VideoSampleNode] carries it.
        Widget("x", "float", "0.0", 0.0, 1.0, hintRes = R.string.hint_drag_frame),
        Widget("y", "float", "0.0", 0.0, 1.0),
        Widget("w", "float", "1.0", 0.0, 1.0),
        Widget("h", "float", "1.0", 0.0, 1.0),
    )

    /**
     * ⭐ One backend process serves every size and length: like every DiT model, the size is a
     * REQUEST field, so the key is the model's own ([com.abrah.nightmare.backendContextKey]'s rule).
     */
    override fun contextKey(node: Node): ContextKey {
        val spec = ModelCatalog.WAN22
        return ContextKey(spec.backendType, spec.id, spec.native.width, spec.native.height)
    }

    /** ⭐ The clip's size, which a picture wired into `image` is framed to. */
    override fun framesTo(node: Node): Pair<Int, Int> = sizeOf(effectiveParams(node))

    /** ⚠ Pure: the frame size the params ask for. Wan 2.2's grid is 32 px. */
    fun sizeOf(p: Map<String, String>): Pair<Int, Int> {
        val (w, h) = if (p["size"] == SIZE_704) 1280 to 704 else 832 to 480
        return if (p["shape"] == PORTRAIT) h to w else w to h
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
     * the size that worked, so 3 s (3.7 GB) and 704p are refused until a tester runs them.
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
    private val NPU_MAP_FAILED = Regex("""fastrpc_mmap failed|failed to allocate HTP0 buffer""")

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

    override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value =
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
        // ⚠ LoRA tags are taken out, not sent: no Wan LoRA has run on the NPU here yet.
        val pos = PromptLoras.extract(wired.positive)
        val neg = PromptLoras.extract(wired.negative)
        if (pos.tags.isNotEmpty() || neg.tags.isNotEmpty()) {
            ctx.say("LoRA tags skipped: Wan video takes no LoRAs yet")
        }
        val p = effectiveParams(node)
        val (w, h) = sizeOf(p)
        val frames = framesFor(p["seconds"]?.toIntOrNull() ?: MAX_SECONDS)
        val seed = p["seed"]?.toLongOrNull() ?: 0L
        // ⚠ A fallback, not the roll: `HarnessOps.runRolled` rolls a 0 before the graph runs
        // ([com.abrah.nightmare.SAMPLER_TYPES]), for the reason [VideoSampleNode] gives.
        val used = if (seed == 0L) System.currentTimeMillis() and 0x7fffffffL else seed

        // ⚠⚠ Refused BEFORE the backend allocates: a DMA-BUF overshoot can reboot the phone
        // rather than close the app (`docs/VIDEO-GGML.md` §4). ⚠ Against the phone's RAM, NOT
        // `MemAvailable`: that read 3.8 GB on the S25 right after a clip that ran from 6.3, and
        // lmkd closes background apps to make room — it refused a clip that fits. 0 = unknown.
        val caps = com.abrah.nightmare.DeviceProbe.caps()
        val ram = caps.ramBytes
        val need = pinnedBytes(w, h, frames)
        if (ram > 0 && !fits(need, ram)) {
            throw IllegalStateException(
                "node \"${node.id}\": ${w}x$h for ${frames / FPS} s needs about ${gb(need)} GB on the NPU, " +
                    "more than this phone's ${gb(ram)} GB of RAM allows. Choose 480p or fewer seconds"
            )
        }
        if (!fitsNpuMap(need, if (caps.known) caps.arch else 0)) {
            throw IllegalStateException(
                "node \"${node.id}\": on Snapdragon 8 Gen 3 the NPU can hold Wan clips up to 2 seconds at 480p " +
                    "for now (${w}x$h for ${frames / FPS} s needs about ${gb(need)} GB). Choose 480p and 2 seconds or less"
            )
        }

        val source = inputs["image"] as? Value.Image
        val startPng: ByteArray? = source?.let { img ->
            val src = ctx.images.get(img.id)
                ?: throw IllegalStateException("node \"${node.id}\": image ${img.id} is no longer in the store")
            fun f(k: String, d: Float) = p[k]?.toFloatOrNull() ?: d
            val framed = com.abrah.nightmare.CropNode.render(
                src, f("x", 0f), f("y", 0f), f("w", 1f), f("h", 1f), w, h,
                com.abrah.nightmare.CropNode.PAD_BLACK,
            ).first
            ByteArrayOutputStream().also { framed.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }

        // ⚠ A content address of everything the clip is made from, as [VideoSampleNode] does.
        val key = sha1(
            listOf(pos.text, neg.text, used.toString(), "${w}x$h", frames.toString(), source?.address().orEmpty())
                .joinToString("\u0000")
        )
        val dir = File(android.cacheDir, "video").apply { mkdirs() }
        val raw = File(dir, "wan_$key.nmv")
        val out = File(dir, "clip_wan_$key.mp4")
        ctx.say("seed $used, ${w}x$h, $frames frames" + if (startPng != null) ", animating your picture" else "")
        Log.i(TAG, "wan: seed $used ${w}x$h x$frames i2v=${startPng != null}")

        val r = ctx.host.video(
            prompt = pos.text, negative = neg.text, width = w, height = h, frames = frames, fps = FPS,
            seed = used, steps = SIGMAS.size - 1, sampleMethod = "lcm", sigmas = SIGMAS, tilingArgs = TILING,
            out = raw.absolutePath, startPng = startPng, loras = emptyList(),
        ) { pr -> ctx.onProgress(pr) }
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
            val poster = encodeClip(raw, out)
            val posterId = ctx.images.put(poster)
            Value.Video(out.absolutePath, clip.frames, clip.width, clip.height, posterId)
        } finally {
            raw.delete()
        }
    }

    /**
     * ⭐ The backend's frame file ([Ops.video]) into [out] as MP4, a frame at a time, with the
     * node's loop ([com.abrah.nightmare.ClipStore]) built on the way. Returns the first frame.
     */
    internal fun encodeClip(raw: File, out: File): Bitmap {
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
            val thumbs = ArrayList<Bitmap>()
            VideoWriter.encode(count, w, h, fps, out, { Log.i(TAG, it) }) { i ->
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
                if (i % com.abrah.nightmare.ClipStore.EVERY == 0) thumbs.add(com.abrah.nightmare.ClipStore.thumb(frame))
                frame
            }
            com.abrah.nightmare.ClipStore.putThinned(out.absolutePath, thumbs)
            return poster ?: error("no frames in ${raw.name}")
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
