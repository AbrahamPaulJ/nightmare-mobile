package com.abrah.nightmare

import com.abrah.nightmare.canvas.Workflow
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
}
