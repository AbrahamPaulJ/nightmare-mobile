package com.abrah.nightmare

import androidx.test.core.app.ApplicationProvider
import com.abrah.nightmare.canvas.RECIPES
import com.abrah.nightmare.canvas.WAN_I2V
import com.abrah.nightmare.canvas.WAN_T2V
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ⭐ "Current model" names the model the OPEN flow runs (`HarnessViewModel.modelLabel`). Reported on
 * 1.6.152: a Wan flow kept showing the last image model, because a video model is never selected.
 */
@RunWith(RobolectricTestRunner::class)
class ModelLabelTest {

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val vm = HarnessViewModel(app)
    private val wan = ModelCatalog.WAN22.label

    private fun opened(id: String) { vm.openRecipe(RECIPES.first { it.id == id }); vm.confirmPendingOpen() }

    @Test
    fun aWanFlowShowsWanAndAnImageFlowShowsItsCheckpointAgain() {
        opened("txt2img")
        assertFalse(vm.modelLabel, vm.modelLabel.contains(wan))
        opened(WAN_T2V)
        assertTrue(vm.modelLabel, vm.modelLabel.startsWith(wan))
        assertFalse("the selection is untouched: new image flows still take a checkpoint", SelectedModel.id == ModelCatalog.WAN22.id)
        opened(WAN_I2V)
        assertTrue(vm.modelLabel, vm.modelLabel.startsWith(wan))
        opened("txt2img")
        assertFalse(vm.modelLabel, vm.modelLabel.contains(wan))
    }

    /** ⭐ Neodragon too (reported 2026-10-14: Text to video kept the last checkpoint). */
    @Test
    fun aNeodragonFlowShowsNeodragon() {
        opened("txt2img")
        assertFalse(vm.modelLabel, vm.modelLabel.contains("Neodragon"))
        opened("t2v")
        assertTrue(vm.modelLabel, vm.modelLabel == com.abrah.nightmare.npu.NpuFiles.LABEL)
    }
}
