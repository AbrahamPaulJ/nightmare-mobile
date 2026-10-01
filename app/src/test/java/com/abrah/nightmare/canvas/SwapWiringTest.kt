package com.abrah.nightmare.canvas

import com.abrah.nightmare.Graph
import com.abrah.nightmare.IpAdapter
import com.abrah.nightmare.Node
import com.abrah.nightmare.SdSampler
import com.abrah.nightmare.SwapInputs
import com.abrah.nightmare.sources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐⭐⭐ An SD 1.5 Swap node's ControlNet and IP-Adapter pictures are WIRED image
 * nodes (`SwapWiring.kt`, the user's call 2026-10-01) — each row of its table.
 */
class SwapWiringTest {

    private val swap = SdSampler.SD15_SWAP.name

    private fun state(i2i: Boolean, params: Map<String, String> = emptyMap()): CanvasState {
        val nodes = listOfNotNull(
            Node("prompt", "core.prompt"),
            if (i2i) Node("image", "core.image", params = mapOf("uri" to "content://photo")) else null,
            Node(
                "generate", swap,
                params = mapOf(SdSampler.CONTROLNET to SwapInputs.NONE, SdSampler.IP_ADAPTER to IpAdapter.NONE) + params,
                inputs = if (i2i) sources("prompt" to "prompt", "image" to "image") else sources("prompt" to "prompt"),
            ),
        )
        val pos = mapOf("prompt" to Pt(24f, 500f), "image" to Pt(24f, 880f), "generate" to Pt(500f, 700f))
        return CanvasState(Workflow(Graph(nodes), pos.filterKeys { k -> nodes.any { it.id == k } }))
    }

    /** What the inspector does: setParam, then the wiring. */
    private fun CanvasState.edit(name: String, value: String) =
        setParam("generate", name, value).swapWired("generate", name, value, this)

    private fun CanvasState.input(port: String) = workflow.graph.byId["generate"]!!.inputs[port]?.node

    @Test
    fun controlNetOnReusesTheImg2imgPhoto() {
        val s = state(i2i = true).edit(SdSampler.CONTROLNET, SwapInputs.CANNY)
        assertEquals("image", s.input(SdSampler.CONTROL))
        assertEquals(3, s.workflow.graph.nodes.size)
    }

    @Test
    fun controlNetOnWithNoPhotoAddsAnImageNode() {
        val s = state(i2i = false).edit(SdSampler.CONTROLNET, SwapInputs.CANNY)
        val id = s.input(SdSampler.CONTROL)!!
        assertEquals("core.image", s.workflow.graph.byId[id]!!.type)
        assertTrue("it must be placed on the canvas", id in s.workflow.positions)
        assertEquals("the inspector stays on the sampler", s.editing, state(false).editing)
    }

    /** ⚠ Replacing the control picture must NEVER replace the img2img photo. */
    @Test
    fun aPictureForAControlWiredToThePhotoGoesIntoANewNode() {
        val s = state(i2i = true).edit(SdSampler.CONTROLNET, SwapInputs.CANNY)
            .edit(SdSampler.CONTROL_IMAGE, "content://edges")
        val id = s.input(SdSampler.CONTROL)!!
        assertTrue(id != "image")
        assertEquals("content://edges", s.workflow.graph.byId[id]!!.params["uri"])
        assertEquals("content://photo", s.workflow.graph.byId["image"]!!.params["uri"])
        assertEquals("the picture lives in the node, not the param", "",
            s.workflow.graph.byId["generate"]!!.params[SdSampler.CONTROL_IMAGE])
    }

    @Test
    fun aSecondPictureReplacesTheOneInItsOwnNode() {
        val s = state(i2i = false).edit(SdSampler.CONTROLNET, SwapInputs.CANNY)
            .edit(SdSampler.CONTROL_IMAGE, "content://a")
            .edit(SdSampler.CONTROL_IMAGE, "content://b")
        val id = s.input(SdSampler.CONTROL)!!
        assertEquals("content://b", s.workflow.graph.byId[id]!!.params["uri"])
        assertEquals("no second node: prompt, sampler, control", 3, s.workflow.graph.nodes.size)
    }

    /**
     * ⭐ Off: the wire AND its image node go, picture or not (the user's call,
     * 2026-10-01 — the executor runs every node, and an empty one fails the run).
     */
    @Test
    fun switchingOffRemovesTheWireAndItsNode() {
        val empty = state(i2i = false).edit(SdSampler.CONTROLNET, SwapInputs.CANNY)
            .edit(SdSampler.CONTROLNET, SwapInputs.NONE)
        assertNull(empty.input(SdSampler.CONTROL))
        assertEquals(2, empty.workflow.graph.nodes.size)

        val filled = state(i2i = false).edit(SdSampler.CONTROLNET, SwapInputs.CANNY)
            .edit(SdSampler.CONTROL_IMAGE, "content://a")
            .edit(SdSampler.CONTROLNET, SwapInputs.NONE)
        assertNull(filled.input(SdSampler.CONTROL))
        assertEquals(2, filled.workflow.graph.nodes.size)
    }

    /** ⚠ …but never the img2img photo, which the sampler still reads. */
    @Test
    fun switchingOffNeverRemovesThePhoto() {
        val s = state(i2i = true).edit(SdSampler.CONTROLNET, SwapInputs.CANNY)
            .edit(SdSampler.CONTROLNET, SwapInputs.NONE)
        assertEquals("image", s.workflow.graph.byId["generate"]!!.inputs["image"]?.node)
        assertEquals(3, s.workflow.graph.nodes.size)
    }

    @Test
    fun ipAdapterOnAlwaysAddsItsOwnNodeEvenInImg2img() {
        val s = state(i2i = true).edit(SdSampler.IP_ADAPTER, IpAdapter.PLUS)
        val id = s.input("reference")!!
        assertTrue(id != "image")
        val off = s.edit(SdSampler.IP_ADAPTER, IpAdapter.NONE)
        assertNull(off.input("reference"))
        assertNull("the reference node goes with it", off.workflow.graph.byId[id])
        // ⚠ …picture or not.
        val picked = s.edit(SdSampler.IP_IMAGE, "content://face").edit(SdSampler.IP_ADAPTER, IpAdapter.NONE)
        assertEquals(3, picked.workflow.graph.nodes.size)
    }

    /** ⭐ Remove empties the picture and KEEPS node and wire, so the next pick goes back in. */
    @Test
    fun removeThenPickReusesTheSameNode() {
        val s = state(i2i = false).edit(SdSampler.IP_ADAPTER, IpAdapter.PLUS)
            .edit(SdSampler.IP_IMAGE, "content://face")
        val id = s.input("reference")!!
        val removed = s.edit(SdSampler.IP_IMAGE, "")
        assertEquals(id, removed.input("reference"))
        assertEquals("", removed.workflow.graph.byId[id]!!.params["uri"])
        val again = removed.edit(SdSampler.IP_IMAGE, "content://other")
        assertEquals(id, again.input("reference"))
        assertEquals("content://other", again.workflow.graph.byId[id]!!.params["uri"])
        assertEquals("no extra node", s.workflow.graph.nodes.size, again.workflow.graph.nodes.size)
    }

    /**
     * ⚠⚠ One image node wired into BOTH `control` and `reference` (reported
     * 2026-10-01): a new control picture must not change the IP-Adapter's.
     */
    @Test
    fun aNodeSharedByControlAndReferenceIsNeverOverwritten() {
        val base = state(i2i = false).let { st ->
            val g = st.workflow.graph.copy(nodes = st.workflow.graph.nodes + Node("pic", "core.image", params = mapOf("uri" to "content://both")))
                .connected("generate", SdSampler.CONTROL, "pic").connected("generate", "reference", "pic")
                .withParams("generate", mapOf(SdSampler.CONTROLNET to SwapInputs.CANNY, SdSampler.IP_ADAPTER to IpAdapter.PLUS))
            st.copy(workflow = st.workflow.copy(graph = g, positions = st.workflow.positions + ("pic" to Pt(24f, 1200f))))
        }
        val s = base.edit(SdSampler.CONTROL_IMAGE, "content://edges")
        assertEquals("pic", s.input("reference"))
        assertEquals("content://both", s.workflow.graph.byId["pic"]!!.params["uri"])
        val ctl = s.input(SdSampler.CONTROL)!!
        assertTrue(ctl != "pic")
        assertEquals("content://edges", s.workflow.graph.byId[ctl]!!.params["uri"])
        // ⚠ …and Remove on the shared one only unwires it from this port.
        val removed = base.edit(SdSampler.CONTROL_IMAGE, "")
        assertNull(removed.input(SdSampler.CONTROL))
        assertEquals("content://both", removed.workflow.graph.byId["pic"]!!.params["uri"])
    }

    /**
     * ⭐ A wire drawn on the CANVAS into `reference` / `control` switches that
     * tool on (reported 2026-10-01: IP-Adapter stayed at none). ⚠ A tool
     * already on keeps its choice.
     */
    @Test
    fun aWireDrawnOnTheCanvasSwitchesTheToolOn() {
        val g = state(i2i = true).workflow.graph
        val ip = g.connected("generate", "reference", "image").switchingOnFor("generate", "reference")
        assertEquals(IpAdapter.PLUS, ip.byId["generate"]!!.params[SdSampler.IP_ADAPTER])
        val cn = g.connected("generate", SdSampler.CONTROL, "image").switchingOnFor("generate", SdSampler.CONTROL)
        assertEquals(SwapInputs.CANNY, cn.byId["generate"]!!.params[SdSampler.CONTROLNET])
        val face = g.withParam("generate", SdSampler.IP_ADAPTER, IpAdapter.FACE)
            .connected("generate", "reference", "image").switchingOnFor("generate", "reference")
        assertEquals(IpAdapter.FACE, face.byId["generate"]!!.params[SdSampler.IP_ADAPTER])
    }

    /** ⚠ Not a Swap node: nothing happens. */
    @Test
    fun otherSamplersAreLeftAlone() {
        val g = Graph(listOf(Node("generate", SdSampler.SD15.name)))
        val s = CanvasState(Workflow(g, emptyMap()))
        val out = s.setParam("generate", SdSampler.CONTROLNET, SwapInputs.CANNY)
            .swapWired("generate", SdSampler.CONTROLNET, SwapInputs.CANNY, s)
        assertEquals(1, out.workflow.graph.nodes.size)
    }

    /** ⭐ The two Advanced flows open with all three switched off. */
    @Test
    fun theAdvancedFlowsOpenWithEverythingOff() {
        for (i2i in listOf(false, true)) {
            val gen = swapWorkflow(i2i).graph.byId["generate"]!!
            assertEquals(swap, gen.type)
            assertEquals(SwapInputs.NONE, gen.params[SdSampler.CONTROLNET])
            assertEquals(IpAdapter.NONE, gen.params[SdSampler.IP_ADAPTER])
            assertEquals(i2i, gen.inputs["image"] != null)
        }
    }
}
