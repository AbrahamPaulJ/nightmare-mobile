package com.abrah.nightmare

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * ⭐⭐ `libdit_engine.so` — the FLUX.2 / Z-Image engine, DOWNLOADED rather than
 * shipped in the APK.
 *
 * ⚠ Why it left the APK: it is 55.7 MB on disk and 21.9 MB deflated inside the
 * archive, which was 23% of `v1.5.500`, and it is dead weight on every phone
 * that never renders a DiT model. Its WEIGHTS already download (6.7–8.8 GB), so
 * the runtime shipping while the model does not was simply inconsistent.
 * `notes/HANDOFF.md` §7.
 *
 * ⚠⚠ It must land in [BackendProcess.runtimeDir] — internal storage — and
 * nowhere else. `getExternalFilesDir` is emulated storage and mounted `noexec`,
 * so a `.so` there cannot be mapped `PROT_EXEC` and `dlopen` fails. That is why
 * this does NOT follow [com.abrah.nightmare.segment.Segmenter] into
 * [BackendProcess.modelsDir]: the segmenter's files are ONNX data read by the
 * CPU, this one is code the backend maps.
 *
 * ⚠ The Hexagon skels (`assets/ditlibs`, 1.8 MB) stay in the APK. They are
 * FastRPC payloads handed to the DSP by bare name, not something anything on
 * the CPU loads, and 1.8 MB does not pay for a second moving part.
 *
 * ⭐ Everything else here is [com.abrah.nightmare.segment.Segmenter]'s shape —
 * a `.part` download, a length check, an unpack through a temp name, a stamp
 * that makes a stale copy reinstall rather than load. Read that one first; the
 * two deviations are argued where they occur.
 */
object DitEngine {

    private const val TAG = "DitEngine"

    const val LABEL = "FLUX.2 / Z-Image engine"

    /** The one entry in [URL]'s archive, and what `--lib_dir` is searched for. */
    const val FILE = BackendProcess.DIT_ENGINE

    /**
     * ⚠⚠ **TAG-PINNED, and that is the whole staleness mechanism.** The engine
     * is ABI-bound to the backend executable in the APK that names it
     * (`DIT_ENGINE_ABI_VERSION`, `backend-src/src/DitEngine.h`), so a mutable
     * `…/main/…` pointer of the kind every model download uses would hand an
     * old install a newer engine than its core. A release asset at a tag cannot
     * move.
     *
     * ⚠ The tag need not match the app version, and by the rule below it
     * normally will not: `v1.5.501` hosted the alpha.1 engine for apps
     * 1.5.502–1.5.503. This is the SECOND engine — upstream local-dream
     * v3.0.0-alpha.2, `DIT_ENGINE_ABI_VERSION` 3, with the Hexagon op fix
     * (their a7dd738) that image editing needs. ⚠⚠ An app built before
     * 1.5.504 keeps fetching the alpha.1 engine from the old tag and keeps
     * working, which is exactly what tag-pinning is for: the core refuses a
     * mismatched ABI outright, so old core + new engine must never meet.
     *
     * ⚠ So: a NEW engine build gets a new filename at a new tag, and this
     * constant changes with it — [isInstalled] compares the stamp against this
     * exact string, so changing it is what makes every existing install
     * re-fetch. ⚠⚠ The corollary, and the way to get this wrong: do NOT
     * re-upload the same engine under each release's tag. The URL changing is
     * read as "the engine changed", and every user would re-download 22 MB per
     * app update for nothing. Point at the release the engine LANDED in.
     * (Same rule, same reason, as `Segmenter.URL`'s "a new filename per
     * revision, never an upload over the old one".)
     */
    const val URL =
        "https://github.com/AbrahamPaulJ/nightmare-mobile/releases/download/v1.5.504/dit-engine-ld3.0.0a2.zip"

    /** ⚠ The ARCHIVE's size, measured off the file that was uploaded. */
    const val BYTES = 23_015_258L

    /** The unpacked `.so`, checked after extraction. */
    private const val FILE_BYTES = 55_719_760L

    /**
     * ⚠ A deviation from [com.abrah.nightmare.segment.Segmenter], which checks
     * length alone: this archive holds NATIVE CODE that a child process will
     * `dlopen`, and a length check cannot tell a truncated-then-resumed
     * download from a correct one. Hashing 22 MB costs ~100 ms once.
     * ⭐ It is the digest GitHub publishes for the release asset, so there is
     * no second place to keep it in step.
     */
    private const val SHA256 = "5ea8575068a9a1694192d00b3d9d5f6a02f5260e07ca4dd908b30dc582e83a59"

    fun dir(context: Context): File = BackendProcess.runtimeDir(context)

    fun file(context: Context): File = File(dir(context), FILE)

    /** ⚠ Holds the [URL] it was installed from — see that constant. */
    private fun stamp(context: Context) = File(dir(context), ".dit-engine")

    private fun installedUrl(context: Context): String? =
        runCatching { stamp(context).readText().trim() }.getOrNull()

    /** Present, the right size, AND from the URL this build names. */
    fun isInstalled(context: Context): Boolean =
        file(context).length() == FILE_BYTES && installedUrl(context) == URL

    fun bytesOnDisk(context: Context): Long = file(context).length()

    /**
     * ⭐ The cheap answer for a launch path that should not hash or stat on
     * every render. Refreshed by [install], [delete] and app start.
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
        val zip = File(dir, "dit-engine.zip.part")
        UpscalerCatalog.download(URL, zip, BYTES, onProgress, isCancelled)
        if (zip.length() != BYTES) {
            val got = zip.length()
            zip.delete()
            throw IOException("$LABEL: downloaded $got bytes, expected $BYTES from $URL")
        }
        onProgress(ModelInstaller.Progress("verifying", 0, 0))
        val got = sha256(zip)
        if (got != SHA256) {
            zip.delete()
            throw IOException("$LABEL: archive sha256 is $got, expected $SHA256 — the download is corrupt")
        }
        onProgress(ModelInstaller.Progress("extracting", 0, 0))
        // ⚠⚠⚠ `.part` then RENAME, never a write in place — and here that is
        // not only about an interrupted unpack leaving a truncated file.
        // `FileOutputStream` opens with `O_TRUNC`, and truncating a file zaps
        // every page of every mapping of it, COW'd pages included, which
        // discards the linker's `.got.plt` fixups and SIGSEGVs whoever holds
        // it open. That is exactly the "ONE render per process" bug
        // (`docs/NEODRAGON.md` §5b, and the note on
        // [BackendProcess.prepareRuntime]). A re-install while a backend has
        // the engine `dlopen`'d is the same hazard; a rename swaps the
        // directory entry and leaves the old inode intact for that process.
        val tmp = File(dir, "$FILE.part")
        var extracted = false
        ZipInputStream(zip.inputStream().buffered(1 shl 16)).use { z ->
            while (true) {
                val entry = z.nextEntry ?: break
                if (entry.isDirectory || entry.name.substringAfterLast('/') != FILE) {
                    z.closeEntry()
                    continue
                }
                if (isCancelled()) throw ModelInstaller.Cancelled()
                tmp.outputStream().use { out -> z.copyTo(out, 1 shl 16) }
                extracted = true
                z.closeEntry()
            }
        }
        if (!extracted) throw IOException("$LABEL: the archive had no $FILE")
        if (tmp.length() != FILE_BYTES) {
            val n = tmp.length()
            tmp.delete()
            throw IOException("$LABEL: unpacked $n bytes, expected $FILE_BYTES")
        }
        tmp.setReadable(true, false)
        tmp.setExecutable(true, false)
        // ⚠ Same directory, same filesystem, so this is `rename(2)`: atomic,
        // and it never truncates the file it replaces.
        if (!tmp.renameTo(file(context))) throw IOException("$LABEL: cannot replace ${file(context)}")
        zip.delete()
        stamp(context).writeText(URL)
        refresh(context)
        Log.i(TAG, "installed $LABEL from $URL (${bytesOnDisk(context)} bytes)")
    }

    fun delete(context: Context) {
        file(context).delete()
        stamp(context).delete()
        refresh(context)
    }

    private fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered(1 shl 16).use { input ->
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
