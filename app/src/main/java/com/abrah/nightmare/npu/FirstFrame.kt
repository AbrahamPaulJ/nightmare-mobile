package com.abrah.nightmare.npu

import android.content.Context
import android.graphics.Bitmap

/**
 * Prompt -> image, entirely on the phone.
 *
 * This is the port of `work/pipeline/run_npu_firstframe.py`, which scores the same path
 * against the fp32 reference on the desktop. Four converted graphs run on the HTP:
 *
 *     CLIP L         60.75 dB   FP16
 *     CLIP G         14.71 dB   FP16
 *     SSD1B UNet     32.51 dB   W8A16, 4 LCM steps
 *     SSD1B VAE dec  32.43 dB   W8A16, 1.57/255 mean pixel error
 *
 * Everything else -- tokenisation, the two sinusoidal projections, `add_embedding`, and
 * the LCM step maths -- runs in Kotlin at fp32, mirroring the reference exactly. The
 * sinusoids are on the host deliberately (see Scheduler.kt).
 */
class FirstFrame(
    private val ctx: Context,
    private val runner: QnnRunner,
    private val log: (String) -> Unit,
) {

    private val cfg get() = AppAssets.config

    private val height get() = cfg.getInt("height")
    private val width get() = cfg.getInt("width")
    private val latH get() = height / 8
    private val latW get() = width / 8
    private val tokens get() = cfg.getInt("tokens")

    companion object {
        val CLIP_L = QnnRunner.Graph("clipl")
        val CLIP_G = QnnRunner.Graph("clipg")
        val UNET = QnnRunner.Graph("ssd1bunet")
        val VAE_DEC = QnnRunner.Graph("ssd1bvaedec")

        /** generation_utils.DEFAULT_PROMPT_MODIFIER, verbatim. */
        const val PROMPT_MODIFIER = ", cinematic, realistic textures, high detail, natural colours"

        /**
         * Keep CLIP G's 1.4 GB context mapped between generations.
         *
         * Costs 1897 ms to load and 40 ms to run, so releasing it after every image was
         * paying ~1.9 s per generation to reclaim memory that is file-backed anyway.
         * Flip to false to restore the old release-always behaviour if lmkd ever
         * objects -- see the note at the release site.
         */
        const val KEEP_CLIPG = true
    }

    fun requiredModels() = listOf("clipl", "clipg", "ssd1bunet", "ssd1bvaedec")


    /** Process RSS in MB, from /proc/self/statm (page count x page size). */
    private fun rssMb(): Double = try {
        val f = java.io.File("/proc/self/statm").readText().trim().split(" ")
        f[1].toLong() * 4096.0 / (1024 * 1024)
    } catch (e: Throwable) { -1.0 }

    /**
     * Time one step and log it.
     *
     * The first-frame path was recorded as a single opaque **7.2 s** against the paper's
     * 1.53 s -- a 4.7x gap, the worst ratio in the pipeline, and never broken down. The
     * split that matters is LOAD versus EXECUTE: `QnnRunner.run` loads lazily, so the
     * first call to each graph silently includes bringing its context binary up, and this
     * path touches ~2.95 GB of them (clipl 234 MB + clipg 1337 + ssd1bunet 1295 +
     * ssd1bvaedec 82). Paper Table 7 measures inference only, so if most of our 7.2 s is
     * load, the two numbers were never comparable and the fix is residency, not kernels.
     */
    private inline fun <T> timed(label: String, body: () -> T): T {
        val t = System.nanoTime()
        val r = body()
        log("      [t] %-20s %8.1f ms".format(label, (System.nanoTime() - t) / 1e6))
        return r
    }

    /**
     * ⭐⭐ SDXL-shaped conditioning — and it is **not** the video path's.
     *
     * ⚠⚠ This is the finding that shapes the whole node split. SSD1B needs
     * `clipl` + `clipg` **hidden states** concatenated to 2048 plus the pooled
     * vector; the MMDiT needs `cliplp` + `clipg` **pooled** plus a DistilT5
     * context. They share `clipg` and use different OUTPUTS of it — which is
     * exactly why [Video.encodePrompt] keeps it resident rather than releasing
     * it (measured: 1897 ms to load, 40 ms to execute).
     *
     * ⇒ A node cannot hand its `cond` to both. `nd.clip_encode` emits two
     * values, and this is the second. `docs/NEODRAGON.md` §8.
     */
    class FrameCond(val ehs: FloatArray, val pooled: FloatArray)

    /** @return the decoded RGB bitmap. ⚠ Unchanged: [encode] then [render]. */
    fun generate(prompt: String, seed: Long = 0L, onStep: (Int, Int) -> Unit = { _, _ -> }): Bitmap =
        render(encode(prompt), seed, onStep)

    fun encode(prompt: String): FrameCond {
        AppAssets.load(ctx)
        val full = prompt + PROMPT_MODIFIER

        // ---- 1. conditioning -------------------------------------------------------
        log("[1/4] tokenise + CLIP L/G")
        val tok = ClipTokenizer(ctx)
        val idsL = tok.encode(full, cfg.getInt("pad_id_clip_l"), tokens)
        val idsG = tok.encode(full, cfg.getInt("pad_id_clip_g"), tokens)

        // handleOf() forces the load so it is timed on its own rather than hiding inside
        // the first run() of each graph.
        timed("load clipl") { runner.handleOf("clipl") }
        val outL = timed("exec clipl") {
            runner.run(CLIP_L, intInputs = mapOf("input_ids" to idsL))
        }
        timed("load clipg") { runner.handleOf("clipg") }
        val outG = timed("exec clipg") {
            runner.run(CLIP_G, intInputs = mapOf("input_ids" to idsG))
        }

        val hl = outL.values.first()                       // [77, 768]
        // CLIP G returns two tensors; select by SIZE rather than by name so a converter
        // that renamed or reordered them cannot silently swap the sequence for the pooled
        // vector -- 98560 vs 1280 is unambiguous, a name is a guess.
        val hg = outG.values.first { it.size == tokens * 1280 }
        val pooled = outG.values.first { it.size == 1280 }

        log("      ids[0..7] = " + idsL.take(8).joinToString(","))

        // SDXL concatenates the two penultimate hidden states on the CHANNEL axis. Done
        // here on the host, so the >320-row multi-input concat trap never applies.
        val ehs = FloatArray(tokens * 2048)
        for (t in 0 until tokens) {
            System.arraycopy(hl, t * 768, ehs, t * 2048, 768)
            System.arraycopy(hg, t * 1280, ehs, t * 2048 + 768, 1280)
        }
        log("      encoder_hidden_states [1,$tokens,2048]  pooled [1,1280]")

        // CLIP L is small (234 MB) and reloads in ~376 ms, so releasing it is close to
        // free. CLIP G is NOT: measured 2026-08-23, it costs **1897 ms to load** and only
        // **40 ms to execute** -- the worst load-to-compute ratio in the pipeline, and 43%
        // of the whole first frame. Releasing it saved 1.4 GB of mapped context to buy
        // 40 ms of compute, and then paid the 1.9 s back on the very next generation.
        //
        // The original note here said "they reload in ~1 s", which was the justification
        // for releasing both; the real figure is nearly twice that for CLIP G alone.
        // Trap #35's lmkd incident was about HEAP copies of the binaries; these are
        // mmap'd, so the pages are file-backed and the kernel can evict them under
        // pressure rather than killing the process. RSS is logged below so the trade is
        // visible rather than assumed.
        runner.release(CLIP_L.name)
        if (!KEEP_CLIPG) runner.release(CLIP_G.name)
        log("      [t] rss after clip      %8.1f MB".format(rssMb()))
        return FrameCond(ehs, pooled)
    }

    /** ⭐ Conditioning + seed → a 1024x640 picture. ⚠ SSD1B UNet, then its VAE. */
    fun render(
        cond: FrameCond,
        seed: Long = 0L,
        onStep: (Int, Int) -> Unit = { _, _ -> },
    ): Bitmap {
        val tAll = System.nanoTime()
        AppAssets.load(ctx)
        val timesteps = cfg.getJSONArray("timesteps").let { a ->
            IntArray(a.length()) { a.getInt(it) }
        }
        val ehs = cond.ehs
        val pooled = cond.pooled

        // ---- 2. host sinusoids + add_embedding -------------------------------------
        log("[2/4] host embeddings")
        val addDim = cfg.getInt("add_time_proj_dim")
        val timeDim = cfg.getInt("time_proj_dim")
        val flip = cfg.getBoolean("flip_sin_to_cos")
        val shift = cfg.getDouble("freq_shift").toFloat()

        // SDXL's micro-conditioning: (h, w, crop_top, crop_left, target_h, target_w)
        val timeIds = floatArrayOf(
            height.toFloat(), width.toFloat(), 0f, 0f, height.toFloat(), width.toFloat())
        val timeIdsEmb = FloatArray(timeIds.size * addDim)
        timeIds.forEachIndexed { i, v ->
            timestepEmbedding(v, addDim, flip, shift).copyInto(timeIdsEmb, i * addDim)
        }

        val addIn = FloatArray(1280 + timeIdsEmb.size)
        System.arraycopy(pooled, 0, addIn, 0, 1280)
        System.arraycopy(timeIdsEmb, 0, addIn, 1280, timeIdsEmb.size)
        val aug = linear(
            silu(linear(addIn, AppAssets["add_embedding.linear_1.weight"],
                        AppAssets["add_embedding.linear_1.bias"])),
            AppAssets["add_embedding.linear_2.weight"],
            AppAssets["add_embedding.linear_2.bias"])

        val tEmb = timesteps.associateWith {
            timestepEmbedding(it.toFloat(), timeDim, flip, shift)
        }

        // ---- 3. denoise ------------------------------------------------------------
        log("[3/4] SSD1B UNet x${timesteps.size}")
        timed("load ssd1bunet") { runner.handleOf("ssd1bunet") }
        val sched = LcmScheduler(timesteps)
        val rng = java.util.Random(seed)
        val n = 4 * latH * latW
        var lat = FloatArray(n) { (rng.nextGaussian() * sched.initNoiseSigma).toFloat() }
        var denoised = lat

        timesteps.forEachIndexed { i, t ->
            val out = timed("exec unet step ${i + 1}") {
                runner.run(UNET, floats = mapOf(
                    "sample" to lat,
                    "t_emb" to tEmb.getValue(t),
                    "aug_emb" to aug,
                    "encoder_hidden_states" to ehs,
                ))
            }
            val noise = out.values.first()
            val (prev, den) = sched.step(noise, t, lat, i, rng)
            lat = prev; denoised = den
            log("      step ${i + 1}/${timesteps.size}  t=$t  rms ${"%.4f".format(rms(lat))}")
            onStep(i + 1, timesteps.size)
        }

        // ---- 4. decode -------------------------------------------------------------
        log("[4/4] VAE decode")
        val sf = cfg.getDouble("vae_scaling_factor").toFloat()
        val scaled = FloatArray(n) { denoised[it] / sf }
        timed("load ssd1bvaedec") { runner.handleOf("ssd1bvaedec") }
        val img = timed("exec ssd1bvaedec") {
            runner.run(VAE_DEC, floats = mapOf("latent" to scaled))
        }.values.first()

        log("      [t] %-20s %8.1f ms".format("TOTAL first frame",
                                              (System.nanoTime() - tAll) / 1e6))
        log("      [t] rss at end          %8.1f MB".format(rssMb()))
        return toBitmap(img)
    }

    /** [1,3,H,W] in [-1,1] -> ARGB bitmap. */
    private fun toBitmap(img: FloatArray): Bitmap {
        val hw = height * width
        val px = IntArray(hw)
        for (i in 0 until hw) {
            val r = ch(img[i]); val g = ch(img[hw + i]); val b = ch(img[2 * hw + i])
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun ch(v: Float): Int {
        val x = ((v + 1f) * 127.5f).toInt()
        return if (x < 0) 0 else if (x > 255) 255 else x
    }

    private fun rms(a: FloatArray): Double {
        var s = 0.0
        for (v in a) s += v.toDouble() * v
        return Math.sqrt(s / a.size)
    }
}
