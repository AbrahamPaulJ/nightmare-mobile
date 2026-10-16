package com.abrah.nightmare.detail

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import com.abrah.nightmare.BackendProcess
import com.abrah.nightmare.ModelInstaller
import com.abrah.nightmare.UpscalerCatalog
import com.abrah.nightmare.segment.IdleRelease
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * ⭐⭐ ADetailer's eyes: finds the faces and hands in a finished render, for [Detailer] to redraw.
 *
 * Bingsu's `face_yolov8n` / `hand_yolov8n` (the A1111 ADetailer models, anime and real, Apache-2.0 on
 * their card), exported to ONNX at 640 and hosted on our Hugging Face (`AbrahamPJ/nightmare-adetailer`,
 * the README names the export). The user's call, 2026-10-17. ONNX Runtime on the CPU, already linked.
 *
 * ⚠ The FOURTH Tools download, shaped like [com.abrah.nightmare.pose.PoseDetector]: a revision stamp,
 * size-checked files, the sessions released when idle.
 */
object PartDetector {

    private const val TAG = "PartDetector"

    const val ID = "adetailer"
    const val LABEL = "Face and hand detector (ADetailer)"

    /** ⚠ Bumped whenever a FILE changes ([com.abrah.nightmare.pose.PoseDetector.REVISION]'s rule). */
    const val REVISION = 1

    private const val BASE = "https://huggingface.co/AbrahamPJ/nightmare-adetailer/resolve/main/"

    /** ⭐ What can be fixed, and the file that finds it. Sizes exact: an upstream change fails loudly. */
    enum class Part(val file: String, val bytes: Long) {
        FACE("face_yolov8n.onnx", 12_265_726L),
        HAND("hand_yolov8n.onnx", 12_265_726L),
    }

    val BYTES: Long = Part.entries.sumOf { it.bytes }

    fun dir(context: Context): File = File(BackendProcess.modelsDir(context), ID)

    private fun file(context: Context, part: Part) = File(dir(context), part.file)

    private fun stamp(context: Context) = File(dir(context), ".rev")

    private fun installedRevision(context: Context): Int =
        runCatching { stamp(context).readText().trim().toInt() }.getOrDefault(0)

    /** Present AND current. */
    fun isInstalled(context: Context): Boolean =
        Part.entries.all { file(context, it).length() == it.bytes } && installedRevision(context) >= REVISION

    fun bytesOnDisk(context: Context): Long = dir(context).listFiles()?.sumOf { it.length() } ?: 0L

    /** ⭐ The cheap answer for a row. Refreshed by [install], [delete] and app start. */
    @Volatile
    var installed: Boolean = false
        private set

    fun refresh(context: Context) {
        installed = isInstalled(context)
    }

    /** Fetch both. ⚠ Blocking — off the main thread. Progress runs over the pair as one total. */
    fun install(
        context: Context,
        onProgress: (ModelInstaller.Progress) -> Unit,
        isCancelled: () -> Boolean = { false },
    ) {
        val dir = dir(context).apply { mkdirs() }
        var before = 0L
        for (part in Part.entries) {
            val target = file(context, part)
            if (target.length() != part.bytes) {
                val tmp = File(dir, part.file + ".part")
                UpscalerCatalog.download(BASE + part.file, tmp, part.bytes, { p ->
                    onProgress(ModelInstaller.Progress(p.phase, before + p.done, BYTES))
                }, isCancelled)
                if (tmp.length() != part.bytes) {
                    val got = tmp.length()
                    tmp.delete()
                    throw IOException("$LABEL: downloaded $got bytes of ${part.file}, expected ${part.bytes}")
                }
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            }
            before += part.bytes
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

    // ---- the open sessions --------------------------------------------------

    private var env: OrtEnvironment? = null
    private val sessions = HashMap<Part, OrtSession>()

    @Synchronized
    private fun session(context: Context, part: Part): OrtSession? {
        sessions[part]?.let { return it }
        if (!isInstalled(context)) return null
        return try {
            val e = env ?: OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR, "nightmare-adetailer")
                .also { env = it }
            e.createSession(
                file(context, part).absolutePath,
                OrtSession.SessionOptions().apply { setIntraOpNumThreads(THREADS) },
            ).also { sessions[part] = it }
        } catch (t: Throwable) {
            Log.e(TAG, "failed to open ${part.file}", t)
            null
        }
    }

    @Synchronized
    fun close() {
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
    }

    private val idle = IdleRelease(IdleRelease.IDLE_MS) {
        close()
        Log.i(TAG, "sessions released (idle or low memory)")
    }

    /** ⭐ Let the sessions go now if nothing is using them — low memory. */
    fun trim() = idle.releaseNow()

    /**
     * ⭐⭐ The [part]s in [image]: boxes in its pixels, most confident first; null when not installed.
     * ⚠ Blocking (~100 ms a part on a phone CPU).
     */
    fun detect(context: Context, image: Bitmap, part: Part, conf: Float = CONF): List<YoloDecode.Box>? {
        idle.begin()
        try {
            val s = session(context, part) ?: return null
            val lb = YoloDecode.Letterbox.of(image.width, image.height)
            val input = letterbox(image, lb)
            val raw = OnnxTensor.createTensor(env, input, longArrayOf(1, 3, DIM.toLong(), DIM.toLong())).use { t ->
                s.run(mapOf(s.inputNames.first() to t)).use { r ->
                    val out = r.get(0) as OnnxTensor
                    FloatArray(out.floatBuffer.remaining()).also { out.floatBuffer.get(it) }
                }
            }
            val boxes = YoloDecode.boxes(raw, raw.size / 5, conf, IOU).map { lb.toSource(it) }
            Log.i(TAG, "${part.name.lowercase()}: ${boxes.size} at ${boxes.joinToString { "%.2f".format(it.score) }}")
            return boxes
        } finally {
            idle.end()
        }
    }

    /** RGB in 0..1, NCHW, on Ultralytics' grey (114) — what the export was traced with. */
    private fun letterbox(image: Bitmap, lb: YoloDecode.Letterbox): java.nio.FloatBuffer {
        val frame = Bitmap.createBitmap(DIM, DIM, Bitmap.Config.ARGB_8888)
        Canvas(frame).apply {
            drawColor(Color.rgb(114, 114, 114))
            val scaled = Bitmap.createScaledBitmap(
                image, (image.width * lb.scale).roundToInt().coerceAtLeast(1),
                (image.height * lb.scale).roundToInt().coerceAtLeast(1), true,
            )
            drawBitmap(scaled, lb.dx, lb.dy, null)
            if (scaled !== image) scaled.recycle()
        }
        val n = DIM * DIM
        val px = IntArray(n)
        frame.getPixels(px, 0, DIM, 0, 0, DIM, DIM)
        frame.recycle()
        val buf = ByteBuffer.allocateDirect(4 * 3 * n).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (shift in intArrayOf(16, 8, 0)) {
            for (i in 0 until n) buf.put(((px[i] ushr shift) and 0xFF) / 255f)
        }
        buf.rewind()
        return buf
    }

    const val DIM = YoloDecode.DIM

    /** A1111 ADetailer's default confidence, and Ultralytics' NMS overlap. */
    const val CONF = 0.3f
    private const val IOU = 0.45f

    /** [com.abrah.nightmare.pose.PoseModel.THREADS]'s reasoning: four, not ORT's pick. */
    private const val THREADS = 4
}

/**
 * ⚠ Pure: a YOLOv8 detect head's `[1, 5, N]` as boxes — validated on the PC against Ultralytics'
 * own predictions on the same export (2026-10-17: the same boxes to a pixel or two, the same scores).
 */
object YoloDecode {

    const val DIM = 640

    /** x1, y1, x2, y2 and the score; in whatever frame the caller is in. */
    data class Box(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val score: Float) {
        val w get() = x2 - x1
        val h get() = y2 - y1
    }

    /** Where a picture sits in the [DIM] square: scaled by [scale], offset by [dx], [dy]. */
    data class Letterbox(val scale: Float, val dx: Float, val dy: Float) {
        fun toSource(b: Box) = Box((b.x1 - dx) / scale, (b.y1 - dy) / scale, (b.x2 - dx) / scale, (b.y2 - dy) / scale, b.score)

        companion object {
            fun of(w: Int, h: Int): Letterbox {
                val s = DIM.toFloat() / maxOf(w, h, 1)
                return Letterbox(s, ((DIM - (w * s).roundToInt()) / 2).toFloat(), ((DIM - (h * s).roundToInt()) / 2).toFloat())
            }
        }
    }

    /** [out] is channel-major: [n] cx, then [n] cy, [n] w, [n] h, [n] scores. Greedy NMS at [iou]. */
    fun boxes(out: FloatArray, n: Int, conf: Float, iou: Float): List<Box> {
        val candidates = (0 until n).filter { out[4 * n + it] >= conf }.sortedByDescending { out[4 * n + it] }
        val kept = ArrayList<Box>()
        for (i in candidates) {
            val cx = out[i]; val cy = out[n + i]; val w = out[2 * n + i]; val h = out[3 * n + i]
            val b = Box(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, out[4 * n + i])
            if (kept.all { overlap(it, b) < iou }) kept += b
        }
        return kept
    }

    private fun overlap(a: Box, b: Box): Float {
        val ix = (minOf(a.x2, b.x2) - maxOf(a.x1, b.x1)).coerceAtLeast(0f)
        val iy = (minOf(a.y2, b.y2) - maxOf(a.y1, b.y1)).coerceAtLeast(0f)
        val inter = ix * iy
        return inter / (a.w * a.h + b.w * b.h - inter).coerceAtLeast(1e-6f)
    }
}
