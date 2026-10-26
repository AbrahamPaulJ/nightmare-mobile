package com.abrah.nightmare.npu

import android.content.Context
import android.util.Log
import com.abrah.nightmare.DeviceProbe
import com.abrah.nightmare.ModelInstaller
import com.abrah.nightmare.ModelStorage
import com.abrah.nightmare.Prefs
import com.abrah.nightmare.UpscalerCatalog
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * ⭐⭐ **Smooth motion**: a Wan clip from 16 (or 24) to 32 (or 48) fps, a new frame between every pair, made by
 * IFRNet-S (CVPR 2022, MIT, GoPro weights) on the NPU in this process ([QnnRunner]). `docs/INTERPOLATION.md`.
 *
 * ⚠ A context binary has ONE shape, so there are four ([SIZES]) and a clip is padded up to the smallest that holds
 * it ([pick]): edge pixels repeated, cut off again after. A clip larger than all four is left at its own rate.
 * ⚠ The `g16` builds: their full-size warp grid runs in int16. Plain fp16 is a 0.2-0.3 px resampling blur at these
 * widths (up to 0.7 dB lost, `docs/INTERPOLATION.md` §5).
 * Files per chip tier, fetched once and SHA256-checked like [com.abrah.nightmare.portrait.LivePortrait]'s.
 */
object FrameInterp {

    private const val TAG = "FrameInterp"
    private const val BASE = "https://huggingface.co/AbrahamPJ/nightmare-ifrnet/resolve/main/"

    /** ⭐ The Models, Tools row's name. */
    const val LABEL = "Smooth motion (IFRNet)"

    data class Size(val w: Int, val h: Int) {
        override fun toString() = "${w}x$h"
    }

    /** ⭐ Cheapest first, so [pick] takes the least padding: 57 G MACs for the 480p pair, 135 G for 720p. */
    val SIZES = listOf(Size(832, 480), Size(480, 832), Size(1280, 736), Size(736, 1280))

    data class F(val size: Size, val bytes: Long, val sha256: String)

    val FILES: Map<String, List<F>> = mapOf(
        "v79" to listOf(
            F(Size(832, 480), 14_285_176L, "84699b4a35c9ce552a80e009f3b1a075a4e106b7cc8f4cabe78fde5ebf6a9254"),
            F(Size(480, 832), 15_018_360L, "98fd49ff7823b88220cbf21375f64b6b8c6eb93f65f960ae7aaf7fe21b41cf84"),
            F(Size(1280, 736), 21_617_016L, "b0d59bb523091f75f3a1a1788e5c17586d9d7ef41202242e3f08d81533da64ba"),
            F(Size(736, 1280), 24_504_696L, "cee0af249c4b274910c990c7594f9e83c1a1e4498e62b3c26f3e6951607df1e6"),
        ),
        "v75" to listOf(
            F(Size(832, 480), 13_912_408L, "661dd8f084f4acf022e758e46b5f0ae0614efe717530463e134c4ac65c7e40f1"),
            F(Size(480, 832), 14_522_712L, "1933157f81cc6501a217f22a69b03c0f1901a7e155701b4ec43bdd60d27e9c39"),
            F(Size(1280, 736), 21_133_656L, "905135b7234f7a36afb9ccaaa645dbac481283e430a957c6219e6514a19d4998"),
            F(Size(736, 1280), 23_869_784L, "dfdeb4fa055b8f4fa6c8ea308cd3158f043b4043e02923692b983eeca0a310ed"),
        ),
    )

    fun fileName(size: Size, tier: String) = "ifrnet_s_${size}_g16_$tier.bin"

    /**
     * ⭐ This phone's build, or null below the 8 Gen 3. ⚠ A v79 build serves v81 and v85 too: the skel follows the
     * DEVICE ([QnnRunner.initBackend]). An UNKNOWN chip is offered v79, as it is offered everything.
     */
    fun tierFor(caps: DeviceProbe.Caps = DeviceProbe.caps()): String? = when {
        !caps.known || caps.arch >= 79 -> "v79"
        caps.arch >= 75 -> "v75"
        else -> null
    }

    /** ⭐ Pure: the smallest graph that holds a [w]x[h] clip, or null when none does. */
    fun pick(w: Int, h: Int): Size? = SIZES.firstOrNull { it.w >= w && it.h >= h }

    fun dir(context: Context): File = File(ModelStorage.root(context), "npu/ifrnet")

    /** This phone's set, whole (0 on a chip with none). */
    val BYTES: Long get() = tierFor()?.let { t -> FILES.getValue(t).sumOf { it.bytes } } ?: 0L

    fun isInstalled(context: Context): Boolean = tierFor()?.let { installed(context, it) } == true

    fun installed(context: Context, tier: String): Boolean =
        FILES.getValue(tier).all { File(dir(context), fileName(it.size, tier)).length() == it.bytes }

    fun bytesOnDisk(context: Context): Long = dir(context).listFiles()?.sumOf { it.length() } ?: 0L

    /** ⭐ The cheap answer for a row. Refreshed by [install], [delete] and app start. */
    @Volatile
    var installed: Boolean = false
        private set

    fun refresh(context: Context) {
        installed = isInstalled(context)
    }

    /** ⚠ Blocking. Fetches what this phone's set lacks, each checked against its SHA256; throws an [IOException] that says what. */
    fun install(context: Context, onProgress: (ModelInstaller.Progress) -> Unit, isCancelled: () -> Boolean = { false }) {
        try {
            val tier = tierFor() ?: throw IOException("$LABEL needs a Snapdragon 8 Gen 3 or newer")
            val d = dir(context).apply { mkdirs() }
            for (f in FILES.getValue(tier)) {
                val name = fileName(f.size, tier)
                val dst = File(d, name)
                if (dst.length() == f.bytes) continue
                val part = File(d, "$name.part")
                UpscalerCatalog.download(Prefs.apply(BASE + name), part, f.bytes, onProgress, isCancelled)
                val got = sha256(part)
                if (got != f.sha256) {
                    part.delete()
                    throw IOException("$LABEL: $name sha256 is $got, expected ${f.sha256}. The download is corrupt")
                }
                if (!part.renameTo(dst)) throw IOException("$LABEL: cannot write $dst")
                Log.i(TAG, "fetched $name (${f.bytes} bytes)")
            }
        } finally {
            refresh(context)
        }
    }

    fun delete(context: Context) {
        dir(context).deleteRecursively()
        refresh(context)
    }

    /**
     * ⭐⭐ One clip's in-between frames: frames in and out are packed RGB, [w]x[h], as the backend's `.nmv` holds
     * them. ⚠ Blocking, and holds a ~25 MB context on the NPU until [close]: open it for one clip.
     */
    class Session(context: Context, private val w: Int, private val h: Int) : AutoCloseable {
        val size: Size = pick(w, h) ?: throw IOException("$LABEL: ${w}x$h is larger than 1280x736 or 736x1280")
        private val runner = QnnRunner(context)
        private val bin: File = run {
            val tier = tierFor() ?: throw IOException("$LABEL needs a Snapdragon 8 Gen 3 or newer")
            File(dir(context), fileName(size, tier)).also {
                if (!it.isFile) throw IOException("$LABEL is not installed (Models, Tools)")
            }
        }
        private val plane = size.w * size.h

        // ⚠ The last frame converted, kept: in order, frame k is the right of one pair and the left of the next.
        private var lastIndex = -1
        private var lastPlanes: FloatArray? = null

        /** ⭐ The midpoint of frames [i] and [i] + 1 into [out]; [read] fills a buffer with a frame by index. */
        fun midpoint(i: Int, read: (Int, ByteArray) -> Unit, out: ByteArray) {
            val a = planesOf(i, read)
            val b = planesOf(i + 1, read)
            val r = runner.run(bin, mapOf("img0" to a, "img1" to b))
            val t = r["imgt"] ?: r.values.first()
            unpack(t, out)
        }

        private val rgb = ByteArray(w * h * 3)

        private fun planesOf(i: Int, read: (Int, ByteArray) -> Unit): FloatArray {
            if (i == lastIndex) lastPlanes?.let { return it }
            read(i, rgb)
            return pack(rgb, w, h, size).also {
                lastIndex = i
                lastPlanes = it
            }
        }

        private fun unpack(t: FloatArray, out: ByteArray) {
            var o = 0
            for (y in 0 until h) {
                val row = y * size.w
                for (x in 0 until w) {
                    val p = row + x
                    out[o++] = byteOf(t[p])
                    out[o++] = byteOf(t[plane + p])
                    out[o++] = byteOf(t[2 * plane + p])
                }
            }
        }

        override fun close() {
            runner.release(bin)
        }
    }

    /**
     * ⭐ Pure: packed RGB [w]x[h] into the graph's NCHW planes in [0,1] at [size], the right and bottom edges
     * repeated into the padding (black padding would pull the edge's motion toward a still border).
     */
    fun pack(rgb: ByteArray, w: Int, h: Int, size: Size): FloatArray {
        val plane = size.w * size.h
        val f = FloatArray(3 * plane)
        for (y in 0 until size.h) {
            val sy = minOf(y, h - 1)
            for (x in 0 until size.w) {
                val s = (sy * w + minOf(x, w - 1)) * 3
                val p = y * size.w + x
                f[p] = (rgb[s].toInt() and 0xFF) / 255f
                f[plane + p] = (rgb[s + 1].toInt() and 0xFF) / 255f
                f[2 * plane + p] = (rgb[s + 2].toInt() and 0xFF) / 255f
            }
        }
        return f
    }

    /** ⭐ Pure: [0,1] to a byte, rounded. */
    fun byteOf(v: Float): Byte = (v * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()

    /**
     * ⭐ Pure: which source frame(s) output frame [i] of a doubled clip is. Even: frame i/2 itself. Odd: the midpoint
     * of i/2 and i/2 + 1. A clip of n frames becomes 2n - 1.
     */
    fun sourceOf(i: Int): Pair<Int, Boolean> = (i / 2) to (i % 2 == 1)

    fun doubled(frames: Int): Int = if (frames < 2) frames else 2 * frames - 1

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
