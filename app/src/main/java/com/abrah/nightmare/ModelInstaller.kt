package com.abrah.nightmare

import android.content.Context
import android.os.StatFs
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Fetches a checkpoint and unpacks it into its model directory.
 *
 * ⚠ Every guard below is one DreamUI's `ModelDownloader.kt` already paid for on
 * ~1 GB archives over phone connections; none of them is defensive
 * programming for its own sake. The shape is deliberately simpler than that
 * one — a model is a SINGLE archive with no shared-file donor logic,
 * because every entry in `ModelCatalog.all` is self-contained. ⚠ That stays
 * true with SDXL: it shares nothing with SD 1.5 (different encoders, different
 * VAE), so a second family adds entries rather than a donor graph.
 */
object ModelInstaller {

    private const val TAG = "ModelInstaller"
    private const val BUFFER = 1 shl 16

    /** Progress, in the shape a UI can render without knowing the phases. */
    data class Progress(val phase: String, val done: Long, val total: Long) {
        val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    }

    class Cancelled : IOException("cancelled")

    /**
     * Downloads and extracts [spec], then verifies the result.
     *
     * ⚠ Blocking. Call it off the main thread; the callers here are coroutines
     * on `Dispatchers.IO`.
     *
     * @param onProgress called from the worker thread, throttled to ~1 MB.
     * @param isCancelled polled during IO so a cancel takes effect promptly
     *   rather than at the end of a gigabyte.
     */
    fun install(
        context: Context,
        spec: ModelSpec,
        /**
         * ⭐ WHICH published build. ⚠ Passed in rather than chosen here: the
         * caller already knows the device, and a downloader that silently
         * picked a tier would be a second place the choice lives.
         */
        build: Build,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean = { false },
    ) {
        val modelDir = spec.dir(context).apply { mkdirs() }
        val cache = ModelCatalog.downloads(context).apply { mkdirs() }

        // ⚠ The archive and its unpacked copy are both on disk at once, so the
        // requirement is roughly twice the download. Failing here beats dying
        // three quarters of the way through and leaving both behind.
        // ⚠⚠ For SDXL that is ~7.5 GB free for a 3.7 GB model, and this check
        // is the only thing that says so before an hour of downloading.
        requireFreeSpace(cache, build.bytes * 2)

        val zip = File(cache, build.archive)
        download(spec, build, zip, onProgress, isCancelled)
        extract(spec, zip, modelDir, onProgress, isCancelled)
        // ⚠ Deleted on success only. A failed extract keeps the archive so a
        // retry resumes from the file rather than re-fetching a gigabyte.
        zip.delete()

        val missing = spec.missing(context)
        if (missing.isNotEmpty()) {
            throw IOException("install incomplete, still missing: ${missing.joinToString()}")
        }
        Log.i(TAG, "installed ${spec.id} (${spec.bytesOnDisk(context)} bytes)")
    }

    /**
     * ⚠ Removes the whole directory, archive leftovers included.
     *
     * ⚠⚠ Refuses to delete the model the app is currently set to use unless
     * [force]. Deleting it leaves the backend pointed at a directory that is no
     * longer there, and the failure surfaces as a launch error naming a path
     * rather than as "you deleted the model you were using".
     */
    fun delete(context: Context, spec: ModelSpec, force: Boolean = false) {
        if (!force && spec.id == SelectedModel.id) {
            throw IllegalStateException("\"${spec.label}\" is the selected model; pick another first")
        }
        spec.dir(context).deleteRecursively()
        // ⚠ EVERY tier's archive, not just the one we would pick today: a
        // half-finished download of a different tier is still gigabytes, and it
        // is invisible in the model directory.
        for (b in spec.builds) File(ModelCatalog.downloads(context), b.archive).delete()
    }

    // ---- internals -------------------------------------------------------

    /**
     * Fetches [spec] into [dest], resuming an existing partial file.
     *
     * ⚠ A resumed request that is answered with a plain `200` instead of `206`
     * means the server ignored the `Range` header and is sending the WHOLE
     * file — appending that to the partial would produce a file of the right
     * length made of the wrong bytes. Restart cleanly instead.
     */
    private fun download(
        spec: ModelSpec,
        build: Build,
        dest: File,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        if (dest.exists() && dest.length() == build.bytes) {
            Log.i(TAG, "already downloaded: ${dest.name}")
            return
        }
        val label = "downloading ${spec.label}"
        var from = if (dest.exists()) dest.length() else 0L
        // A partial LONGER than the target is not a partial; it is junk.
        if (from > build.bytes) {
            dest.delete()
            from = 0L
        }

        val url = spec.url(build)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            if (from > 0) setRequestProperty("Range", "bytes=$from-")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code for $url")
            val append = from > 0 && code == HttpURLConnection.HTTP_PARTIAL
            if (!append) from = 0L

            conn.inputStream.use { input ->
                java.io.FileOutputStream(dest, append).use { output ->
                    val buf = ByteArray(BUFFER)
                    var written = from
                    var since = 0L
                    while (true) {
                        if (isCancelled()) throw Cancelled()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        written += n
                        since += n
                        // Throttled: every chunk would be thousands of updates a second.
                        if (since >= 1 shl 20) {
                            onProgress(Progress(label, written, build.bytes))
                            since = 0
                        }
                    }
                    onProgress(Progress(label, written, build.bytes))
                }
            }
        } finally {
            conn.disconnect()
        }

        // ⚠⚠ THE integrity check. There is no published checksum, and a
        // truncated body arrives as a successful read — this is the only thing
        // between a dropped connection and a model that fails at first render.
        if (dest.length() != build.bytes) {
            throw IOException(
                "size mismatch for ${dest.name}: ${dest.length()} != ${build.bytes}"
            )
        }
    }

    /**
     * Writes the wanted entries of [archive] into [modelDir], flattened.
     *
     * ⚠ Matched on BASENAME: the archives nest under a build directory
     * (`output_512/qnn_models_8gen2/` for SD 1.5), and the model directory is
     * flat.
     * ⚠ Each file is written to `<name>.part` and renamed, so an interrupted
     * extract cannot leave a truncated file that [ModelSpec.missing] then
     * reports as present.
     */
    private fun extract(
        spec: ModelSpec,
        archive: File,
        modelDir: File,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        archive.inputStream().buffered(BUFFER).use { raw ->
            ZipInputStream(raw).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.substringAfterLast('/')
                    // ⚠ The SPEC's filter, not one catalogue-wide rule: SD 1.5
                    // ships one `clip_v2.mnn`, SDXL two encoders plus an
                    // external weight file. A shared list would either drop
                    // files SDXL cannot start without or unpack a gigabyte
                    // nothing reads.
                    if (entry.isDirectory || !spec.wanted(name)) {
                        zip.closeEntry()
                        continue
                    }
                    onProgress(Progress("extracting $name", 0, 0))
                    val target = File(modelDir, name)
                    val tmp = File(modelDir, "$name.part")
                    tmp.outputStream().use { out -> copy(zip, out, isCancelled) }
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                    zip.closeEntry()
                    Log.i(TAG, "extracted $name (${target.length()} bytes)")
                }
            }
        }
    }

    private fun copy(input: InputStream, output: OutputStream, isCancelled: () -> Boolean) {
        val buf = ByteArray(BUFFER)
        while (true) {
            if (isCancelled()) throw Cancelled()
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
        }
    }

    private fun requireFreeSpace(dir: File, needed: Long) {
        val stat = StatFs(dir.absolutePath)
        val free = stat.availableBlocksLong * stat.blockSizeLong
        if (free < needed) {
            throw IOException(
                "not enough free space: need ~${needed shr 20} MB, have ${free shr 20} MB"
            )
        }
    }
}
