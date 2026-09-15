package com.abrah.nightmare

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐⭐⭐ The LAST-NODE rule — `isLastOfKind`, and the two things it decides.
 *
 * The user's rule, 2026-09-15: only the last sampler in a chain may arm a batch
 * sweep, and only the last output node owns save / star / download and feeds
 * Results.
 *
 * ⚠⚠ The reason it is worth a test of its own is the BRANCH. Phrased as "the
 * furthest-downstream node" the rule has no answer when two chains each end in
 * an output; phrased as "nothing of its own KIND downstream" it has one, and it
 * is the answer the user asked for — both branches act, each on its own.
 */
class LastNodeTest {

    private fun sampler(id: String, vararg from: Pair<String, String>) =
        Node(id, "sd15.sample", emptyMap(), sources(*from))

    private fun output(id: String, from: String) =
        Node(id, "core.output", emptyMap(), sources("media" to from))

    /** `prompt → a → b → out`: only `b` may sweep. */
    @Test
    fun onlyTheLastSamplerInAChainMaySweep() {
        val g = Graph(
            listOf(
                Node("prompt", "core.prompt"),
                sampler("a", "prompt" to "prompt"),
                sampler("b", "prompt" to "prompt", "image" to "a"),
                output("out", "b"),
            )
        )
        assertFalse("sweeping `a` would re-run `b` for every value", canSweep(g, "a"))
        assertTrue(canSweep(g, "b"))
    }

    /** ⚠ A lone sampler is trivially the last one. */
    @Test
    fun aSingleSamplerMaySweep() {
        val g = Graph(
            listOf(Node("prompt", "core.prompt"), sampler("s", "prompt" to "prompt"), output("o", "s"))
        )
        assertTrue(canSweep(g, "s"))
    }

    /**
     * ⭐⭐ Two branches, two last samplers — BOTH may sweep, because neither is
     * downstream of the other and each owns its own chain.
     */
    @Test
    fun bothBranchesHaveALastSampler() {
        val g = Graph(
            listOf(
                Node("prompt", "core.prompt"),
                sampler("left", "prompt" to "prompt"),
                sampler("right", "prompt" to "prompt"),
                output("lo", "left"),
                output("ro", "right"),
            )
        )
        assertTrue(canSweep(g, "left"))
        assertTrue(canSweep(g, "right"))
        assertTrue(isLastOutput(g, "lo"))
        assertTrue(isLastOutput(g, "ro"))
    }

    /** ⚠ An output with another output downstream of it does NOT act. */
    @Test
    fun onlyTheLastOutputActs() {
        val g = Graph(
            listOf(
                Node("prompt", "core.prompt"),
                sampler("s", "prompt" to "prompt"),
                output("first", "s"),
                output("second", "first"),
            )
        )
        assertFalse(isLastOutput(g, "first"))
        assertTrue(isLastOutput(g, "second"))
    }

    /** ⚠ Neither question applies to a node of another kind. */
    @Test
    fun theRuleIsPerKind() {
        val g = Graph(
            listOf(Node("prompt", "core.prompt"), sampler("s", "prompt" to "prompt"), output("o", "s"))
        )
        // ⭐ An OUTPUT downstream does not stop a sampler being the last SAMPLER.
        assertTrue(canSweep(g, "s"))
        assertFalse("a prompt is not a sampler", canSweep(g, "prompt"))
        assertFalse("a sampler is not an output", isLastOutput(g, "s"))
    }

    /**
     * ⚠⚠ A graph the executor would refuse must still answer, because this runs
     * while a SHEET is opening — a graph that will not run must still open.
     */
    @Test
    fun aCycleDoesNotHang() {
        val g = Graph(
            listOf(
                sampler("a", "image" to "b"),
                sampler("b", "image" to "a"),
            )
        )
        // ⚠ The answer does not matter; not hanging does.
        canSweep(g, "a")
        assertTrue(true)
    }
}
