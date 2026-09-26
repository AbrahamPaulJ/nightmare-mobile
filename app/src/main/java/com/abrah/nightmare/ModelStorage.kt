package com.abrah.nightmare

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * ⭐⭐⭐ **Where models live — ONE answer for the whole app** (`docs/ROADMAP.md`,
 * "A shared models folder").
 *
 * Two places, the person's choice in Settings → Downloads:
 * - [Place.APP] — `Android/data/com.abrah.nightmare/files/`, the default. No
 *   permission, invisible to file pickers, wiped on uninstall.
 * - [Place.DOWNLOAD] — `Download/Nightmare/`, visible to every file manager and
 *   kept across an uninstall. ⚠ Needs **All files access**
 *   (`MANAGE_EXTERNAL_STORAGE`), asked only when a person picks it — the
 *   user's call, 2026-09-26: *"some users would be wary"*.
 *
 * ⚠⚠ Every model path is built from [root]: [ModelCatalog.root],
 * [ModelCatalog.downloads], [BackendProcess.modelsDir] /
 * [BackendProcess.embeddingsDir], [com.abrah.nightmare.npu.NpuFiles.root] and
 * [PromptTranslate.dir]. A seventh place computing its own path from
 * `getExternalFilesDir` would put its model where the others are not looking.
 *
 * ⚠ `models/` and `embeddings/` stay SIBLINGS under the root: the backend finds
 * embeddings two directories above `--model_dir` (`main.cpp`).
 *
 * ⚠ Only DATA lives here. Code the backend maps (`libdit_engine.so`) stays in
 * internal storage — every external place is mounted `noexec` ([DitEngine]).
 *
 * ✅ Measured 2026-09-26 on the S25 Ultra (Android 16, FUSE passthrough on):
 * a 1.2 GB SD 1.5 checkpoint loads in ~1.35 s direct vs ~1.49 s from
 * `Download/`, inside run-to-run noise.
 */
object ModelStorage {

    private const val TAG = "ModelStorage"
    private const val FILE = "nightmare.prefs"
    private const val KEY = "models_place"

    enum class Place { APP, DOWNLOAD }

    /** ⭐ The folder under `Download/`, named in the UI as written here. */
    const val FOLDER = "Nightmare"

    /**
     * ⚠ The subtrees that are MODELS and move with the choice. `downloads/`
     * (part-files) does not: a half-download is scratch, not a model.
     */
    val SUBDIRS = listOf("models", "embeddings", "npu")

    /**
     * ⚠⚠ Read LAZILY, straight from the preferences file — never from a field
     * something else must load first. `OpService` runs ops with no activity,
     * and a root that defaulted to APP before `Prefs.load` would install a
     * model into the folder nobody is reading.
     */
    @Volatile private var cached: Place? = null

    fun place(context: Context): Place = cached ?: run {
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
        (Place.entries.firstOrNull { it.name == raw } ?: Place.APP).also { cached = it }
    }

    private const val KEY_MOVING = "models_moving_from"

    /**
     * ⭐⭐ A move that has STARTED and not finished — the place it was moving
     * FROM, or null. Written before the first file moves and cleared when the
     * last one has, so a move the app was killed in the middle of is resumed
     * at the next launch (the user's call, 2026-09-27).
     */
    fun pendingMove(context: Context): Place? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_MOVING, null)
            ?.let { raw -> Place.entries.firstOrNull { it.name == raw } }

    fun setPendingMove(context: Context, from: Place?) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply {
            if (from == null) remove(KEY_MOVING) else putString(KEY_MOVING, from.name)
        }.commit()
    }

    /**
     * ⭐ Whether [modelId]'s directory is (partly) in the place NOT chosen —
     * a move cut short. Run then says "finish the move" instead of offering to
     * download a model that is on the phone already.
     */
    fun strandedModel(context: Context, modelId: String): Boolean {
        val other = if (place(context) == Place.APP) Place.DOWNLOAD else Place.APP
        if (other == Place.DOWNLOAD && !hasAccess()) return false
        val dir = File(rootFor(context, other), "models/$modelId")
        return dir.isDirectory && !dir.listFiles().isNullOrEmpty()
    }

    /** ⚠ `commit`, not `apply`: a model move follows at once and must not race the write. */
    fun setPlace(context: Context, value: Place) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, value.name).commit()
        cached = value
    }

    fun rootFor(context: Context, place: Place): File = when (place) {
        Place.APP -> context.getExternalFilesDir(null) ?: File(context.filesDir, "external")
        Place.DOWNLOAD -> File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER,
        )
    }

    /** ⭐ The root every model path is built from. */
    fun root(context: Context): File = rootFor(context, place(context))

    /** Whether this app holds All files access. */
    fun hasAccess(): Boolean =
        // ⚠ It THROWS when no primary volume is mounted (Robolectric has none;
        // so does a phone mid-unmount). No volume is no access.
        runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    /**
     * ⭐ What sits under [place] — each top-level entry of [SUBDIRS], with its
     * size. Empty directories are left out: `ctxDir()` and friends create them
     * on read, and a "0 MB still here" line would be noise.
     */
    fun contents(context: Context, place: Place): List<Pair<File, Long>> =
        contentsOf(rootFor(context, place))

    internal fun contentsOf(root: File): List<Pair<File, Long>> {
        return SUBDIRS.flatMap { sub ->
            File(root, sub).listFiles().orEmpty().mapNotNull { f ->
                val size = sizeOf(f)
                if (size > 0) f to size else null
            }
        }
    }

    fun sizeOf(f: File): Long =
        if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()

    /**
     * ⭐⭐ Move everything from [from] to [to], then leave the source empty.
     *
     * ⚠⚠ **Merge, file by file, and RESUMABLE.** A file already at the
     * destination is kept and the source copy left alone (reported in the
     * result), so re-running after an interruption finishes the job instead of
     * redoing it, and a folder that survived an uninstall is ADOPTED rather
     * than overwritten.
     *
     * ⚠ Rename first; `Android/data` is bind-mounted outside FUSE for its own
     * app, so crossing to `Download/` is usually a different mount and the
     * rename fails with EXDEV — then copy through `<name>.moving` and rename,
     * so a half-copied file never appears under its real name.
     *
     * @return the source files left behind because the destination already had
     *   them.
     */
    fun move(
        context: Context,
        from: Place,
        to: Place,
        onProgress: (ModelInstaller.Progress) -> Unit,
        isCancelled: () -> Boolean,
        /** ⚠ For the headless op only: move just these (`models/qteamix`), leaving the rest stranded. */
        only: Set<String>? = null,
    ): List<File> {
        val dst = rootFor(context, to)
        if (to == Place.DOWNLOAD) {
            dst.mkdirs()
            // ⚠ Nothing here is a picture, and a gallery indexing 12 GB of
            // `.bin` would be work for nobody.
            File(dst, ".nomedia").takeIf { !it.exists() }?.createNewFile()
        }
        return moveRoots(rootFor(context, from), dst, onProgress, isCancelled, only)
    }

    /** ⚠ The Context-free core of [move], so a JVM test can drive it on temp dirs. */
    internal fun moveRoots(
        src: File,
        dst: File,
        onProgress: (ModelInstaller.Progress) -> Unit,
        isCancelled: () -> Boolean,
        only: Set<String>? = null,
    ): List<File> {
        val items = contentsOf(src).filter { only == null || it.first.relativeTo(src).invariantSeparatorsPath in only }
        val total = items.sumOf { it.second }
        var done = 0L
        val kept = mutableListOf<File>()
        for ((item, size) in items) {
            val rel = item.relativeTo(src).path
            // ⚠ Checked per item against what it NEEDS, so a full disk stops
            // before the copy that would fail, not halfway through it.
            val free = dst.let { generateSequence(it) { f -> f.parentFile }.first { f -> f.exists() } }.usableSpace
            if (free < size + (256L shl 20)) {
                throw IOException(
                    "not enough space for $rel: needs ${size shr 20} MB, ${free shr 20} MB free",
                )
            }
            onProgress(ModelInstaller.Progress("moving $rel", done, total))
            moveTree(item, File(dst, rel), kept, isCancelled) { n ->
                done += n
                onProgress(ModelInstaller.Progress("moving $rel", done, total))
            }
        }
        runCatching { Log.i(TAG, "moved ${done shr 20} MB $src → $dst, ${kept.size} left (already there)") }
        return kept
    }

    private fun moveTree(
        src: File,
        dst: File,
        kept: MutableList<File>,
        isCancelled: () -> Boolean,
        onBytes: (Long) -> Unit,
    ) {
        if (isCancelled()) throw ModelInstaller.Cancelled()
        if (src.isDirectory) {
            dst.mkdirs()
            for (c in src.listFiles().orEmpty()) moveTree(c, File(dst, c.name), kept, isCancelled, onBytes)
            // ⚠ Only once EMPTY — a kept file keeps its directory.
            if (src.listFiles().isNullOrEmpty()) src.delete()
            return
        }
        if (src.name.endsWith(".moving")) { src.delete(); return }
        if (dst.exists()) { kept += src; return }
        val len = src.length()
        // ⚠ A top-level FILE (an embedding) has no directory pass to create its
        // parent — found by `ModelStorageTest`, before a phone did.
        dst.parentFile?.mkdirs()
        if (!src.renameTo(dst)) {
            val tmp = File(dst.parentFile, dst.name + ".moving")
            src.inputStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var since = 0L
                    while (true) {
                        if (isCancelled()) { out.close(); tmp.delete(); throw ModelInstaller.Cancelled() }
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        since += n
                        if (since >= 64L shl 20) { onBytes(since); since = 0 }
                    }
                    onBytes(since)
                }
            }
            if (tmp.length() != len || !tmp.renameTo(dst)) {
                tmp.delete()
                throw IOException("could not move ${src.name}")
            }
            src.delete()
        } else {
            onBytes(len)
        }
    }
}
