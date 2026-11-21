package com.abrah.nightmare.vlm

import com.abrah.nightmare.DeviceProbe
import com.abrah.nightmare.MossLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** ⭐ What the vision model's answers become in the prompt box (GitHub #120). */
@RunWith(RobolectricTestRunner::class)
class VisionPromptsTest {

    @Test
    fun proseLosesPreambleQuotesAndMarkdown() {
        assertEquals(
            "A woman in a red coat stands on a rainy street at night.",
            VisionPrompts.finish(VisionPrompts.Style.PROSE, "Here is the prompt:\n\"A woman in a **red coat** stands\non a rainy street at night.\""),
        )
        assertNull(VisionPrompts.finish(VisionPrompts.Style.PROSE, "  \n "))
    }

    @Test
    fun tagsAreLowerSpacedAndUnique() {
        assertEquals(
            "1girl, solo, long hair, blue eyes, school uniform, no humans",
            VisionPrompts.finish(VisionPrompts.Style.TAGS, "1girl, Solo, long_hair,\n- blue eyes, solo, no objects, school uniform, no humans."),
        )
    }

    @Test
    fun layoutIsRepairedIntoADraftMossReads() {
        val raw = """Sure! ```json
            {"caption": "A cafe sign.", "items": [
              {"label": "text", "caption": "Painted letters", "text": "OPEN", "bbox": [600, 100, 200, 300]},
              {"label": "text", "caption": "a blank board", "bbox": [10, 10, 90, 90]},
              {"label": "man", "caption": "a waiter", "bbox_2d": [100, 400, 400, 1200]},
              {"label": "object", "caption": "a speck", "bbox": [5, 5, 6, 6]}
            ]}
            ```"""
        val draft = VisionPrompts.finish(VisionPrompts.Style.LAYOUT, raw)
        assertNotNull(draft)
        val (layout, caption) = MossLayout.parse(draft!!)!!
        assertEquals("A cafe sign.", caption)
        assertEquals(3, layout.boxes.size)
        // Swapped corners put right, in fractions of the picture.
        with(layout.boxes[0]) {
            assertEquals("text", label); assertEquals("OPEN", text)
            assertEquals(0.2, x1, 1e-9); assertEquals(0.6, x2, 1e-9)
        }
        // A text box with no words becomes an object (MOSS refuses it otherwise).
        assertEquals("object", layout.boxes[1].label)
        // A person label becomes a character, and a box past the picture is clipped.
        with(layout.boxes[2]) { assertEquals("character", label); assertEquals(1.0, y2, 1e-9) }
        // And it compiles: what the sampler will send.
        assertNotNull(MossLayout.compile(layout, caption, 1024, 1024))
    }

    @Test
    fun tagsWithNoCommasAreKeptAsWritten() {
        val raw = "1boy white hair blue eyes elderly man brown suspenders light blue shirt sweater neutral expression plain background soft lighting close up portrait realistic photography"
        assertEquals(raw, VisionPrompts.finish(VisionPrompts.Style.TAGS, raw))
    }

    /** ⭐ The 4B model's real answer on the S25 (Q4_0): the items list closed with `}}`, the sign's word only in its caption. */
    @Test
    fun aWrongBracketAndAWordInTheCaptionAreRepaired() {
        val raw = """{"caption": "A tabby cat holds a sign.", "items": [{"label": "character", "caption": "A tabby cat", """ +
            """"bbox": [218, 203, 796, 999]}, {"label": "text", "caption": "The word \"Nightmare\" in bold black font", """ +
            """"bbox": [309, 642, 700, 745]}}}{"caption": "a repeat"}"""
        val (layout, caption) = MossLayout.parse(VisionPrompts.finish(VisionPrompts.Style.LAYOUT, raw)!!)!!
        assertEquals("A tabby cat holds a sign.", caption)
        assertEquals(2, layout.boxes.size)
        with(layout.boxes[1]) { assertEquals("text", label); assertEquals("Nightmare", text) }
    }

    @Test
    fun layoutWithNoBoxFallsBackToTheCaptionsProse() {
        assertEquals(
            "A quiet beach.",
            VisionPrompts.finish(VisionPrompts.Style.LAYOUT, "A quiet beach. {\"caption\": \"x\", \"items\": []}"),
        )
    }

    @Test
    fun npuSessionsFollowTheArch() {
        fun caps(arch: Int, soc: String, qualcomm: Boolean = true) =
            DeviceProbe.Caps(arch = arch, vtcmMb = 8, measured = true, soc = soc, qualcomm = qualcomm)
        assertEquals("HTP0:0,HTP0:1", VisionDescriber.devicesFor(caps(79, "SM8750")))
        assertEquals("HTP0", VisionDescriber.devicesFor(caps(73, "SM8550")))
        assertEquals("none", VisionDescriber.devicesFor(caps(69, "SM8475")))
        assertEquals("none", VisionDescriber.devicesFor(caps(79, "MT6989", qualcomm = false)))
    }

    @Test
    fun everyStyleAsksForItsShape() {
        assertTrue(VisionPrompts.ask(VisionPrompts.Style.LAYOUT).user.contains("bbox"))
        assertTrue(VisionPrompts.ask(VisionPrompts.Style.TAGS).user.contains("Danbooru"))
    }
}
