package com.abrah.nightmare.pose

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.abrah.nightmare.BackendProcess
import com.abrah.nightmare.ModelInstaller
import com.abrah.nightmare.UpscalerCatalog
import com.abrah.nightmare.segment.IdleRelease
import java.io.File
import java.io.IOException
import java.nio.FloatBuffer

/**
 * ⭐⭐ The depth estimator for SD 1.5 Swap's depth ControlNet: a PHOTO becomes a
 * depth map on the phone (near = white), and a picture that already IS one is
 * used as it is ([looksLikeDepthMap]). The user's pick, 2026-09-29: **Depth
 * Anything V2 Small** on the CPU, like [PoseDetector] — every phone, no arch gate.
 *
 * ⚠⚠ The SAME fp32 file and preprocessing the depth ControlNet was CALIBRATED
 * with (`../LocalDream/npuconvert/npuconvertv2/make_depth_hints.py`), so the
 * maps the phone makes are the maps the graph's ranges were fitted to. The
 * 27 MB int8 sibling is not used unmeasured — MoveNet's int8 export ran and
 * found nothing ([PoseDetector.URL]).
 */
object DepthEstimator {

    private const val TAG = "DepthEstimator"

    const val ID = "depthanything"
    const val LABEL = "Depth Estimator (Depth Anything V2 Small)"
    const val REVISION = 1
    const val FILE = "depth_anything_v2_small.onnx"

    /** Apache-2.0; `onnx-community`'s export — linked, not re-hosted. */
    const val URL = "https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/main/onnx/model.onnx"
    const val BYTES = 99_060_839L

    /** The native input edge, a multiple of the ViT's 14-pixel patch. */
    private const val EDGE = 518
    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    private const val THREADS = 4

    fun dir(context: Context): File = File(BackendProcess.modelsDir(context), ID)

    private fun file(context: Context) = File(dir(context), FILE)

    private fun stamp(context: Context) = File(dir(context), ".rev")

    private fun installedRevision(context: Context): Int =
        runCatching { stamp(context).readText().trim().toInt() }.getOrDefault(0)

    fun isInstalled(context: Context): Boolean =
        file(context).length() == BYTES && installedRevision(context) >= REVISION

    fun bytesOnDisk(context: Context): Long = dir(context).listFiles()?.sumOf { it.length() } ?: 0L

    @Volatile
    var installed: Boolean = false
        private set

    fun refresh(context: Context) {
        installed = isInstalled(context)
    }

    /** Fetch it. ⚠ Blocking — off the main thread. */
    fun install(
        context: Context,
        onProgress: (ModelInstaller.Progress) -> Unit,
        isCancelled: () -> Boolean = { false },
    ) {
        val dir = dir(context).apply { mkdirs() }
        val part = File(dir, "$FILE.part")
        UpscalerCatalog.download(URL, part, BYTES, onProgress, isCancelled)
        if (part.length() != BYTES) {
            val got = part.length()
            part.delete()
            throw IOException("$LABEL: downloaded $got bytes, expected $BYTES from $URL")
        }
        val target = file(context)
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        stamp(context).writeText(REVISION.toString())
        refresh(context)
        Log.i(TAG, "installed $LABEL revision $REVISION (${bytesOnDisk(context)} bytes)")
    }

    fun delete(context: Context) {
        close()
        dir(context).deleteRecursively()
        refresh(context)
    }

    // ---- the open model ---------------------------------------------------

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    @Synchronized
    private fun session(context: Context): OrtSession? {
        session?.let { return it }
        if (!isInstalled(context)) return null
        return try {
            val e = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR, "nightmare-depth")
            e.createSession(
                file(context).absolutePath,
                OrtSession.SessionOptions().apply { setIntraOpNumThreads(THREADS) },
            ).also { env = e; session = it }
        } catch (t: Throwable) {
            Log.e(TAG, "failed to open the depth estimator", t)
            null
        }
    }

    @Synchronized
    private fun releaseModel() {
        if (session == null) return
        runCatching { session?.close() }
        session = null
        Log.i(TAG, "model released (idle or low memory)")
    }

    private val idle = IdleRelease(IdleRelease.IDLE_MS) { releaseModel() }

    fun trim() = idle.releaseNow()

    @Synchronized
    fun close() {
        runCatching { session?.close() }
        session = null
    }

    /**
     * ⭐⭐ [square]'s depth map, the same size, near = white, min-max normalised
     * — make_depth_hints.py's `depth_of`, line for line. Null when the estimator
     * is not installed. ⚠ Blocking (~1 s, unmeasured); off the main thread.
     */
    fun depth(context: Context, square: Bitmap): Bitmap? {
        idle.begin()
        try {
            val s = session(context) ?: return null
            val t0 = System.currentTimeMillis()
            val scaled = Bitmap.createScaledBitmap(square, EDGE, EDGE, true)
            val px = IntArray(EDGE * EDGE)
            scaled.getPixels(px, 0, EDGE, 0, 0, EDGE, EDGE)
            val n = EDGE * EDGE
            val chw = FloatArray(3 * n)
            for (i in 0 until n) {
                val c = px[i]
                chw[i] = (((c shr 16) and 0xFF) / 255f - MEAN[0]) / STD[0]
                chw[n + i] = (((c shr 8) and 0xFF) / 255f - MEAN[1]) / STD[1]
                chw[2 * n + i] = ((c and 0xFF) / 255f - MEAN[2]) / STD[2]
            }
            val e = env ?: return null
            val d = OnnxTensor.createTensor(e, FloatBuffer.wrap(chw), longArrayOf(1, 3, EDGE.toLong(), EDGE.toLong())).use { input ->
                s.run(mapOf(s.inputNames.first() to input)).use { r ->
                    val out = r.get(0) as OnnxTensor
                    FloatArray(n).also { out.floatBuffer.get(it) }
                }
            }
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (v in d) { if (v < lo) lo = v; if (v > hi) hi = v }
            val span = (hi - lo).coerceAtLeast(1e-6f)
            val gray = IntArray(n) { i ->
                val g = (((d[i] - lo) / span) * 255f).toInt().coerceIn(0, 255)
                (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
            val map = Bitmap.createBitmap(gray, EDGE, EDGE, Bitmap.Config.ARGB_8888)
            Log.i(TAG, "depth ${square.width}x${square.height} in ${System.currentTimeMillis() - t0} ms")
            return Bitmap.createScaledBitmap(map, square.width, square.height, true)
        } finally {
            idle.end()
        }
    }

    /**
     * ⭐ Is [source] ALREADY a depth map? GREY (every channel alike) and SMOOTH:
     * a black-and-white photo is grey too, but its texture is not — the mean
     * step between neighbours is several times a depth map's.
     */
    fun looksLikeDepthMap(source: Bitmap): Boolean {
        val step = (maxOf(source.width, source.height) / SAMPLES).coerceAtLeast(1)
        var seen = 0
        var grey = 0
        var rough = 0L
        var pairs = 0
        var y = 0
        while (y < source.height) {
            var x = 0
            var prev = -1
            while (x < source.width) {
                val p = source.getPixel(x, y)
                val r = p shr 16 and 0xFF
                val g = p shr 8 and 0xFF
                val b = p and 0xFF
                seen++
                if (maxOf(r, g, b) - minOf(r, g, b) <= GREY_SPREAD) grey++
                if (prev >= 0) { rough += kotlin.math.abs(g - prev); pairs++ }
                prev = g
                x += step
            }
            y += step
        }
        if (seen == 0 || grey < seen * GREY_FRACTION) return false
        return pairs == 0 || rough.toDouble() / pairs <= SMOOTH_MAX
    }

    private const val SAMPLES = 128
    private const val GREY_SPREAD = 10
    private const val GREY_FRACTION = 0.97f
    /**
     * Mean |step| between samples ~1/128 of the picture apart, 0..255. Measured
     * 2026-09-29: eight pose-pack depth maps 1.0–3.1; the same renders turned
     * black-and-white 6.0–16.6, line art 8–16. The gap's middle.
     */
    private const val SMOOTH_MAX = 4.5
}
