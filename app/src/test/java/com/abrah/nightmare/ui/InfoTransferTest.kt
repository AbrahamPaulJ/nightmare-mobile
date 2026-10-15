package com.abrah.nightmare.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ⭐ GitHub #33: which Info rows can go to the open flow, and as what. */
class InfoTransferTest {

    @Test
    fun promptNegativeAndARealSeedTransfer() {
        assertEquals(TransferItem.Prompt("a cat"), transferItemOf("Prompt", "a cat"))
        assertEquals(TransferItem.Negative("blurry"), transferItemOf("Negative", "blurry"))
        assertEquals(TransferItem.Seed("1563986482"), transferItemOf("Seed", " 1563986482 "))
        // ⚠ "0" is "new every Run", and a seed nobody rolled is not a seed to lock.
        assertNull(transferItemOf("Seed", "0"))
        assertNull(transferItemOf("Seed", "random"))
        assertNull(transferItemOf("Model", "Qwen Image 2.1 Turbo"))
    }
}
