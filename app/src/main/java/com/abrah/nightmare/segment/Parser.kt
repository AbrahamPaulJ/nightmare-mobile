package com.abrah.nightmare.segment

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.abrah.nightmare.BackendProcess
import com.abrah.nightmare.ModelInstaller
import com.abrah.nightmare.UpscalerCatalog
import java.io.File
import java.io.IOException

/**
 * ⭐⭐ Pick by name: the parser's file, the one open model, and the label maps
 * it has produced (`docs/SEGMENTER.md` §7–§8).
 *
 * ⚠ The SECOND Tools download, beside [Segmenter], and the same kind of thing:
 * ONNX on the CPU, no arch gate, no context key and nothing to "Use".
 *
 * ⚠⚠ **One file, not an archive.** [Segmenter] unpacks a zip because SAM is
 * three graphs that reference their own `.data` sidecars; this is a single
 * self-contained `.onnx`, so the download IS the install and there is no
 * extract step to interrupt.
 */
object Parser {

    private const val TAG = "Parser"

    const val ID = "atrparse"
    const val LABEL = "Auto Segmenter (ATR)"

    /**
     * ⚠⚠ Bumped whenever the FILE changes, exactly as [Segmenter.REVISION] —
     * and for the reason that one was written: an existence check alone would
     * keep a bad export forever. ⚠ A new filename per revision, never an
     * upload over the old one.
     */
    const val REVISION = 1

    const val FILE = "parser.onnx"

    const val URL =
        "https://huggingface.co/AbrahamPJ/segformer-b2-clothes-onnx/resolve/main/atr-parser-int8-v1.onnx"

    /** ⚠ The int8 export's exact size, measured — not estimated. */
    const val BYTES = 28_635_315L

    /**
     * ⭐⭐ What the chips offer, and **groups only**.
     *
     * ⚠⚠ Measured 2026-09-23 (`docs/SEGMENTER.md` §7): under int8 the model
     * renames pixels between adjacent labels — `Dress 10.8% → 3.9%` with
     * `Upper-clothes 2.0% → 8.7%` on the same picture, and `Bag` ate a pair of
     * forearms on another. A GROUP absorbs that (union IoU 0.97); a single
     * garment's name does not. ⇒ No `Dress` target, no per-limb target, and no
     * `Skin` — skin is the one group int8 does damage (7.7% → 4.8%).
     */
    data class Target(
        val id: String,
        val label: String,
        val labels: IntArray,
        /** ⚠ Face only — see [ParseMask]'s rule 4. */
        val topmostOnly: Boolean = false,
    ) {
        // ⚠ IntArray has identity equals; a data class over one is a trap for
        // `remember` and for the tests. Compare by id, which is what is stored.
        override fun equals(other: Any?) = other is Target && other.id == id
        override fun hashCode() = id.hashCode()
    }

    val TARGETS = listOf(
        Target("clothes", "Clothes", intArrayOf(4, 5, 6, 7, 8, 17)),
        // ⭐ FACE, not Head — the user's call, 2026-09-23: Head swept in the
        // hair, which has its own chip. ⚠ The face label alone, and only its
        // topmost region: ATR labels a bare midriff Face too ([ParseMask]).
        Target("face", "Face", intArrayOf(11), topmostOnly = true),
        Target("hair", "Hair", intArrayOf(2)),
        Target("shoes", "Shoes", intArrayOf(9, 10)),
        Target("bag", "Bag", intArrayOf(16)),
    )

    /**
     * ⚠ `head` is read as `face`: a flow saved while the chip was Head still
     * carries `phead`, and it should pick the chip that replaced it rather
     * than silently nothing.
     */
    fun target(id: String): Target? =
        (if (id == "head") "face" else id).let { i -> TARGETS.firstOrNull { it.id == i } }

    private val GROUPS = TARGETS.map { it.labels }

    fun dir(context: Context): File = File(BackendProcess.modelsDir(context), ID)

    private fun file(context: Context) = File(dir(context), FILE)

    private fun stamp(context: Context) = File(dir(context), ".rev")

    private fun installedRevision(context: Context): Int =
        runCatching { stamp(context).readText().trim().toInt() }.getOrDefault(0)

    /** Present AND current. A stale install is reinstalled, not used. */
    fun isInstalled(context: Context): Boolean =
        file(context).isFile && installedRevision(context) >= REVISION

    fun bytesOnDisk(context: Context): Long =
        dir(context).listFiles()?.sumOf { it.length() } ?: 0L

    /**
     * ⭐ The cheap answer for a `run`, which has a context but should not stat a
     * file per render. Refreshed by [install], [delete] and app start.
     */
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
        // ⚠ `.part` then rename, so an interrupted download cannot leave a
        // truncated file that [isInstalled] reads as present.
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

    private var model: ParseModel? = null

    @Synchronized
    private fun model(context: Context): ParseModel? {
        model?.let { return it }
        if (!isInstalled(context)) return null
        return ParseModel.open(file(context)).also { model = it }
    }

    /** ⚠ The model only — the cached label maps stay ([IdleRelease]). */
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
        cache.clear()
    }

    // ---- label maps -------------------------------------------------------

    /**
     * ⭐⭐ ONE label map per PHOTO, never per target — the pass already labelled
     * everything, so a chip change must not pay for another.
     *
     * ⚠ Bounded at FOUR: a map is the probabilities now, 18 × 128² floats
     * ≈ 1.2 MB, where the argmax it replaced was 16 KB.
     */
    private val cache = object : LinkedHashMap<Long, ParseModel.Labels>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ParseModel.Labels>) =
            size > 4
    }

    /**
     * ⭐⭐ Bumped each time a photo's map lands in the cache. Compose state, so
     * a preview that READS it redraws when the parse it was waiting on
     * finishes — without it, a thumbnail drawn before the parse kept showing
     * no mask until something else happened to recompose it.
     */
    var epoch by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    /** ⚠ The same content hash [Segmenter] uses — two decodes of one photo must agree. */
    fun photoKey(photo: Bitmap): Long = Segmenter.photoKey(photo)

    /** Whether [photo] has already been parsed — "Loading" vs "Finding". */
    @Synchronized
    fun isWarm(photo: Bitmap): Boolean = cache.containsKey(photoKey(photo))

    /** The cached map only, for the main thread. Null means "not yet". */
    @Synchronized
    fun cachedMap(photo: Bitmap): ParseModel.Labels? = cache[photoKey(photo)]

    /**
     * The label map for [photo], parsing it if it is not cached.
     *
     * ⚠⚠ Blocking, up to ~1 s on a cold photo (open + one pass). Never from the
     * main thread.
     */
    fun map(context: Context, photo: Bitmap): ParseModel.Labels? {
        val key = photoKey(photo)
        synchronized(this) { cache[key] }?.let { return it }
        idle.begin()
        val labels = try {
            val m = model(context) ?: return null
            m.parse(photo) ?: return null
        } finally {
            idle.end()
        }
        synchronized(this) { cache[key] = labels }
        Log.i(TAG, "parsed ${photo.width}x${photo.height} (key $key)")
        epoch++
        return labels
    }

    /**
     * ⭐ The mask for one target on [photo], normalised over the whole photo.
     * Null when the parser is not installed, or when the target is not in the
     * picture — the caller says which, it does not draw an empty mask.
     */
    fun pick(context: Context, photo: Bitmap, targetId: String): Bitmap? {
        val t = target(targetId) ?: return null
        val map = map(context, photo) ?: return null
        return ParseModel.maskOf(map, GROUPS, t.labels, t.topmostOnly)
    }

    /** ⚠ Cache only, for a preview that runs on the main thread. */
    fun cachedPick(photo: Bitmap, targetId: String): Bitmap? {
        val t = target(targetId) ?: return null
        val map = cachedMap(photo) ?: return null
        return ParseModel.maskOf(map, GROUPS, t.labels, t.topmostOnly)
    }
}
