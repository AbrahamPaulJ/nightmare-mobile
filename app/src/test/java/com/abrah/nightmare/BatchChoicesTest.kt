package com.abrah.nightmare

import com.abrah.nightmare.canvas.inpaintWorkflow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠⚠ **This test exists because the Batch sheet shipped offering the wrong
 * knobs.** It filtered on "not a context key and not a dropdown", which let
 * through `uri`, `prompt`, the mask's `ops` blob and the crop's rect — and then
 * truncated the list before it reached the sampler, so `seed`, `steps`, `cfg`
 * and `denoise` were not on it at all. Reported from the phone, 2026-09-10.
 *
 * ⚠ The rule is duplicated here rather than imported because the sheet is
 * Compose and this is a JVM test. If the filter moves into a shared function,
 * this test should call it instead.
 */
class BatchChoicesTest {

    private fun choices(): List<Pair<String, String>> {
        val graph = inpaintWorkflow().graph
        val derived = setOf("out_w", "out_h")
        return graph.nodes.flatMap { n ->
            (NODE_TYPES[n.type]?.widgets.orEmpty())
                .filter { w ->
                    w.numeric && w.options == null && w.locked == null &&
                        w.name !in derived && BatchSpec.refusalFor(w.name) == null
                }
                .map { w -> n.id to w.name }
        }
    }

    @Test
    fun theSamplersKnobsAreOffered() {
        val params = choices().filter { it.first == "sample" }.map { it.second }
        for (want in listOf("seed", "steps", "cfg", "denoise")) {
            assertTrue("the sampler's \"$want\" must be sweepable, got $params", want in params)
        }
    }

    /**
     * ⚠ A prompt, a uri and a blob of stroke coordinates are not things a
     * numeric sweep can vary — offering them is what buried the knobs that
     * matter.
     */
    @Test
    fun textAndBlobParamsAreNotOffered() {
        val names = choices().map { it.second }.toSet()
        for (never in listOf("uri", "prompt", "negative", "ops", "aspect", "pad", "scheduler")) {
            assertTrue("\"$never\" must not be sweepable", never !in names)
        }
    }

    /**
     * ⚠⚠ `out_w`/`out_h` are written by the canvas from whatever consumes the
     * node, so a sweep over one is overwritten before it runs — a control that
     * silently does nothing.
     */
    @Test
    fun derivedAndContextKeyParamsAreNotOffered() {
        val names = choices().map { it.second }.toSet()
        for (never in listOf("out_w", "out_h", "model", "width", "height")) {
            assertTrue("\"$never\" must not be sweepable", never !in names)
        }
    }

    /** ⚠ Every offered param must name a node that is really in the graph. */
    @Test
    fun everyChoiceNamesARealNode() {
        val ids = inpaintWorkflow().graph.nodes.map { it.id }.toSet()
        assertEquals(emptyList<String>(), choices().map { it.first }.filter { it !in ids })
    }
}

/**
 * ⚠⚠ The terminal-node rule, which decides which pictures a sweep collects.
 * A wrong answer here is twenty minutes of collecting the wrong node.
 */
class TerminalImageNodeTest {

    @Test
    fun theInpaintRecipeCollectsItsDecode() {
        val g = com.abrah.nightmare.canvas.inpaintWorkflow().graph
        assertEquals(listOf("decode"), terminalImageNodes(g, NODE_TYPES))
    }

    /** ⭐ An Upscale wired after the decode moves the answer, with no list to edit. */
    @Test
    fun anUpscaleAfterTheDecodeBecomesTheTerminal() {
        val g = com.abrah.nightmare.canvas.inpaintWorkflow().graph
        val withUp = Graph(
            g.nodes + Node(
                "up", "image.upscale",
                mapOf("upscaler" to "upscaler_anime"),
                sources("image" to "decode"),
            )
        )
        assertEquals(listOf("up"), terminalImageNodes(withUp, NODE_TYPES))
    }

    /**
     * ⚠⚠ TWO unconsumed image nodes has no single answer, so the rule returns
     * both and the caller refuses. Picking one silently is the failure this
     * shape exists to prevent.
     */
    @Test
    fun twoUnconsumedImageNodesAreBothReturned() {
        val g = com.abrah.nightmare.canvas.inpaintWorkflow().graph
        val two = Graph(
            g.nodes + Node(
                "up", "image.upscale",
                mapOf("upscaler" to "upscaler_anime"),
                sources("image" to "frame"),
            )
        )
        assertEquals(setOf("decode", "up"), terminalImageNodes(two, NODE_TYPES).toSet())
    }
}
