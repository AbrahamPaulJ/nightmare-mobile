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

    /** ⚠ A node with an IMAGE in and an IMAGE out — what a splice needs. */
    private val sampler = com.abrah.nightmare.SdSampler.ALL.first { !it.inpaint }.name

    // ⚠⚠ `core.output` stands in for "a node that consumes a picture" here.
    // It was `image.upscale`, which was DELETED on 2026-09-22 — upscaling is a
    // checkbox on the output node now. The assist's arithmetic is about PORTS,
    // not about which node it is, so the substitution changes nothing it tests.

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

    /**
     * ⭐⭐⭐ **Both are offered for a port something can already feed** — the
     * user's call, 2026-09-22: *"you should offer to connect same prompt node or
     * use new one"*.
     *
     * ⚠ Sharing one prompt between two samplers and giving the second its own
     * are both ordinary things to want; only the person knows which.
     */
    @Test
    fun anExistingFeederIsOfferedBesideAFreshOne() {
        val g = Graph(listOf(Node("prompt", "core.prompt")))
        val plan = planAdd(typeOf("sd15.sample"), g, types)
        assertEquals(
            listOf("prompt"),
            plan.snaps.filter { it.port == "prompt" }.map { it.fromNode },
        )
        assertTrue(
            "a fresh prompt should be offered too: ${plan.helpers}",
            plan.helpers.any { it.port == "prompt" },
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
                Node("out", "core.output", inputs = sources("media" to "gen")),
                Node("gen", "sd15.sample", inputs = sources("prompt" to "prompt")),
                Node("prompt", "core.prompt"),
                Node("photo", "core.image"),
            ),
        )
        val plan = planAdd(typeOf("core.output"), g, types)
        val forImage = plan.snaps.filter { it.port == "media" }
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
        val plan = planAdd(typeOf("core.output"), g, types)
        for ((_, forPort) in plan.snaps.groupBy { it.port }) {
            assertEquals(1, forPort.count { it.recommended })
        }
    }

    // ---- applying what was ticked -----------------------------------------

    @Test
    fun itWiresTheSnapItWasGiven() {
        val g = Graph(listOf(Node("photo", "core.image")))
        val st = CanvasState(Workflow(g, mapOf("photo" to Pt(0f, 0f))))
        val plan = planAdd(typeOf("core.output"), g, types)
        val snap = plan.snaps.single { it.port == "media" && it.recommended }
        val next = st.applyAdd(plan, Pt(300f, 0f), emptySet(), mapOf("media" to snap))
        val added = next.workflow.graph.nodes.single { it.type == "core.output" }
        assertEquals("photo", added.inputs["media"]!!.node)
        // ⚠ The new node's sheet opens, exactly as a plain add does.
        assertEquals(added.id, next.editing)
    }

    // ---- splicing into an existing wire -----------------------------------

    /**
     * ⭐⭐⭐ The case the first version missed, reported 2026-09-22: *"you
     * didnt account for when the new sample node goes between an existing sample
     * node and an output"*.
     */
    @Test
    fun itOffersToSitInAnExistingWire() {
        val g = Graph(
            listOf(
                Node("photo", "core.image"),
                Node("out", "core.output", inputs = sources("media" to "photo")),
            ),
        )
        // ⚠ A SAMPLER, because a splice needs the new node to have an input AND
        // an output — `core.output` has no outputs, so it can never sit IN a
        // wire, only at the end of one.
        val plan = planAdd(typeOf(sampler), g, types)
        val splice = plan.splices.firstOrNull { it.consumer == "out" && it.from == "photo" }
        assertTrue("no splice offered: ${plan.splices}", splice != null)
    }

    /** ⚠⚠ BOTH halves, or the new node makes a picture nothing looks at. */
    @Test
    fun aSpliceRewiresTheConsumerAsWellAsTheInput() {
        val g = Graph(
            listOf(
                Node("photo", "core.image"),
                Node("out", "core.output", inputs = sources("media" to "photo")),
            ),
        )
        val st = CanvasState(Workflow(g, mapOf("photo" to Pt(0f, 0f), "out" to Pt(400f, 0f))))
        val plan = planAdd(typeOf(sampler), g, types)
        val splice = plan.splices.first { it.consumer == "out" && it.from == "photo" }
        val next = st.applyAdd(plan, Pt(200f, 0f), emptySet(), emptyMap(), splice)
        val added = next.workflow.graph.nodes.single { it.type == sampler }
        assertEquals("the new node must read the old source", "photo", added.inputs[splice.inPort]!!.node)
        assertEquals(
            "the consumer must now read the new node",
            added.id,
            next.workflow.graph.byId["out"]!!.inputs["media"]!!.node,
        )
    }

    /** ⚠ Nothing wired means nothing to splice into. */
    @Test
    fun anUnwiredGraphOffersNoSplice() {
        val g = Graph(listOf(Node("photo", "core.image")))
        assertTrue(planAdd(typeOf(sampler), g, types).splices.isEmpty())
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

    // ---- a node with NO inputs: what does it FEED? ------------------------

    /**
     * ⭐⭐⭐ The user's ask, 2026-09-22: *"pls add support for non-sample
     * nodes as well"*. A prompt has nothing to take, so the first version
     * offered it nothing and the sheet never opened — yet "what does this feed"
     * is the only question worth asking about it.
     */
    @Test
    fun aPromptOffersWhatItWouldFeed() {
        val g = Graph(listOf(Node("gen", "sd15.sample")))
        val plan = planAdd(typeOf("core.prompt"), g, types)
        assertTrue("the sheet must open for a prompt now", !plan.isEmpty)
        val feed = plan.feeds.single { it.toNode == "gen" }
        assertTrue("the sampler's prompt port is empty, so it is ticked", feed.free)
    }

    /** ⚠ An occupied port is offered UNTICKED — replacing a wire is a decision. */
    @Test
    fun anOccupiedPortIsOfferedButNotTicked() {
        val g = Graph(
            listOf(
                Node("p1", "core.prompt"),
                Node("gen", "sd15.sample", inputs = sources("prompt" to "p1")),
            ),
        )
        val plan = planAdd(typeOf("core.prompt"), g, types)
        val feed = plan.feeds.single { it.toNode == "gen" && it.toPort == "prompt" }
        assertTrue("it is offered", true)
        assertTrue("but not ticked", !feed.free)
    }

    @Test
    fun itWiresTheFeedItWasGiven() {
        val g = Graph(listOf(Node("gen", "sd15.sample")))
        val st = CanvasState(Workflow(g, mapOf("gen" to Pt(400f, 0f))))
        val plan = planAdd(typeOf("core.prompt"), g, types)
        val feed = plan.feeds.single { it.toNode == "gen" }
        val next = st.applyAdd(plan, Pt(0f, 0f), emptySet(), emptyMap(), null, listOf(feed))
        val added = next.workflow.graph.nodes.single { it.type == "core.prompt" }
        assertEquals(added.id, next.workflow.graph.byId["gen"]!!.inputs["prompt"]!!.node)
    }
}
