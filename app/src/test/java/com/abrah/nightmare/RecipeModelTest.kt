package com.abrah.nightmare

import androidx.test.core.app.ApplicationProvider
import com.abrah.nightmare.canvas.defaultWorkflow
import com.abrah.nightmare.canvas.img2imgWorkflow
import com.abrah.nightmare.canvas.inpaintWorkflow
import com.abrah.nightmare.canvas.runsOn
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ⭐⭐ Every recipe must build nodes whose MODEL belongs to the node's own
 * FAMILY.
 *
 * ⚠⚠ This is the test the suite did not have on 2026-09-20, and the bug it
 * missed was found in thirty seconds by a person holding the phone: with
 * FLUX.2 selected, "Inpaint" built an `sd15.inpaint` node carrying
 * `flux2_klein_4b`. `samplerType` correctly fell back to SD 1.5 inpaint
 * because the DiT families have no inpaint type, and `ctxKeyParams`
 * independently returned `SelectedModel.id` — so the type and the model
 * disagreed. It still RENDERED (the backend takes the model it is handed),
 * which is exactly why nothing caught it; the tell was that the checkpoint
 * picker then refused to offer FLUX back, because a DiT model on an inpaint
 * node is a thing it deliberately hides.
 *
 * ⇒ The invariant is not "the inpaint recipe uses AbsoluteReality Inpaint".
 * It is **every recipe, every selected checkpoint, model family == node
 * family** — which is what stops the next family from re-introducing it.
 */
@RunWith(RobolectricTestRunner::class)
class RecipeModelTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    @After
    fun reset() {
        SelectedModel.set(ctx, V1_MODEL)
    }

    /** The sampler node a recipe built, whichever recipe it was. */
    private fun samplerOf(w: com.abrah.nightmare.canvas.Workflow): Node =
        w.graph.nodes.first { it.type in IMAGE_SAMPLER_TYPES }

    private fun familyOfType(type: String): Family? =
        SdSampler.ALL.firstOrNull { it.name == type }?.family

    /**
     * ⭐ The whole invariant, over every catalogue entry × every recipe. A new
     * family or a new recipe is covered the day it is added.
     */
    @Test
    fun everyRecipeBuildsAModelOfItsOwnNodesFamily() {
        val recipes = listOf(
            "text to image" to ::defaultWorkflow,
            "image to image" to ::img2imgWorkflow,
            "inpaint" to ::inpaintWorkflow,
        )
        for (spec in ModelCatalog.builtIn) {
            SelectedModel.set(ctx, spec.id)
            for ((label, build) in recipes) {
                val node = samplerOf(build())
                val nodeFamily = familyOfType(node.type)
                assertNotNull("$label built an unknown sampler type ${node.type}", nodeFamily)
                val modelId = node.params["model"].orEmpty()
                val model = ModelCatalog.byId(modelId)
                assertNotNull(
                    "$label with ${spec.id} selected named a model the catalogue does not know: $modelId",
                    model,
                )
                assertEquals(
                    "$label with ${spec.id} selected built a ${node.type} node " +
                        "carrying $modelId, which is ${model!!.family}",
                    nodeFamily,
                    model.family,
                )
            }
        }
    }

    /**
     * ⚠ The exact reported case, kept as its own test so a failure names it:
     * a checkpoint whose family CANNOT inpaint, with the Inpaint recipe opened.
     *
     * ⭐ Z-Image, not FLUX.2, since 1.5.507. The original report was about
     * FLUX.2, but ABI 3 gave Klein a real `mask_image` and it now has an
     * inpaint type of its own — so the family that still has none is Z-Image,
     * and it is the one that must substitute. The INVARIANT is unchanged: the
     * recipe never builds a node whose model's family has no inpaint type.
     */
    @Test
    fun inpaintWithANonInpaintFamilySelectedSubstitutes() {
        val orphan = ModelCatalog.builtIn.firstOrNull { spec ->
            SdSampler.ALL.none { it.family == spec.family && it.inpaint }
        }
        assertNotNull("every family can inpaint — this test has nothing to check", orphan)
        SelectedModel.set(ctx, orphan!!.id)
        val node = samplerOf(inpaintWorkflow())
        val model = ModelCatalog.byId(node.params["model"].orEmpty())
        assertNotNull(model)
        assertTrue(
            "the inpaint recipe carried ${model!!.id}, whose family cannot inpaint, onto ${node.type}",
            SdSampler.ALL.any { it.family == model.family && it.inpaint },
        )
        // ⚠ And the node must be an inpaint node at all — falling back to a
        // plain sd15.sample would silently drop the mask the recipe wires in.
        assertTrue(
            "${node.type} is not an inpaint sampler",
            (SdSampler.ALL.first { it.name == node.type }).inpaint,
        )
    }

    /**
     * ⭐⭐ FLUX.2 is now an inpaint family, so the recipe must KEEP it rather
     * than substituting — the other half of the rule above, and the thing that
     * would silently regress if `flux2.inpaint` were ever dropped.
     */
    @Test
    fun inpaintKeepsFluxWhenFluxIsSelected() {
        val flux = ModelCatalog.builtIn.firstOrNull { spec ->
            spec.isDit && SdSampler.ALL.any { it.family == spec.family && it.inpaint }
        } ?: return
        SelectedModel.set(ctx, flux.id)
        val node = samplerOf(inpaintWorkflow())
        assertEquals(
            "the inpaint recipe substituted away from a DiT family that CAN inpaint",
            flux.id,
            node.params["model"].orEmpty(),
        )
        assertTrue((SdSampler.ALL.first { it.name == node.type }).inpaint)
    }

    /**
     * ⭐ The user's ask, 2026-09-20: prefer a checkpoint that is actually
     * downloaded, and on an inpaint node prefer a TRUE inpainting one.
     *
     * ⚠ Drives the [ModelCatalog.installedIds] cache directly rather than
     * writing 1 GB of fixture to disk — the cache is the only thing
     * `ctxKeyParams` reads, since a recipe has no `Context`.
     */
    @Test
    fun inpaintPrefersAnInstalledInpaintCheckpoint() {
        // ⚠ A family that cannot inpaint, so the recipe has to SUBSTITUTE and
        // the preference below is what picks the replacement. With FLUX.2
        // selected there is nothing to choose — it keeps its own checkpoint.
        val dit = ModelCatalog.builtIn.firstOrNull { spec ->
            SdSampler.ALL.none { it.family == spec.family && it.inpaint }
        } ?: return
        val inpaintSpec = ModelCatalog.builtIn.firstOrNull { it.isInpaint }
        assertNotNull("no true inpainting checkpoint in the catalogue", inpaintSpec)
        SelectedModel.set(ctx, dit.id)

        // Nothing installed: it still has to name a model of the right family.
        val bare = ModelCatalog.byId(samplerOf(inpaintWorkflow()).params["model"].orEmpty())
        assertNotNull("with nothing installed the recipe named no known model", bare)

        // ⚠ The inpaint checkpoint present, so it must win — even though the
        // catalogue lists plainer SD 1.5 entries before it.
        ModelCatalog.installedIds = listOf(inpaintSpec!!.id)
        try {
            val picked = samplerOf(inpaintWorkflow()).params["model"].orEmpty()
            assertEquals(
                "the inpaint recipe did not prefer the installed inpainting checkpoint",
                inpaintSpec.id,
                picked,
            )
        } finally {
            ModelCatalog.installedIds = emptyList()
        }
    }

    /**
     * ⭐⭐⭐ **The Use dialog may only offer a flow the model can RUN.**
     *
     * ⚠⚠ Reported from the phone 2026-09-20: *"z-image shouldnt have
     * inpaint"*. The dialog filtered on `usesCheckpoint`, which asks whether a
     * flow needs A checkpoint and never whether it can use THIS one — so Use
     * on Z-Image offered Inpaint, and Z-Image has no inpaint type.
     *
     * ⚠ Over the whole catalogue, not the two families that had the bug: the
     * next family added is covered the day it appears. The oracle is
     * [SdSampler.canInpaint], which is also what the checkpoint picker asks —
     * one rule, two surfaces.
     */
    @Test
    fun useOffersOnlyFlowsTheModelCanRun() {
        var sawInpaintCapable = false
        var sawInpaintIncapable = false
        for (spec in ModelCatalog.builtIn) {
            SelectedModel.set(ctx, spec.id)
            val offered = com.abrah.nightmare.canvas.RECIPES.filter { it.runsOn(spec) }
            // A flow that needs no checkpoint is never offered for one.
            for (r in offered) {
                assertTrue(
                    "${spec.id} was offered ${r.id}, which does not run on a checkpoint",
                    r.usesCheckpoint,
                )
            }
            val masked = offered.filter { r ->
                r.build().graph.nodes.any { (NODE_TYPES[it.type] as? SdSampler)?.inpaint == true }
            }
            if (SdSampler.canInpaint(spec.family)) {
                sawInpaintCapable = true
                assertTrue(
                    "${spec.id} can inpaint but was offered no inpainting flow",
                    masked.isNotEmpty(),
                )
            } else {
                sawInpaintIncapable = true
                assertEquals(
                    "${spec.id} cannot inpaint but Use offered ${masked.map { it.id }}",
                    emptyList<String>(),
                    masked.map { it.id },
                )
            }
        }
        // ⚠⚠ Both halves must actually have been exercised, or a catalogue
        // that lost every DiT model would make this test pass by vacuum.
        assertTrue("no inpaint-capable family in the catalogue", sawInpaintCapable)
        assertTrue("no inpaint-incapable family in the catalogue", sawInpaintIncapable)
    }

    /**
     * ⚠ Z-Image and FLUX.2 by NAME, because they are the two the report was
     * about and a rule can be right in general while the catalogue quietly
     * stops containing the case that mattered.
     */
    @Test
    fun theDitFamiliesCannotInpaint() {
        assertTrue("Z-Image gained an inpaint type", !SdSampler.canInpaint(Family.ZIMAGE))
        assertTrue("FLUX.2 gained an inpaint type", !SdSampler.canInpaint(Family.FLUX2))
        assertTrue("SD 1.5 lost its inpaint type", SdSampler.canInpaint(Family.SD15))
    }

    /**
     * ⭐⭐⭐ **A FLUX.2 node is born at denoise 1.0, and the recipes agree.**
     *
     * ⚠⚠ For Klein denoise is the MODE, not a strength: 1.0 is "edit" (the
     * base as a clean reference, full distilled schedule) and below 1.0 with a
     * reference wired is the combination that returns the input unchanged.
     * 0.65 came from the SD samplers, where it means something else.
     *
     * ⚠ The recipes are checked as well as the widget, because they used to
     * hardcode "0.65" — two magic numbers, and the node default would have
     * been silently overridden by the flow that opens it.
     */
    @Test
    fun fluxNodesAreBornAtDenoiseOne() {
        assertEquals("1.0", SdSampler.defaultDenoise(Family.FLUX2))
        for (f in Family.entries.filter { it != Family.FLUX2 }) {
            assertEquals(
                "$f should keep the ordinary img2img default",
                "0.65",
                SdSampler.defaultDenoise(f),
            )
        }
        // ⚠ …and through the recipe that actually opens, per family.
        for (spec in ModelCatalog.builtIn) {
            SelectedModel.set(ctx, spec.id)
            val node = samplerOf(img2imgWorkflow())
            val want = SdSampler.defaultDenoise(familyOfType(node.type)!!)
            assertEquals(
                "image to image on ${spec.id} built denoise ${node.params["denoise"]}",
                want,
                node.params["denoise"],
            )
        }
    }
}
