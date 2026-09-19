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
            defaultWorkflow().graph.byId["generate"]!!.inputs.keys,
            back.workflow.graph.byId["generate"]!!.inputs.keys,
        )
    }

    /**
     * ⭐⭐ **A starred CLIP keeps the MP4**, not just its poster frame.
     *
     * ⚠⚠ The copy is the point. The file the graph produced lives in
     * `cacheDir/video/`, which Android clears whenever it is short of space —
     * so a result pointing at it would play until the first time that happened
     * and then silently be a still, with nothing saying why. The PNG beside it
     * has always been copied for the same reason.
     */
    @Test
    fun aKeptClipCopiesItsMp4AndForgetsItOnDelete() {
        val dir = tmp.newFolder()
        val s = ResultsStore(dir)
        // ⚠ Bytes, not a real MP4: this pins the COPY, and decoding one
        // would be testing the platform's muxer instead.
        val src = tmp.newFile("clip.mp4").apply { writeBytes(ByteArray(2048) { 7 }) }
        val r = s.keep(
            bitmap(), "img_clip", defaultWorkflow(), NODE_TYPES, "1", "m", "a cat",
            video = src,
        )
        assertNotNull("a kept clip must carry its own copy", r.videoPath)
        assertEquals(2048L, java.io.File(r.videoPath!!).length())
        // ⚠ Read back off DISK on the next launch, not out of the metadata.
        assertNotNull(ResultsStore(dir).all().first().videoPath)

        // ⚠ …and un-starring takes the biggest half with it. A stranded MP4
        // is the one thing here that costs megabytes.
        s.delete(r.id)
        assertTrue("the clip must go with the result", !java.io.File(r.videoPath).isFile)
        // ⚠ The source is the graph's, not ours: deleting a result must
        // never reach back into what produced it.
        assertTrue("the graph's own copy is not ours to delete", src.isFile)
    }

    /**
     * ⭐⭐ [ResultsStore.clipFile] is what Save and Share ask before they
     * decide whether they are handing over an MP4 or a PNG.
     *
     * ⚠⚠ It must agree with [Result.videoPath] and it must answer from the
     * FILE: a result whose clip has been deleted underneath it has to say so,
     * or Share stages a copy of nothing and the user gets an empty MP4.
     */
    @Test
    fun clipFileAnswersForSaveAndShare() {
        val dir = tmp.newFolder()
        val s = ResultsStore(dir)
        val src = tmp.newFile("share.mp4").apply { writeBytes(ByteArray(1024) { 3 }) }
        val clip = s.keep(
            bitmap(), "img_clip", defaultWorkflow(), NODE_TYPES, "1", "m", "a fox",
            video = src,
        )
        val picture = s.keep(bitmap(), "img_pic", defaultWorkflow(), NODE_TYPES, "2", "m", "a cat")

        assertEquals(clip.videoPath, s.clipFile(clip.id)?.path)
        assertEquals(1024L, s.clipFile(clip.id)!!.length())
        assertNull("a picture must not offer a clip", s.clipFile(picture.id))
        assertNull("an id that was never kept has no clip", s.clipFile("nope"))

        // ⚠⚠ The file is the fact. Deleting it must flip the answer, so Save
        // falls back to the poster instead of writing a zero-byte video.
        java.io.File(clip.videoPath!!).delete()
        assertNull("a clip whose file is gone is not a clip", s.clipFile(clip.id))
    }

    /** ⚠ A picture result carries no clip, and must not invent an empty one. */
    @Test
    fun aKeptPictureHasNoClip() {
        val s = store()
        val r = s.keep(bitmap(), "img_test", defaultWorkflow(), NODE_TYPES, "1", "m", "a cat")
        assertNull(r.videoPath)
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

    /**
     * ⭐⭐ **Keeps that run AT ONCE each get their own result, and every PNG
     * decodes.**
     *
     * ⚠⚠ Autosave keeps every output node of a Run on its own coroutine, so a
     * flow with two outputs kept twice concurrently. Both picked the same
     * millisecond id and compressed into one `.png.tmp`: one result with a PNG
     * Skia refused (a blank card) and one "could not keep it". Seen on a phone
     * 2026-09-19. ⚠ The earlier same-millisecond test ran its keeps one after
     * another, which is exactly why it never saw this.
     */
    @Test
    fun concurrentKeepsNeverShareAFile() {
        val dir = tmp.newFolder()
        val s = ResultsStore(dir)
        val n = 6
        val start = java.util.concurrent.CountDownLatch(1)
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val threads = (0 until n).map { i ->
            Thread {
                try {
                    start.await()
                    s.keep(bitmap(256, 256), "img_$i", defaultWorkflow(), NODE_TYPES, "$i", "m", "p$i")
                } catch (t: Throwable) {
                    errors += t
                }
            }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join() }

        assertTrue("every keep must succeed: $errors", errors.isEmpty())
        val all = s.all()
        assertEquals(n, all.size)
        assertEquals("ids must be distinct", n, all.map { it.id }.toSet().size)
        assertEquals((0 until n).map { "img_$it" }.toSet(), all.mapNotNull { it.imageId }.toSet())
        all.forEach { assertNotNull("${it.id} must decode", s.full(it.id)) }
        assertTrue("no temp file may be left behind", dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
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
