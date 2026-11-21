package com.abrah.nightmare.vlm

import com.abrah.nightmare.Graph
import com.abrah.nightmare.Node
import com.abrah.nightmare.Source
import com.abrah.nightmare.npu.H3Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ⭐ The prompt enhancer's pure half (`docs/FLORENCE.md` §7): which form it writes, and what an answer becomes. */
class PromptEnhanceTest {

    private fun h3(vararg wired: String) = Graph(
        listOf(
            Node("prompt", "core.prompt", params = mapOf("prompt" to "a cat says hello")),
            Node("pic", "core.image"),
            Node("video", H3Node.name, inputs = mapOf("prompt" to Source("prompt")) + wired.associateWith { Source("pic") }),
        ),
    )

    @Test
    fun anH3NodeAsksForItsOwnRun() {
        assertEquals(PromptEnhance.Target.H3_TEXT, PromptEnhance.targetFor(h3(), "prompt"))
        assertEquals(PromptEnhance.Target.H3_FIRST_FRAME, PromptEnhance.targetFor(h3("image"), "prompt"))
        assertEquals(PromptEnhance.Target.H3_REFERENCE, PromptEnhance.targetFor(h3(H3Node.REFERENCE), "prompt"))
    }

    @Test
    fun theH3AskCarriesTheIdeaAndTheExample() {
        val ask = PromptEnhance.ask(PromptEnhance.Target.H3_REFERENCE, "  she plays the piano  ", seed = 7)
        assertTrue(ask.user.endsWith("The idea: she plays the piano"))
        assertTrue("Picture 1" in ask.user)
        assertTrue("subject_definitions:" in ask.system)
        assertEquals(7, ask.seed)
    }

    @Test
    fun anH3AnswerIsCutToItsSixSections() {
        val raw = """Sure, here it is:
```
## subject_definitions
S1 is a man with a deep voice.

summary:
He waves.

Retention_Analysis:
N/A - no reference images, video or audio are supplied; everything is generated.

detailed_description:
Live action. The target video lasts 4.00 seconds.
[Shot 1] He waves and says: <d>[English] Hi.</d>



overall_soundscape:
Wind.

non_diegetic_music:
N/A
```"""
        val out = PromptEnhance.finish(PromptEnhance.Target.H3_TEXT, raw)!!
        assertTrue(out.startsWith("subject_definitions:\nS1 is a man"))
        assertTrue("retention_analysis:\n" in out)
        assertTrue(out.endsWith("non_diegetic_music:\nN/A"))
        assertTrue("\n\n\n" !in out)
    }

    @Test
    fun dashesBecomeCommas() {
        assertEquals("Rain on the pavement, distant traffic.", PromptEnhance.finish(PromptEnhance.Target.PROSE, "Rain on the pavement — distant traffic."))
    }

    @Test
    fun aCutOffH3AnswerIsNotOffered() {
        assertNull(PromptEnhance.finish(PromptEnhance.Target.H3_TEXT, "subject_definitions:\nS1 is a man.\n\nsummary:\nHe"))
        assertNull(PromptEnhance.finish(PromptEnhance.Target.H3_TEXT, "A man waves."))
    }
}
