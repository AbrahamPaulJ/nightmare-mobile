package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pointing a whole graph at one model — the "which rewrites every node" half of
 * what the executor tells a user to do.
 *
 * ⚠⚠ The reason this is a pure function with a test rather than a loop inside
 * the view model: the state it repairs is one the user CANNOT get out of by
 * hand. `model`, `width` and `height` are locked knobs, so a graph holding two
 * of them refuses to run and offers no editable way back. A retarget that
 * missed a node would leave exactly that state while reporting success.
 */
class ContextKeyRetargetTest {

    private val types = NODE_TYPES

    /** A different family, so the SIZE has to move as well as the id. */
    private val sdxlish = ModelCatalog.all.first().copy(
        id = "somexl",
        family = Family.SDXL,
        backendType = "sdxl",
        resolutions = listOf(Res(1024, 1024)),
    )

    private fun graph(model: String) = Graph(
        listOf(
            Node("text", "sd.clip_encode", mapOf("prompt" to "a cat", "negative" to "")),
            Node(
                "sample", "sd.sample",
                mapOf("model" to model, "width" to "512", "height" to "512"),
                sources("cond" to "text"),
            ),
            Node(
                "decode", "sd.vae_decode",
                mapOf("model" to model, "width" to "512", "height" to "512"),
                sources("latent" to "sample"),
            ),
        )
    )

    /** ⭐ Every backend node moves, and the size comes from the model. */
    @Test
    fun everyBackendNodeIsRetargetedIncludingItsResolution() {
        val changes = contextKeyRetarget(graph(V1_MODEL), types, sdxlish)
        assertEquals(setOf("sample", "decode"), changes.keys)
        for ((_, p) in changes) {
            assertEquals("somexl", p["model"])
            assertEquals("1024", p["width"])
            assertEquals("1024", p["height"])
        }
    }

    /**
     * ⚠ `sd.clip_encode` carries no context-key knob, so it is not touched --
     * and neither is a node that merely happens to have a param of the same
     * name. Driven by the WIDGET declaration, not by a list of param names.
     */
    @Test
    fun aNodeWithNoContextKeyKnobsIsLeftAlone() {
        val changes = contextKeyRetarget(graph(V1_MODEL), types, sdxlish)
        assertTrue("text" !in changes)
    }

    /** ⚠ "Nothing to do" is an empty map, so the caller need not diff the graph. */
    @Test
    fun retargetingToTheModelAlreadyNamedChangesNothing() {
        val already = ModelCatalog.byId(V1_MODEL)!!
        assertEquals(emptyMap<String, Map<String, String>>(),
            contextKeyRetarget(graph(V1_MODEL), types, already))
    }

    /**
     * ⭐⭐ The state this exists for: a graph the executor refuses because it
     * names two models. Retargeting must leave exactly ONE context key.
     */
    @Test
    fun aMixedGraphCollapsesToOneContextKey() {
        val mixed = Graph(
            graph(V1_MODEL).nodes.map {
                if (it.id == "decode") it.copy(params = it.params + ("model" to "anythingv5")) else it
            }
        )
        assertEquals(2, mixed.nodes.mapNotNull { types[it.type]?.contextKey(it) }.toSet().size)

        val changes = contextKeyRetarget(mixed, types, ModelCatalog.byId(V1_MODEL)!!)
        val fixed = Graph(
            mixed.nodes.map { n -> changes[n.id]?.let { n.copy(params = n.params + it) } ?: n }
        )
        assertEquals(1, fixed.nodes.mapNotNull { types[it.type]?.contextKey(it) }.toSet().size)
    }

    // ---- what a graph NAMES, which is what an opened workflow follows -----

    /** ⭐ The models a graph names, which is what opening it must follow. */
    @Test
    fun theModelsAGraphNamesAreReadOffTheContextKeyNodes() {
        assertEquals(listOf(V1_MODEL), contextKeyModels(graph(V1_MODEL), types))
    }

    /**
     * ⚠⚠ Two models is the case an opened workflow must NOT resolve: there is
     * no single answer, and the executor refuses the run by name instead.
     */
    @Test
    fun aMixedGraphNamesTwo() {
        val mixed = Graph(
            graph(V1_MODEL).nodes.map {
                if (it.id == "decode") it.copy(params = it.params + ("model" to "anythingv5")) else it
            }
        )
        assertEquals(listOf(V1_MODEL, "anythingv5"), contextKeyModels(mixed, types))
    }

    /**
     * ⚠ It must not throw on a graph that cannot RUN -- it is asked on the path
     * that OPENS a workflow, and a broken graph still has to appear on screen.
     */
    @Test
    fun aGraphWithMissingParamsStillAnswers() {
        val broken = Graph(listOf(Node("sample", "sd.sample", emptyMap())))
        assertEquals(emptyList<String>(), contextKeyModels(broken, types))
    }

    /** ⚠ Only the params that actually differ, so an unchanged node is not a graph revision. */
    @Test
    fun onlyTheParamsThatDifferAreReported() {
        val sameSize = ModelCatalog.byId("anythingv5")!!
        val changes = contextKeyRetarget(graph(V1_MODEL), types, sameSize)
        assertEquals(setOf("sample", "decode"), changes.keys)
        assertEquals(mapOf("model" to "anythingv5"), changes["sample"])
    }

    /**
     * ⭐⭐ Switching models WRITES the new checkpoint's recipe onto the graph.
     *
     * ⚠⚠ The bug this pins: `steps`/`cfg`/`scheduler` default from the selected
     * model, so a node carrying none of them rendered differently after a model
     * switch while the file on disk was byte-identical — and the inspector
     * showed the new model's numbers whether or not that is what ran. Reported
     * from the phone 2026-09-10 as "it shows cfg 1.5 and 10 steps but it didn't
     * register"; moving the sliders to the same numbers fixed it, because THAT
     * wrote them down.
     */
    @Test
    fun switchingModelsWritesTheRecipeOntoSamplers() {
        val g = Graph(
            listOf(
                Node("text", "sd.clip_encode", mapOf("prompt" to "x", "negative" to "")),
                Node("s", "sd.sample", mapOf("model" to "absolutereality"),
                    inputs = sources("cond" to "text")),
            )
        )
        val distilled = ModelCatalog.byId(V1_MODEL)!!.copy(
            id = "turbo", steps = 10, cfg = 1.5, scheduler = "euler_a",
        )
        val changes = modelRecipeRetarget(g, NODE_TYPES, distilled)
        assertEquals(setOf("s"), changes.keys)
        assertEquals("10", changes["s"]!!["steps"])
        assertEquals("1.5", changes["s"]!!["cfg"])
        assertEquals("euler_a", changes["s"]!!["scheduler"])
    }

    /** ⚠ The seed is the USER'S. A model switch must not take it away. */
    @Test
    fun theRecipeNeverTouchesTheSeed() {
        val g = Graph(
            listOf(
                Node("text", "sd.clip_encode", mapOf("prompt" to "x", "negative" to "")),
                Node("s", "sd.sample", mapOf("seed" to "12345"),
                    inputs = sources("cond" to "text")),
            )
        )
        val spec = ModelCatalog.byId(V1_MODEL)!!.copy(steps = 10, cfg = 1.5)
        assertFalse("seed" in modelRecipeRetarget(g, NODE_TYPES, spec)["s"]!!.keys)
    }

    /** ⚠ A node with no such knobs is left alone — this goes by WIDGET. */
    @Test
    fun nodesWithoutTheseKnobsAreUntouched() {
        val g = Graph(listOf(Node("d", "sd.vae_decode", mapOf("model" to "x"))))
        assertTrue(modelRecipeRetarget(g, NODE_TYPES, ModelCatalog.byId(V1_MODEL)!!).isEmpty())
    }

    /** ⚠ Nothing to do when the graph already says what the model wants. */
    @Test
    fun anAlreadyCorrectGraphProducesNoChanges() {
        val spec = ModelCatalog.byId(V1_MODEL)!!
        val g = Graph(
            listOf(
                Node("text", "sd.clip_encode", mapOf("prompt" to "x", "negative" to "")),
                Node(
                    "s", "sd.sample",
                    mapOf(
                        "steps" to spec.steps.toString(),
                        "cfg" to spec.cfg.toString(),
                        "scheduler" to spec.scheduler,
                    ),
                    inputs = sources("cond" to "text"),
                ),
            )
        )
        assertTrue(modelRecipeRetarget(g, NODE_TYPES, spec).isEmpty())
    }
}
