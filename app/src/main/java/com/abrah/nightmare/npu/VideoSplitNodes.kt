package com.abrah.nightmare.npu

import android.graphics.Bitmap
import android.util.Log
import com.abrah.nightmare.ContextKey
import com.abrah.nightmare.Node
import com.abrah.nightmare.NodeCtx
import com.abrah.nightmare.NodeType
import com.abrah.nightmare.Port
import com.abrah.nightmare.Value
import com.abrah.nightmare.Widget
import com.abrah.nightmare.str
import java.io.File
import java.util.Random

/**
 * ⭐⭐ **The video path, decomposed — the same shape as the SD nodes.**
 *
 * ```
 *                     ┌─ nd.first_frame(seed) ─→ IMAGE ─┐
 * prompt ─→ nd.clip_encode                              ├→ nd.vae_encode → VIDEO_LATENT
 *                     └─ cond ──────────────────┐       │                      │
 *    image.load → image.crop ── IMAGE ───────────┴───────┘                      ↓
 *                                       nd.sample(cond, latent, seed) → VIDEO_LATENT
 *                                                                              ↓
 *                                                  VIDEO ← nd.vae_decode
 * ```
 *
 * ⚠ **Consistent with SD by construction**, not by resemblance: the prompt is a
 * WIRE and not a widget (`sd.sample` has no prompt either), the core stays
 * FUSED and only the EDGES decompose (`docs/ARCHITECTURE.md` §3), and
 * image-to-video is not a flag — it is `image.crop` where `nd.first_frame`
 * would be, the same substitution img2img already makes against `sd.vae_encode`.
 *
 * ⚠⚠ **`VIDEO_COND` and `VIDEO_LATENT` are deliberately NOT `COND` and
 * `LATENT`.** Those name tensors living in the backend process; these are float
 * arrays in this one. Sharing the port names would let an SD latent drop into a
 * video decoder, and this codebase's recurring failure is a graph that "decodes
 * plausible garbage rather than failing". Port compatibility is exact string
 * match, so distinct names buy the refusal for free.
 *
 * ⚠⚠⚠ **The same seed gives a different clip here than through the fused
 * `nd.video_sample`.** That node threads ONE `Random` through every phase in
 * order; separate nodes each start their own. Nothing in this pipeline was ever
 * bit-reproducible against the desktop reference — the seed hint says so — but
 * it is a behaviour change and it is stated rather than discovered.
 *
 * ⇒ `docs/NEODRAGON.md` §8.
 */

/** ⚠ Every node here reaches the NPU in-process and binds no [ContextKey]. */
abstract class VideoNode : NodeType {
    override val appSide = false
    override val category = "video"
    override fun contextKey(node: Node): ContextKey? = null

    protected fun android(ctx: NodeCtx, node: Node): android.content.Context =
        ctx.android ?: throw IllegalStateException(
            "node \"${node.id}\": the video path needs a platform context and this " +
                "executor has none"
        )

    /**
     * ⚠⚠ **One runner for the whole graph, not one per node.**
     *
     * Residency is the entire reason the pipeline is affordable: `clipg` costs
     * 1897 ms to map and 40 ms to run, and it is needed by two different
     * phases. A runner per node would map it twice. ⇒ The phases release what
     * they are finished with, exactly as they did inside the fused node; what
     * changed is only who calls them.
     */
    protected fun runner(ctx: NodeCtx, node: Node): QnnRunner = VideoRunner.get(android(ctx, node))

    protected fun requireModels(ctx: NodeCtx, node: Node, needed: List<String>) {
        val a = android(ctx, node)
        val missing = NpuFiles.missing(a, needed)
        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "node \"${node.id}\": ${missing.size} video model(s) not on this device " +
                    "(${missing.take(3).joinToString()}${if (missing.size > 3) ", …" else ""}) " +
                    "— install them under Models > Video"
            )
        }
        val weights = NpuFiles.missingAssets(a)
        if (weights.isNotEmpty()) {
            throw IllegalStateException(
                "node \"${node.id}\": video weights missing: ${weights.joinToString()}"
            )
        }
    }
}

/**
 * ⭐⭐ **The graph-scoped runner.**
 *
 * ⚠⚠ Process-global because a node body has no per-run scope to hang it on. It
 * is *cleared* by the executor's caller rather than per node, which is the
 * whole point: contexts have to outlive a single node or the split costs more
 * than it saves.
 *
 * ⚠ The phases already release what they finish with (`cliplp` after the text
 * encode, the stage binaries after the loop, the decoder after the frames), so
 * this holds only what is genuinely still needed. Peak RSS with the canvas up
 * is 1668 MB for t2v, and that budget is what these releases protect.
 */
object VideoRunner {
    private var runner: QnnRunner? = null

    @Synchronized
    fun get(ctx: android.content.Context): QnnRunner =
        runner ?: QnnRunner(ctx).also { runner = it }

    /** ⚠ Called when a graph run ends, however it ended. */
    @Synchronized
    fun releaseAll() {
        runner?.releaseAll()
        TensorStore.clear()
    }

    @Synchronized
    fun resident(): List<String> = runner?.resident().orEmpty()
}

/**
 * ⭐⭐ prompt → the two conditionings.
 *
 * ⚠⚠⚠ **Two outputs, because there are genuinely two conditionings.** SSD1B
 * needs SDXL-shaped conditioning — `clipl` + `clipg` **hidden states**
 * concatenated to 2048 — while the MMDiT needs `cliplp` + `clipg` **pooled**
 * plus a DistilT5 context. They share `clipg` and use different OUTPUTS of it,
 * which is exactly why [Video.encodePrompt] keeps it resident instead of
 * releasing it. One node with two outputs was the user's call, 2026-09-13: one
 * prompt box on the canvas rather than two showing the same text.
 *
 * ⚠⚠ **`frame_cond` is computed only when something reads it** ([NodeCtx.wanted]).
 * In an image-to-video graph nothing does, and producing it anyway would map
 * and run `clipl` (234 MB) for nothing — undoing the saving that makes i2v the
 * cheaper path.
 */
object VideoClipEncodeNode : VideoNode() {
    override val name = "nd.clip_encode"
    override val version = "1"
    override val inputs = emptyList<Port>()

    /** ⚠ `cond` FIRST: the primary port is what the canvas draws and what a bare wire means. */
    override val outputs = listOf(Port("cond", "VIDEO_COND"), Port("frame_cond", "FRAME_COND"))

    /** ⭐ Drawn in the node's body, like `sd.clip_encode`'s. */
    override val prose = listOf("prompt")

    override val widgets = listOf(
        Widget(
            "prompt", "string", "a cat walking through tall grass, cinematic",
            hint = "what to animate",
        ),
    )

    override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value =
        runPorts(ctx, node, inputs).getValue("cond")

    override suspend fun runPorts(
        ctx: NodeCtx,
        node: Node,
        inputs: Map<String, Value>,
    ): Map<String, Value> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val a = android(ctx, node)
        val prompt = node.str("prompt")
        // ⚠ Only what THIS node runs. `clipl` is required solely for the
        // first-frame conditioning, so an i2v graph must not be refused for
        // lacking it.
        val wantsFrame = "frame_cond" in ctx.wanted
        requireModels(
            ctx, node,
            listOf("cliplp", "clipg", "distilt5f", "ctxadaptfp16") +
                if (wantsFrame) listOf("clipl") else emptyList(),
        )
        val r = runner(ctx, node)
        ctx.say("encoding the prompt")
        val v = Video(a, r) { ctx.say(it.trim()); Log.i(TAG, it) }
        val cond = v.encodePrompt(prompt)
        val out = LinkedHashMap<String, Value>()
        out["cond"] = Value.Tensors(
            TensorStore.put(
                "video_cond", "$prompt|${VideoStructure.numStages}",
                TensorStore.Bundle(
                    mapOf("context" to cond.context, "pooled" to cond.pooled,
                          "temb" to cond.temb, "t5mask" to cond.t5mask),
                ),
            ),
            "video_cond",
        )
        if (wantsFrame) {
            ctx.say("encoding the prompt for the first frame")
            val fc = FirstFrame(a, r) { ctx.say(it.trim()); Log.i(TAG, it) }.encode(prompt)
            out["frame_cond"] = Value.Tensors(
                TensorStore.put(
                    "frame_cond", prompt,
                    TensorStore.Bundle(mapOf("ehs" to fc.ehs, "pooled" to fc.pooled)),
                ),
                "frame_cond",
            )
        } else {
            Log.i(TAG, "frame_cond unwired — clipl not loaded")
        }
        out
    }

    private const val TAG = "ndClipEncode"
}

/**
 * ⭐⭐ conditioning + seed → a 1024x640 picture, on the NPU, with no backend and
 * no checkpoint.
 *
 * ⚠ Its output is a plain **IMAGE**, the user's call — so it feeds `image.crop`,
 * `image.upscale` or Save just as readily as it feeds [VideoVaeEncodeNode].
 * That makes it a second text-to-image path in the app for free.
 */
object VideoFirstFrameNode : VideoNode() {
    override val name = "nd.first_frame"
    override val version = "1"
    override val inputs = listOf(Port("cond", "FRAME_COND"))
    override val outputs = listOf(Port("image", "IMAGE"))

    override val widgets = listOf(
        Widget("seed", "int", "0", hint = "0 = a new first frame every Run. Type the seed shown on the node to get that one back."),
    )

    /** ⚠ It makes exactly the size the video path animates. */
    override fun outputSize(node: Node): Pair<Int, Int> = VideoStructure.frameSize

    override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val a = android(ctx, node)
            requireModels(ctx, node, listOf("clipl", "clipg", "ssd1bunet", "ssd1bvaedec"))
            val h = inputs["cond"] as? Value.Tensors
                ?: throw IllegalArgumentException(
                    "node \"${node.id}\": input \"cond\" is not connected"
                )
            val b = TensorStore.get(h.id)
                ?: throw IllegalStateException(
                    "node \"${node.id}\": that conditioning has been evicted — Run again"
                )
            val seed = node.params["seed"]?.toLongOrNull() ?: 0L
            val used = if (seed == 0L) System.currentTimeMillis() else seed
            ctx.say("first frame, seed $used")
            val bmp: Bitmap = FirstFrame(a, runner(ctx, node)) {
                ctx.say(it.trim()); Log.i("ndFirstFrame", it)
            }.render(
                FirstFrame.FrameCond(b.named.getValue("ehs"), b.named.getValue("pooled")),
                used,
            ) { i, n -> ctx.onProgress(com.abrah.nightmare.Ops.Progress(i, n, 0L)) }
            val id = ctx.images.put(bmp)
            Value.Image(id, bmp.width, bmp.height)
        }
}

/**
 * ⭐ A picture → the unit-0 latent.
 *
 * ⚠⚠ It declares [requiredInputSize], which is what makes an `image.crop` in
 * front of it derive and LOCK 512x320 and makes a bare `image.load` refuse
 * itself with "put a Crop between them" — the same mechanism `sd.vae_encode`
 * uses, reused rather than reinvented.
 */
object VideoVaeEncodeNode : VideoNode() {
    override val name = "nd.vae_encode"
    override val version = "1"
    override val inputs = listOf(Port("image", "IMAGE"))
    override val outputs = listOf(Port("latent", "VIDEO_LATENT"))

    override val widgets = listOf(
        Widget("seed", "int", "0", hint = "encoding noise, not the clip seed — it changes the result only slightly, so leave it fixed. The sample node's seed is the one that matters."),
    )

    override fun requiredInputSize(node: Node, port: String): Pair<Int, Int>? =
        if (port == "image") VideoStructure.frameSize else null

    override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val a = android(ctx, node)
            requireModels(ctx, node, listOf("vaeenc"))
            val img = inputs["image"] as? Value.Image
                ?: throw IllegalArgumentException(
                    "node \"${node.id}\": input \"image\" is not connected"
                )
            val bmp = ctx.images.get(img.id)
                ?: throw IllegalStateException(
                    "node \"${node.id}\": image ${img.id} is no longer in the store"
                )
            val seed = node.params["seed"]?.toLongOrNull() ?: 0L
            val used = if (seed == 0L) System.currentTimeMillis() else seed
            val v = Video(a, runner(ctx, node)) { ctx.say(it.trim()); Log.i("ndVaeEncode", it) }
            ctx.say("encoding the first frame")
            val lat = v.encodeImage(bmp, Random(used))
            Value.Tensors(
                TensorStore.put(
                    "video_latent", "enc|${img.id}|$used",
                    TensorStore.Bundle(mapOf("d" to lat.d),
                        mapOf("c" to lat.c, "t" to lat.t, "h" to lat.h, "w" to lat.w)),
                ),
                "video_latent",
            )
        }
}

/**
 * ⭐⭐ The 18 MMDiT calls — **fused, exactly as `sd.sample` is fused**.
 *
 * ⚠⚠ The schedule is pyramidal with per-(unit, stage) structure; exposing one
 * call as a node would put that scheduler's state in JS, which is the reason
 * `docs/ARCHITECTURE.md` §3 gives for `sd.sample` and it applies unchanged.
 */
object VideoSampleSplitNode : VideoNode() {
    override val name = "nd.sample"
    override val version = "1"
    override val inputs = listOf(
        Port("cond", "VIDEO_COND"), Port("latent", "VIDEO_LATENT"),
    )
    override val outputs = listOf(Port("latent", "VIDEO_LATENT"))

    override val widgets = listOf(
        Widget("seed", "int", "0", hint = "0 = a new clip every Run. Type the seed shown on the node to get that one back."),
    )

    override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val a = android(ctx, node)
            requireModels(ctx, node, listOf("mmdit_s0fs", "mmdit_s1fs", "mmdit_s2fs"))
            val ch = inputs["cond"] as? Value.Tensors
                ?: throw IllegalArgumentException(
                    "node \"${node.id}\": input \"cond\" is not connected"
                )
            val lh = inputs["latent"] as? Value.Tensors
                ?: throw IllegalArgumentException(
                    "node \"${node.id}\": input \"latent\" is not connected"
                )
            val cb = TensorStore.get(ch.id) ?: throw IllegalStateException(
                "node \"${node.id}\": that conditioning has been evicted — Run again"
            )
            val lb = TensorStore.get(lh.id) ?: throw IllegalStateException(
                "node \"${node.id}\": that latent has been evicted — Run again"
            )
            val seed = node.params["seed"]?.toLongOrNull() ?: 0L
            val used = if (seed == 0L) System.currentTimeMillis() else seed
            val r = runner(ctx, node)
            val v = Video(a, r) { ctx.say(it.trim()); Log.i("ndSample", it) }
            // ⚠⚠ Before the loop maps three ~1.5 GB stages. The first-frame
            // graphs may still be resident from this same graph run, and
            // leaving them put ~7.5 GB of context in one process and got the
            // app killed (observed 2026-08-23).
            v.releaseFirstFramePath()
            ctx.say("seed $used")
            val first = com.abrah.nightmare.npu.Latent(
                lb.meta.getValue("c"), lb.meta.getValue("t"),
                lb.meta.getValue("h"), lb.meta.getValue("w"),
            ).also { lb.named.getValue("d").copyInto(it.d) }
            val out = v.sample(
                Video.Cond(
                    cb.named.getValue("context"), cb.named.getValue("pooled"),
                    cb.named.getValue("temb"), cb.named.getValue("t5mask"),
                ),
                first, Random(used),
            ) { _, i, n -> ctx.onProgress(com.abrah.nightmare.Ops.Progress(i, n, 0L)) }
            Value.Tensors(
                TensorStore.put(
                    "video_latent", "sample|${ch.id}|${lh.id}|$used",
                    TensorStore.Bundle(mapOf("d" to out.d),
                        mapOf("c" to out.c, "t" to out.t, "h" to out.h, "w" to out.w)),
                ),
                "video_latent",
            )
        }
}

/**
 * ⭐⭐ Latents → a clip. **Terminal, exactly as `sd.vae_decode` is.**
 *
 * ⚠⚠ The upscale stays a WIDGET here rather than becoming a `video.upscale`
 * node, and that is a measurement rather than a preference: QuickSRNet runs per
 * FRAME inside the streaming decode, where the full-size frames exist for a few
 * milliseconds each. A separate node would have to decode the MP4 again to get
 * them back — paying an encode and a decode to move a checkbox.
 */
object VideoVaeDecodeNode : VideoNode() {
    /** ⚠⚠ It RENDERS the clip, so the clip belongs to `core.output` like every
     * other result ([NodeType.showsResult]). One rule, no exceptions. */
    override val showsResult = false
    override val name = "nd.vae_decode"
    override val version = "1"
    override val inputs = listOf(Port("latent", "VIDEO_LATENT"))
    override val outputs = listOf(Port("video", "VIDEO"))

    override val widgets = listOf(
        Widget(
            "upscale", "bool", "true",
            hint = "2x to 1024x640 with QuickSRNet. Off renders 512x320 and is a little faster.",
        ),
    )

    override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val a = android(ctx, node)
            requireModels(ctx, node, listOf("vaedecsn"))
            val lh = inputs["latent"] as? Value.Tensors
                ?: throw IllegalArgumentException(
                    "node \"${node.id}\": input \"latent\" is not connected"
                )
            val lb = TensorStore.get(lh.id) ?: throw IllegalStateException(
                "node \"${node.id}\": that latent has been evicted — Run again"
            )
            val v = Video(a, runner(ctx, node)) { ctx.say(it.trim()); Log.i("ndVaeDecode", it) }
            ctx.say("decoding")
            val lat = com.abrah.nightmare.npu.Latent(
                lb.meta.getValue("c"), lb.meta.getValue("t"),
                lb.meta.getValue("h"), lb.meta.getValue("w"),
            ).also { lb.named.getValue("d").copyInto(it.d) }
            val frames = v.decode(lat)
            check(frames.isNotEmpty()) { "node \"${node.id}\": the decoder produced no frames" }

            // ⚠ Content-addressed, so two identical decodes are one file and one
            // cache key downstream. A timestamp here would make every run look new.
            val out = File(
                File(a.cacheDir, "video").apply { mkdirs() }, "clip_${lh.id.takeLast(16)}.mp4"
            )
            VideoWriter.encode(a, frames, VideoSampleNode.FPS, out) { Log.i("ndVaeDecode", it) }
            com.abrah.nightmare.ClipStore.put(out.absolutePath, frames)
            val posterId = ctx.images.put(frames.first())
            Value.Video(out.absolutePath, frames.size, frames[0].width, frames[0].height, posterId)
        }
}
