package com.abrah.nightmare.npu

import android.content.Context
import android.util.Log
import java.io.File
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * ⭐⭐ Can THIS phone run the video models — answered before an 8.5 GB download.
 *
 * ⚠⚠ **A real context binary, not an allowlist.** The gate is a 58 KB graph in
 * the APK that is loaded and executed. A `Build.SOC_MODEL` allowlist is a guess
 * about which chips carry which Hexagon revision; it goes stale with every new
 * part, and it is exactly the test `../LocalDream/docs/DEVICE-SUPPORT.md` says
 * is the wrong one ("8 Gen 2 or newer" names a chip, not a capability).
 * Loading a context proves the backend, the skel and the architecture actually
 * agree — which is the only question that matters.
 *
 * ⭐⭐ **This is what makes "gen 4 and gen 5" one answer rather than two.** The
 * canary is built for **v79**, and a QNN context runs on the arch it was built
 * for and every newer one (`docs/DEVICES.md` §2). So 8 Elite (v79) loads it,
 * 8 Elite Gen 5 (v81) loads it, and an 8 Gen 3 refuses it — with no version
 * table here to maintain, and no phone silently getting garbage instead of a
 * refusal. ⚠ The v81 half is **reasoned, not measured**: nothing in this
 * project has run on a non-v79 phone, and the canary is precisely how the first
 * such phone will say so honestly.
 *
 * ⚠ It also checks **fp16 arithmetic**, because two of the shipping graphs are
 * float and the HTP runs float graphs in fp16 with no fp32 upcast in the layer
 * norm. A device where fp16 misbehaves returns plausible-looking garbage rather
 * than an error, so the output is compared against a host fp32 reference —
 * `../Neodragon`'s trap #3, where an unscaled DistilT5 build once returned 74%
 * NaN on device.
 */
object NpuCanary {

    private const val TAG = "NpuCanary"

    /** ⚠ Below this against the host reference, fp16 is not doing what it should. */
    private const val MIN_SNR_DB = 20.0

    /** ⚠ Asset names, staged by `tools/stage_neodragon.ps1` (they are not committed). */
    private const val BIN = "npu/canary_v79.bin"
    private const val IN = "npu/canary_in.raw"
    private const val REF = "npu/canary_ref.raw"

    sealed interface Result {
        /** ✅ It loaded and the numbers are right. */
        data class Ok(val snrDb: Double) : Result

        /** ⚠ The context would not load: wrong Hexagon revision, almost always. */
        data class Unsupported(val detail: String) : Result

        /** ⚠⚠ It ran and the numbers are wrong. The fp16 path is suspect. */
        data class Fp16Suspect(val snrDb: Double) : Result

        /** ⚠ No verdict reached — a missing asset, a backend that would not start. */
        data class Inconclusive(val detail: String) : Result

        /**
         * ⚠⚠ **Permissive by design.** A check that cannot run must not block a
         * phone that would otherwise work, so only a REAL refusal stops a
         * download. The cost of being wrong here is a failed load later; the
         * cost of the other default is a working phone locked out by a bug in
         * the gate.
         */
        val canDownload: Boolean get() = this is Ok || this is Inconclusive

        val summary: String
            get() = when (this) {
                is Ok -> "NPU ok (fp16 ${"%.1f".format(snrDb)} dB)"
                is Unsupported -> "this chip cannot run the video models"
                is Fp16Suspect -> "fp16 looks wrong on this chip (${"%.1f".format(snrDb)} dB)"
                is Inconclusive -> "could not tell: $detail"
            }
    }

    /**
     * Run the canary. A few ms of compute — but it brings the whole QNN backend
     * up, so it is seconds on a cold app and must not touch the main thread.
     */
    fun run(context: Context, runner: QnnRunner): Result {
        // ⚠ cacheDir, not filesDir: it is reproducible from the APK at any
        // moment, so it is exactly what a cache is for.
        val bin = File(context.cacheDir, BIN.substringAfterLast('/'))
        return try {
            if (!bin.isFile || bin.length() == 0L) {
                context.assets.open(BIN).use { ins -> bin.outputStream().use { ins.copyTo(it) } }
            }
            val x = readFloats(context, IN)
            val ref = readFloats(context, REF)

            runner.initBackend()
            val h = NativeQnn.load(bin.absolutePath)
            if (h == 0L) {
                // ⚠ The QNN message is not user-facing prose; it is kept for the
                // log and summarised above it.
                val why = NativeQnn.lastError()
                Log.w(TAG, "canary refused: $why")
                return Result.Unsupported(why)
            }
            try {
                val out = NativeQnn.execute(h, arrayOf("x"), arrayOf(x))
                    ?: return Result.Inconclusive("execute failed: ${NativeQnn.lastError()}")
                val got = out.firstOrNull { it.size == ref.size }
                    ?: return Result.Inconclusive("canary returned no tensor of ${ref.size}")
                // ⚠⚠ NaN first. It is the trap #3 signature and must not be
                // allowed to become a large-but-finite SNR by accident.
                if (got.any { it.isNaN() || it.isInfinite() }) return Result.Fp16Suspect(-1.0)
                val snr = snrDb(ref, got)
                Log.i(TAG, "canary ran: ${"%.2f".format(snr)} dB")
                if (snr >= MIN_SNR_DB) Result.Ok(snr) else Result.Fp16Suspect(snr)
            } finally {
                NativeQnn.free(h)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "canary inconclusive", e)
            Result.Inconclusive("${e::class.simpleName}: ${e.message}")
        }
    }

    private fun readFloats(context: Context, name: String): FloatArray {
        val b = context.assets.open(name).use { it.readBytes() }
        val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.size / 4) { bb.getFloat(it * 4) }
    }

    private fun snrDb(ref: FloatArray, got: FloatArray): Double {
        var num = 0.0
        var den = 0.0
        for (i in ref.indices) {
            num += ref[i].toDouble() * ref[i]
            val d = ref[i].toDouble() - got[i]
            den += d * d
        }
        if (den == 0.0) return Double.POSITIVE_INFINITY
        return 20.0 * log10(sqrt(num) / sqrt(den))
    }
}
