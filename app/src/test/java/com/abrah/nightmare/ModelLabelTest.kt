package com.abrah.nightmare

import androidx.test.core.app.ApplicationProvider
import com.abrah.nightmare.canvas.RECIPES
import com.abrah.nightmare.canvas.I2V
import com.abrah.nightmare.canvas.MOTION
import com.abrah.nightmare.canvas.T2V
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

    private fun opened(id: String) { vm.openRecipe(RECIPES.first { it.id == id }) }

    @Test
    fun aWanFlowShowsWanAndAnImageFlowShowsItsCheckpointAgain() {
        opened("txt2img")
        assertFalse(vm.modelLabel, vm.modelLabel.contains(wan))
        opened(T2V)
        assertTrue(vm.modelLabel, vm.modelLabel.startsWith(wan))
        assertFalse("the selection is untouched: new image flows still take a checkpoint", SelectedModel.id == ModelCatalog.WAN22.id)
        opened(I2V)
        assertTrue(vm.modelLabel, vm.modelLabel.startsWith(wan))
        // ⭐ Motion transfer names its own model, Fun Control (2026-10-15).
        opened(MOTION)
        assertTrue(vm.modelLabel, vm.modelLabel.startsWith(ModelCatalog.WAN22_FUN.label))
        opened("txt2img")
        assertFalse(vm.modelLabel, vm.modelLabel.contains(wan))
    }

    /**
     * ⭐ Neodragon too (reported 2026-10-14: Text to video kept the last checkpoint). Since 2026-10-15 the
     * video flows open on Wan and Neodragon is the video node's Model choice ([HarnessViewModel.setVideoModel]).
     */
    @Test
    fun aNeodragonFlowShowsNeodragon() {
        opened("txt2img")
        assertFalse(vm.modelLabel, vm.modelLabel.contains("Neodragon"))
        opened(T2V)
        vm.setVideoModel("video", "nd.sample")
        assertTrue(vm.modelLabel, vm.modelLabel == com.abrah.nightmare.npu.NpuFiles.LABEL)
        assertTrue(vm.canvas.workflow.graph.byId["output"]?.inputs?.get("media")?.node == "video")
        // ⭐ …and back: the wires and seed stay, the node is Wan again.
        vm.setVideoModel("video", com.abrah.nightmare.npu.WanVideoNode.name)
        assertTrue(vm.modelLabel, vm.modelLabel.startsWith(wan))
        // ⭐ Wan 2.2 14B, the same node shape on the big model (2026-10-25).
        vm.setVideoModel("video", com.abrah.nightmare.npu.WanA14Node.name)
        assertTrue(vm.modelLabel, vm.modelLabel.startsWith(com.abrah.nightmare.ModelCatalog.WAN22_A14B.label))
    }
}
