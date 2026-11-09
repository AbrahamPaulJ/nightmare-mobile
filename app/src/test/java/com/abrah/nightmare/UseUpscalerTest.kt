package com.abrah.nightmare

import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** ⭐ GitHub #99: Use on an imported upscaler opened the Upscale flow on the node's default, a built-in, so the import never ran. */
@RunWith(RobolectricTestRunner::class)
class UseUpscalerTest {

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val vm = HarnessViewModel(app)

    @org.junit.After fun stop() { vm.viewModelScope.cancel() }

    private fun output() = vm.canvas.workflow.graph.nodes.single { it.type == MediaOutputNode.name }

    private val imported = UpscalerSpec("my_esrgan", "my_esrgan", "Imported ESRGAN: my_esrgan.", "", emptyList(), fileName = "upscaler.pth")

    @Test
    fun useOnAnUpscalerOpensItsFlowOnThatUpscaler() {
        vm.askUseUpscaler(imported)
        val p = vm.pendingUse!!
        vm.confirmUse(p.spec, p.offer.single())
        assertEquals("my_esrgan", output().params[MediaOutputNode.UPSCALER])
        assertEquals("true", output().params[MediaOutputNode.UPSCALE])
        assertNull(vm.pendingUse)
    }

    /** ⚠ The control: opened any other way the flow names no upscaler, so a fresh install runs the first it has. */
    @Test
    fun theFlowOpenedOnItsOwnNamesNone() {
        vm.openRecipe(com.abrah.nightmare.canvas.RECIPES.first { it.id == "upscale" })
        assertNull(output().params[MediaOutputNode.UPSCALER])
    }
}
