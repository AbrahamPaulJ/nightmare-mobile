package com.abrah.nightmare

import com.abrah.nightmare.canvas.CropRect
import com.abrah.nightmare.canvas.pictureRegion
import com.abrah.nightmare.canvas.wholePhotoFraming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐⭐ A Swap node's ControlNet / IP-Adapter pictures take the RENDER's shape and may be
 * zoomed out (the user's call, 2026-10-08), and an empty optional picture is a warning,
 * not a failed run ([skipEmptyPictures]).
 */
class SwapPictureFramingTest {

    @Test
    fun theAspectBoxIsTheRendersRectangleOnTheSquareHint() {
        assertEquals(1024 to 1024, SwapInputs.aspectBox(1024, 1f))
        assertEquals(1024 to 576, SwapInputs.aspectBox(1024, 16f / 9f))
        assertEquals(768 to 1024, SwapInputs.aspectBox(1024, 3f / 4f))
    }

    /** ⚠ The bug this replaced: a portrait frame was squared on its WIDTH and lost its top and bottom. */
    @Test
    fun aPortraitFrameFillsAPortraitBoxCentred() {
        // A 3:4 frame on a 1000x1000 picture: 0.6 wide, 0.8 tall.
        val p = SwapInputs.placement(1000, 1000, SwapInputs.Frame(0.2f, 0.1f, 0.6f, 0.8f, null), 1024, 3f / 4f)!!
        assertEquals(128, p.left)
        assertEquals(0, p.top)
        assertEquals(768, p.w)
        assertEquals(1024, p.h)
    }

    /** ⭐ A frame zoomed out past the picture: only the picture is drawn, inset by the bar. */
    @Test
    fun aZoomedOutFrameInsetsThePicture() {
        // Square frame reaching 0.25 past every edge of a square picture: the picture is the middle 2/3.
        val p = SwapInputs.placement(600, 600, SwapInputs.Frame(-0.25f, -0.25f, 1.5f, 1.5f, null), 1024, 1f)!!
        assertEquals(SwapInputs.Frame(0f, 0f, 1f, 1f, null), p.part)
        assertEquals(171, p.left)
        assertEquals(171, p.top)
        assertEquals(683, p.w)
    }

    @Test
    fun aWholePictureIsContainedNeverStretched() {
        // A 2:1 picture in a 1:1 render: full width, half height, centred.
        val p = SwapInputs.placement(2000, 1000, SwapInputs.Frame(0f, 0f, 1f, 1f, null), 512, 1f)!!
        assertEquals(0, p.left)
        assertEquals(128, p.top)
        assertEquals(512, p.w)
        assertEquals(256, p.h)
    }

    @Test
    fun anUntouchedRegionOpensAsTheWholePictureFitted() {
        assertEquals(
            wholePhotoFraming(1000, 500, 1f, PadRule.PAD),
            pictureRegion(CropRect.WHOLE, 1000, 500, 1f),
        )
        val moved = CropRect(0.1f, 0.1f, 0.5f, 0.5f)
        assertSame(moved, pictureRegion(moved, 1000, 500, 1f))
    }

    // --- empty optional pictures --------------------------------------------------------

    private val swap = SdSampler.SD15_SWAP.name

    private fun graph(samplerType: String, controlUri: String = "", photoUri: String = "content://p", extra: List<Node> = emptyList()) =
        Graph(
            listOf(
                Node("prompt", "core.prompt"),
                Node("image", "core.image", params = mapOf("uri" to photoUri)),
                Node("control", "core.image", params = mapOf("uri" to controlUri)),
                Node(
                    "generate", samplerType,
                    params = mapOf(SdSampler.CONTROLNET to SwapInputs.CANNY),
                    inputs = sources("prompt" to "prompt", "image" to "image", SdSampler.CONTROL to "control"),
                ),
            ) + extra,
        )

    @Test
    fun anEmptyControlPictureSwitchesControlNetOffForTheRun() {
        val (g, skipped) = skipEmptyPictures(graph(swap))
        assertEquals(listOf(SkippedPicture("control", "generate", SdSampler.CONTROL)), skipped)
        assertNull(g.byId["control"])
        assertNull(g.byId["generate"]!!.inputs[SdSampler.CONTROL])
        assertEquals(SwapInputs.NONE, g.byId["generate"]!!.params[SdSampler.CONTROLNET])
        assertEquals("the photo stays", "image", g.byId["generate"]!!.inputs["image"]?.node)
    }

    @Test
    fun anEmptyImg2imgPhotoRendersFromText() {
        val (g, skipped) = skipEmptyPictures(graph(swap, controlUri = "content://c", photoUri = ""))
        assertEquals(listOf(SkippedPicture("image", "generate", "image")), skipped)
        assertNull(g.byId["generate"]!!.inputs["image"])
        assertEquals("control", g.byId["generate"]!!.inputs[SdSampler.CONTROL]?.node)
    }

    @Test
    fun anInpaintPhotoIsNotOptional() {
        val (g, skipped) = skipEmptyPictures(graph(SdSampler.SD15_SWAP_INPAINT.name, controlUri = "content://c", photoUri = ""))
        assertTrue(skipped.isEmpty())
        assertEquals("image", g.byId["generate"]!!.inputs["image"]?.node)
    }

    @Test
    fun anEmptyPictureAlsoReadElsewhereStillFails() {
        val other = Node("up", "image.upscale", inputs = sources("image" to "control"))
        val (g, skipped) = skipEmptyPictures(graph(swap, extra = listOf(other)))
        assertTrue(skipped.isEmpty())
        assertTrue(g.byId["control"] != null)
    }

    @Test
    fun aGraphWithEveryPictureChosenIsUntouched() {
        val g0 = graph(swap, controlUri = "content://c")
        val (g, skipped) = skipEmptyPictures(g0)
        assertSame(g0, g)
        assertTrue(skipped.isEmpty())
    }
}
