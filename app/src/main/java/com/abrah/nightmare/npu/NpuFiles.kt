package com.abrah.nightmare.npu

import android.content.Context
import java.io.File
import java.io.InputStream

/**
 * ⭐⭐ Where the video path's files live — and why **none of them is an APK asset**.
 *
 * ```
 *   <external files>/npu/
 *     ctx/      the 13 QNN context binaries, ~8.5 GB
 *     assets/   54 MB of host-side weights + tokenizer data
 * ```
 *
 * ⚠⚠ `../Neodragon` ships the `assets/` half **inside its APK**, and copying that
 * here was measured and rejected: `mmdit_temb.ndw` (33 MB) plus
 * `ssd1b_addembed.ndw` (21 MB) roughly DOUBLE a 58 MB APK — permanently, for
 * everyone, whether or not they ever make a video (`docs/ROADMAP.md` §3c). They
 * are data, not code, so they join the download the way a checkpoint does.
 *
 * ⚠ The ONE exception is the canary ([NpuCanary]), which is what decides whether
 * to start the download and therefore cannot be in it. 58 KB.
 *
 * ⚠⚠ **`ctx/` is not [com.abrah.nightmare.ModelCatalog]'s tree, deliberately.**
 * A checkpoint there is a directory whose family is INFERRED from its CLIP files
 * and whose required-file list is per family (`docs/MODELS.md` §3b). The video
 * path is 13 flat context binaries with no family to infer and no `--type` to
 * launch; forcing it into that shape would mean teaching the catalogue about a
 * kind of model it cannot describe, and the scan would report a broken import.
 */
object NpuFiles {

    /**
     * ⭐ What to CALL this path in the UI.
     *
     * ⚠⚠ A constant here rather than a [com.abrah.nightmare.ModelCatalog]
     * entry, for the reason the class note gives: the catalogue describes a
     * checkpoint directory with a family and a `--type`, and this is 13 flat
     * context binaries with neither. The load readout still has to name it, and
     * naming it "the selected SD checkpoint, idle" is what it did before.
     */
    const val LABEL = "Neodragon (video)"

    fun root(ctx: Context): File = File(ctx.getExternalFilesDir(null), "npu")

    fun ctxDir(ctx: Context): File = File(root(ctx), "ctx").apply { mkdirs() }

    fun assetsDir(ctx: Context): File = File(root(ctx), "assets").apply { mkdirs() }

    /**
     * The context binary for a graph name, or null.
     *
     * ⚠⚠ Matched by PREFIX, not by a hardcoded `_v79` suffix. The files are named
     * for the HTP arch they were built at, and a v75 rebuild would be a second
     * legitimate name for the same graph (`docs/ROADMAP.md` §3c). A suffix rule
     * spelled into the code would have to be found and changed in every caller
     * on the day that happens; a prefix match is correct either way.
     *
     * ⚠ Newest first when several match, so a rebuilt graph wins over the one it
     * replaced without anyone having to delete the old file first.
     */
    fun contextFile(ctx: Context, name: String): File? =
        ctxDir(ctx).listFiles()
            ?.filter { it.isFile && it.length() > 0 && it.name.startsWith(name) }
            ?.filter { it.name == "$name.bin" || it.name.startsWith("${name}_v") }
            ?.maxByOrNull { it.lastModified() }

    fun isReady(ctx: Context, name: String): Boolean = contextFile(ctx, name) != null

    /** Which of [names] are absent — i.e. what a flow is waiting on. */
    fun missing(ctx: Context, names: List<String>): List<String> =
        names.filter { !isReady(ctx, it) }

    /**
     * A host-side asset, as a stream.
     *
     * ⚠ Throws with the PATH when it is absent. The caller is usually deep in a
     * pipeline and "missing weight" without a filename is a message that sends
     * someone reading the conversion scripts.
     */
    fun asset(ctx: Context, name: String): InputStream {
        val f = File(assetsDir(ctx), name)
        check(f.isFile) { "video asset missing: ${f.absolutePath}" }
        return f.inputStream()
    }

    fun hasAsset(ctx: Context, name: String): Boolean = File(assetsDir(ctx), name).isFile

    /** Every host-side asset the video path reads. ⚠ Keep in step with the loaders. */
    val ASSETS = listOf(
        "pipeline_config.json",
        "video_structure.json",
        "video_structure.ndw",
        "mmdit_temb.ndw",
        "ssd1b_addembed.ndw",
        "clip_vocab.json",
        "clip_merges.txt",
        "t5_unigram.tsv",
    )

    fun missingAssets(ctx: Context): List<String> = ASSETS.filter { !hasAsset(ctx, it) }
}
