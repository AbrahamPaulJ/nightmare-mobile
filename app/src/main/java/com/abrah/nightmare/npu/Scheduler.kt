package com.abrah.nightmare.npu

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Diffusers' `Timesteps` sinusoidal projection.
 *
 * This is on the host on purpose. Inside a quantised graph the `Sin` is quantised at its
 * ARGUMENT, which spans [0,1000] at 16 bits -- a 0.0153 rad step and a ~47 dB ceiling.
 * That single mechanism cost the MMDiT 8.41 dB, so every sinusoid in this port is
 * computed at full precision on the CPU and passed in as an ordinary tensor.
 */
fun timestepEmbedding(
    t: Float, dim: Int, flipSinToCos: Boolean = true, downscaleFreqShift: Float = 0f,
    maxPeriod: Float = 10_000f, scale: Float = 1f,
): FloatArray {
    val half = dim / 2
    val out = FloatArray(dim)
    for (i in 0 until half) {
        val exponent = -ln(maxPeriod.toDouble()) * i / (half - downscaleFreqShift)
        val emb = t * exp(exponent)
        val s = (scale * sin(emb)).toFloat()
        val c = (scale * cos(emb)).toFloat()
        // flip_sin_to_cos puts cos first, which is what SDXL's UNet expects.
        if (flipSinToCos) { out[i] = c; out[half + i] = s }
        else { out[i] = s; out[half + i] = c }
    }
    return out
}

/**
 * LCMScheduler, constructed EXACTLY as the reference does:
 *
 *     LCMScheduler(set_alpha_to_one=False, original_inference_steps=4, steps_offset=1)
 *
 * Those two non-default flags change the sigma schedule. A default LCMScheduler denoises
 * differently and nothing about the output announces it -- run_reference.py:77 and
 * first_frame_gen.py:97 pin them, so this does too.
 */
class LcmScheduler(private val timesteps: IntArray) {

    private val numTrain = AppAssets.config.getInt("num_train_timesteps")
    private val timestepScaling = AppAssets.config.getDouble("timestep_scaling")
    private val sigmaData = AppAssets.config.getDouble("sigma_data")
    private val setAlphaToOne = AppAssets.config.getBoolean("set_alpha_to_one")

    private val alphasCumprod = DoubleArray(numTrain)

    /** LCM's initial latent scale. */
    val initNoiseSigma = 1.0f

    init {
        // "scaled_linear": betas are linear in sqrt-space, the Stable Diffusion schedule.
        val bs = sqrt(AppAssets.config.getDouble("beta_start"))
        val be = sqrt(AppAssets.config.getDouble("beta_end"))
        var acc = 1.0
        for (i in 0 until numTrain) {
            val b = bs + (be - bs) * i / (numTrain - 1)
            acc *= (1.0 - b * b)
            alphasCumprod[i] = acc
        }
    }

    /** With set_alpha_to_one=False this is alphas_cumprod[0], not 1.0. */
    private val finalAlphaCumprod = if (setAlphaToOne) 1.0 else alphasCumprod[0]

    private fun scalings(t: Int): Pair<Double, Double> {
        val st = t * timestepScaling
        val d2 = sigmaData * sigmaData
        val cSkip = d2 / (st * st + d2)
        val cOut = st / sqrt(st * st + d2)
        return cSkip to cOut
    }

    /**
     * One LCM step. Returns (prevSample, denoised); on the last step they are equal.
     *
     * The noise injected between steps comes from [rng] so a run is reproducible from a
     * seed. It will not be bit-identical to the desktop run -- torch's RNG is a different
     * stream -- but it is the same distribution, so the image is equally valid.
     */
    fun step(
        modelOutput: FloatArray, t: Int, sample: FloatArray, stepIndex: Int, rng: java.util.Random,
    ): Pair<FloatArray, FloatArray> {
        val prevT = if (stepIndex + 1 < timesteps.size) timesteps[stepIndex + 1] else t
        val alphaT = alphasCumprod[t]
        val alphaPrev = if (prevT >= 0) alphasCumprod[prevT] else finalAlphaCumprod
        val betaT = 1.0 - alphaT
        val betaPrev = 1.0 - alphaPrev
        val (cSkip, cOut) = scalings(t)

        val sqrtAlphaT = sqrt(alphaT)
        val sqrtBetaT = sqrt(betaT)
        val n = sample.size
        val denoised = FloatArray(n)
        for (i in 0 until n) {
            // prediction_type == "epsilon"
            val x0 = (sample[i] - sqrtBetaT * modelOutput[i]) / sqrtAlphaT
            denoised[i] = (cOut * x0 + cSkip * sample[i]).toFloat()
        }

        if (stepIndex == timesteps.size - 1) return denoised to denoised

        val sa = sqrt(alphaPrev)
        val sb = sqrt(betaPrev)
        val prev = FloatArray(n) { (sa * denoised[it] + sb * rng.nextGaussian()).toFloat() }
        return prev to denoised
    }
}
