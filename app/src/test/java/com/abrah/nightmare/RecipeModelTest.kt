package com.abrah.nightmare

import androidx.test.core.app.ApplicationProvider
import com.abrah.nightmare.canvas.defaultWorkflow
import com.abrah.nightmare.canvas.img2imgWorkflow
import com.abrah.nightmare.canvas.inpaintWorkflow
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
}
