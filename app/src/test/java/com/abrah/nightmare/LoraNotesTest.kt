package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ⭐ A LoRA's note: stored beside the files, its first line is the trigger,
 * and "Add to prompt" appends it to the prompt node wired into the sampler.
 */
@RunWith(RobolectricTestRunner::class)
class LoraNotesTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun roundTripsAndBlankRemoves() {
        val dir = tmp.newFolder("_loras")
        assertEquals(emptyMap<String, String>(), LoraNotes.read(dir))
        LoraNotes.write(dir, "marin.safetensors", "marin kitagawa, blonde\nstrength 0.8\n\n")
        LoraNotes.write(dir, "film.safetensors", "film grain")
        val read = LoraNotes.read(dir)
        assertEquals("marin kitagawa, blonde\nstrength 0.8", read["marin.safetensors"])
        assertEquals("film grain", read["film.safetensors"])
        LoraNotes.write(dir, "film.safetensors", "   ")
        assertEquals(setOf("marin.safetensors"), LoraNotes.read(dir).keys)
    }

    /** ⚠ A corrupt file reads as no notes rather than crashing the picker. */
    @Test
    fun corruptFileIsEmpty() {
        val dir = tmp.newFolder("_loras")
        java.io.File(dir, LoraNotes.FILE).writeText("{not json")
        assertEquals(emptyMap<String, String>(), LoraNotes.read(dir))
    }

    @Test
    fun triggerIsFirstNonBlankLine() {
        assertEquals("marin, blonde", LoraNotes.trigger("\n  marin, blonde  \nuse 0.8"))
        assertNull(LoraNotes.trigger("  \n "))
    }

    @Test
    fun appendJoinsWithACommaAndNeverTwice() {
        assertEquals("1girl, marin", LoraNotes.appendToPrompt("1girl", "marin"))
        assertEquals("1girl, marin", LoraNotes.appendToPrompt("1girl, ", "marin,"))
        assertEquals("marin", LoraNotes.appendToPrompt("", "marin"))
        assertEquals("1girl, Marin", LoraNotes.appendToPrompt("1girl, Marin", "marin"))
    }

    @Test
    fun promptNodeIsOnlyADirectCorePrompt() {
        val g = Graph(
            listOf(
                Node("p", LoraNotes.PROMPT_TYPE, mapOf("prompt" to "1girl")),
                Node("s", "sdxlswap.sample", inputs = mapOf("prompt" to Source("p"))),
                Node("t", "text.translate"),
                Node("s2", "sdxlswap.sample", inputs = mapOf("prompt" to Source("t"))),
                Node("s3", "sdxlswap.sample"),
            ),
        )
        assertEquals("p", LoraNotes.promptNodeOf(g, "s"))
        assertNull(LoraNotes.promptNodeOf(g, "s2"))
        assertNull(LoraNotes.promptNodeOf(g, "s3"))
    }
}
