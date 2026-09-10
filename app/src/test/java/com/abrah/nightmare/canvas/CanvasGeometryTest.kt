package com.abrah.nightmare.canvas

import com.abrah.nightmare.Graph
import com.abrah.nightmare.NODE_TYPES
import com.abrah.nightmare.Node
import com.abrah.nightmare.sources
import com.abrah.nightmare.Port
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The canvas arithmetic, on the JVM.
 *
 * ⭐ These are the tests that matter most for a canvas, and they are the ones a
 * screenshot cannot do: a port whose hit-box is a few units off does not throw
 * and does not look wrong — it just makes the app feel broken in a way nobody
 * can reproduce on demand.
 */
class CanvasGeometryTest {

    private val workflow = Workflow(
        Graph(
            listOf(
                Node("s", "sd.sample", mapOf("model" to "m", "width" to "512", "height" to "512")),
                Node("d", "sd.vae_decode", mapOf("model" to "m"), sources("latent" to "s")),
            )
        ),
        mapOf("s" to Pt(0f, 0f), "d" to Pt(400f, 0f)),
    )

    private val boxes = layout(workflow, NODE_TYPES)

    // --- the transform ------------------------------------------------------

    @Test
    fun worldAndScreenRoundTrip() {
        val v = Viewport(Pt(37f, -12f), 1.7f)
        val p = Pt(123.5f, -45.25f)
        val back = v.toWorld(v.toScreen(p))
        assertEquals(p.x, back.x, 0.001f)
        assertEquals(p.y, back.y, 0.001f)
    }

    /**
     * ⚠⚠ The point under the fingers must not move. Scaling the offset instead
     * of solving for it makes the graph slide away under a pinch, which reads
     * as the canvas fighting the user.
     */
    @Test
    fun zoomKeepsTheFocusPointStill() {
        val v = Viewport(Pt(10f, 20f), 1f)
        val focus = Pt(300f, 500f)
        val worldBefore = v.toWorld(focus)
        val z = v.zoomedAround(focus, 2f)
        val worldAfter = z.toWorld(focus)
        assertEquals(worldBefore.x, worldAfter.x, 0.01f)
        assertEquals(worldBefore.y, worldAfter.y, 0.01f)
    }

    @Test
    fun zoomIsClamped() {
        val v = Viewport(scale = 1f)
        assertEquals(3f, v.zoomedAround(Pt(0f, 0f), 100f).scale, 0.001f)
        assertEquals(0.25f, v.zoomedAround(Pt(0f, 0f), 0.001f).scale, 0.001f)
    }

    // --- ports --------------------------------------------------------------

    /**
     * ⚠⚠ If two adjacent hit-boxes overlap, a tap between them connects
     * whichever port the geometry happens to favour — the wrong wire, silently.
     */
    @Test
    fun adjacentPortHitBoxesDoNotOverlap() =
        assertTrue(
            "PORT_SPACING ${Sizes.PORT_SPACING} must exceed 2 * PORT_HIT_RADIUS " +
                "${Sizes.PORT_HIT_RADIUS}",
            Sizes.PORT_SPACING > 2 * Sizes.PORT_HIT_RADIUS,
        )

    /** ⚠ A finger is ~9 mm: the hit-box has to be far bigger than the dot. */
    @Test
    fun theHitBoxIsMuchBiggerThanTheDot() =
        assertTrue(Sizes.PORT_HIT_RADIUS > 2.5f * Sizes.PORT_RADIUS)

    @Test
    fun inputsSitOnTheLeftEdgeAndOutputsOnTheRight() {
        val s = boxes.first { it.id == "s" }
        assertEquals(s.topLeft.x + s.width, s.outputPort(0).x, 0.001f)
        val d = boxes.first { it.id == "d" }
        assertEquals(d.topLeft.x, d.inputPort(0).x, 0.001f)
    }

    @Test
    fun aTapOnAPortFindsIt() {
        val d = boxes.first { it.id == "d" }
        val hit = portAt(boxes, d.inputPort(0) + Pt(4f, -3f))
        assertNotNull(hit)
        assertEquals("d", hit!!.nodeId)
        assertTrue(hit.isInput)
        assertEquals("latent", hit.port.name)
    }

    @Test
    fun aTapInEmptySpaceFindsNoPort() =
        assertNull(portAt(boxes, Pt(2000f, 2000f)))

    /**
     * ⚠⚠ Nearest, not first-found. First-found makes the result depend on node
     * declaration order, so a tap between two ports connects whichever node was
     * declared earlier — which looks like the user missed.
     */
    @Test
    fun theNearestPortWinsRatherThanTheFirstDeclared() {
        // Two nodes whose ports are close together; the tap is nearer the
        // SECOND one's port, and the second is declared last.
        val near = Workflow(
            Graph(
                listOf(
                    Node("a", "sd.vae_decode", mapOf("model" to "m")),
                    Node("b", "sd.vae_decode", mapOf("model" to "m")),
                )
            ),
            mapOf("a" to Pt(0f, 0f), "b" to Pt(0f, 30f)),
        )
        val nb = layout(near, NODE_TYPES)
        val aPort = nb.first { it.id == "a" }.inputPort(0)
        val bPort = nb.first { it.id == "b" }.inputPort(0)
        val nearerB = Pt(bPort.x, bPort.y - 2f)
        assertTrue("the fixture must put the tap nearer b",
            Math.abs(nearerB.y - bPort.y) < Math.abs(nearerB.y - aPort.y))
        assertEquals("b", portAt(nb, nearerB, radius = 40f)!!.nodeId)
    }

    // --- nodes --------------------------------------------------------------

    @Test
    fun aTapOnANodeBodyFindsIt() {
        val s = boxes.first { it.id == "s" }
        assertEquals("s", nodeAt(boxes, Pt(s.topLeft.x + 20f, s.topLeft.y + 20f))!!.id)
    }

    /** ⚠ Later nodes draw on top, so the topmost must win the tap. */
    @Test
    fun theTopmostOverlappingNodeWins() {
        val stacked = Workflow(
            workflow.graph,
            mapOf("s" to Pt(0f, 0f), "d" to Pt(10f, 10f)),
        )
        val sb = layout(stacked, NODE_TYPES)
        assertEquals("d", nodeAt(sb, Pt(30f, 40f))!!.id)
    }

    @Test
    fun aTapOffEveryNodeFindsNothing() = assertNull(nodeAt(boxes, Pt(-50f, -50f)))

    // --- connection rules ---------------------------------------------------

    private fun out(node: String, type: String) =
        PortRef(node, Port("o", type), isInput = false, at = Pt(0f, 0f))

    private fun inp(node: String, type: String) =
        PortRef(node, Port("i", type), isInput = true, at = Pt(0f, 0f))

    @Test
    fun matchingTypesConnect() = assertNull(connectionError(out("a", "LATENT"), inp("b", "LATENT")))

    /** ⚠ The message says what does not fit, because the user cannot see types. */
    @Test
    fun mismatchedTypesAreRefusedByName() {
        val why = connectionError(out("a", "LATENT"), inp("b", "IMAGE"))
        assertNotNull(why)
        assertTrue(why!!, why.contains("LATENT") && why.contains("IMAGE"))
    }

    @Test
    fun aNodeCannotFeedItself() =
        assertEquals("a node cannot feed itself", connectionError(out("a", "IMAGE"), inp("a", "IMAGE")))

    @Test
    fun twoOutputsDoNotConnect() =
        assertEquals("both ends are outputs", connectionError(out("a", "IMAGE"), out("b", "IMAGE")))

    @Test
    fun twoInputsDoNotConnect() =
        assertEquals("both ends are inputs", connectionError(inp("a", "IMAGE"), inp("b", "IMAGE")))

    // --- layout -------------------------------------------------------------

    /** A node with more ports must be taller, or the ports fall out of it. */
    @Test
    fun heightFollowsThePortCount() =
        assertTrue(Sizes.nodeHeight(3, 1) > Sizes.nodeHeight(1, 1))

    @Test
    fun everyPortFitsInsideItsNode() {
        for (b in boxes) {
            b.inputs.indices.forEach {
                assertTrue("input $it of ${b.id} falls below the node",
                    b.inputPort(it).y <= b.bottom)
            }
            b.outputs.indices.forEach {
                assertTrue("output $it of ${b.id} falls below the node",
                    b.outputPort(it).y <= b.bottom)
            }
        }
    }

    /** Moving a node must not disturb the graph the executor runs. */
    @Test
    fun movingANodeChangesOnlyItsPosition() {
        val moved = workflow.moved("s", Pt(99f, 99f))
        assertEquals(Pt(99f, 99f), moved.positions["s"])
        assertEquals(workflow.graph, moved.graph)
        assertEquals(workflow.positions["d"], moved.positions["d"])
    }

    // -------------------------------------------------------- failure notes

    /**
     * ⭐⭐ The line number is the whole point. A Tier 0 contributor has no
     * debugger and no console; if the node does not say `index.js:12`, nothing
     * anywhere does.
     */
    @Test
    fun aJsFailureKeepsItsLineNumber() {
        val note = failureNote(
            "TypeError: cannot read property 'w' of undefined\n" +
                "    at resize (index.js:12)\n" +
                "    at <anonymous> (host.js:3)"
        )
        assertTrue(note, note.startsWith("index.js:12"))
        assertFalse("the rest of the stack must not come with it", note.contains("anonymous"))
    }

    /** ⚠ The FIRST location, not the last: the innermost frame is the plugin's. */
    @Test
    fun theFirstLocationWins() {
        assertTrue(failureNote("boom\n at a (index.js:7)\n at b (other.js:99)").contains("index.js:7"))
    }

    /** ⚠ A Kotlin frame is not a plugin location and must not be offered as one. */
    @Test
    fun aKotlinFrameIsNotALocation() {
        val note = failureNote("missing param \"denoise\"\n\tat Executor.kt:716")
        assertEquals("missing param \"denoise\"", note)
    }

    @Test
    fun aLocationAlreadyInTheMessageIsNotRepeated() {
        val note = failureNote("SyntaxError at index.js:4\n    at eval (index.js:4)")
        assertEquals(1, Regex("index\\.js:4").findAll(note).count())
    }

    /** ⚠ Never blank: `e.message` is null often enough to matter. */
    @Test
    fun thereIsAlwaysSomethingToDraw() {
        assertEquals("failed", failureNote(null))
        assertEquals("failed", failureNote("   \n  "))
    }

    @Test
    fun aLongMessageIsCutAndSaysSo() {
        val note = failureNote("x".repeat(200), limit = 20)
        assertTrue(note, note.endsWith("…"))
        assertEquals(20, note.length)
    }

    /**
     * ⚠⚠ The regression the golden found: with the message first, a long one
     * pushed `(index.js:27)` off the end of the node — cutting the only part
     * that cannot be guessed from looking at the graph.
     */
    @Test
    fun aLongMessageCannotPushOutTheLineNumber() {
        val note = failureNote(
            "TypeError: cannot read property 'width' of undefined, which is a " +
                "very long sentence indeed\n    at LatentMix (index.js:27)"
        )
        assertTrue(note, note.startsWith("index.js:27"))
        assertTrue(note, note.endsWith("…"))
    }

    /** …and a short one is left exactly alone. */
    @Test
    fun aShortMessageIsUntouched() {
        assertEquals("no such handle", failureNote("no such handle", limit = 20))
    }

}
