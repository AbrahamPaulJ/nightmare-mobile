package com.abrah.nightmare

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ⭐ GitHub #68 and the user's report of 2026-10-27 ("when i repairing neodragon (downloading), i cant use abs
 * reality"): a run goes ahead beside ANY download but a move, unless it loads the thing downloading.
 */
@RunWith(RobolectricTestRunner::class)
class RunBesideDownloadTest {

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun aRunGoesAheadBesideADownloadItDoesNotUse() {
        val vm = HarnessViewModel(app)
        val sd = { listOf("absolutereality") }
        // ⭐ Neodragon's download (the report), a tool's, another checkpoint's.
        assertTrue(vm.runsBesideDownload("\u0000video", sd))
        assertTrue(vm.runsBesideDownload("pose-tool", sd))
        assertTrue(vm.runsBesideDownload("qteamix", sd))
        // ⚠ Refused: the run loads it, models are moving, or the caller cannot say what it loads.
        assertFalse(vm.runsBesideDownload("absolutereality", sd))
        assertFalse(vm.runsBesideDownload("\u0000move", sd))
        assertFalse(vm.runsBesideDownload("\u0000video", null))
    }

    @Test
    fun aNeodragonCanvasUsesTheVideoDownload() {
        val vm = HarnessViewModel(app)
        vm.openWorkflow(com.abrah.nightmare.canvas.textToVideoWorkflow())
        assertTrue("\u0000video" in vm.canvasModels())
        assertFalse(vm.runsBesideDownload("\u0000video", vm::canvasModels))
    }
}
