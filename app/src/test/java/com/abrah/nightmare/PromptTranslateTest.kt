package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ⭐ The two halves of prompt translation that need no model
 * (`docs/TRANSLATE.md`): which language a prompt is in, and the split that
 * lets weights and English tags survive translation untouched.
 */
class PromptTranslateTest {

    @Test
    fun detectReadsTheScript() {
        assertEquals(PromptTranslate.Source.RU, PromptTranslate.detect("красная шляпа, 8k"))
        assertEquals(PromptTranslate.Source.ZH, PromptTranslate.detect("汉服，古风"))
        // ⚠ No button for English, numbers or an empty box.
        assertNull(PromptTranslate.detect("a red hat, (masterpiece:1.2), 8k"))
        assertNull(PromptTranslate.detect(""))
    }

    @Test
    fun theSplitRoundTripsAnyPrompt() {
        for (p in listOf(
            "красивая девушка, (длинные волосы:1.2), 8k",
            "汉服，古风、少女\n高清",
            "  leading, trailing  ",
            "[[old photo]], ((detailed:1.3))",
            "",
        )) {
            assertEquals(p, PromptTranslate.splitPrompt(p).joinToString("") { it.render() })
        }
    }

    @Test
    fun onlyTheWordsOfAWeightedPieceAreTranslated() {
        val pieces = PromptTranslate.splitPrompt("(длинные волосы:1.2), 8k")
        val text = pieces.filterIsInstance<PromptTranslate.Piece.Text>()
        assertEquals(PromptTranslate.Piece.Text("(", "длинные волосы", ":1.2)"), text[0])
        // ⚠ The tag carries no Cyrillic, so `translate` leaves it as typed.
        assertNull(PromptTranslate.detect(text[1].core))
    }
}
