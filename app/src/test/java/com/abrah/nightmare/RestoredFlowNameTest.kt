package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** GitHub #114: a canvas restored at a cold start knows the saved flow it is ([restoredFlowName]). */
class RestoredFlowNameTest {
    @Test
    fun onlyASavedFlowsKeyNamesOne() {
        assertEquals("fibo edit", restoredFlowName("saved:fibo edit"))
        assertNull(restoredFlowName("recipe:t2i:sd15"))
        assertNull(restoredFlowName("saved:"))
        assertNull(restoredFlowName(null))
    }
}
