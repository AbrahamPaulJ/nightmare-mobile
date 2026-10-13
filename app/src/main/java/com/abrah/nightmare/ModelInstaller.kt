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

    /**
     * ⚠ How often [fetch] reports progress, at most. 150 ms is ~7 updates a
     * second: faster than a person reads a percentage and slower than the
     * display refreshes, so nothing is gained by a smaller number and the cost
     * is paid on the main thread.
     */
    private const val TICK_MS = 150L

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
     * @param onProgress called from the worker thread, at most every [TICK_MS].
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
        // ⚠⚠⚠ ONE install per model, across every caller. The Models tab
        // guards its own button, but the headless `model_install` op does not
        // go through it — and on 2026-09-19 the two ran at once on FLUX, both
        // appending to the same in-place `dit.safetensors`: 76 MB and then
        // 115 MB of duplicated stream in the middle of a 3.9 GB file (head and
        // tail both intact). The size check caught it; this stops it. A second
        // caller fails at once rather than waiting behind a 7 GB download.
        if (!inFlight.add(spec.id)) throw IOException("${spec.label} is already downloading")
        try {
            installOnce(context, spec, build, onProgress, isCancelled)
        } finally {
            inFlight.remove(spec.id)
        }
    }

    /** ⭐ Where a [RemoteFile.convert] file's download waits for its conversion. */
    private fun sourceOf(out: File) = File(out.parentFile, out.name + ".source")

    /** ⚠ Model ids with an install running — see [install]. */
    private val inFlight: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun installOnce(
        context: Context,
        spec: ModelSpec,
        build: Build,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        // ⚠ OUR folder, always — never Local Dream's, even when its copy is the one in use.
        val modelDir = spec.ownDir(context).apply { mkdirs() }
        val cache = ModelCatalog.downloads(context).apply { mkdirs() }

        // ⭐⭐ The DiT engine rides along with the WEIGHTS, here rather than in
        // the ViewModel, because the headless `model_install` op is a second
        // caller (`HarnessOps`) and a checkpoint that downloads without the
        // code to run it is the same class of failure in both.
        // ⚠⚠ ONE bar with the model's, the engine first (a tester, 2026-10-13, Krea 2 Turbo on
        // a China mirror: the engine's own 22 MB bar crawled in single-digit MB and then
        // "9000 MB" appeared, which read as a broken progress display). Its bytes are the
        // head of the package total; its verify/unpack phases hold the bar where it is.
        val engineBytes = if (spec.isDit && !DitEngine.isInstalled(context)) DitEngine.BYTES else 0L
        val packageTotal = engineBytes + spec.active.sumOf { maxOf(it.bytes, it.sourceBytes) }
        if (engineBytes > 0) {
            DitEngine.install(context, { p ->
                val done = if (p.total > 0) p.done.coerceAtMost(engineBytes) else engineBytes
                onProgress(Progress(p.phase, done, packageTotal.coerceAtLeast(engineBytes)))
            }, isCancelled)
        }

        // ⭐⭐ A plain-file package (the DiT families): each file fetched into
        // the model dir under the name the backend expects, no zip, no extract.
        // ⚠ Straight into place, resumably: a 4 GB file that had to be copied
        // out of a download cache would need twice the space for no reason.
        // A file already present at its full size is skipped by [fetch], which
        // is what lets an interrupted 7 GB install pick up where it stopped.
        if (spec.active.isNotEmpty()) {
            val need = spec.active.sumOf { f ->
                val out = File(modelDir, f.name)
                val have = out.takeIf { it.isFile }?.length() ?: 0L
                when {
                    f.convert == null -> (f.bytes - have).coerceAtLeast(0L)
                    have == f.bytes -> 0L
                    // ⚠ A conversion holds its source and its output at once.
                    else -> f.sourceBytes - (sourceOf(out).takeIf { it.isFile }?.length() ?: 0L) + f.bytes
                }
            }
            requireFreeSpace(modelDir, need)
            // ⭐⭐ ONE bar for the whole package, not one per file. Per file, it
            // ran to 100% on the 3.9 GB DiT and dropped to 0% for the next one
            // — which reads as "it started over" (reported 2026-09-19).
            // Upstream reports packageOffset + done against the package total
            // for the same reason.
            val total = packageTotal
            var before = engineBytes
            for (f in spec.active) {
                val out = File(modelDir, f.name)
                val convert = f.convert
                if (convert == null) {
                    fetch(f.url, out, f.bytes, "downloading", { p ->
                        onProgress(Progress(p.phase, before + p.done, total))
                    }, isCancelled)
                } else if (!(out.isFile && out.length() == f.bytes)) {
                    // ⭐ Download the source beside the output (resumable, size-checked like
                    // any file), convert into a `.part`, check the EXACT size, then rename:
                    // a half-written output must never pass for an installed one.
                    val src = sourceOf(out)
                    fetch(f.url, src, f.sourceBytes, "downloading", { p ->
                        onProgress(Progress(p.phase, before + p.done, total))
                    }, isCancelled)
                    val part = File(modelDir, f.name + ".part")
                    convert.run(src, part, { done, all ->
                        onProgress(Progress("converting", before + done * f.sourceBytes / all.coerceAtLeast(1L), total))
                    }, isCancelled)
                    if (part.length() != f.bytes) {
                        part.delete()
                        throw IOException("${f.name}: converted to ${part.length()} bytes, expected ${f.bytes}")
                    }
                    if (!part.renameTo(out)) throw IOException("could not move ${part.name} into place")
                    src.delete()
                    Log.i(TAG, "converted ${f.name} (${f.bytes} bytes) from a ${f.sourceBytes}-byte download")
                }
                before += maxOf(f.bytes, f.sourceBytes)
            }
            val missing = spec.missing(context)
            if (missing.isNotEmpty()) {
                throw IOException("install incomplete, still missing: ${missing.joinToString()}")
            }
            Log.i(TAG, "installed ${spec.id} (${spec.bytesOnDisk(context)} bytes)")
            return
        }

        // ⚠ The archive and its unpacked copy are both on disk at once, so the
        // requirement is roughly twice the download. Failing here beats dying
        // three quarters of the way through and leaving both behind.
        // ⚠⚠ For SDXL that is ~7.5 GB free for a 3.7 GB model, and this check
        // is the only thing that says so before an hour of downloading.
        requireFreeSpace(cache, (build.bytes + build.extras.sumOf { it.second }) * 2)

        val zip = File(cache, build.archive)
        download(spec, build, zip, onProgress, isCancelled)
        extract(spec, zip, modelDir, onProgress, isCancelled)
        // ⚠ Deleted on success only. A failed extract keeps the archive so a
        // retry resumes from the file rather than re-fetching a gigabyte.
        zip.delete()

        // ⭐ The build's extra archives — resolution patches cut against THIS
        // build's `unet.bin` ([Build.extras]) — into the same directory, where
        // `availableResolutions` discovers them. ⚠ Same resumable, size-checked
        // fetch as the model itself, and the same delete-on-success.
        for ((name, bytes) in build.extras) {
            val extra = File(cache, name)
            fetch(spec.baseUrl + name, extra, bytes, "downloading", onProgress, isCancelled)
            extract(spec, extra, modelDir, onProgress, isCancelled)
            extra.delete()
        }

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
        // ⚠⚠ Never in Local Dream's folder (the user, 2026-10-09: used in place, never deleted).
        if (spec.fromLocalDream(context)) {
            throw IllegalStateException(context.getString(R.string.ld_delete_refused, spec.label))
        }
        spec.dir(context).deleteRecursively()
        // ⚠ EVERY tier's archive, not just the one we would pick today: a
        // half-finished download of a different tier is still gigabytes, and it
        // is invisible in the model directory.
        for (b in spec.builds) {
            discard(File(ModelCatalog.downloads(context), b.archive))
            for ((name, _) in b.extras) discard(File(ModelCatalog.downloads(context), name))
        }
    }

    /** ⚠ A download and its parallel partial ([fetchParallel]'s `.mt` + `.mt.ranges`). */
    private fun discard(f: File) {
        f.delete()
        File(f.path + ".mt").delete()
        File(f.path + ".mt.ranges").delete()
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
    ) = fetch(spec.url(build), dest, build.bytes, "downloading", onProgress, isCancelled)

    /**
     * ⭐⭐ One resumable, size-checked GET — the only downloader in the app.
     *
     * ⚠⚠ Lifted out of [download] rather than copied for the video models
     * ([com.abrah.nightmare.npu.VideoInstaller]). The resume rules below are the
     * part that is easy to get subtly wrong — a `200` answering a `Range`
     * request appends a whole file onto a partial one and produces a file of
     * the right LENGTH made of the wrong bytes — and two copies of that
     * reasoning is one copy that eventually stops matching.
     *
     * @param bytes the expected size. ⚠⚠ **THE integrity check**: nothing here
     *   publishes a checksum, and a truncated body arrives as a perfectly
     *   successful read.
     */
    fun fetch(
        url: String,
        dest: File,
        bytes: Long,
        label: String,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean = { false },
    ) {
        if (dest.exists() && dest.length() == bytes) {
            Log.i(TAG, "already downloaded: ${dest.name}")
            return
        }
        // ⭐⭐ Big files over [PARALLEL] connections — unless a single-stream partial is
        // already here, which keeps resuming the way it started.
        if (bytes >= parallelMinBytes && !(dest.exists() && dest.length() > 0)) {
            if (fetchParallel(url, dest, bytes, label, onProgress, isCancelled)) return
        }
        var from = if (dest.exists()) dest.length() else 0L
        // A partial LONGER than the target is not a partial; it is junk.
        if (from > bytes) {
            dest.delete()
            from = 0L
        }

        // ⭐⭐ The mirror, applied HERE and not in the catalogue ([Prefs.apply];
        // [LoraSources] applies it to its Hugging Face API calls too).
        val from_ = Prefs.apply(url)
        val conn = (URL(from_).openConnection() as HttpURLConnection).apply {
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
                    var last = 0L
                    while (true) {
                        if (isCancelled()) throw Cancelled()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        written += n
                        // ⚠⚠ Throttled on TIME, not bytes. Every chunk would be
                        // thousands of updates a second — but so was the 1 MB
                        // rule this replaced, because it scales with the
                        // CONNECTION: at 60 MB/s it fires 60 times a second, and
                        // each one crosses to the main thread. A bar cannot show
                        // more than the display refreshes anyway.
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - last >= TICK_MS) {
                            last = now
                            onProgress(Progress(label, written, bytes))
                        }
                    }
                    onProgress(Progress(label, written, bytes))
                }
            }
        } finally {
            conn.disconnect()
        }

        if (dest.length() != bytes) {
            // ⚠ Said for a PERSON — it reaches the Models screen verbatim. It
            // printed `size mismatch for X.zip: 913410048 != 1056615116` until the
            // design review, 2026-09-15; the file name stays for the harness log.
            // ⚠ LONGER than expected is not a short download and cannot be
            // resumed — the next attempt discards it (see the top of [fetch]).
            // Saying "resume" there promised a thing that does not happen.
            throw IOException(
                if (dest.length() > bytes) {
                    "the download came out larger than expected (${dest.name}) and is " +
                        "damaged. Download again. That file starts over."
                } else {
                    "the download stopped short. ${dest.length() shr 20} of ${bytes shr 20} MB " +
                        "arrived (${dest.name}). Download again to resume."
                }
            )
        }
    }

    /**
     * ⭐ How many connections a big download uses (a tester, 2026-10-13: *"increase the
     * number of download threads? Download speeds are too slow when using mirrors in
     * China"* — a mirror throttles per connection, so four ranges are ~four times one).
     */
    private const val PARALLEL = 4

    /** ⚠ Below this, one connection: four requests for a small file cost more than they save. */
    @androidx.annotation.VisibleForTesting
    internal var parallelMinBytes = 64L shl 20

    /** ⚠ How often the ranges file is rewritten (after an fsync of what it claims). */
    private const val PERSIST_MS = 1_000L

    /** ⚠ Retries per range before the download fails (and stays resumable). */
    private const val RANGE_TRIES = 3

    private class RangesRefused : IOException("the server ignored a Range request")

    /**
     * ⭐⭐ [fetch] over [PARALLEL] byte ranges. True when [dest] is complete; false when the
     * server will not serve ranges (or fails before a single byte), and the caller falls back
     * to one connection.
     *
     * ⚠⚠ Written into `<dest>.mt`, never into [dest]: the file is sized up front, and a
     * full-length [dest] is what "installed" means everywhere ([ModelSpec.missing], the top of
     * [fetch]). Renamed into place only when every range is complete.
     * ⚠⚠ Resumable through `<dest>.mt.ranges` — the bytes done per range, written only after
     * an fsync of those bytes, so after a crash it can be behind the data but never ahead.
     */
    private fun fetchParallel(
        url: String,
        dest: File,
        bytes: Long,
        label: String,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean,
    ): Boolean {
        val mt = File(dest.path + ".mt")
        val side = File(dest.path + ".mt.ranges")
        val starts = LongArray(PARALLEL) { bytes * it / PARALLEL }
        val ends = LongArray(PARALLEL) { bytes * (it + 1) / PARALLEL }
        val resumed = runCatching {
            side.readText().trim().split(' ').map { it.toLong() }
        }.getOrNull()?.takeIf { d ->
            mt.isFile && mt.length() == bytes && d.size == PARALLEL &&
                d.indices.all { d[it] in 0..(ends[it] - starts[it]) }
        }
        if (resumed == null) {
            mt.delete()
            side.delete()
            java.io.RandomAccessFile(mt, "rw").use { it.setLength(bytes) }
        }
        val done = java.util.concurrent.atomic.AtomicLongArray(PARALLEL)
        val synced = java.util.concurrent.atomic.AtomicLongArray(PARALLEL)
        resumed?.forEachIndexed { i, d -> done.set(i, d); synced.set(i, d) }
        val startedWith = (0 until PARALLEL).sumOf { done.get(it) }
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val source = Prefs.apply(url)

        fun range(i: Int) {
            var tries = 0
            while (!stop.get() && starts[i] + done.get(i) < ends[i]) {
                val pos = starts[i] + done.get(i)
                val conn = (URL(source).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("Range", "bytes=$pos-${ends[i] - 1}")
                }
                try {
                    val code = conn.responseCode
                    if (code == HttpURLConnection.HTTP_OK) throw RangesRefused()
                    if (code != HttpURLConnection.HTTP_PARTIAL) throw IOException("HTTP $code for $url")
                    java.io.RandomAccessFile(mt, "rw").use { raf ->
                        raf.seek(pos)
                        conn.inputStream.use { input ->
                            val buf = ByteArray(BUFFER)
                            var lastSync = android.os.SystemClock.uptimeMillis()
                            while (!stop.get()) {
                                val left = ends[i] - starts[i] - done.get(i)
                                if (left <= 0) break
                                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                if (n < 0) break
                                raf.write(buf, 0, n)
                                done.addAndGet(i, n.toLong())
                                tries = 0
                                val now = android.os.SystemClock.uptimeMillis()
                                if (now - lastSync >= PERSIST_MS) {
                                    lastSync = now
                                    raf.fd.sync()
                                    synced.set(i, done.get(i))
                                }
                            }
                        }
                        raf.fd.sync()
                        synced.set(i, done.get(i))
                    }
                } catch (e: RangesRefused) {
                    throw e
                } catch (e: IOException) {
                    if (stop.get() || ++tries >= RANGE_TRIES) throw e
                    Log.w(TAG, "range $i of ${dest.name}: ${e.message}; retry $tries")
                    Thread.sleep(1_000L * tries)
                } finally {
                    conn.disconnect()
                }
            }
        }

        val workers = (0 until PARALLEL).map { i ->
            Thread({
                try {
                    range(i)
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                    stop.set(true)
                }
            }, "fetch-${dest.name}-$i").apply { start() }
        }
        fun persist() = runCatching {
            side.writeText((0 until PARALLEL).joinToString(" ") { synced.get(it).toString() })
        }
        var lastPersist = 0L
        while (workers.any { it.isAlive }) {
            if (isCancelled()) stop.set(true)
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastPersist >= PERSIST_MS) {
                lastPersist = now
                persist()
            }
            onProgress(Progress(label, (0 until PARALLEL).sumOf { done.get(it) }, bytes))
            workers.firstOrNull { it.isAlive }?.join(TICK_MS)
        }
        persist()
        val got = (0 until PARALLEL).sumOf { done.get(it) }
        onProgress(Progress(label, got, bytes))
        if (isCancelled()) throw Cancelled()
        failure.get()?.let { t ->
            // ⚠ Nothing gained: let one connection try instead (a host that refuses ranges,
            // or limits connections). Something gained: fail now, resume next time.
            if (t is RangesRefused || got == startedWith && startedWith == 0L) {
                Log.i(TAG, "${dest.name}: parallel download not possible (${t.message}); one connection")
                mt.delete()
                side.delete()
                return false
            }
            throw t as? IOException ?: IOException(t)
        }
        if (got != bytes || mt.length() != bytes) {
            throw IOException(
                "the download stopped short. ${got shr 20} of ${bytes shr 20} MB " +
                    "arrived (${dest.name}). Download again to resume."
            )
        }
        side.delete()
        if (!mt.renameTo(dest)) throw IOException("could not move ${mt.name} into place")
        Log.i(TAG, "downloaded ${dest.name} over $PARALLEL connections")
        return true
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

    /**
     * ⚠ Public since 2026-09-21: [CustomModels] space-checks a DiT import's
     * shared parts the same way, and two copies of this sum would drift.
     */
    fun requireFreeSpace(dir: File, needed: Long) {
        val stat = StatFs(dir.absolutePath)
        val free = stat.availableBlocksLong * stat.blockSizeLong
        if (free < needed) {
            throw IOException(
                "not enough free space: need ~${needed shr 20} MB, have ${free shr 20} MB"
            )
        }
    }
}
