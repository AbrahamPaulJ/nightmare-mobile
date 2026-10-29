package com.abrah.nightmare

import androidx.test.core.app.ApplicationProvider
import com.abrah.nightmare.canvas.RECIPES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
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

    /** ⚠ The view model's background work (model and disk scans) must not run on into the next test class: a scan
     *  that ends calls `CrashReport.clear`, a process-wide state, and `CrashReportTest` failed under it. */
    @org.junit.After fun stop() { vm.viewModelScope.cancel() }

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

    private fun video() = g().nodes.single { it.type in VIDEO_MODEL_TYPES }
    private val h3 = com.abrah.nightmare.npu.H3Node.name

    /** ⭐ The video pair swaps like t2i and i2i (the user, 2026-10-30), the node's model kept. */
    @Test
    fun theVideoPairSwapsKeepingTheModelItsSettingsAndThePrompt() {
        opened(com.abrah.nightmare.canvas.T2V)
        vm.setVideoModel(video().id, h3)
        vm.editCanvas { s -> s.setParams(prompt().id, mapOf("prompt" to "a cat says hello")) }
        vm.editCanvas { s -> s.setParams(video().id, mapOf("seed" to "77", "seconds" to "0.9")) }
        val flows = vm.swapFlows()!!
        assertEquals(listOf(com.abrah.nightmare.canvas.T2V, com.abrah.nightmare.canvas.I2V), flows.map { it.recipe.id })
        assertEquals(com.abrah.nightmare.canvas.T2V, flows.single { it.current }.recipe.id)

        vm.swapFlow(flows.single { it.takesPicture }.recipe)
        assertEquals(h3, video().type)
        assertNotNull(video().inputs["image"])
        assertEquals("77", video().params["seed"])
        assertEquals("0.9", video().params["seconds"])
        assertEquals("a cat says hello", prompt().params["prompt"])

        val image = g().nodes.single { it.type == "core.image" }
        vm.editCanvas { s -> s.setParam(image.id, "uri", "/sdcard/cat.png") }
        vm.swapFlow(vm.swapFlows()!!.first { !it.takesPicture }.recipe)
        assertNull("Remove input leaves no picture node", g().nodes.firstOrNull { it.type == "core.image" })
        assertEquals(h3, video().type)
        assertEquals("a cat says hello", prompt().params["prompt"])
    }

    @Test
    fun anOpeningPromptBecomesTheOtherFlowsAndTheOneWayModelsHaveNoSwap() {
        opened(com.abrah.nightmare.canvas.T2V)
        vm.setVideoModel(video().id, h3)
        assertEquals(com.abrah.nightmare.npu.VideoPrompts.H3, prompt().params["prompt"])
        vm.swapFlow(RECIPES.first { it.id == com.abrah.nightmare.canvas.I2V })
        assertEquals(com.abrah.nightmare.npu.VideoPrompts.H3_I2V, prompt().params["prompt"])

        vm.setVideoModel(video().id, com.abrah.nightmare.npu.WanA14Node.name)
        assertEquals("Wan 2.2 14B animates a picture only: one flow, so no Swap", 1, vm.swapFlows()!!.size)
    }
}
