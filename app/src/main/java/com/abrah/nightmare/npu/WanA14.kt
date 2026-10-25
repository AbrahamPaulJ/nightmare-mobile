package com.abrah.nightmare.npu

import com.abrah.nightmare.ModelCatalog
import com.abrah.nightmare.Node
import com.abrah.nightmare.NodeCtx
import com.abrah.nightmare.NodeType
import com.abrah.nightmare.Port
import com.abrah.nightmare.R
import com.abrah.nightmare.Value

/**
 * ⭐⭐ **Wan 2.2 14B** on [ModelCatalog.WAN22_A14B]: Wan's node ([WanVideoNode], same size, LoRAs, seed and
 * seconds) on the big model. Its own type (as [WanFunNode]) so every "which model does this node need" table names
 * the 14B ([WanVideoNode.specOf]); what differs in the run is [WanVideoNode.A14B] (16 fps, euler, 4x the tokens of
 * the 5B at a size, always streamed). The engine loads the high-noise expert and Lightning's two halves from the
 * launch environment (`BackendProcess.wanA14Env`, engine `dit/044`). `docs/VIDEO-GGML.md` §8.
 * ⭐⭐ Since 1.6.240 it runs ONLY the I2V experts ([ModelCatalog.WAN22_A14B_I2V]) and needs a picture: one 14B
 * download, not two (the user's call, 2026-10-25). Text to video is the `canvas.T2V14` flow, a picture model making
 * the first frame. [ModelCatalog.WAN22_A14B] stays defined (the engine's T2V path) but is not offered.
 */
object WanA14Node : NodeType {

    override val appSide = false
    override val name = "wan14.sample"
    override val version = "1"
    override val inputs = listOf(Port("prompt", "PROMPT"), Port("image", "IMAGE"))
    override val outputs get() = WanVideoNode.outputs
    override val category = "generate"
    override val paletteName = "Wan 14B video"
    override fun titleFor(node: Node): String = "Image to video (14B)"
    override val defaultId = "video"
    override val about = "a picture and a prompt into a clip with Wan 2.2 14B, the bigger and slower Wan"
    override val showsResult = false

    // ⭐ Its own size advice under the picker: the 5B's (made for 1280x704, 832x480 "three times faster") is wrong here.
    override val widgets get() = WanVideoNode.widgets.map {
        when (it.name) {
            "seconds" -> it.copy(default = DEFAULT_SECONDS.toString())
            "width" -> it.copy(hintRes = R.string.hint_wan14_size, step = WanVideoNode.A14B.grid)
            "height" -> it.copy(step = WanVideoNode.A14B.grid)
            else -> it
        }
    }

    /** ⭐ Its frame size on its own grid and area (`WanVideoNode.A14B`): the crop editor reads it. */
    override fun framesTo(node: Node): Pair<Int, Int> = WanVideoNode.sizeOf(effectiveParams(node), WanVideoNode.A14B)

    /** ⚠ A short clip first: the 14B costs minutes a second (`docs/VIDEO-GGML.md` §8). */
    const val DEFAULT_SECONDS = 2

    override fun contextKey(node: Node) = WanVideoNode.keyFor(node, specFor(node))

    /** ⭐ Always the I2V experts ([ModelCatalog.WAN22_A14B_I2V]): the one 14B download. */
    @Suppress("UNUSED_PARAMETER")
    fun specFor(node: Node) = ModelCatalog.WAN22_A14B_I2V

    /** ⚠ Said when no picture is wired: the 14B animates a picture, it does not start from text alone. */
    const val NEEDS_PICTURE = "Wan 14B animates a picture. Wire one in, or open the Text to video (14B) flow: it " +
        "makes the first frame with your picture model"

    override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value =
        runPorts(ctx, node, inputs).getValue("video")

    override suspend fun runPorts(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Map<String, Value> {
        require(node.inputs["image"] != null) { "node \"${node.id}\": $NEEDS_PICTURE" }
        return WanVideoNode.runClip(ctx, node, inputs, specFor(node), null)
    }
}
