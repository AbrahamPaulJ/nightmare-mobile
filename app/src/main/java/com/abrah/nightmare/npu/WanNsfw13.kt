package com.abrah.nightmare.npu

import com.abrah.nightmare.ModelCatalog
import com.abrah.nightmare.Node
import com.abrah.nightmare.NodeCtx
import com.abrah.nightmare.NodeType
import com.abrah.nightmare.Port
import com.abrah.nightmare.R
import com.abrah.nightmare.Value

/**
 * ⭐⭐ **NSFW Wan 1.3B** on [ModelCatalog.WAN21_NSFW13]: Wan's node ([WanVideoNode]: size, LoRAs, seed, seconds) on
 * NSFW-API's Wan 2.1 T2V 1.3B fine-tune. Its own type (as [WanA14Node]) so every "which model does this node need"
 * table names it ([WanVideoNode.specOf]); what differs in the run is [WanVideoNode.NSFW13] (16 fps, euler, CausVid's
 * steps, 16 px tokens) and the CausVid LoRA sent with every clip. ⚠ TEXT to video only: no picture port, and no
 * [WanParts] (each part after the first starts from a picture, which a T2V model does not take).
 */
object WanNsfw13Node : NodeType {

    override val appSide = false
    override val name = "wan13.sample"
    override val version = "1"
    override val inputs = listOf(Port("prompt", "PROMPT"))
    override val outputs get() = WanVideoNode.outputs
    override val category = "generate"
    override val paletteName = "NSFW Wan 1.3B"
    override fun titleFor(node: Node): String = "Text to video (NSFW 1.3B)"
    override val defaultId = "video"
    override val about = "a prompt into a clip with NSFW Wan 1.3B, a community fine-tune for adult content"
    override val showsResult = false

    // ⭐ Its own size and seconds advice (480p is its native size; the 5B's 704p advice is wrong here). ⚠ No framing
    // knobs (nothing to frame) and no parts.
    override val widgets get() = WanVideoNode.widgets.filterNot {
        it.name in WanParts.KNOBS || it.name in FRAMING
    }.map {
        when (it.name) {
            "seconds" -> it.copy(
                default = DEFAULT_SECONDS.toString(), max = WanVideoNode.NSFW13.maxSeconds.toDouble(),
                hintRes = R.string.hint_wan13_seconds,
            )
            "width" -> it.copy(hintRes = R.string.hint_wan13_size, step = WanVideoNode.NSFW13.grid)
            "height" -> it.copy(step = WanVideoNode.NSFW13.grid)
            else -> it
        }
    }

    private val FRAMING = setOf("x", "y", "w", "h")

    /** ⭐ Its frame size on its own grid and area ([WanVideoNode.NSFW13]). */
    override fun framesTo(node: Node): Pair<Int, Int> = WanVideoNode.sizeOf(effectiveParams(node), WanVideoNode.NSFW13)

    /** ⚠ Two seconds first: Wan 2.1's 8x VAE makes four times the 5B's tokens a frame (`docs/ROADMAP.md`). */
    const val DEFAULT_SECONDS = 2

    override fun contextKey(node: Node) = WanVideoNode.keyFor(node, ModelCatalog.WAN21_NSFW13)

    override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value =
        runPorts(ctx, node, inputs).getValue("video")

    override suspend fun runPorts(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Map<String, Value> =
        WanVideoNode.runClip(ctx, node, inputs, ModelCatalog.WAN21_NSFW13, null)
}
