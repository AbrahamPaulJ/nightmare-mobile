package com.abrah.nightmare.canvas

import com.abrah.nightmare.Graph
import com.abrah.nightmare.NODE_TYPES
import com.abrah.nightmare.Node
import com.abrah.nightmare.sources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Renaming a node.
 *
 * ⚠⚠ **A node's id is not a label — it is what every wire points at.** So the
 * thing these tests actually guard is that a rename never silently disconnects
 * the graph: every `inputs` Source naming the old id has to move with it, and
 * so does every map keyed by id. A rename that moved only the node would leave
 * a graph that opens fine and produces nothing.
 */
class RenameNodeTest {

    private fun state(): CanvasState {
        val g = Graph(
            listOf(
                Node("text", "sd.clip_encode", mapOf("prompt" to "a cat", "negative" to "")),
                Node("sample", "sd.sample", mapOf("model" to "m"), sources("cond" to "text")),
                Node("decode", "sd.vae_decode", mapOf("model" to "m"), sources("latent" to "sample")),
            )
        )
        return CanvasState(
            workflow = Workflow(
                g,
                positions = mapOf("text" to Pt(0f, 0f), "sample" to Pt(0f, 100f), "decode" to Pt(0f, 200f)),
                sizes = mapOf("text" to 250f),
            ),
            previews = mapOf("text" to ("img_1" to 1f)),
            selection = setOf("text"),
            editing = "text",
        )
    }

    /** ⭐ The wire follows the name. This is the whole point. */
    @Test
    fun everyWirePointingAtTheOldNameMoves() {
        val s = state().renameNode("text", "prompt")
        assertNotNull("the renamed node exists", s.workflow.graph.byId["prompt"])
        assertNull("the old id is gone", s.workflow.graph.byId["text"])
        assertEquals(
            "sample's cond wire did not follow the rename",
            "prompt",
            s.workflow.graph.byId["sample"]!!.inputs["cond"]!!.node,
        )
        // ⚠ …and an unrelated wire is untouched.
        assertEquals("sample", s.workflow.graph.byId["decode"]!!.inputs["latent"]!!.node)
    }

    /** ⚠ Every map keyed by id moves too, or the node jumps to 0,0 and loses its size. */
    @Test
    fun theIdKeyedMapsMoveWithIt() {
        val s = state().renameNode("text", "prompt")
        assertEquals(Pt(0f, 0f), s.workflow.positions["prompt"])
        assertNull(s.workflow.positions["text"])
        assertEquals(250f, s.workflow.sizes["prompt"])
        assertEquals("img_1" to 1f, s.previews["prompt"])
        assertNull(s.previews["text"])
        assertTrue("prompt" in s.selection)
        assertEquals("the inspector follows the node it had open", "prompt", s.editing)
    }

    /**
     * ⚠⚠ A name already in use is REFUSED, not merged. Two nodes with one id
     * makes `topoSort` refuse the graph by name — and silently merging them
     * would be worse.
     */
    @Test
    fun aNameAlreadyTakenIsRefused() {
        val s = state()
        assertEquals(s, s.renameNode("text", "sample"))
    }

    /**
     * ⚠⚠ A ':' is refused because the wire format reads the tail as a PORT
     * (`Source.parse`), so `topoSort` rejects such an id by name. Accepting it
     * here would build a graph that cannot be loaded back.
     */
    @Test
    fun aNameWithThePortSeparatorIsRefused() {
        val s = state()
        assertEquals(s, s.renameNode("text", "pro:mpt"))
    }

    /** ⚠ Blank, whitespace-only, and unknown sources all leave the state alone. */
    @Test
    fun blankAndUnknownAreRefused() {
        val s = state()
        assertEquals(s, s.renameNode("text", ""))
        assertEquals(s, s.renameNode("text", "   "))
        assertEquals(s, s.renameNode("nosuchnode", "whatever"))
    }

    /** ⚠ Trimmed, so a stray space from a keyboard does not become part of the id. */
    @Test
    fun theNameIsTrimmed() {
        val s = state().renameNode("text", "  prompt  ")
        assertNotNull(s.workflow.graph.byId["prompt"])
    }

    /** ⚠ Renaming to the same name is a no-op, not a rebuild. */
    @Test
    fun renamingToItselfChangesNothing() {
        val s = state()
        assertEquals(s, s.renameNode("text", "text"))
    }

    /** ⭐ The graph still sorts, which is the real proof nothing was orphaned. */
    @Test
    fun theRenamedGraphStillOrders() {
        val s = state().renameNode("text", "prompt")
        val order = com.abrah.nightmare.topoSort(s.workflow.graph)
        assertTrue("renamed graph no longer sorts: $order", order is com.abrah.nightmare.Order.Ok)
        assertEquals(
            listOf("prompt", "sample", "decode"),
            (order as com.abrah.nightmare.Order.Ok).nodes.map { it.id },
        )
    }

    /**
     * ⭐⭐ A prompt node carries its text in the body, so the canvas shows what
     * it says instead of an anonymous box with one port.
     */
    @Test
    fun aPromptNodeLaysOutItsTextAndGrowsForIt() {
        val boxes = layout(state().workflow, NODE_TYPES)
        val prompt = boxes.first { it.id == "text" }
        assertNotNull("the prompt node has no prose block", prompt.prose)
        // ⚠⚠ BOTH captions, even though `negative` is blank here. Showing only
        // what was typed made an empty field vanish, so a node with one prompt
        // filled in looked like a node that HAS one field -- and nothing said
        // the other existed without opening the inspector.
        assertEquals(
            listOf("prompt", "negative"),
            prompt.prose!!.fields.map { it.first },
        )
        // ⚠ The body block ADDS height, the same way a picture does.
        val sampler = boxes.first { it.id == "sample" }
        assertTrue(
            "a prose node is no taller than one without text",
            prompt.height > sampler.height - Sizes.PORT_SPACING,
        )
    }

    /**
     * ⚠ A node with NOTHING typed still shows both boxes, empty.
     *
     * ⚠⚠ That is the point: the boxes are what tell a user the node has two
     * fields. An empty node that drew nothing was indistinguishable from one
     * with no text fields at all.
     */
    @Test
    fun anEmptyPromptStillShowsBothBoxes() {
        val g = Graph(listOf(Node("text", "sd.clip_encode", mapOf("prompt" to "", "negative" to ""))))
        val boxes = layout(Workflow(g, mapOf("text" to Pt(0f, 0f))), NODE_TYPES)
        val prose = boxes.first().prose
        assertNotNull(prose)
        assertEquals(listOf("prompt", "negative"), prose!!.fields.map { it.first })
        assertEquals(listOf("", ""), prose.fields.map { it.second })
    }

    /**
     * ⭐⭐ Dragging the node taller gives the PROMPTS the room, not the padding.
     *
     * ⚠ Height is never stored as a number — `proseLines` is a LINE COUNT the
     * derivation consumes, for the same reason `sizes` is width-only: two
     * stored dimensions can disagree, and a derived one cannot.
     */
    @Test
    fun draggingTallerGivesTheProseMoreLines() {
        val long = "a ".repeat(400)
        val g = Graph(listOf(Node("text", "sd.clip_encode", mapOf("prompt" to long, "negative" to ""))))
        val short = Workflow(g, mapOf("text" to Pt(0f, 0f)))
        val tall = short.proseResized("text", Sizes.PROSE_MAX_LINES)
        val a = layout(short, NODE_TYPES).first()
        val b = layout(tall, NODE_TYPES).first()
        assertTrue("a taller node did not get a taller body", b.height > a.height)
        assertEquals(Sizes.PROSE_MAX_LINES, b.prose!!.maxLines)
    }

    /**
     * ⭐⭐ A SHORT prompt's box still grows with the drag.
     *
     * ⚠⚠ This is the regression that made "vertical resize not working" true:
     * the box height was `min(wrapped, budget)`, so a one-line prompt pinned it
     * to one line and raising the budget changed nothing. Every box is the
     * budget's height now, whatever it holds.
     */
    @Test
    fun aShortPromptsBoxStillGrowsWithTheDrag() {
        val g = Graph(listOf(Node("t", "sd.clip_encode", mapOf("prompt" to "hi", "negative" to ""))))
        val small = Workflow(g, mapOf("t" to Pt(0f, 0f)))
        val big = small.proseResized("t", 6)
        val a = layout(small, NODE_TYPES).first()
        val b = layout(big, NODE_TYPES).first()
        assertTrue("a two-character prompt did not grow when dragged", b.height > a.height)
    }

    /**
     * ⚠⚠ Both boxes are the SAME height whatever they hold, so neither is a
     * thin strip to aim a finger at — and a tap is what opens the prompt.
     */
    @Test
    fun bothBoxesAreTheSameHeight() {
        val g = Graph(
            listOf(Node("t", "sd.clip_encode", mapOf("prompt" to "a ".repeat(200), "negative" to "")))
        )
        val rects = layout(Workflow(g, mapOf("t" to Pt(0f, 0f))), NODE_TYPES).first().proseRects()
        assertEquals(2, rects.size)
        val heights = rects.map { it.third - it.second }
        assertEquals(heights[0], heights[1], 0.01f)
    }

    /** ⚠ The rects do not overlap, or a tap would open the wrong field. */
    @Test
    fun theBoxesDoNotOverlap() {
        val g = Graph(listOf(Node("t", "sd.clip_encode", mapOf("prompt" to "x", "negative" to "y"))))
        val rects = layout(Workflow(g, mapOf("t" to Pt(0f, 0f))), NODE_TYPES).first().proseRects()
        assertTrue("prose boxes overlap: $rects", rects[0].third <= rects[1].second)
    }

    /** ⚠ Clamped, so a drag cannot make a node taller than the canvas. */
    @Test
    fun theLineCountIsClamped() {
        val w = Workflow(Graph(emptyList()), emptyMap())
        assertEquals(1, w.proseResized("n", -5).proseLinesOf("n"))
        assertEquals(Sizes.PROSE_MAX_LINES, w.proseResized("n", 999).proseLinesOf("n"))
    }
}
