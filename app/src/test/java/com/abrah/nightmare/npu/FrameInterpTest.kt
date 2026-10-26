package com.abrah.nightmare.npu

import com.abrah.nightmare.DeviceProbe
import com.abrah.nightmare.Node
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ⭐ Smooth motion ([FrameInterp], `docs/INTERPOLATION.md`): which graph a clip uses, the frame order, the packing. */
class FrameInterpTest {

    private fun caps(arch: Int) = DeviceProbe.Caps(arch = arch, vtcmMb = 8, measured = true, soc = "TEST")

    @Test
    fun aClipTakesTheSmallestGraphThatHoldsIt() {
        assertEquals(FrameInterp.Size(832, 480), FrameInterp.pick(832, 480))
        assertEquals(FrameInterp.Size(480, 832), FrameInterp.pick(480, 832))
        // ⭐ The 14B's 720p is padded 16 rows; the 5B's 704p 32.
        assertEquals(FrameInterp.Size(1280, 736), FrameInterp.pick(1280, 720))
        assertEquals(FrameInterp.Size(1280, 736), FrameInterp.pick(1280, 704))
        assertEquals(FrameInterp.Size(736, 1280), FrameInterp.pick(720, 1280))
        // A square 512 does not fit 832x480 (too tall) and takes the landscape 720p graph.
        assertEquals(FrameInterp.Size(1280, 736), FrameInterp.pick(512, 512))
        // ⚠ Larger than every graph: no smoothing, never a crash.
        assertNull(FrameInterp.pick(928, 928))
    }

    @Test
    fun everyChipFromTheEightGenThreeHasABuild() {
        assertEquals("v79", FrameInterp.tierFor(caps(79)))
        // ⭐ v81 and v85 run the v79 build (the skel follows the device).
        assertEquals("v79", FrameInterp.tierFor(caps(85)))
        assertEquals("v75", FrameInterp.tierFor(caps(75)))
        assertNull(FrameInterp.tierFor(caps(73)))
        // Every tier lists the same four graphs, so [FrameInterp.pick] holds on all of them.
        for ((_, files) in FrameInterp.FILES) assertEquals(FrameInterp.SIZES, files.map { it.size })
    }

    @Test
    fun aDoubledClipAlternatesModelFramesAndMidpoints() {
        assertEquals(33, FrameInterp.doubled(17))
        assertEquals(1, FrameInterp.doubled(1))
        assertEquals(0 to false, FrameInterp.sourceOf(0))
        assertEquals(0 to true, FrameInterp.sourceOf(1))
        assertEquals(1 to false, FrameInterp.sourceOf(2))
        // The last output frame is the model's last frame, so a chain's last_frame is a real frame.
        assertEquals(16 to false, FrameInterp.sourceOf(FrameInterp.doubled(17) - 1))
    }

    @Test
    fun packingRepeatsTheEdgesIntoThePadding() {
        // 2x1 RGB: red, blue, into a 4x2 graph.
        val rgb = byteArrayOf(255.toByte(), 0, 0, 0, 0, 255.toByte())
        val f = FrameInterp.pack(rgb, 2, 1, FrameInterp.Size(4, 2))
        val plane = 8
        // R plane: 1 0 0 0 / 1 0 0 0 (the red column, the blue column and its repeats).
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f), f.copyOfRange(0, plane), 0f)
        // B plane: 0 1 1 1 / 0 1 1 1.
        assertArrayEquals(floatArrayOf(0f, 1f, 1f, 1f, 0f, 1f, 1f, 1f), f.copyOfRange(2 * plane, 3 * plane), 0f)
        assertEquals(255, FrameInterp.byteOf(1.2f).toInt() and 0xFF)
        assertEquals(128, FrameInterp.byteOf(0.5f).toInt() and 0xFF)
        assertEquals(0, FrameInterp.byteOf(-0.1f).toInt())
    }

    @Test
    fun everyWanNodeHasTheSwitchOffByDefault() {
        for (t in listOf(WanVideoNode, WanA14Node, WanFunNode)) {
            val w = t.widgets.firstOrNull { it.name == WanVideoNode.SMOOTH }
            assertTrue("${t.name} has no ${WanVideoNode.SMOOTH}", w != null)
            assertEquals("bool", w!!.type)
            assertEquals("false", w.default)
        }
        assertEquals("false", com.abrah.nightmare.applyDefaults(WanVideoNode.widgets, Node("v", WanVideoNode.name))[WanVideoNode.SMOOTH])
    }
}
