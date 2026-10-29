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
     * ⭐⭐ The engine current since 1.6.266: `dit-engine-abi110-h3enc`, CI 40950650774 (branch `dit-068`): abi110 +
     * `dit/079` (MiniMax H3's start picture encoded on the NPU's CONV_3D_CAUSAL, not a CPU 3D conv: 94 to 8 s of a
     * 384x672 clip on the S25, `docs/MINIMAX-H3.md` §5e). Still ABI 110; the wrapper only, skels are abi110's.
     * Before it, since 1.6.261: `dit-engine-abi110`, CI 40876155848 (branch `dit-068`): lora14 + `dit/069`-`076`
     * and `dit/078` (MiniMax H3, video with sound). ⚠ **ABI 110**, a two-binary change: `audio_vae_path` and `on_audio`
     * (backend 050); an ABI 108 backend refuses this engine and the other way round. The wrapper only: skels are lora14's.
     * Before it, since 1.6.259: `dit-engine-abi108-lora14`, CI 40741059307 (branch `dit-068`): stream +
     * `dit/068` (a Wan 2.2 A14B user LoRA binds to its expert by a "high:" / "both:" path prefix, Joachim's TG #7454).
     * The wrapper only: skels are stream's. Still ABI 108.
     * Before it, since 1.6.256: `dit-engine-abi108-stream`, CI 40591974445 (branch `dit-060`): fibo +
     * `dit/060` (a cut graph carries use counts, so a streamed DiT fuses its ops again), `dit/061` (every weight repack
     * threaded, fp8 a word at a time), `dit/062` (the loader reads with `pread` in 16 MiB chunks), `dit/064` + `dit/051`
     * (the graph-cut and runner caches reuse buffers) and `dit/063` + `dit/065` (the qk-norm rope kernel in token
     * blocks with an L2 prefetch). Z-Image streamed at 4 GiB on the S25 (`sd-cli`): sampling 74 to 41 s, pictures
     * byte-identical (`docs/ENGINE-PERF.md` §2g). Still ABI 108; the v69-v81 skels are this build's, v85 stays fibo's.
     * Before it, since 1.6.249: `dit-engine-abi108-fibo`, CI 40386764103 (branch `dit-056`): i2v720 +
     * `dit/055` (FLUX.3 cut points, so an 8 Gen 3's 2.75 GiB streams it), `dit/056` (Fibo 2.0 Edit, upstream #2180) and
     * `dit/057` (Fibo's self-attention through the fused qk-norm + rope: S25 512² 53 to 15 s a step; `docs/FIBO2.md`).
     * Z-Image Turbo byte-identical to i2v720 on the S25. Still ABI 108; skels unchanged.
     * Before it, since 1.6.237: `dit-engine-abi108-i2v720`, CI 40169137127: a14fast + `dit/052` (TAEHV
     * encodes in chunks sized by the clip's area, `SD_TAEHV_ENCODE_CHUNK_MPX`, overlap 6 not 8: Wan 2.2 14B image to
     * video at 720x1280 asked 5.7 GB in one chunk and failed; byte-identical at 320x544) and `dit/053` (two VAE tiles
     * that barely overlap become three: the out-of-memory decode retry split 720 px into two halves meeting at a hard
     * seam). Still ABI 108; skels unchanged.
     * Before it, since 1.6.235: `dit-engine-abi108-a14fast`, CI 40137515324: flux3wan + `dit/046` (a Wan
     * 2.2 14B block in token chunks: 832x480 5 s fits 4.5 GiB, byte-identical), `dit/047`/`048` (MOSS-Image edit
     * references and a per-model reference preset, unused until the backend's `NM_DIT_NATIVE_EDIT`), `dit/049` (the
     * 14B's high-noise Lightning LoRA reaches the high-noise expert: before it every tensor was filtered out and the
     * first two steps ran undistilled) and `dit/050` (streamed weights load while the previous segment computes:
     * 320x480 1 s 182 to 106 s, 480x832 1 s 372 to 273 s, byte-identical). Still ABI 108; skels unchanged.
     * The TWENTIETH engine: `dit-engine-abi108-flux3wan`, CI 40107042364: the wanfa engine + `dit/042`
     * (FLUX.3 Image Klein, a backport of upstream #2143), `dit/043` (its fused Q/K norm + rope), `dit/044` (Wan 2.2
     * A14B's high-noise expert and per-expert LoRAs from the environment) and `dit/045` (FLUX.3's txt_in and mlp_out
     * scaled through the NPU's fp16 matmul, which they overflowed: noise without it). Still ABI 108; skels unchanged.
     * The NINETEENTH engine: `dit-engine-abi108-mossrope`, CI 39739946133: the eighteenth + `dit/035`,
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
        "https://github.com/AbrahamPaulJ/nightmare-mobile/releases/download/dit-engine-abi110-h3enc/dit-engine-abi110-h3enc.zip"

    /**
     * ⭐ The same archive, byte for byte, on Hugging Face, where every model already comes from.
     * A user report (2026-10-14): `Unable to resolve host "github.com"`, and the engine was the one
     * download the app could not make. [install] tries [sources] in order; [SHA256] checks whichever
     * answered, so a second source costs no integrity. ⚠ [URL] stays the version stamp either way.
     * ⚠ A new engine uploads here too, under the same file name as its GitHub asset.
     */
    const val HF_URL =
        "https://huggingface.co/AbrahamPJ/nightmare-dit-engine/resolve/main/dit-engine-abi110-h3enc.zip"

    /**
     * ⭐ Where [install] looks, in order: GitHub first, unless the user chose a Hugging Face mirror in
     * Settings (China: GitHub crawls there, a tester's report 2026-10-13), then Hugging Face first.
     * [Prefs.apply] rewrites [HF_URL] to that mirror on the way out.
     */
    internal fun sources(downloadBase: String): List<String> =
        if (downloadBase != Prefs.HF_ORIGIN) listOf(HF_URL, URL) else listOf(URL, HF_URL)

    /** ⚠ The ARCHIVE's size, measured off the file that was uploaded. */
    const val BYTES = 23_313_128L

    /** The unpacked `.so`, checked after extraction. */
    /** ⚠ Public so a test can build an engine fixture without the network. */
    const val FILE_BYTES = 56_225_464L

    /**
     * ⚠ A deviation from [com.abrah.nightmare.segment.Segmenter], which checks
     * length alone: this archive holds NATIVE CODE that a child process will
     * `dlopen`, and a length check cannot tell a truncated-then-resumed
     * download from a correct one. Hashing 22 MB costs ~100 ms once.
     * ⭐ It is the digest GitHub publishes for the release asset, so there is
     * no second place to keep it in step.
     */
    private const val SHA256 = "1d47358c1477a1aa74b4d84688d9a50a5c8efa5646f891b4037fea2cb9824353"

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
        unpack(context, zip, onProgress, isCancelled)
    }

    /**
     * ⭐ An engine archive pushed by hand (the harness's `engine_sideload`), checked against THIS build's [BYTES] and
     * [SHA256] exactly as a download is: a new engine is A/B'd on the phone before its release exists, and publishing
     * the same bytes under [URL] afterwards changes nothing on a phone that sideloaded them.
     */
    fun installFrom(context: Context, archive: File) = synchronized(installLock) {
        val zip = File(dir(context).apply { mkdirs() }, "dit-engine.zip.part")
        archive.copyTo(zip, overwrite = true)
        if (zip.length() != BYTES || sha256(zip) != SHA256) {
            zip.delete()
            throw IOException("$LABEL: ${archive.name} is not this build's engine (${archive.length()} bytes)")
        }
        unpack(context, zip, {}, { false })
    }

    private fun unpack(
        context: Context,
        zip: File,
        onProgress: (ModelInstaller.Progress) -> Unit,
        isCancelled: () -> Boolean,
    ) {
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
        val tmp = File(dir(context), "$FILE.part")
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
