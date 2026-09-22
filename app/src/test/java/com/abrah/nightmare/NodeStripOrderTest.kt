package com.abrah.nightmare

import com.abrah.nightmare.canvas.Workflow
import com.abrah.nightmare.canvas.looseNodes
import com.abrah.nightmare.canvas.nodeStripOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ⭐⭐ The node strip is ordered the way the graph RUNS — the user's call,
 * 2026-09-21, replacing canvas position.
 *
 * ⚠ It is [topoSort], the executor's own function, so this test is about
 * the strip USING it rather than about sorting; the ordering itself is
 * covered where that function lives.
 */
class NodeStripOrderTest {

    private fun wf(nodes: List<Node>) = Workflow(Graph(nodes), emptyMap(), emptyMap())

    @Test
    fun runOrderNotDeclarationOrder() {
        // Declared backwards on purpose.
        val w = wf(
            listOf(
                Node("output", "core.output", inputs = sources("media" to "gen")),
                Node("gen", "sd15.sample", inputs = sources("prompt" to "prompt")),
                Node("prompt", "core.prompt"),
            ),
        )
        assertEquals(
            listOf("prompt", "gen", "output"),
            nodeStripOrder(w).map { it.id },
        )
    }

    /**
     * ⚠⚠ A graph that cannot be ordered still lists every node. The
     * inspector is where someone goes to FIX a broken graph, so an empty
     * strip would take away the only way to reach the node at fault.
     */
    @Test
    fun aBrokenGraphStillListsItsNodes() {
        val w = wf(
            listOf(
                Node("a", "core.image", inputs = sources("image" to "b")),
                Node("b", "core.image", inputs = sources("image" to "a")),
            ),
        )
        assertEquals(listOf("a", "b"), nodeStripOrder(w).map { it.id })
    }

    /** ⚠ Position is no longer consulted at all, so it cannot reshuffle. */
    @Test
    fun draggingANodeDoesNotReorderTheStrip() {
        val nodes = listOf(
            Node("prompt", "core.prompt"),
            Node("gen", "sd15.sample", inputs = sources("prompt" to "prompt")),
        )
        val left = Workflow(Graph(nodes), mapOf("gen" to com.abrah.nightmare.canvas.Pt(0f, 0f)), emptyMap())
        val right = Workflow(Graph(nodes), mapOf("gen" to com.abrah.nightmare.canvas.Pt(9000f, 0f)), emptyMap())
        assertEquals(nodeStripOrder(left).map { it.id }, nodeStripOrder(right).map { it.id })
    }

    // ---- nodes that are not part of the main flow ------------------------

    /**
     * ⭐⭐⭐ The user's rule, 2026-09-22: *"if 2 nodes are connected and
     * theres a 3rd unconnected row, 2 is majority so those show"*.
     *
     * ⚠ They are ordered LAST rather than hidden — the user's call when
     * asked. A node just dropped has no wires, and hiding it would leave no
     * way to configure it before wiring it up.
     */
    @Test
    fun aLooseNodeSortsAfterTheFlow() {
        val w = wf(
            listOf(
                Node("prompt", "core.prompt"),
                Node("gen", "sd15.sample", inputs = sources("prompt" to "prompt")),
                Node("stray", "core.prompt"),
            ),
        )
        assertEquals(setOf("stray"), looseNodes(w.graph))
        assertEquals(listOf("prompt", "gen", "stray"), nodeStripOrder(w).map { it.id })
    }

    /** ⚠ Weakly connected: a prompt feeds a sampler and nothing feeds it. */
    @Test
    fun aSourceNodeIsPartOfTheFlowItFeeds() {
        val w = wf(
            listOf(
                Node("prompt", "core.prompt"),
                Node("gen", "sd15.sample", inputs = sources("prompt" to "prompt")),
            ),
        )
        assertEquals(emptySet<String>(), looseNodes(w.graph))
    }

    /**
     * ⚠⚠ A TIE keeps everything. With two pairs there is no majority, and
     * guessing which pair is "the flow" would hide half a graph on a coin toss.
     */
    @Test
    fun twoEqualGroupsMeanNothingIsLoose() {
        val w = wf(
            listOf(
                Node("p1", "core.prompt"),
                Node("g1", "sd15.sample", inputs = sources("prompt" to "p1")),
                Node("p2", "core.prompt"),
                Node("g2", "sd15.sample", inputs = sources("prompt" to "p2")),
            ),
        )
        assertEquals(emptySet<String>(), looseNodes(w.graph))
    }

    /** ⚠ One node on its own is the flow, not a stray. */
    @Test
    fun aSingleNodeIsNeverLoose() {
        assertEquals(emptySet<String>(), looseNodes(wf(listOf(Node("a", "core.prompt"))).graph))
    }
}
