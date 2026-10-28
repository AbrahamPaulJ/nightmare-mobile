package com.abrah.nightmare

import androidx.test.core.app.ApplicationProvider
import com.abrah.nightmare.canvas.RECIPES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ⭐ The Default view's Swap flow and Remove input (`HarnessViewModel.swapFlows` / `swapFlow`, the
 * user's ask 2026-10-11): one-sampler flows only, and what both flows share comes along.
 */
@RunWith(RobolectricTestRunner::class)
class SwapFlowTest {

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val vm = HarnessViewModel(app)

    private fun opened(id: String) { vm.openRecipe(RECIPES.first { it.id == id }) }
    private fun g() = vm.canvas.workflow.graph
    private fun sampler() = g().nodes.single { it.type in IMAGE_SAMPLER_TYPES }
    private fun prompt() = g().nodes.first { it.type == "core.prompt" }

    @Test
    fun aOneSamplerFlowOffersItsModelsFlowsWithTheOpenOneMarked() {
        opened("txt2img")
        val flows = vm.swapFlows()
        assertNotNull(flows)
        assertTrue(flows!!.any { it.recipe.id == "img2img" && it.takesPicture })
        assertTrue(flows.single { it.current }.recipe.id == "txt2img")
        assertFalse(flows.single { it.current }.takesPicture)
    }

    @Test
    fun swappingCarriesThePromptSeedAndPictureAndRemoveInputGoesBackToTextToImage() {
        opened("txt2img")
        vm.editCanvas { s -> s.setParams(prompt().id, mapOf("prompt" to "a lighthouse", "negative" to "fog")) }
        vm.editCanvas { s -> s.setParams(sampler().id, mapOf("seed" to "4242", "steps" to "13")) }

        vm.swapFlow(RECIPES.first { it.id == "img2img" })
        val image = g().nodes.single { it.type == "core.image" }
        assertEquals("a lighthouse", prompt().params["prompt"])
        assertEquals("fog", prompt().params["negative"])
        assertEquals("4242", sampler().params["seed"])
        assertEquals("13", sampler().params["steps"])

        vm.editCanvas { s -> s.setParam(image.id, "uri", "/sdcard/cat.png") }
        val t2i = vm.swapFlows()!!.first { !it.takesPicture }
        vm.swapFlow(t2i.recipe)
        assertNull("Remove input leaves no picture node", g().nodes.firstOrNull { it.type == "core.image" })
        assertEquals("a lighthouse", prompt().params["prompt"])
        assertEquals("4242", sampler().params["seed"])

        vm.swapFlow(RECIPES.first { it.id == "img2img" })
        assertEquals("…and an image flow gets the picture back only if it had one", "", g().nodes.single { it.type == "core.image" }.params["uri"].orEmpty())
    }
}
