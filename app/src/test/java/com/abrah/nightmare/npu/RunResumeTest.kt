package com.abrah.nightmare.npu

import com.abrah.nightmare.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ⭐ A killed Motion transfer's seeds come back for the SAME flow only ([RunResume]). */
class RunResumeTest {

    private val motion = Node("motion", WanFunNode.name, mapOf("seed" to "0", "seconds" to "10"))

    @Test
    fun parseKeepsSeedsAndDropsJunk() {
        assertEquals(
            mapOf("motion" to mapOf("seed" to "42"), "b" to mapOf("seed" to "7")),
            RunResume.parse("motion=42,b=7,bad,c=0,d=x"),
        )
        assertTrue(RunResume.parse(null).isEmpty())
    }

    /** ⚠ A changed knob is a different run, so its parts are not the killed run's. Order does not matter. */
    @Test
    fun theKeyIsTheFlow() {
        val other = Node("image", "core.image")
        assertEquals(RunResume.keyOf(listOf(motion, other)), RunResume.keyOf(listOf(other, motion)))
        assertNotEquals(
            RunResume.keyOf(listOf(motion)),
            RunResume.keyOf(listOf(motion.copy(params = motion.params + ("seconds" to "5")))),
        )
        assertTrue(RunResume.resumable(listOf(other, motion)))
        assertTrue(!RunResume.resumable(listOf(other)))
    }
}
