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
    /**
     * ⭐⭐ The kept CLIP, when this result came from a video graph — and null
     * for every picture.
     *
     * ⚠⚠ A COPY in the results directory, not the path the graph produced.
     * That one is in `cacheDir/video/`, which Android clears whenever it wants
     * to, so a starred clip would play until the first time the phone got
     * short of space and then silently be a still. The PNG beside it is kept
     * for exactly the same reason.
     *
     * ⚠ The PNG is still written and is still the poster: the card in the tab
     * is a grid of thumbnails, and decoding a frame out of an MP4 to draw one
     * would be work for a picture that already exists.
     */
    val videoPath: String? = null,
    /**
     * ⭐⭐ Starred — the Favourites filter in the Results tab.
     *
     * ⚠⚠ **Separate from being kept, and that is the whole point of the icon
     * change of 2026-09-15.** The disk keeps a picture; the star keeps it AND
     * flags it. Before this the star WAS the keep, so "in Results" and
     * "favourite" were one fact and there was nothing to filter by.
     *
     * ⚠ Defaults false, so every result kept before this field existed reads
     * back as an ordinary one rather than as a favourite.
     */
    val favourite: Boolean = false,
    /**
     * ⭐ Kept by the output node's AUTOSAVE rather than by a person. ⚠ Only these
     * are deleted when the picture is Cleared from the node (the user's call,
     * 2026-09-17) — something kept or starred by hand was a decision, and a
     * Clear on the canvas must not undo it. Defaults false for older files.
     */
    val auto: Boolean = false,
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

    private companion object {
        /** ⚠ Process-wide, not per instance: two stores may share one directory. See [keep]. */
        val KEEP_LOCK = Any()
    }

    private fun png(id: String) = File(dir, "$id.png")
    private fun meta(id: String) = File(dir, "$id.json")

    /** ⚠ See [Result.videoPath] — absent for every picture result. */
    private fun mp4(id: String) = File(dir, "$id.mp4")

    fun imageFile(id: String): File = png(id)

    /**
     * ⭐ The kept CLIP, or null when this result is an ordinary picture.
     *
     * ⚠⚠ One `stat`, and the same answer [all] derives. Reading it out of
     * [all] instead costs a directory scan and a JSON parse per result, which
     * a caller in a loop over a selection pays once per item — and the file is
     * the fact here, exactly as [Result.videoPath] says.
     */
    fun clipFile(id: String): File? = mp4(id).takeIf { it.isFile }

    /**
     * ⭐⭐ Star or un-star a result that is already kept.
     *
     * ⚠⚠ It rewrites the metadata in place rather than deleting and re-keeping:
     * the PNG, the clip and the FLOW are the expensive parts and none of them
     * changes. ⚠ Returns the new state so a caller can draw the star without
     * re-listing the whole directory.
     *
     * ⚠ Silently false for an id that is not kept — starring something that is
     * not there is not an error, it is a no-op the UI can ignore.
     */
    fun setFavourite(id: String, on: Boolean): Boolean {
        val f = meta(id)
        if (!f.isFile) return false
        return try {
            val j = org.json.JSONObject(f.readText()).put("favourite", on)
            val tmp = File(dir, "$id.json.tmp")
            tmp.writeText(j.toString())
            if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
            on
        } catch (e: Exception) {
            false
        }
    }

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
        /**
         * ⭐ The MP4 this result was made from, copied in beside the PNG.
         *
         * ⚠ ~1-2 MB for a 2 s clip against ~2.6 MB for the picture beside it,
         * so keeping it costs about what keeping the still already did. ⚠ A
         * copy that FAILS does not fail the keep: a result with a poster and a
         * flow is still worth having, and a clip is the one part of it the
         * gallery may already hold.
         */
        video: File? = null,
        /** ⭐ True when the STAR kept this rather than the disk. */
        favourite: Boolean = false,
        /** ⭐ True when AUTOSAVE kept it. See [Result.auto]. */
        auto: Boolean = false,
    ): Result {
        synchronized(KEEP_LOCK) {
            dir.mkdirs()
            // ⚠⚠⚠ **A millisecond is not unique, and a batch keeps in a tight loop.**
            // `"r" + currentTimeMillis()` alone collided whenever two results were
            // kept inside the same millisecond: the second silently overwrote the
            // first's PNG and metadata, and `clipFile` then handed a picture the
            // other result's MP4. Found 2026-09-15 by a test that had been green for
            // days — timing, not logic, decided whether it failed.
            //
            // ⚠ A suffix rather than nanoTime: the id is a FILE NAME and it sorts,
            // so it has to stay readable and monotonic. The loop is bounded by how
            // many results share one millisecond, which is single digits.
            //
            // ⚠⚠⚠ **And the suffix only worked for keeps that run one after
            // another.** Autosave keeps every output node of a Run, each on its
            // own IO coroutine, so a flow with two outputs kept twice AT ONCE:
            // both saw no `r<ms>.png` yet (each was still writing its `.tmp`),
            // both chose the same id and compressed into the SAME
            // `r<ms>.png.tmp`. The first rename filed a PNG of interleaved bytes
            // that Skia refuses ("bad adaptive filter value") — a blank card —
            // and the second found its tmp gone and lost its picture. Reported
            // from a phone 2026-09-19 as "Results does not show the image",
            // only ever with two output nodes.
            //
            // ⇒ Two guards: [KEEP_LOCK] serialises every keep in the process,
            // and `createNewFile` CLAIMS the id atomically rather than asking
            // whether it is free, so a second writer can never share a tmp
            // name even if one ever bypasses the lock. ⚠ The claimed PNG is
            // empty until the rename replaces it, which [all] never lists: a
            // result is listed by its metadata, written last.
            var id = "r" + System.currentTimeMillis()
            var n = 1
            while (meta(id).exists() || !png(id).createNewFile()) {
                id = "r" + System.currentTimeMillis() + "_" + n++
            }
            val tmpPng = File(dir, "$id.png.tmp")
            tmpPng.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmpPng.renameTo(png(id))) {
                tmpPng.copyTo(png(id), overwrite = true); tmpPng.delete()
            }

            if (video != null && video.isFile) {
                runCatching { video.copyTo(mp4(id), overwrite = true) }
            }
            val r = Result(
                id, System.currentTimeMillis(), imageId, seed, model, prompt,
                bitmap.width, bitmap.height, batchId, batchLabel,
                videoPath = mp4(id).takeIf { it.isFile }?.path,
                favourite = favourite,
                auto = auto,
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
                .put("favourite", r.favourite)
                .put("auto", r.auto)
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
                        favourite = j.optBoolean("favourite", false),
                        auto = j.optBoolean("auto", false),
                        // ⚠ Read off the DISK rather than out of the metadata:
                        // the file is the fact, and a `hasVideo` flag in the
                        // JSON could outlive the clip it names.
                        videoPath = mp4(id).takeIf { it.isFile }?.path,
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

    /**
     * ⭐ The kept PNG as a FILE, for a caller that can stream it.
     *
     * ⚠⚠ [fullBytes] reads the whole picture into the heap, which is ~40 MB
     * for a 4096² upscale — the same defect as `ImageStore.png()`, in the
     * other direction. A save or a share only ever copies these bytes, so
     * nothing needs them in memory. [com.abrah.nightmare.ImageSaver.saveBitmap].
     */
    fun pngFile(id: String): File? = png(id).takeIf { it.isFile }

    /**
     * ⚠⚠ Decoded at [maxEdge], same shape as [thumbnail] — NOT the raw file
     * resolution. This is what the swipeable viewer shows, called inline from
     * composition ([HarnessViewModel.resultImage]), and an upscaled result can
     * reach 6144 px on its long edge: decoding that at native size is a
     * multi-hundred-ms main-thread stall per picture, which is exactly what
     * made the Results tab "very laggy" on a swipe. 2048 is comfortably above
     * this phone's screen resolution even zoomed in, and [pngFile]/[fullBytes]
     * still hand out the untouched file for a save or a share, so nothing
     * downstream of THIS picture loses resolution.
     */
    fun full(id: String, maxEdge: Int = 2048): Bitmap? {
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
        g.nodes.firstOrNull { it.type in com.abrah.nightmare.IMAGE_SAMPLER_TYPES }?.params?.let { p ->
            p["model"]?.let { out += "model" to it }
            val size = listOfNotNull(p["width"], p["height"]).joinToString("x")
            if (size.isNotBlank()) out += "size" to size
            p["steps"]?.let { out += "steps" to it }
            p["cfg"]?.let { out += "cfg" to it }
            p["scheduler"]?.let { out += "scheduler" to it }
            p["seed"]?.takeIf { it != "0" }?.let { out += "seed" to it }
            // ⚠ Only when a latent is wired: on txt2img it is not read, and
            // showing it would imply it did something.
            g.nodes.firstOrNull { it.type in com.abrah.nightmare.IMAGE_SAMPLER_TYPES }
                ?.takeIf { it.inputs.containsKey("latent") }
                ?.let { p["denoise"]?.let { d -> out += "denoise" to d } }
        }
        // ⭐ …and the video recipe, which keeps its prompt on the sampler and
        // names no checkpoint at all. ⚠ Listed here rather than in a second
        // details function: a kept clip is a kept result like any other.
        g.nodes.firstOrNull { it.type == "nd.clip_encode" }?.params?.let { p ->
            p["prompt"]?.takeIf { it.isNotBlank() }?.let { out += "prompt" to it }
            out += "size" to if (p["upscale"].equals("false", true)) "512x320" else "1024x640"
            out += "frames" to "49"
            p["seed"]?.takeIf { it != "0" }?.let { out += "seed" to it }
        }
        out += "nodes" to g.nodes.size.toString()
        return out
    }

    fun delete(id: String) {
        png(id).delete()
        meta(id).delete()
        // ⚠ The clip too, or un-starring a video leaves the biggest half of it
        // on disk with nothing left pointing at it.
        mp4(id).delete()
    }

    /** Bytes on disk, for a line that tells the user what this is costing. */
    fun bytes(): Long =
        dir.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }
}
