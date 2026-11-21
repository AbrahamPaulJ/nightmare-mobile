package com.abrah.nightmare.vlm

import android.content.Context
import android.util.Log
import com.abrah.nightmare.ModelInstaller
import com.abrah.nightmare.Prefs
import com.abrah.nightmare.UpscalerCatalog
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * ⭐⭐ The picture-to-prompt engine (GitHub #120): `libnmvlm.so` (llama.cpp's mtmd and ggml, CPU and Hexagon, linked
 * statically into our bridge) and the four Hexagon skels it opens NPU sessions with. Built by `vlm-engine.yml` at a
 * pinned llama.cpp commit, published once as a tag-pinned release asset and mirrored on Hugging Face, checked by
 * sha256 ([com.abrah.nightmare.DitEngine]'s rules: native code, so a length check is not enough).
 *
 * ⚠ Its own directory, not the backend's runtime dir: [VlmService] points ggml-hexagon at it, and the skel names
 * (`libggml-htp-v79.so`) must not mix with the DiT engine's.
 */
object VlmEngine {

    private const val TAG = "VlmEngine"
    const val MAIN = "libnmvlm.so"

    /** ⭐ llama.cpp v1.0.0 (`b1b770d`), CI 44902865061 (1.6.334's bridge: the picture token budget); the tag is the version stamp. */
    const val URL = "https://github.com/AbrahamPaulJ/nightmare-mobile/releases/download/vlm-engine-2/vlm-engine-2.zip"
    val HF_URL: String get() = "https://huggingface.co/AbrahamPJ/nightmare-dit-engine/resolve/main/" + URL.substringAfterLast('/')
    const val BYTES = 5_460_420L
    private const val SHA256 = "d21f45af8d50773f63fbad60309650d0e456ce576138096453224d4972788b25"

    /** Every file the archive must unpack, and its exact size. */
    internal val FILES: List<Pair<String, Long>> = listOf(
        MAIN to 5_810_480L,
        "libggml-htp-v73.so" to 969_864L,
        "libggml-htp-v75.so" to 969_864L,
        "libggml-htp-v79.so" to 990_440L,
        "libggml-htp-v81.so" to 1_002_792L,
        "libggml-htp-v85.so" to 1_002_792L,
    )

    fun dir(context: Context): File = File(context.filesDir, "vlm-engine")

    private fun stamp(context: Context) = File(dir(context), ".vlm-engine")

    fun isInstalled(context: Context): Boolean =
        FILES.all { (n, size) -> File(dir(context), n).length() == size } &&
            runCatching { stamp(context).readText().trim() }.getOrNull() == URL

    fun bytesOnDisk(context: Context): Long = dir(context).listFiles()?.sumOf { it.length() } ?: 0L

    internal fun sources(downloadBase: String): List<String> =
        if (downloadBase != Prefs.HF_ORIGIN) listOf(HF_URL, URL) else listOf(URL, HF_URL)

    private val lock = Any()

    /** Fetch, check, unpack. ⚠ Blocking. */
    fun install(context: Context, onProgress: (ModelInstaller.Progress) -> Unit, isCancelled: () -> Boolean) = synchronized(lock) {
        if (isInstalled(context)) return@synchronized
        val dir = dir(context).apply { mkdirs() }
        val zip = File(dir, "vlm-engine.zip.part")
        val failures = mutableListOf<String>()
        for (src in sources(Prefs.downloadBase)) {
            try {
                UpscalerCatalog.download(src, zip, BYTES, onProgress, isCancelled)
                if (zip.length() != BYTES) {
                    val got = zip.length()
                    zip.delete()
                    throw IOException("downloaded $got bytes, expected $BYTES")
                }
                onProgress(ModelInstaller.Progress("verifying", 0, 0))
                val got = sha256(zip)
                if (got != SHA256) {
                    zip.delete()
                    throw IOException("archive sha256 is $got, expected $SHA256. The download is corrupt")
                }
                failures.clear()
                break
            } catch (e: ModelInstaller.Cancelled) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "from ${java.net.URL(src).host} failed: ${e.message}")
                failures += "${java.net.URL(src).host}: ${e.message}"
            }
        }
        if (failures.isNotEmpty()) throw IOException("picture-to-prompt engine: no source answered. ${failures.joinToString("; ")}")
        unpack(context, zip, onProgress, isCancelled)
    }

    /**
     * ⚠ Each file to `.part`, then renamed: a `:vlm` process may have the old one mapped, and truncating a mapped
     * `.so` crashes it ([com.abrah.nightmare.DitEngine]'s unpack note).
     */
    private fun unpack(context: Context, zip: File, onProgress: (ModelInstaller.Progress) -> Unit, isCancelled: () -> Boolean) {
        onProgress(ModelInstaller.Progress("extracting", 0, 0))
        val want = FILES.toMap()
        val got = mutableSetOf<String>()
        ZipInputStream(zip.inputStream().buffered(1 shl 16)).use { z ->
            while (true) {
                val entry = z.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                val size = want[name]
                if (entry.isDirectory || size == null) {
                    z.closeEntry()
                    continue
                }
                if (isCancelled()) throw ModelInstaller.Cancelled()
                val tmp = File(dir(context), "$name.part")
                tmp.outputStream().use { out -> z.copyTo(out, 1 shl 16) }
                if (tmp.length() != size) {
                    val n = tmp.length()
                    tmp.delete()
                    throw IOException("$name: unpacked $n bytes, expected $size")
                }
                tmp.setReadable(true, false)
                if (!tmp.renameTo(File(dir(context), name))) throw IOException("cannot replace $name")
                got += name
                z.closeEntry()
            }
        }
        val missing = want.keys - got
        if (missing.isNotEmpty()) throw IOException("the engine archive had no ${missing.joinToString()}")
        zip.delete()
        stamp(context).writeText(URL)
        Log.i(TAG, "installed (${bytesOnDisk(context)} bytes)")
    }

    fun delete(context: Context) {
        dir(context).deleteRecursively()
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
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
