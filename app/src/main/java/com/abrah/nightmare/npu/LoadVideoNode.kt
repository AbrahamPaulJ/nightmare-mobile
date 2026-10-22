package com.abrah.nightmare.npu

import android.media.MediaMetadataRetriever
import com.abrah.nightmare.ContextKey
import com.abrah.nightmare.Node
import com.abrah.nightmare.NodeCtx
import com.abrah.nightmare.NodeType
import com.abrah.nightmare.Port
import com.abrah.nightmare.Value
import com.abrah.nightmare.Widget
import java.io.File
import java.security.MessageDigest

/**
 * ⭐⭐ **The Video node**: a clip picked from the gallery or Files, the video sibling of the image node
 * (`core.image`). Its first job is Motion transfer's driving video ([WanFunNode]'s `control`), the user's
 * call 2026-10-15: "the video should belong in a video node, not an image one".
 *
 * ⚠ The clip is COPIED into the app's cache under a content address (the URI and its size), because a
 * [Value.Video] carries a file PATH that players and the frame reader open, and its file name is its
 * address downstream. A picked URI's grant is persisted by the picker, as the image node's is.
 */
object LoadVideoNode : NodeType {

    override val name = "core.video"
    override val defaultId = "clip"
    override val version = "1"
    override val inputs = emptyList<Port>()
    override val outputs = listOf(Port("video", "VIDEO"))
    override val category = "source"
    override val paletteName = "Video"
    override val about = "a video from the gallery or Files, for Motion transfer"
    override val cacheable = false
    override val widgets = listOf(
        // A content:// URI from the picker, or an absolute path.
        Widget("uri", "string", ""),
    )

    override fun contextKey(node: Node): ContextKey? = null

    override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val uri = node.params["uri"].orEmpty().trim()
            require(uri.isNotBlank()) { "node \"${node.id}\": no video chosen" }
            val platform = ctx.android
                ?: throw IllegalStateException("node \"${node.id}\": loading a video needs a platform context")
            val file = if (uri.startsWith("content://")) {
                val u = android.net.Uri.parse(uri)
                val size = runCatching {
                    platform.contentResolver.openAssetFileDescriptor(u, "r")?.use { it.length }
                }.getOrNull() ?: -1L
                val dir = File(platform.cacheDir, "videos_in").apply { mkdirs() }
                val f = File(dir, "in_${sha1("$uri\u0000$size")}.mp4")
                if (!f.isFile || f.length() == 0L) {
                    // ⚠⚠ Into `.part`, renamed when whole: the copy went straight to its content-addressed name, so a
                    // process killed mid-copy (lmkd, a 200 MB driver) left a truncated clip that `length() != 0`
                    // accepted for ever, and that URI always failed at frame N or read short.
                    val part = File(dir, f.name + ".part")
                    try {
                        platform.contentResolver.openInputStream(u)?.use { i -> part.outputStream().use { i.copyTo(it) } }
                            ?: throw IllegalArgumentException("nothing to read at $uri")
                        if (!part.renameTo(f)) throw java.io.IOException("could not move ${part.name} into place")
                    } catch (e: SecurityException) {
                        throw IllegalStateException("node \"${node.id}\": no longer permitted to read $uri. Pick the video again")
                    } finally {
                        part.delete()
                    }
                }
                f
            } else File(uri)
            if (!file.isFile) throw IllegalArgumentException("node \"${node.id}\": $uri is not there any more. Pick the video again")
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(file.absolutePath)
                val durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val poster = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: throw IllegalArgumentException("node \"${node.id}\": $uri is not a video this phone can read")
                // ⭐ Frames as Wan samples them (24 fps), so the node says what a clip can take from it.
                val frames = (durationMs * WanVideoNode.FPS / 1000L).toInt() + 1
                Value.Video(file.absolutePath, frames, poster.width, poster.height, ctx.images.put(poster))
            } finally {
                runCatching { r.release() }
            }
        }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
}
