package com.abrah.nightmare

import com.abrah.nightmare.canvas.Pt
import com.abrah.nightmare.canvas.Workflow
import com.abrah.nightmare.canvas.nodeStripOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ⭐⭐ The node strip's order — the canvas's own, left to right then top to
 * bottom.
 *
 * ⚠ The user's call, 2026-09-20, over an explicit hand-set order: it needs no
 * new state in a saved flow and cannot disagree with what the eye sees,
 * because the person arranging the canvas IS choosing it.
 */
class NodeStripOrderTest {

    private fun wf(vararg at: Pair<String, Pt>) = Workflow(
        Graph(at.map { Node(it.first, "core.image") }),
        at.toMap(),
        emptyMap(),
    )

    @Test
    fun leftToRightThenTopToBottom() {
        // Deliberately built out of order, and a column on the left.
        val w = wf(
            "output" to Pt(900f, 40f),
            "prompt" to Pt(0f, 0f),
            "photo" to Pt(0f, 300f),
            "edit" to Pt(450f, 150f),
        )
        assertEquals(
            listOf("prompt", "photo", "edit", "output"),
            nodeStripOrder(w).map { it.id },
        )
    }

    /**
     * ⚠⚠ A node with NO recorded position sorts LAST, not at the origin. One
     * just dropped on the canvas must not jump to the front of the strip and
     * shift every card under a finger that is mid-swipe.
     */
    @Test
    fun aNodeWithNoPositionGoesLast() {
        val w = Workflow(
            Graph(listOf(Node("fresh", "core.image"), Node("prompt", "core.prompt"))),
            mapOf("prompt" to Pt(10f, 10f)),
            emptyMap(),
        )
        assertEquals(listOf("prompt", "fresh"), nodeStripOrder(w).map { it.id })
    }

    /**
     * ⚠ Ties break on the ID, so two nodes at the same point keep a stable
     * order between frames rather than swapping while someone steps through.
     */
    @Test
    fun tiesAreStable() {
        val w = wf("b" to Pt(5f, 5f), "a" to Pt(5f, 5f))
        assertEquals(listOf("a", "b"), nodeStripOrder(w).map { it.id })
        assertEquals(nodeStripOrder(w).map { it.id }, nodeStripOrder(w).map { it.id })
    }
}
