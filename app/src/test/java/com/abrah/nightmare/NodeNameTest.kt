package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐⭐⭐ **One name per node, and both surfaces read it from here.**
 *
 * ⚠⚠⚠ Reported 2026-09-21 as "when we choose flux, the node doesn't change to
 * edit node". [SdSampler.titleFor] had been returning "FLUX.2 Image edit" all
 * along — what never changed was the **id**, and the id was the big line on the
 * canvas box and the only line on the inspector's tabs. ⇒ These assert the
 * naming, not the title, because the title was never the broken half.
 */
class NodeNameTest {

    private fun flux(id: String, wired: Boolean = true) = Node(
        id, SdSampler.FLUX2.name, emptyMap(),
        if (wired) mapOf("image" to Source("photo")) else emptyMap(),
    )

    /** The title itself — the half that always worked, pinned so it stays that way. */
    @Test
    fun aWiredFluxNodeIsAnEdit() {
        assertEquals("FLUX.2 Text to image", SdSampler.FLUX2.titleFor(flux("x", wired = false)))
        assertEquals("FLUX.2 Image edit", SdSampler.FLUX2.titleFor(flux("x")))
    }

    /**
     * ⚠⚠⚠ **The actual bug.** A node added as SD 1.5 keeps the id
     * `sd15_generate` when its model is switched to FLUX.2 — the swap retypes
     * the node and leaves the id alone, because wires reference it. So the
     * auto-id test has to accept ANY type's default, not the current type's,
     * or that stale id reads as a name the user chose and gets shown.
     */
    @Test
    fun anIdLeftOverFromAnotherFamilyIsStillAutomatic() {
        assertTrue("sd15_generate", isAutoNodeId("sd15_generate"))
        assertTrue("flux2_generate", isAutoNodeId("flux2_generate"))
        assertTrue("the _2 suffix freeId adds", isAutoNodeId("sd15_generate_2"))
        assertFalse("a name a person typed", isAutoNodeId("hero shot"))
        assertFalse("a name a person typed", isAutoNodeId("sd15_generate_final"))
    }

    @Test
    fun anAutoNamedNodeLeadsWithWhatItDoes() {
        val n = nodeNameOf(Node("sd15_generate", SdSampler.FLUX2.name, emptyMap(),
            mapOf("image" to Source("photo"))))
        assertEquals("Image edit", n.primary)
        // ⚠ The id survives as the small line: the run log and every error
        // message name a node by it, so it has to be findable on the node.
        assertEquals("FLUX.2", n.secondary)
    }

    @Test
    fun aRenamedNodeKeepsTheNameYouGaveIt() {
        val n = nodeNameOf(flux("hero shot"))
        assertEquals("hero shot", n.primary)
        assertEquals("FLUX.2 Image edit", n.secondary)
    }

    /** ⚠ No line saying the same thing twice — the 2026-09-15 rule, kept. */
    @Test
    fun aSecondLineThatWouldRepeatTheFirstIsDropped() {
        val n = nodeNameOf(Node("FLUX.2 Image edit", SdSampler.FLUX2.name, emptyMap(),
            mapOf("image" to Source("photo"))))
        assertEquals("FLUX.2 Image edit", n.primary)
        assertEquals(null, n.secondary)
    }

    /**
     * ⭐⭐⭐ **The Image edit FLOW, with its checkpoint changed.** The user's
     * report, 2026-09-21: *"when i pick flux edit workflow and from sample
     * dropdown i change to a different model, sample still named edit"*.
     *
     * ⚠⚠⚠ A recipe hand-writes the id `edit`, which is no type's
     * `defaultId` — so the first version of this fix read it as a name the user
     * had chosen and kept showing it. The recipes' own ids have to count as
     * generated, or the fix misses the exact flow it was written for.
     */
    @Test
    fun theEditFlowStopsSayingEditWhenYouLeaveFlux() {
        val recipe = com.abrah.nightmare.canvas.RECIPES.first { it.id == "flux_edit" }
        val sampler = recipe.build().graph.nodes.first { it.type in IMAGE_SAMPLER_TYPES }
        assertEquals("the recipe still hand-writes this id", "edit", sampler.id)
        assertTrue("a recipe's own id is generated, not chosen", isAutoNodeId(sampler.id))
        assertEquals("Image edit", nodeNameOf(sampler).primary)

        // …and the move the user actually made.
        val toSd = sampler.copy(type = SdSampler.SD15.name)
        assertEquals("Image to image", nodeNameOf(toSd).primary)
    }

    /** ⚠ The other recipes write `generate`, and that is the word that was seen. */
    @Test
    fun theRecipesPlainGenerateIdIsGeneratedToo() {
        assertTrue(isAutoNodeId("generate"))
        assertTrue(isAutoNodeId("prompt"))
        assertTrue(isAutoNodeId("output"))
    }

    /**
     * ⭐⭐⭐ **A node that is NOT a sampler keeps its id as its name**, even
     * though that id is one a recipe generated.
     *
     * ⚠⚠⚠ A first version overrode every auto id and renamed the source
     * node from `photo` to `image` on five goldens. `photo` is the word the
     * recipe chose and it is better than the type's label — and unlike a
     * sampler, a `core.image` node never gets retyped under the user, so its
     * id cannot go stale. ⇒ The override is for samplers alone.
     */
    @Test
    fun onlyASamplerLosesItsIdToItsTitle() {
        val photo = Node("photo", "core.image", mapOf("uri" to ""))
        assertTrue("the recipe id is still recognised as generated", isAutoNodeId("photo"))
        assertEquals("photo", nodeNameOf(photo).primary)
    }

    /**
     * ⭐ The whole point, stated as one assertion: switching family changes
     * what the node calls itself, on whatever surface asks.
     */
    @Test
    fun switchingFamilyRenamesTheNode() {
        val before = Node("sd15_generate", SdSampler.SD15.name, emptyMap(),
            mapOf("image" to Source("photo")))
        val after = before.copy(type = SdSampler.FLUX2.name)
        assertEquals("Image to image", nodeNameOf(before).primary)
        assertEquals("Image edit", nodeNameOf(after).primary)
    }
}
