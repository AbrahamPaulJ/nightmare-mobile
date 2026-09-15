package com.abrah.nightmare.npu

import android.content.Context
import android.graphics.Bitmap
import java.util.Random

/**
 * 49-frame video, end to end on the phone.
 *
 * The port of `work/pipeline/run_npu_video.py` plus the decode half that the desktop
 * driver never wired. Seven graphs run on the HTP:
 *
 *     cliplp / clipg   pooled text embeddings -> the MMDiT's 2048-d pooled_projections
 *     distilt5f        prompt -> [1,128,2048]                      49.04 dB
 *     ctxadaptfp16     -> [1,128,1536] cross-attention context     39.36 dB
 *     ssd1b*           the first frame (via FirstFrame)            see that file
 *     vaeenc           first frame -> the unit-0 latent            41.60 dB
 *     mmdit_s0fs/s1fs/s2fs  18 calls, ~90% of the compute       39.7/37.8/33.9 dB
 *     quicksrm2x       2x upscale -> 640x1024                    optional
 *     vaedecsn         streaming decode, 8 frames per invocation   34.27 dB
 *
 * The orchestration -- the pyramid schedule, the history pyramid, the per-stage sigmas --
 * is NOT reimplemented here. Everything that depends only on (unit, stage) was computed
 * on the desktop against the reference and shipped as an asset; see VideoStructure.
 *
 * With `num_inference_steps=[1,1,1]` each stage is a single Euler step from sigma 1 to 0,
 * so `scheduler.step` reduces to `sample - noise_pred`.
 */
class Video(
    private val ctx: Context,
    private val runner: QnnRunner,
    private val log: (String) -> Unit,
) {

    private val vs get() = VideoStructure.cfg

    companion object {
        val CLIP_LP = QnnRunner.Graph("cliplp")
        val CLIP_G = QnnRunner.Graph("clipg")
        val T5 = QnnRunner.Graph("distilt5f")
        val CTXADAPT = QnnRunner.Graph("ctxadaptfp16")
        val VAE_ENC = QnnRunner.Graph("vaeenc")
        val VAE_DEC = QnnRunner.Graph("vaedecsn")
        val UPSCALE = QnnRunner.Graph("quicksrm2x")
        val STAGE_BIN = arrayOf("mmdit_s0fs", "mmdit_s1fs", "mmdit_s2fs")

        fun requiredModels() = listOf(
            "cliplp", "clipg", "distilt5f", "ctxadaptfp16", "vaeenc",
            "mmdit_s0fs", "mmdit_s1fs", "mmdit_s2fs", "vaedecsn", "quicksrm2x",
            "clipl", "ssd1bunet", "ssd1bvaedec",
        )
    }

    class Result(val frames: List<Bitmap>, val seconds: Double)

    /**
     * ⭐⭐ The prompt, encoded — everything the MMDiT needs that does not
     * depend on the picture or the seed.
     *
     * ⚠⚠ [temb] is here rather than computed in [sample] because it is a
     * function of the POOLED text embedding and the stage schedule, and nothing
     * else. Recomputing it per call would be free; putting it here is what
     * makes "encode the prompt" a complete answer, so a node can hold it.
     */
    class Cond(
        val context: FloatArray,
        val pooled: FloatArray,
        val temb: FloatArray,
        val t5mask: FloatArray,
    )

    /**
     * ⭐⭐ **The phases, as functions.**
     *
     * ⚠⚠ Extracted from [generate] so the graph can hold the seams as nodes
     * (`docs/NEODRAGON.md` §8). [generate] still composes them in the same
     * order, against ONE [Random], so the fused path is bit-identical to what
     * it was — which is the only reason this refactor could be verified
     * against a 25-second render rather than argued about.
     *
     * ⚠⚠⚠ **The rng is a PARAMETER, and that is the whole difficulty of
     * splitting this.** Today one stream is drawn from in order: the first
     * latent's sampling, then the unit noise, then every pyramidal upsample. As
     * separate nodes each phase must start its own stream, so the same seed
     * gives a DIFFERENT clip than the fused path does. That is acceptable —
     * nothing here was ever bit-reproducible against the desktop reference, and
     * the node's own hint says so — but it is a behaviour change, not a
     * refactor, and it must not happen by accident. ⇒ Each caller passes the
     * stream it means.
     */
    fun encodePrompt(prompt: String): Cond {
        AppAssets.load(ctx)
        VideoStructure.load(ctx)
        val full = prompt + vs.getString("prompt_modifier")
        val clipTok = ClipTokenizer(ctx)
        val idsL = clipTok.encode(full, AppAssets.config.getInt("pad_id_clip_l"), 77)
        val idsG = clipTok.encode(full, AppAssets.config.getInt("pad_id_clip_g"), 77)
        val pooledL = runner.run(CLIP_LP, intInputs = mapOf("input_ids" to idsL))
            .values.first { it.size == 768 }
        val pooledG = runner.run(CLIP_G, intInputs = mapOf("input_ids" to idsG))
            .values.first { it.size == 1280 }
        // cliplp (238 MB) is done for good -- the video path uses it only for the pooled
        // vector above. clipg is NOT: the first frame needs it again. Releasing it here
        // made the video path map its 1.4 GB context TWICE, at 1897 ms a time, for no
        // benefit (measured 2026-08-23).
        runner.release("cliplp")

        // `TextEncoderBundle` concatenates the two POOLED vectors -- not the hidden
        // states -- to form the MMDiT's pooled_projections.
        val pooled = FloatArray(2048)
        System.arraycopy(pooledL, 0, pooled, 0, 768)
        System.arraycopy(pooledG, 0, pooled, 768, 1280)

        val t5tok = T5Tokenizer(ctx)
        val (t5ids, t5mask) = t5tok.encode(full)
        log("      t5 ids[0..7] = " + t5ids.take(8).joinToString(",") +
            "  valid ${t5mask.count { it > 0f }}")
        val promptEmbeds = runner.run(
            T5, floats = mapOf("attention_mask" to t5mask),
            intInputs = mapOf("input_ids" to t5ids)).values.first()
        runner.release("distilt5f")

        val context = runner.run(CTXADAPT, floats = mapOf("prompt_embeds" to promptEmbeds))
            .values.first()
        runner.release("ctxadaptfp16")
        log("      context ${context.size} floats")

        val numStages = VideoStructure.numStages
        val stageT = vs.getJSONArray("stage_timestep").let { a ->
            FloatArray(a.length()) { a.getDouble(it).toFloat() }
        }
        val temb = FloatArray(numStages * 1536).also { buf ->
            for (st in 0 until numStages) hostTemb(stageT[st], pooled).copyInto(buf, st * 1536)
        }
        return Cond(context, pooled, temb, t5mask)
    }

    /**
     * ⭐ A picture → the unit-0 latent.
     *
     * ⚠ The reference SAMPLES from the VAE's distribution rather than taking
     * the mean, so this is not a deterministic function of the picture — which
     * is why it takes [rng] and not merely a seed.
     */
    fun encodeImage(bmp: Bitmap, rng: Random): Latent {
        VideoStructure.load(ctx)
        val latC = VideoStructure.latC
        val H = vs.getInt("video_height") / 8
        val W = vs.getInt("video_width") / 8
        // ⚠⚠ The crop node's demand is a compile-time default until the
        // structure has been read ([VideoStructure.frameSize]). If the file ever
        // disagreed, every crop in every saved graph would be silently
        // re-derived. Checked here, loudly, rather than trusted.
        check(VideoStructure.frameSize == vs.getInt("video_width") to vs.getInt("video_height")) {
            "video_structure.json says ${vs.getInt("video_width")}x${vs.getInt("video_height")} " +
                "but the node declares ${VideoStructure.frameSize} — every image.crop " +
                "feeding a video sampler is now the wrong size"
        }
        val img = bitmapToChw(bmp, vs.getInt("video_width"), vs.getInt("video_height"))
        val moments = runner.run(VAE_ENC, floats = mapOf("image" to img)).values.first()
        runner.release("vaeenc")

        val nLat = latC * H * W
        val firstLatent = Latent(latC, 1, H, W)
        for (i in 0 until nLat) {
            val mean = moments[i]
            val std = kotlin.math.exp(0.5 * moments[nLat + i]).toFloat()
            firstLatent.d[i] = mean + std * rng.nextGaussian().toFloat()
        }
        val sf = vs.getDouble("vae_scale_factor").toFloat()
        val sh = vs.getDouble("vae_shift_factor").toFloat()
        for (i in firstLatent.d.indices) firstLatent.d[i] = (firstLatent.d[i] - sh) * sf
        return firstLatent
    }

    /**
     * ⭐⭐ The autoregressive loop — 18 MMDiT calls.
     *
     * ⚠⚠ **FUSED and staying fused**, exactly as `sd.sample` is
     * (`docs/ARCHITECTURE.md` §3): the schedule is pyramidal with per-(unit,
     * stage) structure, and exposing one call as a node would put that
     * scheduler's state in JS.
     */
    fun sample(
        cond: Cond,
        firstLatent: Latent,
        rng: Random,
        onProgress: (String, Int, Int) -> Unit,
    ): Latent {
        VideoStructure.load(ctx)
        val numStages = VideoStructure.numStages
        val startUnit = VideoStructure.startUnit
        val numUnits = VideoStructure.numUnits
        val latC = VideoStructure.latC
        val H = vs.getInt("video_height") / 8
        val W = vs.getInt("video_width") / 8
        val chol = vs.getJSONArray("block_noise_chol").let { a ->
            Array(4) { i -> a.getJSONArray(i).let { r -> FloatArray(4) { r.getDouble(it).toFloat() } } }
        }
        val gamma = vs.getDouble("gamma")
        val origStart = vs.getJSONArray("orig_start_sigmas").let { a ->
            DoubleArray(a.length()) { a.getDouble(it) }
        }

        log("[4/6] MMDiT ${(numUnits - startUnit) * numStages} calls")
        // full-resolution noise for every unit, then dropped to the coarsest stage
        val noiseFull = Latent(latC, numUnits, H, W).also { l ->
            for (i in l.d.indices) l.d[i] = rng.nextGaussian().toFloat()
        }
        val noise = downsampleNoise2x(noiseFull, numStages - 1)

        val history = ArrayList<Latent>()
        history.add(firstLatent)

        var call = 0
        val totalCalls = (numUnits - startUnit) * numStages
        for (unit in startUnit until numUnits) {
            val pyr = pyramid(Latent.catTime(history), numStages)
            var lat = noise.slice(unit, unit + 1)

            for (stage in 0 until numStages) {
                if (stage > 0) {
                    lat = upsamplePyramidal(lat, 1.0 - origStart[stage], gamma, chol, rng)
                }
                val c = VideoStructure.call(unit, stage)

                // past conditions, gathered from the history pyramid by the precomputed
                // recipe, then the current latent last
                val real = ArrayList<Latent>()
                for (hsl in c.history) real.add(pyr[hsl[0]].slice(hsl[1], hsl[2]))
                real.add(lat)

                val pred = runMmdit(c, stage, real, cond.context, cond.temb, stage, cond.t5mask)

                // one Euler step, sigma 1 -> 0
                for (i in lat.d.indices) lat.d[i] -= pred[i]

                call++
                onProgress("MMDiT unit $unit stage $stage", call, totalCalls)
            }
            history.add(lat)
            log("      unit $unit done  rms ${"%.4f".format(rms(lat.d))}")
        }
        for (n in STAGE_BIN) runner.release(n)
        return Latent.catTime(history)
    }

    /** ⭐ Latents → frames. ⚠ Releases the decoder and the upscaler after. */
    fun decode(latents: Latent): List<Bitmap> {
        VideoStructure.load(ctx)
        val frames = decodeVideo(latents)
        runner.release("vaedecsn"); runner.release("quicksrm2x")
        return frames
    }

    /** ⚠ Freed unconditionally before the MMDiT maps three ~1.5 GB stages. */
    fun releaseFirstFramePath() {
        // These may be resident even in the image-to-video case -- a previous "Image"
        // run leaves ssd1bunet (1.3 GB), clipl and ssd1bvaedec mapped, and clipg
        // (1.4 GB) is deliberately kept. None are needed past this point in either
        // mode, and the MMDiT is about to map three ~1.5 GB stages on top. Leaving
        // these behind put ~7.5 GB of context in one process and the app was killed
        // mid-loop (observed 2026-08-23).
        //
        // release() is a no-op for a graph that is not loaded, so this is safe either way.
        for (n in listOf("ssd1bunet", "ssd1bvaedec", "clipl", "clipg")) runner.release(n)
        log("      released first-frame graphs before the MMDiT loop")
    }

    /**
     * @param image when non-null, animate THIS picture instead of generating a first
     *        frame with SSD1B. The reference pipeline is built this way round already --
     *        `generate(image=...)` is the real entry point and `generate_hybrid()` is a
     *        thin wrapper that makes a first frame and passes it in -- so image-to-video
     *        is the native mode, not a bolt-on.
     *
     *        Skipping SSD1B removes ~2.1 s of compute AND means clipl / ssd1bunet /
     *        ssd1bvaedec (1.68 GB) never need to be mapped at all.
     */
    fun generate(prompt: String, seed: Long = 0L, image: Bitmap? = null,
                 onProgress: (String, Int, Int) -> Unit):
        Result {
        AppAssets.load(ctx)
        VideoStructure.load(ctx)
        val t0 = System.currentTimeMillis()

        // ⚠⚠⚠ **ONE stream, threaded through every phase in order**, which is
        // what keeps this bit-identical to the version before the phases were
        // extracted. The node split cannot do this — each node starts its own
        // — so the fused path and the graph path give different clips for the
        // same seed, deliberately and not by accident. `docs/NEODRAGON.md` §8.
        val rng = Random(seed)

        // ---- 1. text ------------------------------------------------------------
        onProgress("text encoders", 0, 1)
        log("[1/6] text encoders")
        val cond = encodePrompt(prompt)

        // ---- 2. first frame -> unit 0 latent -------------------------------------
        val bmp: Bitmap
        if (image != null) {
            onProgress("using supplied image", 0, 1)
            log("[2/6] first frame: user image (SSD1B skipped)")
            bmp = image
        } else {
            onProgress("first frame", 0, 1)
            log("[2/6] first frame (SSD1B)")
            bmp = FirstFrame(ctx, runner) { log("      $it") }.generate(prompt, seed)
        }
        releaseFirstFramePath()

        log("[3/6] VAE encode first frame")
        val firstLatent = encodeImage(bmp, rng)

        // ---- 4. autoregressive loop ---------------------------------------------
        val latents = sample(cond, firstLatent, rng, onProgress)

        // ---- 5. decode -----------------------------------------------------------
        log("[5/6] streaming VAE decode")
        val frames = decode(latents)

        val secs = (System.currentTimeMillis() - t0) / 1000.0
        log("[6/6] ${frames.size} frames in %.1f s".format(secs))
        return Result(frames, secs)
    }

    // ------------------------------------------------------------------------- //

    /** `silu(time_text_embed(t, pooled))`, hoisted out of the graph (trap #20). */
    private fun hostTemb(timestepRatio: Float, pooled: FloatArray): FloatArray {
        val proj = timestepEmbedding(timestepRatio, 256, true, 0f)
        val tEmb = linear(
            silu(linear(proj, AppAssets["timestep_embedder.linear_1.weight"],
                        AppAssets["timestep_embedder.linear_1.bias"])),
            AppAssets["timestep_embedder.linear_2.weight"],
            AppAssets["timestep_embedder.linear_2.bias"])
        val pEmb = linear(
            silu(linear(pooled, AppAssets["text_embedder.linear_1.weight"],
                        AppAssets["text_embedder.linear_1.bias"])),
            AppAssets["text_embedder.linear_2.weight"],
            AppAssets["text_embedder.linear_2.bias"])
        return silu(FloatArray(tEmb.size) { tEmb[it] + pEmb[it] })
    }

    /** Pad the real latents onto the envelope slots and run the stage graph. */
    private fun runMmdit(
        c: VideoStructure.Call, stage: Int, real: List<Latent>,
        context: FloatArray, temb: FloatArray, tembSlot: Int, textMask: FloatArray,
    ): FloatArray {
        val env = c.envShapes
        val plan = c.padPlan
        val feed = HashMap<String, FloatArray>()

        for ((slot, shape) in env.withIndex()) {
            val (ri, nPad) = plan[slot].let { it[0] to it[1] }
            val (t, h, w) = Triple(shape[0], shape[1], shape[2])
            val padded = Latent(VideoStructure.latC, t, h, w)
            if (ri >= 0) {
                val x = real[ri]
                // front-padding: real frames sit at the END, because split_output takes
                // the trailing tokens
                for (ci in 0 until x.c) {
                    System.arraycopy(x.d, ci * x.frame, padded.d,
                                     ci * padded.frame + nPad * padded.plane,
                                     x.t * x.plane)
                }
            }
            feed["latent_$slot"] = padded.d
        }

        feed["encoder_hidden_states"] = context
        feed["temb_act"] = FloatArray(1536) { temb[tembSlot * 1536 + it] }
        feed["attn_mask"] = VideoStructure.attnMask(c, textMask)
        feed["rope_cos"] = c.ropeCos.data
        feed["rope_sin"] = c.ropeSin.data

        return runner.run(QnnRunner.Graph(STAGE_BIN[stage]), floats = feed).values.first()
    }

    /**
     * Streaming decode: one latent frame in, 8 video frames out, 9 MemBlock states
     * carried across invocations.
     *
     * The states are never interpreted here -- each `nstate_i` is fed straight back as
     * the next `state_i` -- so the fact that the shipping graph wants them channel-last
     * is invisible. Their sizes come from the binary's own metadata.
     */
    /** True when quicksrm2x is on the device; the video is 320x512 without it. */
    private val upscale: Boolean by lazy {
        runner.isReady("quicksrm2x").also {
            log(if (it) "      QuickSRNet 2x enabled -> 640x1024"
                else "      QuickSRNet absent -> 320x512")
        }
    }

    private fun decodeVideo(latents: Latent): List<Bitmap> {
        val h = VideoStructure.cfg.getInt("video_height")
        val w = VideoStructure.cfg.getInt("video_width")
        val sf = vs.getDouble("vae_scale_factor").toFloat()
        val sh = vs.getDouble("vae_shift_factor").toFloat()
        val vsf = vs.getDouble("vae_video_scale_factor").toFloat()
        val vsh = vs.getDouble("vae_video_shift_factor").toFloat()

        // The first latent frame and the rest use DIFFERENT scale/shift factors; using
        // one pair for both washes the video out with nothing to say why.
        val un = Latent(latents.c, latents.t, latents.h, latents.w, latents.d.copyOf())
        for (ci in 0 until un.c) for (ti in 0 until un.t) {
            val base = (ci * un.t + ti) * un.plane
            val (s, o) = if (ti == 0) sf to sh else vsf to vsh
            for (i in 0 until un.plane) un.d[base + i] = un.d[base + i] / s + o
        }

        val desc = runner.describe("vaedecsn")
        val stateSizes = HashMap<String, Int>()
        for (part in desc.split(";")) {
            if (!part.startsWith("in state_")) continue
            val name = part.removePrefix("in ").substringBefore(":")
            val dims = part.split(":")[1].split(",").map { it.toInt() }
            stateSizes[name] = dims.fold(1) { a, b -> a * b }
        }
        var states = stateSizes.mapValues { FloatArray(it.value) }

        // The graph always emits 8 frames per latent, and the first `TRIM` frames of the
        // WHOLE video are padding from the causal warm-up: 7 latent frames x 8 = 56
        // emitted, minus 7, is the 49 the pipeline promises.
        val trim = 7
        val want = vs.getInt("num_frames")
        val out = ArrayList<Bitmap>()
        var emitted = 0
        for (ti in 0 until un.t) {
            val feed = HashMap<String, FloatArray>()
            feed["latent"] = un.slice(ti, ti + 1).d
            states.forEach { (k, v) -> feed[k] = v }
            val r = runner.run(VAE_DEC, floats = feed)

            val video = r.getValue("video")
            states = states.keys.associateWith { k ->
                r.getValue("n" + k)
            }
            // [1, 8*3, H, W]: frame-major, so frame f owns channels [3f, 3f+3).
            // Reading it channel-major instead gives three plausible greyscale videos.
            val nF = video.size / (3 * h * w)
            for (f in 0 until nF) {
                if (emitted++ < trim) continue
                if (out.size >= want) break
                out.add(if (upscale) upscaleFrame(video, f, h, w)
                        else framesToBitmap(video, f, h, w))
            }
            if (out.size >= want) break
        }
        return out
    }

    /**
     * QuickSRNet 2x on one decoded frame: 320x512 -> 640x1024.
     *
     * The paper's headline resolution is [640x1024] and it gets there by 2x
     * supersampling the [320x512] the VAE emits (Table 7, 6.5 ms on this chip). Without
     * this the video is 320x512 and is not the same product the 6.7 s figure describes.
     *
     * RANGES, which is the whole trap here. The decoder emits **[-1,1]**; the QuickSRNet
     * checkpoint was trained on **[0,1]** and returns [0,1]. Getting either end wrong
     * does not crash -- it produces a washed-out but entirely plausible video, which is
     * the hardest kind of error to notice. The calibration set was built in [0,1] for
     * the same reason (`make_quicksr_calib.py`).
     *
     * Upscaling happens INSIDE the decode loop rather than over a finished list, so only
     * one 640x1024 frame is alive at a time; holding 49 of them alongside the 320x512
     * originals would be ~120 MB of bitmaps in a process that lmkd has already killed
     * once (trap #35).
     */
    private fun upscaleFrame(v: FloatArray, f: Int, h: Int, w: Int): Bitmap {
        val plane = h * w
        val base = f * 3 * plane
        val inp = FloatArray(3 * plane)
        for (c in 0 until 3) {
            val src = base + c * plane
            for (i in 0 until plane) {
                // [-1,1] -> [0,1], clamped: the encoder can overshoot slightly.
                val x = (v[src + i] + 1f) * 0.5f
                inp[c * plane + i] = if (x < 0f) 0f else if (x > 1f) 1f else x
            }
        }
        val up = runner.run(UPSCALE, floats = mapOf("frame" to inp)).values.first()
        val H = h * 2
        val W = w * 2
        val p2 = H * W
        val px = IntArray(p2)
        for (i in 0 until p2) {
            val r = ch01(up[i])
            val g = ch01(up[p2 + i])
            val b = ch01(up[2 * p2 + i])
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(px, W, H, Bitmap.Config.ARGB_8888)
    }

    /** [0,1] -> 0..255, for QuickSRNet's output range. */
    private fun ch01(v: Float): Int {
        val x = (v * 255f).toInt()
        return if (x < 0) 0 else if (x > 255) 255 else x
    }

    private fun framesToBitmap(v: FloatArray, f: Int, h: Int, w: Int): Bitmap {
        val px = IntArray(h * w)
        val plane = h * w
        val base = f * 3 * plane
        for (i in 0 until plane) {
            val r = ch(v[base + i])
            val g = ch(v[base + plane + i])
            val b = ch(v[base + 2 * plane + i])
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun ch(v: Float): Int {
        val x = ((v + 1f) * 127.5f).toInt()
        return if (x < 0) 0 else if (x > 255) 255 else x
    }

    /** Bitmap -> [1,3,H,W] in [-1,1], resized to the video resolution. */
    /**
     * Centre-crop to the target aspect, THEN scale.
     *
     * This used to be a bare `createScaledBitmap`, i.e. a non-uniform stretch. That was
     * harmless while the only caller was SSD1B, which always emits exactly 512x320 -- the
     * scale was a no-op. A user photo is any aspect at all (4:3, or portrait), and
     * stretching one to 16:10 squashes every face in it. The failure would look like a
     * bad model rather than bad preprocessing, which is what makes it worth guarding.
     */
    private fun centreCrop(b: Bitmap, w: Int, h: Int): Bitmap {
        val want = w.toFloat() / h
        val have = b.width.toFloat() / b.height
        if (kotlin.math.abs(want - have) < 1e-3) return b
        return if (have > want) {                       // too wide: trim left/right
            val cw = (b.height * want).toInt().coerceAtMost(b.width)
            Bitmap.createBitmap(b, (b.width - cw) / 2, 0, cw, b.height)
        } else {                                        // too tall: trim top/bottom
            val ch = (b.width / want).toInt().coerceAtMost(b.height)
            Bitmap.createBitmap(b, 0, (b.height - ch) / 2, b.width, ch)
        }
    }

    private fun bitmapToChw(b0: Bitmap, w: Int, h: Int): FloatArray {
        val b = centreCrop(b0, w, h)
        val s = Bitmap.createScaledBitmap(b, w, h, true)
        val px = IntArray(w * h)
        s.getPixels(px, 0, w, 0, 0, w, h)
        val out = FloatArray(3 * w * h)
        val plane = w * h
        for (i in 0 until plane) {
            val p = px[i]
            out[i] = ((p shr 16 and 0xFF) / 127.5f) - 1f
            out[plane + i] = ((p shr 8 and 0xFF) / 127.5f) - 1f
            out[2 * plane + i] = ((p and 0xFF) / 127.5f) - 1f
        }
        return out
    }

    private fun rms(a: FloatArray): Double {
        var s = 0.0
        for (v in a) s += v.toDouble() * v
        return kotlin.math.sqrt(s / a.size)
    }
}
