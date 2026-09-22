package com.abrah.nightmare.canvas

import com.abrah.nightmare.Graph
import com.abrah.nightmare.NODE_TYPES
import com.abrah.nightmare.Node
import com.abrah.nightmare.sources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐⭐⭐ **The add-node offer** — helpers to create, and the node to snap to.
 *
 * Asked for 2026-09-22: *"to add an inpaint node a user has to manually add the
 * prompt and segmenter … offer to include helper nodes and smart snap it to the
 * node that makes sense."*
 *
 * ⚠⚠ Against the REAL [NODE_TYPES], not invented ones: the whole design rests
 * on the offer being derived from each node's declared ports, so a fixture with
 * ports of my own choosing would test the arithmetic and not the claim.
 */
class AddAssistTest {

    private val types = NODE_TYPES
    private fun typeOf(name: String) = types.getValue(name)

    private val empty = Graph(emptyList())

    /** ⚠ A node with no inputs proposes nothing, so no sheet is shown for it. */
    @Test
    fun aSourceNodeOffersNothing() {
        val plan = planAdd(typeOf("core.prompt"), empty, types)
        assertTrue("a prompt has no inputs to fill", plan.isEmpty)
    }

    /**
     * ⭐ On an EMPTY canvas a sampler has nothing to snap to, so every port it
     * can start is offered as a helper instead.
     */
    @Test
    fun anEmptyCanvasOffersHelpersNotSnaps() {
        val plan = planAdd(typeOf("sd15.sample"), empty, types)
        assertTrue("nothing to snap to", plan.snaps.isEmpty())
        assertTrue(
            "the prompt port should offer a helper: ${plan.helpers}",
            plan.helpers.any { it.port == "prompt" },
        )
        // ⚠ …and the helper is a node you can actually START with.
        assertTrue(plan.helpers.all { it.type.inputs.isEmpty() })
    }

    /** ⚠ A port something already feeds gets no helper — that is how you end up with three prompts. */
    @Test
    fun anExistingFeederReplacesTheHelper() {
        val g = Graph(listOf(Node("prompt", "core.prompt")))
        val plan = planAdd(typeOf("sd15.sample"), g, types)
        assertTrue(
            "no helper for a port that has a feeder",
            plan.helpers.none { it.port == "prompt" },
        )
        assertEquals(
            listOf("prompt"),
            plan.snaps.filter { it.port == "prompt" }.map { it.fromNode },
        )
    }

    /**
     * ⭐⭐ The RECOMMENDED source is the last in EXECUTION order — "what I just
     * made" — not the first found and not the nearest on screen.
     */
    @Test
    fun theRecommendedSourceIsTheLastInRunOrder() {
        val g = Graph(
            listOf(
                Node("out", "core.output", inputs = sources("image" to "gen")),
                Node("gen", "sd15.sample", inputs = sources("prompt" to "prompt")),
                Node("prompt", "core.prompt"),
                Node("photo", "core.image"),
            ),
        )
        val plan = planAdd(typeOf("image.upscale"), g, types)
        val forImage = plan.snaps.filter { it.port == "image" }
        assertTrue("both picture sources offered: $forImage", forImage.size >= 2)
        assertEquals(
            "the sampler runs last, so it is what 'what I just made' means",
            "gen",
            forImage.single { it.recommended }.fromNode,
        )
    }

    /** ⚠ At most one recommendation per port — an input takes one wire. */
    @Test
    fun oneRecommendationPerPort() {
        val g = Graph(listOf(Node("a", "core.image"), Node("b", "core.image")))
        val plan = planAdd(typeOf("image.upscale"), g, types)
        for ((_, forPort) in plan.snaps.groupBy { it.port }) {
            assertEquals(1, forPort.count { it.recommended })
        }
    }

    // ---- applying what was ticked -----------------------------------------

    @Test
    fun itWiresTheSnapItWasGiven() {
        val g = Graph(listOf(Node("photo", "core.image")))
        val st = CanvasState(Workflow(g, mapOf("photo" to Pt(0f, 0f))))
        val plan = planAdd(typeOf("image.upscale"), g, types)
        val snap = plan.snaps.single { it.port == "image" && it.recommended }
        val next = st.applyAdd(plan, Pt(300f, 0f), emptySet(), mapOf("image" to snap))
        val added = next.workflow.graph.nodes.single { it.type == "image.upscale" }
        assertEquals("photo", added.inputs["image"]!!.node)
        // ⚠ The new node's sheet opens, exactly as a plain add does.
        assertEquals(added.id, next.editing)
    }

    @Test
    fun itCreatesOnlyTheHelpersLeftTicked() {
        val st = CanvasState(Workflow(empty, emptyMap()))
        val plan = planAdd(typeOf("sd15.sample"), empty, types)
        val keep = plan.helpers.first().port
        val next = st.applyAdd(plan, Pt(0f, 0f), setOf(keep), emptyMap())
        val sampler = next.workflow.graph.nodes.single { it.type == "sd15.sample" }
        assertEquals(
            "one helper kept, one node made for it",
            plan.helpers.first().type.name,
            next.workflow.graph.byId[sampler.inputs[keep]!!.node]!!.type,
        )
        // Nothing else was created.
        assertEquals(2, next.workflow.graph.nodes.size)
    }

    @Test
    fun untickingEverythingJustAddsTheNode() {
        val st = CanvasState(Workflow(empty, emptyMap()))
        val plan = planAdd(typeOf("sd15.sample"), empty, types)
        val next = st.applyAdd(plan, Pt(0f, 0f), emptySet(), emptyMap())
        assertEquals(1, next.workflow.graph.nodes.size)
    }
}
