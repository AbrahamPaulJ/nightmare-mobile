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
     * The THIRD engine: upstream v3.0.0-alpha.3 (Qwen Image 2.1) plus our LoRA
     * fields, ABI **105** (`backend-patches/dit/003`) — 1xx because our previous
     * LoRA engine and upstream's alpha.3 both called themselves 5 over
     * different structs.
     *
     * ⭐⭐ The NINTH engine, current (1.6.158): the eighth + `dit/018`, `temporal_tile_cut` in the
     * VAE's temporal tiling, which [com.abrah.nightmare.npu.WanVideoNode.TILING] asks for (the Wan
     * decode's seam flicker, `docs/VIDEO-GGML.md` §3). Still ABI 106: an engine without 018 ignores
     * the key, so the backend did not move. CI run 38509077877; skels unchanged.
     *
     * ⭐⭐ The NINETEENTH engine, current: `dit-engine-abi108-mossrope`, CI 39739946133: the eighteenth + `dit/035`,
     * MOSS-Image's Q/K norm and rope as one fused op (its composed rope was ~13 of 18.7 s a step at 512², `docs/MOSS.md`
     * §3). Still ABI 108; backend and skels unchanged.
     *
     * The EIGHTEENTH engine: `dit-engine-abi108`, CI 39248301752: the seventeenth + `dit/031` (Hexagon
     * concat of contiguous tensors as HVX block copies: Wan decode 7.50 to 4.85 s, pictures bit-identical) + `dit/032`
     * (**ABI 108**: `dit_gen_params.scheduler`, Karras and Exponential on the DiT samplers; backend 038). ⚠ A
     * two-binary change: an ABI 107 backend refuses this engine and the other way round. New v73-v81 skels.
     *
     * The SEVENTEENTH engine: `dit-engine-abi107-hmxtest`, CI 39103362819: the sixteenth + `dit/030`, an
     * HMX self-test at session start (one Q4_0 256x256 matmul on HMX against the CPU, one `ggml-hex: … HMX self-test:`
     * line, kept in error reports by [BackendProcess.engineNpuLine]); the 8s Gen 3/4 question. S25: ok, rel-err 0.0003.
     * Still ABI 107, backend and skels unchanged.
     *
     * The SIXTEENTH engine: `dit-engine-abi107-ideogram`, CI 39099673021: the fifteenth + `dit/029`,
     * Ideogram 4's Q/K norm and rope as the fused `ggml_qknorm_rope` (its split-half rope reordered into the kernel's
     * interleaved order; 73-85 s a step at 768² before, 36-42 s after, S25). Still ABI 107, backend and skels unchanged.
     *
     * The FIFTEENTH engine: `dit-engine-abi107-moss`, CI 39040008523: the fourteenth + `dit/028`,
     * MOSS-Image (its DiT, the Qwen3.5 encoder with its tokenizer.json via `SD_QWEN35_TOKENIZER`, run-time LoRA on
     * separate q/k/v; ported from upstream #2148, `docs/MOSS.md`). Still ABI 107, backend and skels unchanged.
     *
     * The FOURTEENTH engine: `dit-engine-abi107-lokr2`, CI 38978502945: the thirteenth + `dit/027`,
     * LoKr's runtime forward with a factored w1/w2 in LyCORIS order (a tester's Krea 2 LoKr aborted on
     * `ggml_can_mul_mat` at the first step, 2026-10-16). Still ABI 107, backend and skels unchanged.
     *
     * The THIRTEENTH engine: `dit-engine-abi107-zimage`, CI 38869095514: the twelfth + `dit/025`,
     * Z-Image's `attention.out` pre-scaled 1/16 on Hexagon for non-FP8 weights (its result overflowed fp16, so every
     * Q4_0/Q8_0 GGUF render was a NaN latent, `docs/DIT.md` §2e); what lets an 8 Gen 3 take Z-Image's Q4_0 build.
     * Still ABI 107, backend unchanged; v79/v81 skels byte-identical.
     *
     * The TWELFTH engine: `dit-engine-abi107-encchunk`, CI 38850507798: the eleventh + `dit/024`,
     * TAEHV encodes a long control video in overlapped time chunks (Wan Fun Control at 5 s, GitHub #40;
     * byte-identical to the whole-clip encode). Still ABI 107, backend unchanged; v79/v81 skels byte-identical.
     *
     * The ELEVENTH engine: `dit-engine-abi107-iris`, CI 38776845309: the tenth + `dit/022`,
     * Iris-3B (a pixel-space DiT, no VAE; its default schedule is Turbo's). Still ABI 107, so backend 035
     * (`--type iris`) is the only other half; v79/v81 skels byte-identical.
     *
     * The TENTH engine (1.6.165): `dit-engine-abi107-refcap`, CI 38743114916: the ABI 107
     * one + `dit/021`, a wired reference encoded at no more than `DIT_REF_MAX_PIXELS`, which backend 034
     * sets per request below v79 (GitHub #35). Still ABI 107; v79/v81 skels byte-identical.
     *
     * The NINTH engine: ABI **107** (`dit-engine-abi107-fun`, CI 38704238843): the eighth + `dit/019`
     * (Wan 2.2 Fun 5B Control) + `dit/020` (control frames and a reference picture on the video params),
     * which backend 033 sends. ⚠ ABI 107 against a 106 backend is refused both ways: APK and URL move
     * together. v79/v81 skels unchanged (byte-identical).
     *
     * The EIGHTH engine: ABI **106** — the Anima engine + Wan 2.2 video (`dit/009`,
     * `010`, `012`, `013`, `016`, `017`: `generate_video`, `docs/VIDEO-GGML.md`). The backend moved
     * with it (backend 028), so every DiT install re-fetches it once. CI run 38404727564; skels
     * unchanged (012 is ggml's host side).
     *
     * The SEVENTH (1.6.141): the sixth + `dit/008`, Anima's fused ops (CI run 38253705642).
     *
     * The SIXTH engine (1.6.133): the fifth + `dit/007`, graph-cut points in Qwen Image
     * 2.1's DiT so the budget can split it too (CI run 38105032541; skels unchanged).
     *
     * The FIFTH engine (1.6.129): the fourth + `dit/006`, which reads a VRAM budget
     * from `DIT_MAX_VRAM` so an 8 Gen 3 splits a DiT it cannot map whole ([BackendProcess]).
     * Its v79/v81 skels are byte-identical to the fourth's (CI run 38090219858; ggml unchanged).
     *
     * The FOURTH engine: upstream v3.0.0-alpha.4 (the fused DiT
     * modulation, ~20% per step; Qwen 2.1's FP8 loader), `dit/003` unchanged,
     * STILL ABI 105 — the header did not move, so the backend did not either.
     * ⚠ Its v79/v81 skels came from the same CI run (37434737157) and ship in
     * this APK's `assets/ditlibs`: a new engine is a new URL AND new skels.
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
        "https://github.com/AbrahamPaulJ/nightmare-mobile/releases/download/dit-engine-abi108-mossrope/dit-engine-abi108-mossrope.zip"

    /**
     * ⭐ The same archive, byte for byte, on Hugging Face, where every model already comes from.
     * A user report (2026-10-14): `Unable to resolve host "github.com"`, and the engine was the one
     * download the app could not make. [install] tries [sources] in order; [SHA256] checks whichever
     * answered, so a second source costs no integrity. ⚠ [URL] stays the version stamp either way.
     * ⚠ A new engine uploads here too, under the same file name as its GitHub asset.
     */
    const val HF_URL =
        "https://huggingface.co/AbrahamPJ/nightmare-dit-engine/resolve/main/dit-engine-abi108-mossrope.zip"

    /**
     * ⭐ Where [install] looks, in order: GitHub first, unless the user chose a Hugging Face mirror in
     * Settings (China: GitHub crawls there, a tester's report 2026-10-13), then Hugging Face first.
     * [Prefs.apply] rewrites [HF_URL] to that mirror on the way out.
     */
    internal fun sources(downloadBase: String): List<String> =
        if (downloadBase != Prefs.HF_ORIGIN) listOf(HF_URL, URL) else listOf(URL, HF_URL)

    /** ⚠ The ARCHIVE's size, measured off the file that was uploaded. */
    const val BYTES = 23_194_267L

    /** The unpacked `.so`, checked after extraction. */
    /** ⚠ Public so a test can build an engine fixture without the network. */
    const val FILE_BYTES = 55_954_064L

    /**
     * ⚠ A deviation from [com.abrah.nightmare.segment.Segmenter], which checks
     * length alone: this archive holds NATIVE CODE that a child process will
     * `dlopen`, and a length check cannot tell a truncated-then-resumed
     * download from a correct one. Hashing 22 MB costs ~100 ms once.
     * ⭐ It is the digest GitHub publishes for the release asset, so there is
     * no second place to keep it in step.
     */
    private const val SHA256 = "cd6202ae546c1de52f3fbc0e2edd3372396bc9b14043e9ea698c2eb528b6c945"

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

    /**
     * Fetch and unpack. ⚠ Blocking — off the main thread.
     *
     * ⚠⚠ One at a time, and the second caller re-checks: two DiT installs (or an install and an import) both
     * appended to the same `dit-engine.zip.part` and unpacked into the same `.so.part`, and one renamed it while
     * the other still wrote, so a short engine could be stamped installed. [ModelInstaller]'s guard is per
     * MODEL id; the engine is shared by all of them.
     */
    fun install(
        context: Context,
        onProgress: (ModelInstaller.Progress) -> Unit,
        isCancelled: () -> Boolean = { false },
    ) = synchronized(installLock) {
        if (isInstalled(context)) {
            refresh(context)
            return@synchronized
        }
        installOnce(context, onProgress, isCancelled)
    }

    private val installLock = Any()

    private fun installOnce(
        context: Context,
        onProgress: (ModelInstaller.Progress) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val dir = dir(context).apply { mkdirs() }
        val zip = File(dir, "dit-engine.zip.part")
        // ⭐ One archive, two hosts: a partial from one resumes from the other (the bytes are the same),
        // and a failure on the first is only logged unless the second fails too.
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
                Log.w(TAG, "$LABEL from ${java.net.URL(src).host} failed: ${e.message}")
                failures += "${java.net.URL(src).host}: ${e.message}"
            }
        }
        if (failures.isNotEmpty()) throw IOException("$LABEL: no source answered. ${failures.joinToString("; ")}")
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
        Log.i(TAG, "installed $LABEL (${bytesOnDisk(context)} bytes)")
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
