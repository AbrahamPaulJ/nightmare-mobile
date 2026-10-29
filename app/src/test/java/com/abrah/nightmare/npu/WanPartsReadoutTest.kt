package com.abrah.nightmare.npu

import org.junit.Assert.assertEquals
import org.junit.Test

/** ⭐ The Part prompts box's live readout ([WanParts.readout]): the same rule the run applies ([WanParts.promptsFor]). */
class WanPartsReadoutTest {

    @Test
    fun eachPartSaysWhereItsPromptComesFrom() {
        val r = WanParts.readout("a street musician", "he turns\n\nhe bows", 4)
        assertEquals(listOf(WanParts.Source.MAIN, WanParts.Source.LINE, WanParts.Source.REPEAT, WanParts.Source.LINE), r.rows.map { it.source })
        assertEquals(listOf("a street musician", "he turns", "", "he bows"), r.rows.map { it.text })
        assertEquals(0, r.unused)
        // The readout and the run agree.
        assertEquals(listOf("a street musician", "he turns", "he turns", "he bows"), WanParts.promptsFor("a street musician", "he turns\n\nhe bows", 4))
    }

    @Test
    fun linesPastTheLastPartAreFlagged() {
        val r = WanParts.readout("p", "a\nb\nc\nd", 3)
        assertEquals(2, r.unused)
        assertEquals(5, r.needed)
        assertEquals(0, WanParts.readout("p", "", 3).unused)
        assertEquals(listOf(WanParts.Source.MAIN, WanParts.Source.REPEAT, WanParts.Source.REPEAT), WanParts.readout("p", "", 3).rows.map { it.source })
    }
}
