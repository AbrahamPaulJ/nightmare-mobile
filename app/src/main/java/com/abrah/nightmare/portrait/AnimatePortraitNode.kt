package com.abrah.nightmare.portrait

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.abrah.nightmare.ContextKey
import com.abrah.nightmare.NeedsDownload
import com.abrah.nightmare.Node
import com.abrah.nightmare.NodeCtx
import com.abrah.nightmare.NodeType
import com.abrah.nightmare.Port
import com.abrah.nightmare.Value
import com.abrah.nightmare.Widget
import com.abrah.nightmare.npu.DriverFrames
import com.abrah.nightmare.npu.VideoWriter
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ⭐⭐ Animate a portrait: the photo's face moved the way the driving video's face moves, frame by frame (LivePortrait's
 * own use; facefusion-mobile's face animator, `PortraitVideo.kt`). The user's call, 2026-10-21: an "Animate a portrait"
 * flow, built on Motion transfer's node set (a Video node wired in).
 *
 * ⚠ Relative motion: the photo starts in its own pose and follows the driver's CHANGES from its first frame
 * (`ffportrait::animateKeypoints`). A driver frame with no face leaves the photo as it is.
 * ⚠ At [FPS] at most, frames read with [MediaMetadataRetriever] like Motion transfer's, written one at a time by
 * [VideoWriter] (a long clip never holds its frames as bitmaps).
 */
object AnimatePortraitNode : NodeType {
    override val name = "face.animate"
    override val version = "1"
    override val inputs = listOf(Port("image", "IMAGE"), Port(DRIVER, "VIDEO"))
    override val outputs = listOf(Port("video", "VIDEO"))
    override val category = "video"
    override val paletteName = "Animate portrait"
    override val defaultId = "animate"
    override val about = "a photo's face moved like the face in a video you choose (LivePortrait)"
    override val cacheable = true

    const val DRIVER = "driver"
    const val SECONDS = "seconds"

    /** FF's cap: a driver at 30 or 60 fps is sampled at 25. */
    const val FPS = 25

    /**
     * ⚠⚠ The driver is decoded IN ORDER, [BATCH] frames at a time ([DriverFrames], FilmJoin's reader), never by
     * seeking each frame. On the S25 (2026-10-21) the exact seek (`getFrameAtTime` CLOSEST) failed at frame 14 of a
     * 2 s clip, and falling back to the nearest keyframe left 2 or 3 moves in the whole clip.
     */
    private const val BATCH = 8

    /** ⭐ The clip's long side at most: within every phone AVC encoder's range (Wan caps by area). */
    private const val MAX_EDGE = 1280

    override val widgets = listOf(Widget(SECONDS, "int", "10", 1.0, 30.0))

    override fun contextKey(node: Node): ContextKey? = null

    override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value {
        val photo = inputs["image"] as? Value.Image
            ?: throw IllegalArgumentException("node \"${node.id}\": \"image\" is not connected. Wire the portrait to animate")
        val driver = inputs[DRIVER] as? Value.Video
            ?: throw IllegalArgumentException("node \"${node.id}\": \"$DRIVER\" is not connected. Wire a Video node with a face in it")
        val app = ctx.android ?: throw IllegalStateException("animating a portrait needs the app")
        if (!LivePortrait.isInstalled(app)) {
            throw NeedsDownload(NeedsDownload.LIVEPORTRAIT, "node \"${node.id}\": download ${LivePortrait.LABEL} first. In Models, Faces")
        }
        val src = ctx.images.get(photo.id)
            ?: throw IllegalStateException("node \"${node.id}\": the photo is no longer held. Run again")
        // ⚠⚠ Capped at [MAX_EDGE]: a 4080x3060 camera photo is past what phone AVC encoders take (it
        // failed at `configure`), and every frame allocated ~48 MB at that size twice over.
        val k = minOf(1f, MAX_EDGE.toFloat() / maxOf(src.width, src.height))
        val sized = if (k < 1f) {
            Bitmap.createScaledBitmap(src, Math.round(src.width * k).coerceAtLeast(2), Math.round(src.height * k).coerceAtLeast(2), true)
        } else src
        // ⚠ H.264 wants even sides ([VideoWriter.encode]).
        val w = sized.width and 1.inv()
        val h = sized.height and 1.inv()
        val portrait = if (w == sized.width && h == sized.height) sized else Bitmap.createBitmap(sized, 0, 0, w, h)
        val px = IntArray(w * h).also {
            val argb = portrait.copy(Bitmap.Config.ARGB_8888, false)
            argb.getPixels(it, 0, w, 0, 0, w, h)
            argb.recycle()
        }
        if (portrait !== src) portrait.recycle()
        if (sized !== src && sized !== portrait) sized.recycle()
        val seconds = node.params[SECONDS]?.toIntOrNull()?.coerceIn(1, 30) ?: 10

        return withContext(Dispatchers.IO) {
            LivePortrait.open(app)
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(driver.path)
                val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val count = (minOf(durMs, seconds * 1000L) * FPS / 1000L).toInt()
                val frames = DriverFrames(r, durMs, BATCH)
                if (count < 2) throw IllegalArgumentException("node \"${node.id}\": the driving video is too short")
                val key = sha1("${photo.id}\u0000${driver.path}\u0000$seconds")
                val out = File(File(app.cacheDir, "video").apply { mkdirs() }, "portrait_$key.mp4")
                // ⭐ The canvas loop: a small copy of every frame ([com.abrah.nightmare.ClipStore] thins them).
                val loop = ArrayList<Bitmap>()
                val loopScale = 320f / maxOf(w, h)
                var first: Bitmap? = null
                // ⚠ The previous frame, recycled once the encoder has drawn it (the next ask): over
                // 250 frames none was, and a long clip piled native bitmaps up beside LivePortrait.
                var prev: Bitmap? = null
                val t0 = System.nanoTime()
                VideoWriter.encode(
                    count, w, h, FPS, out,
                    log = { line -> android.util.Log.i("AnimatePortrait", line) },
                    isCancelled = VideoWriter.cancelledWith(coroutineContext[kotlinx.coroutines.Job]),
                ) { i ->
                    prev?.takeIf { it !== first }?.recycle()
                    prev = null
                    // ⚠ Frame 0 can be asked for twice (the encoder's software retry): start the motion over.
                    if (i == 0) {
                        loop.clear()
                        first = null
                        if (!NativePortrait.setPortrait(px, w, h)) {
                            throw IllegalArgumentException("node \"${node.id}\": ${NativePortrait.lastError()}")
                        }
                    }
                    val frame = frames.at(i * 1000L / FPS)
                        ?: throw IllegalStateException("node \"${node.id}\": could not read frame ${i + 1} of the driving video")
                    val dw = frame.width
                    val dh = frame.height
                    val dpx = IntArray(dw * dh).also {
                        val argb = frame.copy(Bitmap.Config.ARGB_8888, false)
                        argb.getPixels(it, 0, dw, 0, 0, dw, dh)
                        argb.recycle()
                    }
                    val moved = NativePortrait.animate(dpx, dw, dh)
                        ?: throw IllegalStateException("node \"${node.id}\": ${NativePortrait.lastError()}")
                    val bmp = Bitmap.createBitmap(moved, w, h, Bitmap.Config.ARGB_8888)
                    if (i == 0) first = bmp
                    loop += Bitmap.createScaledBitmap(bmp, (w * loopScale).toInt().coerceAtLeast(2), (h * loopScale).toInt().coerceAtLeast(2), true)
                    ctx.onProgress(com.abrah.nightmare.Ops.Progress(((i + 1) * 100 / count).coerceIn(0, 100), 100, 0L))
                    prev = bmp
                    bmp
                }
                ctx.say("$count frames in ${"%.1f".format((System.nanoTime() - t0) / 1e9)} s · ${frames.describe()}")
                com.abrah.nightmare.ClipStore.put(out.absolutePath, loop)
                // ⭐ The first animated frame, full size, is the poster (Save, Share, Keep, Results act on it).
                val poster = first ?: loop.first()
                val posterId = ctx.images.put(poster, id = VideoWriter.posterIdFor(out))
                Value.Video(out.absolutePath, count, w, h, posterId)
            } finally {
                r.release()
            }
        }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
}
