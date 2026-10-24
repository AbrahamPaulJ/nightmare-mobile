package com.abrah.nightmare.npu

import com.abrah.nightmare.ModelCatalog
import com.abrah.nightmare.Node
import com.abrah.nightmare.NodeCtx
import com.abrah.nightmare.NodeType
import com.abrah.nightmare.Port
import com.abrah.nightmare.Value

/**
 * ⭐⭐ **Wan 2.2 14B** on [ModelCatalog.WAN22_A14B]: Wan's node ([WanVideoNode], same size, LoRAs, seed and
 * seconds) on the big model, text to video only. Its own type (as [WanFunNode]) so every "which model does this
 * node need" table names the 14B; what differs in the run is [WanVideoNode.A14B] (16 fps, euler, 4x the tokens of
 * the 5B at a size, always streamed). The engine loads the high-noise expert and Lightning's two halves from the
 * launch environment (`BackendProcess.wanA14Env`, engine `dit/044`). `docs/VIDEO-GGML.md` §8.
 */
object WanA14Node : NodeType {

    override val appSide = false
    override val name = "wan14.sample"
    override val version = "1"
    // ⚠ No `image`: this is the T2V model (an I2V A14B is its own pair of experts).
    override val inputs = listOf(Port("prompt", "PROMPT"))
    override val outputs get() = WanVideoNode.outputs
    override val category = "generate"
    override val paletteName = "Wan 14B video"
    override fun titleFor(node: Node): String = "Text to video (14B)"
    override val defaultId = "video"
    override val about = "a prompt into a clip with Wan 2.2 14B, the bigger and slower Wan"
    override val showsResult = false

    // ⚠ The framing knobs only crop a wired picture, and this node takes none.
    override val widgets get() = WanVideoNode.widgets.filterNot { it.name in setOf("x", "y", "w", "h") }.map {
        if (it.name == "seconds") it.copy(default = DEFAULT_SECONDS.toString()) else it
    }

    /** ⚠ A short clip first: the 14B costs minutes a second (`docs/VIDEO-GGML.md` §8). */
    const val DEFAULT_SECONDS = 2

    override fun contextKey(node: Node) = WanVideoNode.keyFor(node, ModelCatalog.WAN22_A14B)

    override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value =
        runPorts(ctx, node, inputs).getValue("video")

    override suspend fun runPorts(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Map<String, Value> =
        WanVideoNode.runClip(ctx, node, inputs, ModelCatalog.WAN22_A14B, null)
}
