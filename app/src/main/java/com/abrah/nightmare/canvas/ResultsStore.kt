package com.abrah.nightmare.canvas

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.abrah.nightmare.NodeType
import org.json.JSONObject
import java.io.File

/**
 * One kept picture: the image, and **the graph that made it**.
 *
 * ⚠ [flow] is the workflow JSON, stored verbatim beside the PNG. ⭐ Keeping it
 * is close to free and was measured before being built: a workflow is **816
 * bytes** (`workflow_io` on a three-node graph) against ~2.6 MB for the picture
 * beside it — a third of a percent. The graph is the expensive thing to
 * *recreate* and the cheap thing to *store*, which is the whole argument for
 * doing it.
 */
data class Result(
    val id: String,
    val savedAt: Long,
    /**
     * ⭐⭐ The [ImageStore] id of the picture this was kept FROM, so the star
     * that kept it can be un-starred.
     *
     * ⚠⚠ A [Result] id is `"r" + currentTimeMillis`, which says nothing about
     * which picture it holds — so nothing could answer "is this one already
     * kept?" and the star was a one-way action. The image id IS a content
     * address (`img_<rgb_sha>`), so it identifies the pixels rather than the
     * moment.
     *
     * ⚠ Null for a result kept before this field existed. Those cannot be
     * un-starred from the node; they are still deletable from Results.
     */
    val imageId: String? = null,
    /** ⚠ May be null for a result kept before its sampler had rolled. */
    val seed: String?,
    val model: String?,
    val prompt: String?,
    val width: Int,
    val height: Int,
    /**
     * ⭐⭐ Which sweep this came from, or null for an ordinary render.
     *
     * ⚠⚠ Results groups by this rather than listing every item, because a
     * 10x10 sweep is a hundred cards that bury every other picture in the tab.
     * The user asked for one entry per batch that opens into its items —
     * 2026-09-10.
     */
    val batchId: String? = null,
    /** ⭐ What made this item different, e.g. `cfg 7.5`. Empty when it is alone. */
    val batchLabel: String = "",
) {
    val label: String get() = prompt?.take(60)?.ifBlank { null } ?: "no prompt"
}

/**
 * ⭐ A sweep, as the Results tab lists it: one entry that opens into its items.
 *
 * ⚠ `items` is newest-first like everything else here, so the cover is the
 * LAST picture the sweep made rather than the first — which is the one a user
 * was looking at when it finished.
 */
data class ResultGroup(val batchId: String?, val items: List<Result>) {
    val cover: Result get() = items.first()
    val size: Int get() = items.size
    val isBatch: Boolean get() = batchId != null && items.size > 1
}

/**
 * ⭐⭐ Pictures the user chose to keep, each with the graph that produced it.
 *
 * ⚠⚠ **Not the gallery.** Saving to the gallery hands a PNG to MediaStore and
 * loses everything about how it was made; this keeps the flow, which is the
 * point — a favourite you cannot reopen is just a photo, and the phone already
 * has somewhere to put photos.
 *
 * ⚠ App-private storage (`files/results/`), so nothing here is visible to other
 * apps and deleting the app takes it. That is the right default for something
 * the user did not explicitly export; the gallery button is the export.
 */
class ResultsStore(private val dir: File) {

    private fun png(id: String) = File(dir, "$id.png")
    private fun meta(id: String) = File(dir, "$id.json")

    fun imageFile(id: String): File = png(id)

    /**
     * Keeps [bitmap] and the graph that made it.
     *
     * ⚠ Written to temp files and renamed, the same as [WorkflowStore.save] and
     * for the same reason: a process death midway through a direct write leaves
     * a truncated file, and a favourite that fails to load is worse than one
     * that was never kept.
     *
     * ⚠ Blocking — it encodes a PNG. Call it off the main thread.
     */
    fun keep(
        bitmap: Bitmap,
        /** ⚠ See [Result.imageId] — what makes the star a toggle. */
        imageId: String?,
        workflow: Workflow,
        types: Map<String, NodeType>,
        seed: String?,
        model: String?,
        prompt: String?,
        /** ⭐ Non-null when this is one item of a sweep. See [Result.batchId]. */
        batchId: String? = null,
        batchLabel: String = "",
    ): Result {
        dir.mkdirs()
        val id = "r" + System.currentTimeMillis()
        val tmpPng = File(dir, "$id.png.tmp")
        tmpPng.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (!tmpPng.renameTo(png(id))) {
            tmpPng.copyTo(png(id), overwrite = true); tmpPng.delete()
        }

        val r = Result(
            id, System.currentTimeMillis(), imageId, seed, model, prompt,
            bitmap.width, bitmap.height, batchId, batchLabel,
        )
        val json = JSONObject()
            .put("savedAt", r.savedAt)
            .put("imageId", r.imageId ?: JSONObject.NULL)
            .put("seed", r.seed ?: JSONObject.NULL)
            .put("model", r.model ?: JSONObject.NULL)
            .put("prompt", r.prompt ?: JSONObject.NULL)
            .put("width", r.width)
            .put("height", r.height)
            // ⚠ Written even when null, so a reader never has to guess whether
            // an absent key means "not a batch" or "an older file".
            .put("batchId", r.batchId ?: JSONObject.NULL)
            .put("batchLabel", r.batchLabel)
            // ⭐ The graph, as the same JSON a saved workflow uses — so
            // reopening a result is exactly reopening a workflow, with no
            // second format to keep in step.
            .put("flow", workflow.toJson(types))
            .toString()
        val tmpMeta = File(dir, "$id.json.tmp")
        tmpMeta.writeText(json)
        if (!tmpMeta.renameTo(meta(id))) {
            tmpMeta.copyTo(meta(id), overwrite = true); tmpMeta.delete()
        }
        return r
    }

    /**
     * ⭐⭐ Everything kept, with a sweep's items gathered into one entry.
     *
     * ⚠ Order is by the NEWEST item in each group, so a sweep that just
     * finished is at the top even though its earliest item is older than a
     * single render made in between.
     *
     * ⚠ A result with no `batchId` is its own group of one — so the list has a
     * single shape and the UI does not branch on "is this a batch" per row.
     */
    fun grouped(): List<ResultGroup> {
        val (batched, single) = all().partition { it.batchId != null }
        val groups = batched.groupBy { it.batchId }
            .map { (id, items) -> ResultGroup(id, items.sortedByDescending { it.savedAt }) }
        return (groups + single.map { ResultGroup(null, listOf(it)) })
            .sortedByDescending { it.items.first().savedAt }
    }

    /**
     * Everything kept, newest first.
     *
     * ⚠ A result whose metadata will not parse is SKIPPED rather than throwing.
     * One bad file must not empty the tab.
     */
    fun all(): List<Result> =
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".json") && !it.name.endsWith(".tmp") }
            .mapNotNull { f ->
                val id = f.name.removeSuffix(".json")
                // ⚠ Both halves or neither: a metadata file whose PNG is gone
                // would draw an empty card that cannot be opened.
                if (!png(id).isFile) return@mapNotNull null
                try {
                    val j = JSONObject(f.readText())
                    Result(
                        id = id,
                        savedAt = j.optLong("savedAt", f.lastModified()),
                        imageId = j.optString("imageId").takeIf { it.isNotBlank() && it != "null" },
                        seed = j.optString("seed").takeIf { it.isNotBlank() && it != "null" },
                        model = j.optString("model").takeIf { it.isNotBlank() && it != "null" },
                        prompt = j.optString("prompt").takeIf { it.isNotBlank() && it != "null" },
                        width = j.optInt("width"),
                        height = j.optInt("height"),
                        batchId = j.optString("batchId").takeIf { it.isNotBlank() && it != "null" },
                        batchLabel = j.optString("batchLabel"),
                    )
                } catch (e: Exception) {
                    null
                }
            }
            .sortedByDescending { it.savedAt }

    /** ⚠ Decoded at [maxEdge] so a list of 1024² PNGs is not a list of 4 MB bitmaps. */
    fun thumbnail(id: String, maxEdge: Int = 384): Bitmap? {
        val f = png(id)
        if (!f.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var sample = 1
        while (longest / sample > maxEdge) sample *= 2
        return BitmapFactory.decodeFile(
            f.path,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    /**
     * ⚠ The stored PNG's BYTES, for a gallery save.
     *
     * ⚠⚠ Not `full(id)` re-encoded: decoding a PNG to a bitmap and compressing
     * it again is two lossless passes for nothing, and it is the copy someone
     * keeps — handing MediaStore the file we already wrote is both faster and
     * exactly what they saw.
     */
    fun fullBytes(id: String): ByteArray? = png(id).takeIf { it.isFile }?.readBytes()

    fun full(id: String): Bitmap? = png(id).takeIf { it.isFile }?.let {
        BitmapFactory.decodeFile(it.path)
    }

    /** ⭐ The graph that made [id], ready to put back on the canvas. */
    fun flow(id: String): LoadedWorkflow? {
        val f = meta(id)
        if (!f.isFile) return null
        return try {
            workflowFromJson(JSONObject(f.readText()).getString("flow"))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * ⭐⭐ Everything that made this picture, as label/value pairs.
     *
     * ⚠ Read out of the STORED FLOW rather than from extra columns. The graph is
     * already kept verbatim, so the params are in it — and answering "is that
     * straightforward, given we use nodes?" with a second copy of the same
     * facts would be two things to keep in step for no gain.
     *
     * ⚠ Node types are matched, not node ids: a graph can name its sampler
     * anything, and a user's own saved flow usually does.
     */
    fun details(id: String): List<Pair<String, String>> {
        val g = flow(id)?.workflow?.graph ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        g.nodes.firstOrNull { it.type == "sd.clip_encode" }?.params?.let { p ->
            p["prompt"]?.takeIf { it.isNotBlank() }?.let { out += "prompt" to it }
            p["negative"]?.takeIf { it.isNotBlank() }?.let { out += "negative" to it }
        }
        g.nodes.firstOrNull { it.type == "sd.sample" }?.params?.let { p ->
            p["model"]?.let { out += "model" to it }
            val size = listOfNotNull(p["width"], p["height"]).joinToString("×")
            if (size.isNotBlank()) out += "size" to size
            p["steps"]?.let { out += "steps" to it }
            p["cfg"]?.let { out += "cfg" to it }
            p["scheduler"]?.let { out += "scheduler" to it }
            p["seed"]?.takeIf { it != "0" }?.let { out += "seed" to it }
            // ⚠ Only when a latent is wired: on txt2img it is not read, and
            // showing it would imply it did something.
            g.nodes.firstOrNull { it.type == "sd.sample" }
                ?.takeIf { it.inputs.containsKey("latent") }
                ?.let { p["denoise"]?.let { d -> out += "denoise" to d } }
        }
        out += "nodes" to g.nodes.size.toString()
        return out
    }

    fun delete(id: String) {
        png(id).delete()
        meta(id).delete()
    }

    /** Bytes on disk, for a line that tells the user what this is costing. */
    fun bytes(): Long =
        dir.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }
}
