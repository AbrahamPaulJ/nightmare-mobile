package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /** ⭐ Saved #8050: a saved flow keeps no draft (it opens as saved); a recipe's does while the switch is on. */
    @Test
    fun onlyARecipeKeepsADraftAndOnlyWhileSwitchedOn() {
        assertTrue(keepsDraft("recipe:t2i:sd15", keep = true))
        assertFalse(keepsDraft("recipe:t2i:sd15", keep = false))
        assertFalse(keepsDraft("saved:fibo edit", keep = true))
        assertFalse(keepsDraft(null, keep = true))
    }
}
