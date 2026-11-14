package com.abrah.nightmare.canvas

import com.abrah.nightmare.Graph
import com.abrah.nightmare.NODE_TYPES
import com.abrah.nightmare.Node
import com.abrah.nightmare.SdSampler
import com.abrah.nightmare.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ⭐ A DiT edit's references 2 to 4 (2026-10-21): ports that grow with their wires, and the Default view's row. */
class ReferencePortsTest {

    private val edit = SdSampler.FLUX2

    private fun state(vararg wired: String): CanvasState {
        val sampler = Node("edit", edit.name, inputs = wired.associateWith { Source("p_$it") })
        val pics = wired.map { Node("p_$it", "core.image", params = mapOf("uri" to "/$it.png")) }
        return CanvasState(workflow = Workflow(Graph(listOf(sampler) + pics), mapOf("edit" to Pt(500f, 0f))))
    }

    private fun shown(st: CanvasState) = edit.inputsFor(st.workflow.graph.byId.getValue("edit")).map { it.name }

    @Test
    fun portsGrowOneAtATime() {
        assertFalse("reference_3" in shown(state()))
        assertTrue("reference_3" in shown(state("reference")))
        assertFalse("reference_4" in shown(state("reference")))
        assertTrue("reference_4" in shown(state("reference", "reference_3")))
    }

    @Test
    fun addWiresTheFirstFreePortWithAnImageNode() {
        val st = state("reference").addReference("edit", "/new.png", NODE_TYPES)
        val src = st.workflow.graph.byId.getValue("edit").inputs.getValue("reference_3").node
        assertEquals("core.image", st.workflow.graph.byId.getValue(src).type)
        assertEquals("/new.png", st.workflow.graph.byId.getValue(src).params["uri"])
        // ⚠ Full: a fourth add changes nothing.
        val full = state("reference", "reference_3", "reference_4")
        assertEquals(full, full.addReference("edit", "/x.png", NODE_TYPES))
    }

    /** ⭐ GitHub #107: five pictures picked on the edit's image: one there, three references, the fifth a loose node. */
    @Test
    fun severalPicturesFillTheImageThenTheReferencesThenNewNodes() {
        val st = state("image").placePictures("p_image", listOf("/1.png", "/2.png", "/3.png", "/4.png", "/5.png"), NODE_TYPES)
        val g = st.workflow.graph
        val ins = g.byId.getValue("edit").inputs
        assertEquals("/1.png", g.byId.getValue("p_image").params["uri"])
        assertEquals(listOf("/2.png", "/3.png", "/4.png"), listOf("reference", "reference_3", "reference_4").map { p ->
            g.byId.getValue(ins.getValue(p).node).params["uri"]
        })
        val loose = g.nodes.filter { n -> n.type == "core.image" && g.nodes.none { m -> m.inputs.values.any { it.node == n.id } } }
        assertEquals(listOf("/5.png"), loose.map { it.params["uri"] })
        // ⚠ An image node feeding nothing: every other picture becomes a node of its own.
        val alone = CanvasState(workflow = Workflow(Graph(listOf(Node("pic", "core.image"))), mapOf("pic" to Pt(0f, 0f))))
            .placePictures("pic", listOf("/a.png", "/b.png"), NODE_TYPES)
        assertEquals(listOf("/a.png", "/b.png"), alone.workflow.graph.nodes.map { it.params["uri"] })
    }

    /** ⚠ Removing image 2 moves 3 and 4 up, so no port is left empty in the middle. */
    @Test
    fun removingOneRestacksTheRestAndDropsItsNode() {
        val st = state("reference", "reference_3", "reference_4").removeReference("edit", "reference", NODE_TYPES)
        val ins = st.workflow.graph.byId.getValue("edit").inputs
        assertEquals("p_reference_3", ins["reference"]?.node)
        assertEquals("p_reference_4", ins["reference_3"]?.node)
        assertNull(ins["reference_4"])
        assertNull(st.workflow.graph.byId["p_reference"])
    }
}
