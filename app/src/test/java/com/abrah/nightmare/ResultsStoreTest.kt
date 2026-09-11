package com.abrah.nightmare

import android.graphics.Bitmap
import com.abrah.nightmare.canvas.ResultsStore
import com.abrah.nightmare.canvas.defaultWorkflow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Keeping a picture, and the graph that made it.
 *
 * ⭐ The graph is the whole point. A favourite that is only a picture is a
 * photo, and the phone already has somewhere to put photos — so the test that
 * matters is that the flow comes back and still runs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResultsStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = ResultsStore(tmp.newFolder())

    private fun bitmap(w: Int = 64, h: Int = 64) =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366AA.toInt()) }

    /** ⭐⭐ The round trip that IS the feature. */
    @Test
    fun aKeptResultGivesBackItsFlow() {
        val s = store()
        val r = s.keep(bitmap(), "img_test", defaultWorkflow(), NODE_TYPES, "12345", "AbsoluteReality", "a cat")
        assertEquals("12345", r.seed)
        assertEquals("a cat", r.prompt)

        val back = s.flow(r.id)
        assertNotNull("the flow must come back", back)
        // ⚠ The same nodes, not merely *a* graph: a flow that loses its wiring
        // reopens as a pile of disconnected nodes and the feature is worthless.
        assertEquals(
            defaultWorkflow().graph.nodes.map { it.id }.toSet(),
            back!!.workflow.graph.nodes.map { it.id }.toSet(),
        )
        assertEquals(
            defaultWorkflow().graph.byId["sample"]!!.inputs.keys,
            back.workflow.graph.byId["sample"]!!.inputs.keys,
        )
    }

    @Test
    fun listingIsNewestFirstAndSurvivesRestart() {
        val dir = tmp.newFolder()
        ResultsStore(dir).also {
            it.keep(bitmap(), "img_test", defaultWorkflow(), NODE_TYPES, "1", "m", "first")
            Thread.sleep(5)
            it.keep(bitmap(), "img_test", defaultWorkflow(), NODE_TYPES, "2", "m", "second")
        }
        // ⚠ A NEW store over the same directory: nothing may live only in memory.
        val all = ResultsStore(dir).all()
        assertEquals(2, all.size)
        assertEquals("second", all[0].prompt)
    }

    /** ⚠ Both halves or neither — a card that cannot be opened is worse than none. */
    @Test
    fun aResultWithNoImageIsNotListed() {
        val dir = tmp.newFolder()
        val s = ResultsStore(dir)
        val r = s.keep(bitmap(), "img_test", defaultWorkflow(), NODE_TYPES, "1", "m", "p")
        assertTrue(s.imageFile(r.id).delete())
        assertTrue("a result with no picture must not be listed", s.all().isEmpty())
    }

    /** ⚠⚠ One unreadable file must not empty the tab. */
    @Test
    fun brokenMetadataIsSkippedRatherThanThrowing() {
        val dir = tmp.newFolder()
        val s = ResultsStore(dir)
        s.keep(bitmap(), "img_test", defaultWorkflow(), NODE_TYPES, "1", "m", "good")
        java.io.File(dir, "rbroken.json").writeText("{ not json")
        java.io.File(dir, "rbroken.png").writeText("not a png")
        val all = s.all()
        assertEquals(1, all.size)
        assertEquals("good", all[0].prompt)
    }

    @Test
    fun forgettingRemovesBothHalves() {
        val s = store()
        val r = s.keep(bitmap(), "img_test", defaultWorkflow(), NODE_TYPES, "1", "m", "p")
        s.delete(r.id)
        assertTrue(s.all().isEmpty())
        assertNull(s.flow(r.id))
        assertNull(s.full(r.id))
    }

    /**
     * ⭐ The storage claim, in the only form a unit test can honestly make it:
     * the metadata is **kilobytes**.
     *
     * ⚠⚠ It deliberately does NOT compare against the PNG beside it. The
     * fixture is a solid colour, which compresses to ~2 KB — nothing like a
     * render — so a ratio here would be measuring the fixture and would pass or
     * fail on how compressible the test bitmap happened to be. The real
     * comparison was measured on the device: **816 B of workflow against ~2.6 MB
     * of picture**, and no synthetic image can stand in for that.
     */
    @Test
    fun theStoredFlowIsKilobytes() {
        val dir = tmp.newFolder()
        val s = ResultsStore(dir)
        val r = s.keep(bitmap(512, 512), "img_test", defaultWorkflow(), NODE_TYPES, "1", "m", "p")
        val meta = java.io.File(dir, "${r.id}.json").length()
        assertTrue("metadata was $meta bytes, expected a few KB", meta in 1..8_000)
    }

    /** ⚠ Thumbnails are downsampled, or a list of 1024² PNGs is a list of 4 MB bitmaps. */
    @Test
    fun thumbnailsAreSmallerThanTheOriginal() {
        val s = store()
        val r = s.keep(bitmap(1024, 1024), "img_test", defaultWorkflow(), NODE_TYPES, "1", "m", "p")
        val thumb = s.thumbnail(r.id, maxEdge = 128)
        assertNotNull(thumb)
        assertTrue("thumb was ${thumb!!.width}px", thumb.width <= 256)
        assertEquals(1024, s.full(r.id)!!.width)
    }
}
