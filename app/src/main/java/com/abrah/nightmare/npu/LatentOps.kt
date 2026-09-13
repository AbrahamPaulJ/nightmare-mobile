package com.abrah.nightmare.npu

import java.util.Random
import kotlin.math.sqrt

/**
 * A batch-1 latent, laid out [C, T, H, W] contiguously -- the same order PyTorch uses,
 * so an index computed here matches one computed in the reference.
 */
class Latent(val c: Int, val t: Int, val h: Int, val w: Int, val d: FloatArray) {

    constructor(c: Int, t: Int, h: Int, w: Int) : this(c, t, h, w, FloatArray(c * t * h * w))

    val plane get() = h * w
    val frame get() = t * plane

    fun idx(ci: Int, ti: Int, y: Int, x: Int) = ((ci * t + ti) * h + y) * w + x

    /** Frames [t0, t1) as a new latent. */
    fun slice(t0: Int, t1: Int): Latent {
        val n = t1 - t0
        val out = Latent(c, n, h, w)
        for (ci in 0 until c) {
            System.arraycopy(d, ci * frame + t0 * plane, out.d, ci * n * plane, n * plane)
        }
        return out
    }

    /** Concatenate along the temporal axis. */
    companion object {
        fun catTime(parts: List<Latent>): Latent {
            val f = parts[0]
            val tt = parts.sumOf { it.t }
            val out = Latent(f.c, tt, f.h, f.w)
            var off = 0
            for (p in parts) {
                for (ci in 0 until f.c) {
                    System.arraycopy(p.d, ci * p.frame, out.d,
                                     ci * out.frame + off * f.plane, p.t * f.plane)
                }
                off += p.t
            }
            return out
        }
    }
}

/**
 * `torch.nn.functional.interpolate(mode="bilinear", align_corners=False)`.
 *
 * The half-pixel convention matters: source coordinate is (dst + 0.5) * scale - 0.5, not
 * dst * scale. Getting it wrong shifts every pyramid level by half a pixel, which looks
 * like a slightly soft video rather than like a bug.
 */
fun bilinear(x: Latent, newH: Int, newW: Int): Latent {
    if (newH == x.h && newW == x.w) return x
    val out = Latent(x.c, x.t, newH, newW)
    val sy = x.h.toDouble() / newH
    val sx = x.w.toDouble() / newW
    val ys = IntArray(newH); val ys1 = IntArray(newH); val wy = DoubleArray(newH)
    for (i in 0 until newH) {
        val src = (i + 0.5) * sy - 0.5
        val f = kotlin.math.floor(src)
        val y0 = f.toInt()
        wy[i] = src - f
        ys[i] = y0.coerceIn(0, x.h - 1)
        ys1[i] = (y0 + 1).coerceIn(0, x.h - 1)
        if (y0 < 0) wy[i] = 0.0
        if (y0 + 1 > x.h - 1) wy[i] = if (y0 > x.h - 1) 0.0 else wy[i]
    }
    val xs = IntArray(newW); val xs1 = IntArray(newW); val wx = DoubleArray(newW)
    for (j in 0 until newW) {
        val src = (j + 0.5) * sx - 0.5
        val f = kotlin.math.floor(src)
        val x0 = f.toInt()
        wx[j] = src - f
        xs[j] = x0.coerceIn(0, x.w - 1)
        xs1[j] = (x0 + 1).coerceIn(0, x.w - 1)
        if (x0 < 0) wx[j] = 0.0
        if (x0 + 1 > x.w - 1) wx[j] = if (x0 > x.w - 1) 0.0 else wx[j]
    }
    for (ci in 0 until x.c) for (ti in 0 until x.t) {
        val sBase = (ci * x.t + ti) * x.plane
        val dBase = (ci * out.t + ti) * out.plane
        for (i in 0 until newH) {
            val a = wy[i]
            val r0 = sBase + ys[i] * x.w
            val r1 = sBase + ys1[i] * x.w
            for (j in 0 until newW) {
                val b = wx[j]
                val v00 = x.d[r0 + xs[j]]; val v01 = x.d[r0 + xs1[j]]
                val v10 = x.d[r1 + xs[j]]; val v11 = x.d[r1 + xs1[j]]
                val top = v00 + (v01 - v00) * b
                val bot = v10 + (v11 - v10) * b
                out.d[dBase + i * newW + j] = (top + (bot - top) * a).toFloat()
            }
        }
    }
    return out
}

/** `interpolate(scale_factor=2, mode="nearest")`. */
fun nearest2x(x: Latent): Latent {
    val out = Latent(x.c, x.t, x.h * 2, x.w * 2)
    for (ci in 0 until x.c) for (ti in 0 until x.t) {
        val sBase = (ci * x.t + ti) * x.plane
        val dBase = (ci * out.t + ti) * out.plane
        for (i in 0 until out.h) {
            val srow = sBase + (i / 2) * x.w
            val drow = dBase + i * out.w
            for (j in 0 until out.w) out.d[drow + j] = x.d[srow + j / 2]
        }
    }
    return out
}

/**
 * `_get_pyramid_latent`: successively halve h,w with bilinear, then reverse so index 0 is
 * the COARSEST level. Stage s reads level s.
 */
fun pyramid(x: Latent, numStages: Int): List<Latent> {
    val levels = ArrayList<Latent>()
    levels.add(x)
    var cur = x
    repeat(numStages - 1) {
        cur = bilinear(cur, cur.h / 2, cur.w / 2)
        levels.add(cur)
    }
    return levels.reversed()
}

/** `_downsample_noise_2x`: bilinear halve, then scale by 2 to keep the variance. */
fun downsampleNoise2x(x: Latent, times: Int): Latent {
    var cur = x
    repeat(times) {
        cur = bilinear(cur, cur.h / 2, cur.w / 2)
        for (i in cur.d.indices) cur.d[i] *= 2f
    }
    return cur
}

/**
 * `_sample_block_noise`: noise correlated within each 2x2 block.
 *
 * The reference draws from MultivariateNormal(0, I*(1+gamma) - ones*gamma) per block. The
 * Cholesky factor of that fixed 4x4 covariance is precomputed on the host and shipped in
 * video_structure.json, so this is a matrix-vector product on four standard normals.
 */
fun blockNoise(chol: Array<FloatArray>, c: Int, t: Int, h: Int, w: Int, rng: Random): Latent {
    val out = Latent(c, t, h, w)
    val z = FloatArray(4)
    val v = FloatArray(4)
    for (ci in 0 until c) for (ti in 0 until t) {
        val base = (ci * t + ti) * h * w
        for (by in 0 until h / 2) for (bx in 0 until w / 2) {
            for (k in 0 until 4) z[k] = rng.nextGaussian().toFloat()
            for (r in 0 until 4) {
                var acc = 0f
                for (k in 0..r) acc += chol[r][k] * z[k]
                v[r] = acc
            }
            // (p, q) within the block -> row 2*by+p, col 2*bx+q
            out.d[base + (2 * by) * w + 2 * bx] = v[0]
            out.d[base + (2 * by) * w + 2 * bx + 1] = v[1]
            out.d[base + (2 * by + 1) * w + 2 * bx] = v[2]
            out.d[base + (2 * by + 1) * w + 2 * bx + 1] = v[3]
        }
    }
    return out
}

/**
 * `_upsample_pyramidal_latent`: nearest 2x, then mix in correlated block noise so the
 * duplicated pixels inside each 2x2 block stop being identical.
 */
fun upsamplePyramidal(
    x: Latent, origSigma: Double, gamma: Double, chol: Array<FloatArray>, rng: Random,
): Latent {
    val up = nearest2x(x)
    val alpha = 1.0 / (sqrt(1.0 + (1.0 / gamma)) * (1.0 - origSigma) + origSigma)
    val beta = alpha * (1.0 - origSigma) / sqrt(gamma)
    val noise = blockNoise(chol, up.c, up.t, up.h, up.w, rng)
    for (i in up.d.indices) up.d[i] = (alpha * up.d[i] + beta * noise.d[i]).toFloat()
    return up
}
