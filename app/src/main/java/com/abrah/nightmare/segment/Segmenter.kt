package com.abrah.nightmare.segment

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.abrah.nightmare.BackendProcess
import com.abrah.nightmare.ModelInstaller
import com.abrah.nightmare.UpscalerCatalog
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * ⭐⭐ Tap to select: the segmenter's files, the one open model, and the regions
 * it has produced (`docs/SEGMENTER.md`).
 *
 * ⚠ A THIRD kind of download beside a checkpoint and an upscaler, and not a
 * row in either catalogue: it is ONNX on the CPU, has no arch gate, no context
 * key and nothing to "Use". DreamUI kept it out of `ModelSpec` for the same
 * reason.
 */
object Segmenter {

    private const val TAG = "Segmenter"

    const val ID = "sam2"
    const val LABEL = "Segment Anything 2.1"

    /**
     * ⚠⚠ Bumped whenever the archive's CONTENTS change. Revision 1's prompt
     * encoder had a dead zone over the left/top 38% of the frame, and an
     * existence check alone would keep broken files forever (DreamUI's
     * `Segmenter.REVISION`, same number, same archive).
     */
    const val REVISION = 2

    /** ⚠ Flat: each `.onnx` references its `.data` by bare filename. */
    val FILES = listOf(
        "trunk.onnx", "trunk.data",
        "prompt.onnx", "prompt.data",
        "decoder.onnx", "decoder.data",
    )

    /** ⚠ A new filename per revision, never an upload over the old one. */
    const val URL = "https://huggingface.co/AbrahamPJ/sam2-tiny-split-onnx/resolve/main/sam2-split-w8a8-v2.zip"

    /** ⚠ Read from the server 2026-09-17 (`X-Linked-Size`), not estimated. */
    const val BYTES = 86_837_030L

    fun dir(context: Context): File = File(BackendProcess.modelsDir(context), ID)

    private fun stamp(context: Context) = File(dir(context), ".rev")

    /** ⚠ An absent stamp is revision 1 — exactly the broken files. */
    private fun installedRevision(context: Context): Int =
        runCatching { stamp(context).readText().trim().toInt() }.getOrDefault(1)

    /** Present AND current. A stale install is reinstalled, not used. */
    fun isInstalled(context: Context): Boolean =
        FILES.all { File(dir(context), it).isFile } && installedRevision(context) >= REVISION

    fun bytesOnDisk(context: Context): Long =
        dir(context).listFiles()?.sumOf { it.length() } ?: 0L

    /**
     * ⭐ The cheap answer for a node's `run`, which has a context but should not
     * stat six files per render. Refreshed by [install], [delete] and app start.
     */
    @Volatile
    var installed: Boolean = false
        private set

    fun refresh(context: Context) {
        installed = isInstalled(context)
    }

    /** Fetch and unpack. ⚠ Blocking — off the main thread. */
    fun install(
        context: Context,
        onProgress: (ModelInstaller.Progress) -> Unit,
        isCancelled: () -> Boolean = { false },
    ) {
        val dir = dir(context).apply { mkdirs() }
        val zip = File(dir, "archive.zip.part")
        UpscalerCatalog.download(URL, zip, BYTES, onProgress, isCancelled)
        if (zip.length() != BYTES) {
            val got = zip.length()
            zip.delete()
            throw IOException("$LABEL: downloaded $got bytes, expected $BYTES from $URL")
        }
        onProgress(ModelInstaller.Progress("extracting", 0, 0))
        ZipInputStream(zip.inputStream().buffered(1 shl 16)).use { z ->
            while (true) {
                val entry = z.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                if (entry.isDirectory || name !in FILES) {
                    z.closeEntry()
                    continue
                }
                if (isCancelled()) throw ModelInstaller.Cancelled()
                // ⚠ `.part` then rename, so an interrupted unpack cannot leave a
                // truncated file that [isInstalled] reads as present.
                val tmp = File(dir, "$name.part")
                tmp.outputStream().use { out -> z.copyTo(out, 1 shl 16) }
                val target = File(dir, name)
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                z.closeEntry()
            }
        }
        val missing = FILES.filterNot { File(dir, it).isFile }
        if (missing.isNotEmpty()) throw IOException("$LABEL: the archive had no ${missing.joinToString()}")
        zip.delete()
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

    private var model: SegmentModel? = null

    /**
     * ⭐ ONE model for the process, opened on first use (~400 ms) and held: the
     * mask editor and the sampler's `run` both segment, and two copies would be
     * two trunks' worth of memory for the same photo.
     */
    @Synchronized
    private fun model(context: Context): SegmentModel? {
        model?.let { return it }
        if (!isInstalled(context)) return null
        return SegmentModel.open(dir(context)).also { model = it }
    }

    @Synchronized
    fun close() {
        model?.close()
        model = null
        cache.clear()
    }

    // ---- regions ----------------------------------------------------------

    /**
     * ⭐⭐ Candidates by (photo, point). A tap is stored as a POINT
     * (`MaskOp.Tap`, `docs/SEGMENTER.md` §3 option A) and re-segmented when it
     * is drawn; this is what makes the redraw free after the first time.
     *
     * ⚠ Bounded: three 256² ALPHA_8 bitmaps an entry is ~200 KB.
     */
    private val cache = object : LinkedHashMap<String, SegmentModel.Segmentation>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SegmentModel.Segmentation>) =
            size > 24
    }

    /**
     * ⚠ The photo's identity by CONTENT, not by object: the editor and the
     * sampler hold different `Bitmap`s of the same picture, and a reopened flow
     * holds a third. A sparse pixel sample plus the size is enough to tell two
     * photos apart and costs microseconds.
     */
    fun photoKey(photo: Bitmap): Long {
        var h = 1125899906842597L
        h = 31 * h + photo.width
        h = 31 * h + photo.height
        val step = 17
        for (j in 0 until step) {
            for (i in 0 until step) {
                val x = (photo.width - 1) * i / (step - 1)
                val y = (photo.height - 1) * j / (step - 1)
                h = 31 * h + photo.getPixel(x, y)
            }
        }
        return h
    }

    private fun cacheKey(photo: Long, x: Float, y: Float) = "$photo:${q(x)}:${q(y)}"

    /** ⚠ The precision `MaskState.encode` writes, so a decoded tap hits its own entry. */
    private fun q(f: Float) = Math.round(f * 1000f)

    /**
     * Every candidate for a tap at ([x], [y]) on [photo], normalised over the
     * photo. Null when the segmenter is not installed or nothing is there.
     *
     * ⚠⚠ Blocking, up to ~1.2 s on a cold photo (open + trunk). Never from the
     * main thread. ⚠ Primes the trunk ON DEMAND — a decoder with no trunk
     * returns nothing, indistinguishable from a miss (DreamUI's trap).
     */
    fun segment(context: Context, photo: Bitmap, x: Float, y: Float): SegmentModel.Segmentation? {
        val pk = photoKey(photo)
        val key = cacheKey(pk, x, y)
        synchronized(this) { cache[key] }?.let { return it }
        val m = model(context) ?: return null
        val t0 = System.nanoTime()
        val primed = m.isReady(pk)
        if (!primed) m.encode(photo, pk)
        val t1 = System.nanoTime()
        val result = m.segment(x, y) ?: return null
        Log.i(
            TAG, "segment ${q(x)},${q(y)}: trunk ${if (primed) "cached" else "${(t1 - t0) / 1_000_000} ms"}, " +
                "decode ${(System.nanoTime() - t1) / 1_000_000} ms, ${result.candidates.size} candidates",
        )
        synchronized(this) { cache[key] = result }
        return result
    }

    /** ⚠ Cache only — for a caller on the main thread that must not block. */
    fun cached(photo: Bitmap, x: Float, y: Float): SegmentModel.Segmentation? =
        synchronized(this) { cache[cacheKey(photoKey(photo), x, y)] }
}
