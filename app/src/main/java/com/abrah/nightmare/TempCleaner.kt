package com.abrah.nightmare

import android.content.Context
import android.util.Log
import java.io.File

/**
 * ⭐⭐ **Scratch the app leaves behind, and nothing else.** Ported from
 * upstream `local-dream`'s `utils/TempCleaner.kt` at the user's ask,
 * 2026-09-20, and re-aimed at OUR directory layout — the two apps share the
 * idea and not one path.
 *
 * ⚠⚠⚠ **The rule is conservative by construction: a target has to be
 * something the app can rebuild.** Everything a person would grieve is
 * enumerated as kept, not merely omitted, because the failure mode here is
 * silent and total. ⇒ `filesDir/results` (kept pictures), `filesDir/workflows`
 * (saved flows), `filesDir/sent`, `filesDir/qnnruntime` (the QNN libraries and
 * the DiT engine), and under the external files dir `models/` (31 GB of
 * checkpoints), `npu/` (8 GB of video weights), `embeddings/`, `segment/` and
 * `plugins/` are NEVER touched.
 *
 * ⚠⚠ The two INBOXES are kept too, and that is deliberate rather than an
 * oversight: `models-inbox/` and `plugins-inbox/` hold archives that FAILED to
 * import, which are kept on purpose so a retry needs no second push
 * (`notes/HANDOFF.md`). Deleting them here would quietly undo that.
 *
 * ⚠ What it does take:
 *
 *  - `downloads/` — part-downloaded archives, which is where the GBs are.
 *    ⚠⚠ Skipped while an install is running, or cleaning pulls the file out
 *    from under a live transfer. ⚠ It costs the RESUME: our downloader
 *    resumes from the partial's length ([ModelInstaller.fetch]), so this
 *    restarts an interrupted download rather than continuing it. That is the
 *    trade the confirm states, and it is upstream's trade too.
 *  - `cacheDir` — clip previews, staged zips, the canary binary, probe PNGs.
 *    Disposable by definition; Android clears it unasked. ⚠ A starred clip is
 *    copied beside its PNG precisely so it survives this
 *    ([canvas.ResultsStore]).
 *  - the harness scratch directories — `dit_edit/`, `inpaint/`, `t2i/` and
 *    `aspect_probe.png`. Diagnostics written by ops in `notes/HANDOFF.md` §5,
 *    read once by whoever ran them, and never by the app.
 *  - stray plain FILES directly under `models/`. A model is a directory; a
 *    loose file there is a leftover. ⚠ Directories are left alone even when
 *    unrecognised — a custom import is identified by scanning its contents,
 *    not by a marker, so "unrecognised" here does not mean "not a model".
 */
object TempCleaner {
    private const val TAG = "TempCleaner"

    /** ⚠ Harness output, not app state. `notes/HANDOFF.md` §5. */
    private val SCRATCH_DIRS = listOf("dit_edit", "inpaint", "inpaint-debug", "t2i")
    private val SCRATCH_FILES = listOf("aspect_probe.png")

    /** Bytes [clean] would free right now. ⚠ Call off the main thread. */
    fun scan(context: Context, busy: Boolean = false): Long =
        targets(context, busy).sumOf { sizeOf(it) }

    /** Deletes them and returns what was actually freed. */
    fun clean(context: Context, busy: Boolean = false): Long {
        var freed = 0L
        for (t in targets(context, busy)) {
            val size = sizeOf(t)
            val gone = if (t.isDirectory) t.deleteRecursively() else t.delete()
            if (gone) freed += size else Log.w(TAG, "could not delete ${t.absolutePath}")
        }
        Log.i(TAG, "freed $freed bytes")
        return freed
    }

    /**
     * ⚠ Exposed so a caller can LIST what it is about to remove. The dialog
     * shows a total, but a bug here is invisible without the names.
     */
    fun targets(context: Context, busy: Boolean = false): List<File> {
        val ext = context.getExternalFilesDir(null)
        val out = mutableListOf<File>()

        // ⚠⚠ Only when nothing is installing — see the class note.
        if (!busy && ext != null) {
            File(ext, "downloads").takeIf { it.isDirectory }?.let { out += it }
            File(ext, "models").takeIf { it.isDirectory }?.listFiles()
                ?.filter { it.isFile }
                ?.forEach { out += it }
        }
        if (ext != null) {
            for (d in SCRATCH_DIRS) File(ext, d).takeIf { it.isDirectory }?.let { out += it }
            for (f in SCRATCH_FILES) File(ext, f).takeIf { it.isFile }?.let { out += it }
        }
        context.cacheDir?.listFiles()?.forEach { out += it }
        return out
    }

    private fun sizeOf(f: File): Long =
        if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        else f.length()
}
