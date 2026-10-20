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
