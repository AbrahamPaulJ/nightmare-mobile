package com.abrah.nightmare.portrait

import com.abrah.nightmare.ContextKey
import com.abrah.nightmare.NeedsDownload
import com.abrah.nightmare.Node
import com.abrah.nightmare.NodeCtx
import com.abrah.nightmare.NodeType
import com.abrah.nightmare.Port
import com.abrah.nightmare.Value
import com.abrah.nightmare.Widget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ⭐⭐ Face edit: LivePortrait's 14 sliders on the faces of a picture ([LivePortrait], `nmportrait.cpp`).
 *
 * ⚠ The user's call, 2026-10-21: a node (wired after any picture), an "Edit a face" flow, the same edit on Results,
 * and its own viewer like crop and mask. It earns its node (`docs/NODES.md` §5.7): its output is reused and looked at.
 * ⚠ In-process on the NPU, no backend and no context key, like the video nodes' runner: it joins any graph freely.
 */
object FaceEditNode : NodeType {
    override val name = "face.edit"
    override val version = "1"
    override val inputs = listOf(Port("image", "IMAGE"))
    override val outputs = listOf(Port("image", "IMAGE"))
    override val category = "image"
    override val paletteName = "Face edit"
    override val defaultId = "face"
    override val about = "smile, eyes, gaze, mouth and head turn on the faces in a picture (LivePortrait)"

    /**
     * ⚠ RETIRED (the user, 2026-10-21: every face, no switch). Kept as a name so a saved flow holding it loads; the
     * node and the viewer ignore it and edit every face found.
     */
    const val LARGEST_ONLY = "largest_only"

    override val widgets: List<Widget> = LivePortrait.SLIDERS.map { Widget(it, "float", "0.0", -1.0, 1.0) }

    override fun contextKey(node: Node): ContextKey? = null

    /** The 14 slider values of [node], native order. */
    fun slidersOf(node: Node): FloatArray =
        FloatArray(LivePortrait.SLIDERS.size) { i -> node.params[LivePortrait.SLIDERS[i]]?.toFloatOrNull()?.coerceIn(-1f, 1f) ?: 0f }

    override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value {
        val src = inputs["image"] as? Value.Image
            ?: throw IllegalArgumentException("node \"${node.id}\": \"image\" is not connected. Wire the picture whose face to edit")
        val sliders = slidersOf(node)
        // ⚠ Nothing moved: the picture passes through untouched, with no download asked for.
        if (sliders.all { it == 0f }) return src
        val android = ctx.android ?: throw IllegalStateException("face edit needs the app")
        if (!LivePortrait.isInstalled(android)) {
            throw NeedsDownload(NeedsDownload.LIVEPORTRAIT, "node \"${node.id}\": download ${LivePortrait.LABEL} first. In Models, Faces")
        }
        val bmp = ctx.images.get(src.id)
            ?: throw IllegalStateException("node \"${node.id}\": the picture is no longer held. Run again")
        val out = withContext(Dispatchers.IO) { edit(android, bmp, sliders, largestOnly = false) }
            ?: throw IllegalArgumentException("node \"${node.id}\": no face found in the picture")
        return Value.Image(ctx.images.put(out), out.width, out.height)
    }

    /**
     * ⭐ The edit itself, shared by the node, its viewer and Results. Null when no face was found; throws on a failure.
     * ⚠ Blocking (~250 ms on an 8 Elite, plus ~0.8 s the first time while the five graphs load).
     */
    fun edit(context: android.content.Context, bmp: android.graphics.Bitmap, sliders: FloatArray, largestOnly: Boolean): android.graphics.Bitmap? {
        LivePortrait.open(context)
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h).also { bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false).getPixels(it, 0, w, 0, 0, w, h) }
        val out = NativePortrait.edit(px, w, h, sliders, largestOnly)
        if (out == null) {
            val why = NativePortrait.lastError()
            if (why == "no face found") return null
            throw IllegalStateException("face edit: $why")
        }
        return android.graphics.Bitmap.createBitmap(out, w, h, android.graphics.Bitmap.Config.ARGB_8888)
    }
}
