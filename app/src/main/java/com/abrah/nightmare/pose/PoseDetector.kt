package com.abrah.nightmare.pose

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.abrah.nightmare.BackendProcess
import com.abrah.nightmare.ModelInstaller
import com.abrah.nightmare.UpscalerCatalog
import com.abrah.nightmare.segment.IdleRelease
import java.io.File
import java.io.IOException

/**
 * ⭐⭐ The pose detector for SD 1.5 Swap's openpose ControlNet: a PHOTO becomes
 * an OpenPose skeleton on the phone ([PoseModel] → [OpenPoseRender]), and a
 * picture that already IS a skeleton is used as it is ([looksLikeSkeleton]).
 *
 * ⚠ The THIRD Tools download, the same kind as [com.abrah.nightmare.segment.Parser]
 * and shaped like it: one `.onnx` on the CPU, no arch gate, a revision stamp,
 * released when idle. Ported from DreamUI with the user's go-ahead 2026-09-29 —
 * *"for now yes, port"* — and a better (hands, fingers) model is on the roadmap
 * (`docs/ROADMAP.md` §2h).
 */
object PoseDetector {

    private const val TAG = "PoseDetector"

    const val ID = "movenet"
    const val LABEL = "Pose Detector (MoveNet)"

    /** ⚠ Bumped whenever the FILE changes — [com.abrah.nightmare.segment.Parser.REVISION]'s rule. */
    const val REVISION = 1

    const val FILE = "movenet.onnx"

    /**
     * MoveNet MultiPose Lightning, **float**, Apache-2.0, `Xenova`'s existing ONNX
     * export — linked, not re-hosted. ⚠⚠ Never the 5.3 MB int8 sibling: it runs
     * and returns zero detections on every photo (DreamUI, measured).
     */
    const val URL = "https://huggingface.co/Xenova/movenet-multipose-lightning/resolve/main/onnx/model.onnx"

    /** ⚠ Exact, as DreamUI checked it: an upstream change fails loudly. */
    const val BYTES = 19_049_169L

    fun dir(context: Context): File = File(BackendProcess.modelsDir(context), ID)

    internal fun file(context: Context) = File(dir(context), FILE)

    private fun stamp(context: Context) = File(dir(context), ".rev")

    private fun installedRevision(context: Context): Int =
        runCatching { stamp(context).readText().trim().toInt() }.getOrDefault(0)

    /** Present AND current. */
    fun isInstalled(context: Context): Boolean =
        file(context).length() == BYTES && installedRevision(context) >= REVISION

    fun bytesOnDisk(context: Context): Long = dir(context).listFiles()?.sumOf { it.length() } ?: 0L

    /** ⭐ The cheap answer for a preview. Refreshed by [install], [delete] and app start. */
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

    private var model: PoseModel? = null

    @Synchronized
    private fun model(context: Context): PoseModel? {
        model?.let { return it }
        return PoseModel.open(context).also { model = it }
    }

    @Synchronized
    private fun releaseModel() {
        if (model == null) return
        model?.close()
        model = null
        Log.i(TAG, "model released (idle or low memory)")
    }

    private val idle = IdleRelease(IdleRelease.IDLE_MS) { releaseModel() }

    /** ⭐ Let the model go now if nothing is using it — low memory. */
    fun trim() = idle.releaseNow()

    @Synchronized
    fun close() {
        model?.close()
        model = null
    }

    /**
     * ⭐⭐ [square]'s people as an OpenPose skeleton the same size, on black —
     * or null when the detector is not installed. ⚠ Blocking (~50–100 ms).
     * An empty skeleton (no one found) is black, which is the ControlNet's
     * "no pose" and is logged, not refused.
     */
    fun skeleton(context: Context, square: Bitmap): Bitmap? {
        idle.begin()
        try {
            val m = model(context) ?: return null
            val people = PoseModel.detectIn(m, square)
            return OpenPoseRender.render(people, square.width, square.height)
        } finally {
            idle.end()
        }
    }

    /**
     * ⭐ Is [source] ALREADY a pose hint? A skeleton is drawn on black in
     * saturated colours: DreamUI's test (at least half the samples dark) plus a
     * second one of ours — most of what is NOT dark must be strongly coloured,
     * because a night photo is dark too, but its lit parts are not pure hues.
     */
    fun looksLikeSkeleton(source: Bitmap): Boolean {
        val step = (maxOf(source.width, source.height) / SAMPLES).coerceAtLeast(1)
        var dark = 0
        var lit = 0
        var vivid = 0
        var y = 0
        while (y < source.height) {
            var x = 0
            while (x < source.width) {
                val p = source.getPixel(x, y)
                val r = p shr 16 and 0xFF
                val g = p shr 8 and 0xFF
                val b = p and 0xFF
                if (r < DARK && g < DARK && b < DARK) dark++ else {
                    lit++
                    if (maxOf(r, g, b) - minOf(r, g, b) >= VIVID) vivid++
                }
                x += step
            }
            y += step
        }
        val seen = dark + lit
        return seen > 0 && dark >= seen * DARK_FRACTION && (lit == 0 || vivid >= lit * VIVID_FRACTION)
    }

    private const val SAMPLES = 128
    private const val DARK = 40
    private const val DARK_FRACTION = 0.5f
    /** Joint and limb colours differ from their darkest channel by ≥ 150 (0.6× limbs ≥ 90). */
    private const val VIVID = 80
    private const val VIVID_FRACTION = 0.7f
}
