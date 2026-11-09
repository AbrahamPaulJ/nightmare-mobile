package com.abrah.nightmare

import android.graphics.Bitmap
import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.abrah.nightmare.canvas.ResultsStore
import com.abrah.nightmare.canvas.defaultWorkflow
import java.io.File
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** ⭐ A result's flow opens with that result on its Output, and its clip when it has one (found driving the phone, 2026-11-08). */
@RunWith(RobolectricTestRunner::class)
class ResultFlowOutputTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val vm = HarnessViewModel(app)
    private val store = ResultsStore(File(app.filesDir, "results"))

    @org.junit.After fun stop() { vm.viewModelScope.cancel() }

    private fun bitmap() = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888)
    private fun output() = vm.canvas.workflow.graph.nodes.first { it.type == MediaOutputNode.name }

    /** The picture is decoded off the main thread, so wait for it (a bound, never a sleep to a guess). */
    private fun awaitPreview(id: String) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (vm.canvas.previews[id] != null) return
            Thread.sleep(25)
        }
    }

    @Test
    fun aPictureShowsOnTheOutputWithNoClip() {
        val r = store.keep(bitmap(), "img_a", defaultWorkflow(), NODE_TYPES, "1", "m", "a cat")
        vm.openResultFlow(r.id)
        val out = output().id
        awaitPreview(out)
        val shown = vm.canvas.previews[out]
        assertNotNull(shown)
        assertEquals(2f, shown!!.second, 0.01f)
        assertNull(vm.canvas.videos[out])
    }

    @Test
    fun aClipsFlowShowsItsPosterAndKeepsTheClip() {
        val src = File(app.cacheDir, "clip.mp4").apply { writeBytes(ByteArray(2048) { 7 }) }
        val r = store.keep(bitmap(), "img_b", defaultWorkflow(), NODE_TYPES, "1", "m", "a cat", video = src)
        vm.openResultFlow(r.id)
        val out = output().id
        awaitPreview(out)
        assertNotNull(vm.canvas.previews[out])
        assertEquals(store.clipFile(r.id)!!.path, vm.canvas.videos[out])
    }
}
