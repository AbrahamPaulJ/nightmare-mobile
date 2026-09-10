package com.abrah.nightmare

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * ⭐⭐ **Upscalers: the second KIND of model this app installs.**
 *
 * ⚠⚠ A separate catalogue from [ModelCatalog] rather than more rows in it, and
 * the reason is not tidiness — the two differ in every way that matters:
 *
 * | | checkpoint | upscaler |
 * |---|---|---|
 * | on disk | a directory of ~12 files, unzipped | ONE loose `upscaler.bin` |
 * | download | a 1-3.7 GB zip, extracted | a 8-24 MB file, used as-is |
 * | launch | binds `--type`/`--model_dir`/`--patch` | **nothing** |
 * | context key | `(type, model, resolution)` | **none** |
 * | loading | held for the process's life | per REQUEST, freed after |
 *
 * ⭐⭐⭐ **The last two rows are why an upscaler can share a graph with a
 * sampler.** `/upscale` is registered in `main.cpp` outside the `if (pipeline)`
 * guards and builds its QNN model from the path in the request header, so it
 * runs inside whichever backend is already up and costs no process transition
 * (`docs/ARCHITECTURE.md` §4). An upscaler is not a checkpoint the graph is
 * pinned to; it is a file a node names. ⚠ `--upscaler_mode` is unrelated — it
 * means "a server with NO diffusion model".
 *
 * ⚠ Sizes below were read from the Hugging Face tree API on 2026-09-10, not
 * estimated: they are what the free-space check and the progress bar divide by.
 */
data class UpscalerBuild(
    /** The published tier, e.g. `8gen2`. ⚠ No leading underscore: upstream's
     *  upscaler files are `upscaler_8gen2.bin`, where a checkpoint archive is
     *  `..._qnn2.28_8gen2.zip`. Two different spellings of the same idea. */
    val tier: String,
    val bytes: Long,
    /** The HTP arch this context needs. A lower-arch device cannot load it. */
    val minArch: Int,
    val minVtcmMb: Int,
) {
    fun runsOn(caps: DeviceProbe.Caps): Boolean =
        caps.arch >= minArch && caps.vtcmMb >= minVtcmMb
}

/**
 * One upscaler, as the catalogue holds it.
 *
 * ⚠ [remoteDir] is the folder in `xororz/upscaler`; the file inside it is
 * `upscaler_<tier>.bin` for every tier.
 */
data class UpscalerSpec(
    val id: String,
    val label: String,
    val about: String,
    val remoteDir: String,
    /** ⚠ Best-first — [buildFor] takes the FIRST one this device can load. */
    val builds: List<UpscalerBuild>,
) {
    fun dir(context: Context): File = File(BackendProcess.modelsDir(context), id)

    /**
     * ⚠⚠ The file the BACKEND is told to open, by absolute device path. The
     * weights never cross the wire — the same handles-not-buffers rule the rest
     * of the op surface follows (`docs/ARCHITECTURE.md` §6).
     */
    fun file(context: Context): File = File(dir(context), UpscalerCatalog.FILE_NAME)

    /**
     * ⚠ Existence AND non-zero length, matching what upstream checks. A
     * half-written file from a killed download is present and useless, and the
     * failure it causes surfaces inside QNN rather than here.
     */
    fun installed(context: Context): Boolean = file(context).let { it.exists() && it.length() > 0 }

    fun bytesOnDisk(context: Context): Long = file(context).takeIf { it.exists() }?.length() ?: 0L

    fun buildFor(caps: DeviceProbe.Caps): UpscalerBuild? = builds.firstOrNull { it.runsOn(caps) }
}

object UpscalerCatalog {

    private const val TAG = "Upscalers"

    /** ⚠ The name upstream uses, and what `UpscaleNode` hands the backend. */
    const val FILE_NAME = "upscaler.bin"

    const val BASE_URL = "https://huggingface.co/xororz/upscaler/resolve/main/"

    /**
     * ⚠⚠ The SAME three tiers the checkpoints use, deliberately.
     *
     * Upstream also publishes `8gen3` and `8gen4`, and both are byte-identical
     * in size to `8gen2` — so they are near-certainly the same context rebuilt
     * for a different `socModel`, and `8gen2` is a v73 context that newer HTPs
     * run forward (`../LocalDream/docs/MODEL-SUPPORT.md`). Adding tiers whose
     * arch numbers nobody here has verified would be inventing a gate, and a
     * wrong gate refuses a device that would have worked.
     */
    private const val ARCH_8GEN2 = 73
    private const val ARCH_8GEN1 = 69
    private const val ARCH_MIN = 68

    /**
     * ⚠ Two, and they are a genuine pair rather than a quality ladder: the
     * anime one is a 6-block RealESRGAN trained on illustration, the realistic
     * one is UltraSharp. Running a photo through the anime weights does not
     * fail, it flattens the texture — so the choice is stated on the node.
     */
    val ALL: List<UpscalerSpec> = listOf(
        UpscalerSpec(
            id = "upscaler_anime",
            label = "Anime 4x",
            about = "RealESRGAN x4plus anime 6B — for illustration and anime. " +
                "Flattens skin and fabric texture on a photo.",
            remoteDir = "realesrgan_x4plus_anime_6b",
            builds = listOf(
                UpscalerBuild("8gen2", 8_353_936L, ARCH_8GEN2, 8),
                UpscalerBuild("8gen1", 11_286_672L, ARCH_8GEN1, 8),
                UpscalerBuild("min", 10_492_056L, ARCH_MIN, 2),
            ),
        ),
        UpscalerSpec(
            id = "upscaler_realistic",
            label = "Realistic 4x",
            about = "4x UltraSharp V2 Lite — for photos and realistic renders.",
            remoteDir = "4x_UltraSharpV2_Lite",
            builds = listOf(
                UpscalerBuild("8gen2", 23_461_888L, ARCH_8GEN2, 8),
                UpscalerBuild("8gen1", 23_662_592L, ARCH_8GEN1, 8),
                UpscalerBuild("min", 21_647_360L, ARCH_MIN, 2),
            ),
        ),
    )

    fun byId(id: String): UpscalerSpec? = ALL.firstOrNull { it.id == id }

    fun installed(context: Context): List<UpscalerSpec> = ALL.filter { it.installed(context) }

    /**
     * ⚠ The absolute path, or null when it is not installed. Null is the node's
     * cue to fail with a sentence naming the upscaler rather than to hand the
     * backend a path that is not there — QNN's own error for a missing context
     * file names neither the file nor the node.
     */
    fun pathFor(context: Context, id: String): String? =
        byId(id)?.takeIf { it.installed(context) }?.file(context)?.absolutePath

    /**
     * Fetch [spec] into its model directory.
     *
     * ⚠ Blocking — call it off the main thread.
     *
     * ⚠⚠ Downloaded straight to its final name via a `.part` file, with NO
     * unzip step: an upscaler ships as a bare weight file. That is also why
     * there is no "twice the download" free-space rule here, unlike
     * [ModelInstaller] — nothing is ever on disk twice.
     */
    fun install(
        context: Context,
        spec: UpscalerSpec,
        build: UpscalerBuild,
        onProgress: (ModelInstaller.Progress) -> Unit,
        isCancelled: () -> Boolean = { false },
    ) {
        val dir = spec.dir(context).apply { mkdirs() }
        val dest = spec.file(context)
        val part = File(dir, "${FILE_NAME}.part")
        val url = "$BASE_URL${spec.remoteDir}/upscaler_${build.tier}.bin"

        download(url, part, build.bytes, onProgress, isCancelled)

        // ⚠⚠ Renamed only once the bytes are all there. `installed()` tests the
        // final name, so a download killed at 90% must not leave something that
        // answers "yes" — the failure would then be a QNN init error on a
        // truncated context, which names nothing a user can act on.
        if (part.length() != build.bytes) {
            val got = part.length()
            part.delete()
            throw IOException(
                "${spec.label}: downloaded $got bytes, expected ${build.bytes} from $url"
            )
        }
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) throw IOException("could not finish writing ${dest.name}")
        Log.i(TAG, "installed ${spec.id} tier ${build.tier} (${dest.length()} bytes)")
    }

    /** ⚠ The whole directory, `.part` leftovers included. */
    fun delete(context: Context, spec: UpscalerSpec) {
        spec.dir(context).deleteRecursively()
    }

    /**
     * ⚠⚠ **A near-copy of `ModelInstaller.download`, and it is a copy on
     * purpose.** The first version of this was written from scratch "because an
     * upscaler is just one file", and it left a 0-byte `upscaler.bin.part` on
     * the device with no picture of why: the stream was opened for WRITING
     * before the response body was opened for READING, so anything that threw
     * on the first read created the file and then abandoned it. The proven
     * downloader opens them the other way round.
     *
     * ⇒ Same order, same `!in 200..299` check, same resume rule, same integrity
     * check. The differences that remain are the ones that are actually
     * different: no zip, and the size comes from the catalogue rather than a
     * `Build`.
     *
     * ⚠ A resumed request answered with `200` rather than `206` means the
     * server ignored the `Range` header and is sending the WHOLE file —
     * appending that produces a file of the right length made of the wrong
     * bytes. Restart cleanly instead.
     */
    private fun download(
        url: String,
        dest: File,
        expected: Long,
        onProgress: (ModelInstaller.Progress) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        if (dest.exists() && dest.length() == expected) return
        var from = if (dest.exists()) dest.length() else 0L
        // A partial LONGER than the target is not a partial; it is junk.
        if (from > expected) {
            dest.delete()
            from = 0L
        }

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            if (from > 0) setRequestProperty("Range", "bytes=$from-")
        }
        try {
            val code = conn.responseCode
            // ⚠ The URL is in the message. A bare "HTTP 404" on a catalogue
            // whose paths are hand-written says nothing about which path.
            if (code !in 200..299) throw IOException("HTTP $code for $url")
            val append = from > 0 && code == HttpURLConnection.HTTP_PARTIAL
            if (!append) from = 0L

            // ⚠⚠ READ opened before WRITE — see the note above. This ordering
            // is the actual bug fix.
            conn.inputStream.use { input ->
                java.io.FileOutputStream(dest, append).use { output ->
                    copy(input, output, from, expected, onProgress, isCancelled)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** ⚠ Progress throttled to ~1 MB: it crosses to the main thread. */
    private fun copy(
        input: InputStream,
        output: OutputStream,
        startAt: Long,
        total: Long,
        onProgress: (ModelInstaller.Progress) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val buf = ByteArray(64 * 1024)
        var done = startAt
        var lastReport = 0L
        while (true) {
            if (isCancelled()) throw ModelInstaller.Cancelled()
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            done += n
            if (done - lastReport >= 1_000_000L) {
                lastReport = done
                onProgress(ModelInstaller.Progress("downloading", done, total))
            }
        }
        onProgress(ModelInstaller.Progress("downloading", done, total))
    }
}
