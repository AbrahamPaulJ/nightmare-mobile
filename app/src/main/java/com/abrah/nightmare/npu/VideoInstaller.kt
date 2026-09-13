package com.abrah.nightmare.npu

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.abrah.nightmare.ModelInstaller
import java.io.File
import java.io.IOException

/**
 * ⭐⭐ Getting the video models onto a phone that is not the developer's.
 *
 * ⚠⚠ **This is what stood between "it runs here" and "somebody else can run
 * it".** The 13 context binaries reached the first device by `adb push` out of
 * `../Neodragon`'s own app directory, which is not a distribution mechanism —
 * `docs/NEODRAGON.md` §6.
 *
 * ⚠⚠ **NOT [com.abrah.nightmare.ModelInstaller]'s shape, and deliberately.**
 * A checkpoint there is ONE zip that unpacks into a directory whose family is
 * inferred from its contents. This is a flat set of independently-downloadable
 * `.bin` files with no archive and nothing to infer ([NpuFiles] says why they
 * do not live in the catalogue's tree). What the two share is the part worth
 * sharing — [ModelInstaller.fetch], the resumable size-checked GET.
 *
 * ⭐ **Resumable per FILE, which matters more here than anywhere else in the
 * app.** 8.6 GB over a phone connection will be interrupted; a run that had to
 * restart from zero would never finish. Each file is checked against its
 * published size and skipped if it is already whole, so re-running this after
 * a drop costs one HEAD-shaped request per completed file.
 */
object VideoInstaller {

    private const val TAG = "VideoInstaller"

    /**
     * ⚠⚠ **The published arch is v79 and that is not a bug.** A QNN context
     * runs on the arch it was built for and every NEWER one (`docs/DEVICES.md`
     * §2), so one conversion serves 8 Elite and 8 Elite Gen 5 — the phone's own
     * arch is deliberately NOT consulted here. What decides whether a device
     * may download is [NpuCanary], which runs a real v79 context rather than
     * consulting a table.
     */
    private const val ARCH = "v79"

    /** ⚠ Public, ungated, BSD-3-Clause-Clear. Verified 2026-09-13. */
    private const val REPO = "https://huggingface.co/AbrahamPJ/neodragon-npu-s25u/resolve/main"

    /**
     * The 13 graphs [Video.requiredModels] asks for, with their published
     * sizes.
     *
     * ⚠⚠ **Sizes are the integrity check and they are hardcoded on purpose.**
     * Reading them from the HF API at install time would make the check agree
     * with whatever the server just served, which is not a check at all. They
     * were read off the repo on 2026-09-13; a re-upload has to update this
     * table, and the size mismatch it causes in the meantime is the correct
     * failure.
     *
     * ⚠ The repo holds 16; the three the pipeline never maps — `mmdit_s0g`,
     * `mmdit_s1f`, `mmdit_s2f`, the non-shortcut MMDiT variants — are **not**
     * listed. That is 4.6 GB a user would otherwise wait for and never use.
     */
    private val GRAPHS = linkedMapOf(
        "cliplp" to 249_451_112L,
        "clipg" to 1_402_277_416L,
        "distilt5f" to 260_050_504L,
        "ctxadaptfp16" to 260_531_784L,
        "vaeenc" to 41_667_768L,
        "mmdit_s0fs" to 1_525_163_128L,
        "mmdit_s1fs" to 1_533_523_064L,
        "mmdit_s2fs" to 1_573_344_888L,
        "vaedecsn" to 12_104_632L,
        "quicksrm2x" to 377_880L,
        "clipl" to 233_993_824L,
        "ssd1bunet" to 1_358_184_480L,
        "ssd1bvaedec" to 85_467_216L,
    )

    /**
     * ⭐⭐ The host-side weights — published 2026-09-13, and with them the
     * video path became installable by somebody who is not the developer.
     *
     * These are read by the APP, not the NPU: embedding tables, the tokenizer
     * vocabularies and the pyramidal schedule. `../Neodragon` ships them inside
     * its APK and `docs/ROADMAP.md` §3c rejected that here — `mmdit_temb.ndw`
     * (33 MB) plus `ssd1b_addembed.ndw` (21 MB) roughly DOUBLE a 58 MB APK,
     * permanently, for everyone, whether or not they ever make a video. So they
     * are data and they join the download, exactly as a checkpoint does.
     *
     * ⚠ They live under `assets/` in the repo, mirroring [NpuFiles]'s own
     * split: `ctx/` for the graphs, `assets/` for these.
     *
     * ⚠⚠ Sizes read back OFF THE REPO after the upload rather than off the
     * local files, so the table is the server's answer — see [GRAPHS] for why
     * these numbers are the integrity check and not decoration.
     */
    private val ASSET_BYTES = linkedMapOf(
        "pipeline_config.json" to 565L,
        "video_structure.json" to 12_972L,
        "video_structure.ndw" to 4_402_736L,
        "mmdit_temb.ndw" to 33_055_080L,
        "ssd1b_addembed.ndw" to 20_981_936L,
        "clip_vocab.json" to 1_059_962L,
        "clip_merges.txt" to 524_619L,
        "t5_unigram.tsv" to 649_836L,
    )

    /** Total download for a phone that has nothing. */
    val totalBytes: Long get() = GRAPHS.values.sum() + ASSET_BYTES.values.sum()

    /** ⚠ Byte counts, not file counts: 12 of 13 files can be 3% of the job. */
    fun installedBytes(ctx: Context): Long =
        GRAPHS.entries.sumOf { (name, bytes) ->
            if (NpuFiles.contextFile(ctx, name)?.length() == bytes) bytes else 0L
        } + ASSET_BYTES.entries.sumOf { (name, bytes) ->
            if (java.io.File(NpuFiles.assetsDir(ctx), name).length() == bytes) bytes else 0L
        }

    /** Which graphs are absent or the wrong size. */
    fun missing(ctx: Context): List<String> =
        GRAPHS.entries.filter { (name, bytes) ->
            NpuFiles.contextFile(ctx, name)?.length() != bytes
        }.map { it.key }

    /**
     * Which host-side weights are absent or the wrong size.
     *
     * ⚠ Stricter than [NpuFiles.missingAssets], which only asks whether the
     * file exists. That is the right question for a RENDER about to read them;
     * this is the question for an install, where a truncated download is the
     * failure being guarded against.
     */
    fun missingAssets(ctx: Context): List<String> =
        ASSET_BYTES.entries.filter { (name, bytes) ->
            java.io.File(NpuFiles.assetsDir(ctx), name).let { !it.isFile || it.length() != bytes }
        }.map { it.key }

    fun isComplete(ctx: Context): Boolean =
        missing(ctx).isEmpty() && missingAssets(ctx).isEmpty()

    /**
     * Fetches every missing graph, resuming whatever is half-there.
     *
     * ⚠ Blocking. Call it off the main thread.
     *
     * @throws IOException on a size mismatch, a dead connection, or too little
     *   free space — and [ModelInstaller.Cancelled] when [isCancelled] flips.
     */
    fun install(
        ctx: Context,
        onProgress: (ModelInstaller.Progress) -> Unit,
        isCancelled: () -> Boolean = { false },
    ) {
        val dir = NpuFiles.ctxDir(ctx)
        val want = missing(ctx)
        val wantAssets = missingAssets(ctx)
        if (want.isEmpty() && wantAssets.isEmpty()) {
            Log.i(TAG, "everything already present")
        } else {
            // ⚠⚠ Checked ONCE for the whole set rather than per file. Failing
            // on file 11 of 13 with 6 GB written is the failure this prevents,
            // and it is the only warning a user gets before an hour of
            // downloading. ⚠ No doubling here, unlike a checkpoint: these are
            // written straight to their destination with no archive beside
            // them.
            requireFreeSpace(
                dir,
                want.sumOf { GRAPHS.getValue(it) } + wantAssets.sumOf { ASSET_BYTES.getValue(it) },
            )

            // ⭐ ONE scale across the whole set — graphs AND weights — so the
            // bar does not restart twenty-one times. Same reasoning as the
            // render's progress.
            //
            // ⚠⚠ The WEIGHTS GO FIRST, and that is not cosmetic. They are 0.7%
            // of the bytes and the app cannot render a frame without them, so
            // fetching them last means a cancel at 99% leaves 8.5 GB on disk
            // that still cannot make a video.
            val jobs = wantAssets.map {
                Triple(it, ASSET_BYTES.getValue(it), File(NpuFiles.assetsDir(ctx), it))
            } + want.map {
                Triple(it, GRAPHS.getValue(it), File(dir, "${it}_$ARCH.bin"))
            }
            val total = jobs.sumOf { it.second }
            var done = 0L
            for ((name, bytes, file) in jobs) {
                val base = done
                // ⚠ The repo mirrors [NpuFiles]: graphs at the root with their
                // arch suffix, weights under `assets/` by their plain name.
                val path = if (name in ASSET_BYTES) "assets/$name" else "${name}_$ARCH.bin"
                ModelInstaller.fetch(
                    url = "$REPO/$path",
                    dest = file,
                    bytes = bytes,
                    label = "downloading $name",
                    onProgress = { p ->
                        onProgress(ModelInstaller.Progress(p.phase, base + p.done, total))
                    },
                    isCancelled = isCancelled,
                )
                done += bytes
                Log.i(TAG, "have $name (${bytes shr 10} KB)")
            }
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
