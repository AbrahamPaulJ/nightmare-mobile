package com.abrah.nightmare.canvas

import com.abrah.nightmare.Graph
import com.abrah.nightmare.Node
import com.abrah.nightmare.UpscaleNode
import com.abrah.nightmare.sources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ⭐⭐⭐ **The upscale button's graph surgery** — asked for 2026-09-22: trace
 * the wire behind an output, put an upscale node in between, pin the seed, Run.
 *
 * ⚠⚠ The seed pinning is the half worth testing hardest. Without it Run rolls a
 * new seed and enlarges a DIFFERENT picture from the one on screen, at full
 * render cost, and the button looks broken for a reason nothing shows.
 */
class UpscaleInsertTest {

    private fun state(vararg nodes: Node) = CanvasState(
        Workflow(
            Graph(nodes.toList()),
            nodes.mapIndexed { i, n -> n.id to Pt(i * 200f, 0f) }.toMap(),
        )
    )

    private val flow = state(
        Node("prompt", "core.prompt"),
        Node("gen", "sd15.sample", mapOf("seed" to "0"), sources("prompt" to "prompt")),
        Node("out", "core.output", inputs = sources("image" to "gen")),
    )

    private fun insert(st: CanvasState, seeds: Map<String, String> = emptyMap()) =
        insertUpscale(st, "out", "upscaler_realistic", UpscaleNode.UPSCALER, seeds)

    @Test
    fun itSitsBetweenTheOutputAndWhatFedIt() {
        val next = insert(flow)!!
        val g = next.workflow.graph
        val added = g.nodes.single { it.type == UpscaleNode.name }
        // The output now reads the upscale node…
        assertEquals(added.id, g.byId["out"]!!.inputs["image"]!!.node)
        // …and the upscale node reads what the output used to.
        assertEquals("gen", added.inputs["image"]!!.node)
        assertEquals("upscaler_realistic", added.params[UpscaleNode.UPSCALER])
    }

    /** ⚠ Halfway between the two it sits between, so the graph still reads left to right. */
    @Test
    fun itIsPlacedBetweenThem() {
        val next = insert(flow)!!
        val id = next.workflow.graph.nodes.single { it.type == UpscaleNode.name }.id
        assertEquals(300f, next.workflow.positions[id]!!.x, 0.01f)
    }

    @Test
    fun itPinsARollingSeed() {
        val next = insert(flow, mapOf("gen" to "12345"))!!
        assertEquals("12345", next.workflow.graph.byId["gen"]!!.params["seed"])
    }

    /** ⚠ A seed someone pinned by hand is not ours to overwrite. */
    @Test
    fun itLeavesAPinnedSeedAlone() {
        val pinned = state(
            Node("gen", "sd15.sample", mapOf("seed" to "777")),
            Node("out", "core.output", inputs = sources("image" to "gen")),
        )
        val next = insertUpscale(pinned, "out", "u", UpscaleNode.UPSCALER, mapOf("gen" to "12345"))!!
        assertEquals("777", next.workflow.graph.byId["gen"]!!.params["seed"])
    }

    /** ⚠ A graph that has never run pins nothing and simply renders. */
    @Test
    fun noRolledSeedPinsNothing() {
        val next = insert(flow)!!
        assertEquals("0", next.workflow.graph.byId["gen"]!!.params["seed"])
    }

    /**
     * ⚠⚠ Refused rather than stacked. Two upscale nodes in a row is a second
     * 4x on top of a 4x, which is not what a second tap means.
     */
    @Test
    fun itRefusesWhenOneIsAlreadyThere() {
        val once = insert(flow)!!
        assertNull(insert(once))
    }

    /** ⚠ Nothing wired in is nothing to enlarge. */
    @Test
    fun itRefusesAnOutputWithNoWire() {
        val bare = state(Node("out", "core.output"))
        assertNull(insertUpscale(bare, "out", "u", UpscaleNode.UPSCALER, emptyMap()))
    }

    @Test
    fun itRefusesANodeThatIsNotThere() {
        assertNull(insertUpscale(flow, "nope", "u", UpscaleNode.UPSCALER, emptyMap()))
        assertNotNull(insert(flow))
    }
}
