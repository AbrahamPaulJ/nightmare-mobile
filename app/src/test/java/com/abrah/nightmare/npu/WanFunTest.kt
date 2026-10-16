package com.abrah.nightmare.npu

import com.abrah.nightmare.Node
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ⭐ Motion transfer's pure halves ([WanFun]): how many frames a driving video gives, how a frame of
 * another shape is cropped, and that a driving video relaunches Wan with the Fun DiT (backend 033).
 */
class WanFunTest {

    @Test
    fun aShortVideoShortensTheClipToFourNPlusOne() {
        // 5 s wanted (121 frames), a 10 s video: the clip keeps its 121.
        assertEquals(121, WanFun.framesFrom(121, 10_000))
        // A 2 s video: 49 frames at 24 fps, already 4n+1.
        assertEquals(49, WanFun.framesFrom(121, 2_000))
        // 1.5 s: 37 available, 4n+1 = 37.
        assertEquals(37, WanFun.framesFrom(121, 1_500))
        // 1.6 s: 39 available, down to 37.
        assertEquals(37, WanFun.framesFrom(121, 1_600))
        // Too short to make a clip of.
        assertEquals(0, WanFun.framesFrom(121, 100))
    }

    @Test
    fun aFrameIsCentreCroppedToTheClipsShape() {
        // A 1080x1920 portrait video (0.5625) into a 704x1280 clip (0.55): a sliver off each side.
        assertArrayEquals(intArrayOf(12, 0, 1056, 1920), WanFun.cropFor(1080, 1920, 704, 1280))
        // A landscape video into a portrait clip: the middle column.
        assertArrayEquals(intArrayOf(663, 0, 594, 1080), WanFun.cropFor(1920, 1080, 704, 1280))
        // Same shape: the whole frame.
        assertArrayEquals(intArrayOf(0, 0, 832, 480), WanFun.cropFor(832, 480, 832, 480))
    }

    /**
     * ⭐ The Video tab's box keeps its height and centre and takes the clip's shape; a box hanging
     * off the frame (zoomed out √2) stays off it, so the frame is padded, not re-framed.
     */
    @Test
    fun theVideoTabsBoxTakesTheClipsShape() {
        val r = WanFun.fitRect(floatArrayOf(0.5f, 0f, 0.5f, 1f), 1920, 1080, 704, 1280)
        assertArrayEquals(floatArrayOf(0.5953125f, 0f, 0.309375f, 1f), r, 1e-5f)
        val off = WanFun.fitRect(floatArrayOf(-0.2f, -0.1f, 0.5f, 1.2f), 1920, 1080, 704, 1280)
        assertArrayEquals(floatArrayOf(-0.135625f, -0.1f, 0.37125f, 1.2f), off, 1e-5f)
    }

    /** ⭐ The window's length IS the clip, on Wan's 4-frame step, 5 frames to 5 s. */
    @Test
    fun theWindowSetsTheClipsLength() {
        assertEquals(49, WanFun.framesForGap(2.0))
        assertEquals(57, WanFun.framesForGap(2.3))
        assertEquals(5, WanFun.framesForGap(0.01))
        assertEquals(121, WanFun.framesForGap(9.0))
        val p = mapOf(WanFun.START to "1.5", WanFun.END to "3.5")
        assertEquals(1.5 to 3.5, WanFun.windowOf(p))
        assertEquals(49, WanVideoNode.framesOf(Node("m", WanFunNode.name, p), p))
        // ⚠ An end more than 5 s after the start is held to 5 s; an end before it, to the start.
        assertEquals(1.0 to 6.0, WanFun.windowOf(mapOf(WanFun.START to "1", WanFun.END to "20")))
        assertEquals(null, WanFun.rectOf(mapOf(WanFun.VX to "", WanFun.VY to "0")))
        // ⚠ Wan Turbo still reads its Seconds.
        assertEquals(49, WanVideoNode.framesOf(Node("v", WanVideoNode.name, mapOf("seconds" to "2")), mapOf("seconds" to "2")))
    }

    /** ⭐ The handle that moved gives way; the other never jumps under the finger. */
    @Test
    fun theSeekerKeepsTheWindowBetweenFiveFramesAndFiveSeconds() {
        // The end dragged 7 s past the start: stops at 5 s, the start stays.
        assertEquals(1.0 to 6.0, WanFun.constrainWindow(1.0, 8.0, 1.0, 3.0, 12.0))
        // The start dragged onto the end: stops 4 frames short of it, the end stays.
        val (s, e) = WanFun.constrainWindow(3.0, 3.0, 1.0, 3.0, 12.0)
        assertEquals(3.0 - 4.0 / 24, s, 1e-9)
        assertEquals(3.0, e, 1e-9)
        // The end dragged past the video's end: held at it.
        assertEquals(10.0 to 12.0, WanFun.constrainWindow(10.0, 14.0, 10.0, 11.0, 12.0))
        // The start dragged more than 5 s before the end: held 5 s before it.
        assertEquals(4.0 to 9.0, WanFun.constrainWindow(1.0, 9.0, 6.0, 9.0, 12.0))
    }

    /** ⭐ Motion transfer is its own model: its key names Fun Control, Wan's names Turbo. */
    @Test
    fun motionTransferLaunchesFunControl() {
        val wan = Node("video", WanVideoNode.name)
        val motion = Node("motion", WanFunNode.name)
        assertEquals(com.abrah.nightmare.ModelCatalog.WAN22.id, WanVideoNode.contextKey(wan).model)
        assertEquals(com.abrah.nightmare.ModelCatalog.WAN22_FUN.id, WanFunNode.contextKey(motion).model)
        assertEquals(WanFun.Mode.POSE, WanFun.Mode.of(null))
        assertEquals(WanFun.Mode.POSE, WanFun.Mode.of("depth")) // Depth was removed: an old graph opens as Pose
        assertEquals(listOf("prompt", "image", WanFunNode.CONTROL), WanFunNode.inputs.map { it.name })
        assertEquals("VIDEO", WanFunNode.inputs.last().type)
    }

    /**
     * ⭐ Fun Control borrows Wan 2.2 Turbo's umT5 and TAEHV when Turbo has them whole, and fetches only
     * its own two files then (3.7 GB, not 7.3); without Turbo it needs all four.
     */
    @Test
    fun funControlBorrowsTurbosParts() {
        val funSpec = com.abrah.nightmare.ModelCatalog.WAN22_FUN
        val root = kotlin.io.path.createTempDirectory().toFile()
        val own = java.io.File(root, funSpec.id).apply { mkdirs() }
        val turbo = java.io.File(root, "wan22_ti2v_turbo").apply { mkdirs() }
        val umt5 = funSpec.files.first { it.name == "umt5.gguf" }
        assertEquals(false, funSpec.borrows(own, umt5))
        java.io.RandomAccessFile(java.io.File(turbo, "umt5.gguf"), "rw").use { it.setLength(umt5.bytes) }
        assertEquals(true, funSpec.borrows(own, umt5))
        // ⚠ A partial copy in the donor is not borrowed.
        java.io.RandomAccessFile(java.io.File(turbo, "umt5.gguf"), "rw").use { it.setLength(umt5.bytes - 1) }
        assertEquals(false, funSpec.borrows(own, umt5))
        root.deleteRecursively()
    }
}
