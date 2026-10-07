package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐ The prompt toolbar's edits (`PromptTags`) — local-dream's behaviour, pinned. The
 * caret is written `|` in each case and taken out before the call.
 */
class PromptTagsTest {

    private fun at(s: String): Pair<String, Int> = s.replace("|", "") to s.indexOf('|')

    private fun show(r: Pair<String, Int>?): String? = r?.let { (t, c) -> t.substring(0, c) + "|" + t.substring(c) }

    private fun heavier(s: String) = at(s).let { (t, c) -> show(PromptTags.adjustWeight(t, c, 0.1)) }
    private fun lighter(s: String) = at(s).let { (t, c) -> show(PromptTags.adjustWeight(t, c, -0.1)) }
    private fun delete(s: String) = at(s).let { (t, c) -> show(PromptTags.deleteTag(t, c)) }
    private fun add(s: String) = at(s).let { (t, c) -> show(PromptTags.addTagAfter(t, c)) }

    @Test
    fun aBareTagGetsAnExplicitWeight() {
        assertEquals("1girl, (red hat:1.1)|", heavier("1girl, red h|at"))
        assertEquals("(red hat:0.9)|, 8k", lighter("red| hat, 8k"))
    }

    @Test
    fun anExplicitWeightSteps() {
        assertEquals("(hat:1.3)|", heavier("(hat:1.2|)"))
        assertEquals("(hat:1.1)|", lighter("(h|at:1.2)"))
    }

    /** ⭐ Back to 1.0 drops the wrapper — the tag returns to its bare text. */
    @Test
    fun weightOneUnwraps() {
        assertEquals("hat|", lighter("(hat:1.1)|"))
        assertEquals("hat|", heavier("(hat:0.9)|"))
    }

    /** A1111's shorthands are read: each () +0.1, each [] −0.1. */
    @Test
    fun shorthandWrappersAreRead() {
        assertEquals("(hat:1.3)|", heavier("((h|at))"))
        assertEquals("(hat:0.8)|", lighter("[h|at]"))
        assertEquals(1.2, PromptTags.parse("((hat))").second, 1e-9)
    }

    /** ⚠ An escaped bracket is part of the NAME, not a weight — the tagger writes these. */
    @Test
    fun anEscapedBracketIsNotAWeight() {
        assertEquals("(power \\(chainsaw man\\):1.1)|", heavier("power \\(chainsaw man\\)|"))
    }

    /** ⭐ Just after ", " the weight goes to the tag just typed, not to nothing. */
    @Test
    fun anEmptySlotWeighsThePreviousTag() {
        // The caret stays in its slot, shifted by the edit.
        assertEquals("1girl, (hat:1.1), |", heavier("1girl, hat, |"))
    }

    @Test
    fun deleteTakesTheTagAndOneComma() {
        assertEquals("1girl|, 8k", delete("1girl, red h|at, 8k"))
        assertEquals("|8k", delete("red h|at, 8k"))
        assertEquals("|", delete("h|at"))
    }

    /** ⚠ In an empty slot it closes the slot — never the intact tag before it. */
    @Test
    fun deleteInAnEmptySlotClosesTheSlot() {
        assertEquals("1girl, hat|", delete("1girl, hat, |"))
    }

    @Test
    fun addOpensASlotAfterTheTag() {
        assertEquals("1girl, hat, |, 8k", add("1girl, h|at, 8k"))
        assertEquals("|", add("|"))
        assertNull(add("1girl, |"))
    }

    @Test
    fun aWeightUsesADotWhateverTheLocale() {
        val before = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("(hat:1.1)|", heavier("h|at"))
        } finally {
            java.util.Locale.setDefault(before)
        }
    }

    /** ⭐ Typing coalesces into one undo step; a toolbar edit is its own. */
    @Test
    fun historyCoalescesTypingAndRedoes() {
        val h = PromptTags.History()
        h.record("", 1_000, coalesce = true)        // "" -> "h"
        h.record("h", 1_200, coalesce = true)       // "h" -> "ha": same step
        h.record("ha", 1_300, coalesce = true)      // -> "hat"
        h.record("hat", 5_000, coalesce = false)    // toolbar: "hat" -> "(hat:1.1)"
        assertEquals("hat", h.undo("(hat:1.1)"))
        assertEquals("", h.undo("hat"))
        assertFalse(h.canUndo)
        assertTrue(h.canRedo)
        assertEquals("hat", h.redo(""))
        assertEquals("(hat:1.1)", h.redo("hat"))
        assertFalse(h.canRedo)
    }

    @Test
    fun anEditAfterUndoDropsTheRedo() {
        val h = PromptTags.History()
        h.record("a", 0, coalesce = false)
        h.undo("b")
        h.record("a", 10_000, coalesce = false)
        assertFalse(h.canRedo)
    }
}
