package com.abrah.nightmare

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐ The low-RAM switches default from the phone's `totalMem` ([Prefs.lowRamDefault]).
 * The numbers are what phones actually REPORT, not what is printed on the box:
 * a 12 GB phone shows ~10.9–11.4 GiB and a 16 GB one ~14.5–15 GiB.
 */
class LowRamDefaultTest {
    private fun gib(x: Double) = (x * (1L shl 30)).toLong()

    @Test fun aTwelveGbPhoneIsLowRam() {
        assertTrue(Prefs.lowRamDefault(gib(10.9)))  // this dev phone, S25 Ultra 12 GB
        assertTrue(Prefs.lowRamDefault(gib(11.4)))
        assertTrue(Prefs.lowRamDefault(gib(7.3)))   // an 8 GB phone
    }

    @Test fun aSixteenGbPhoneIsNot() {
        assertFalse(Prefs.lowRamDefault(gib(14.5)))
        assertFalse(Prefs.lowRamDefault(gib(15.0)))
        assertFalse(Prefs.lowRamDefault(gib(22.0))) // 24 GB
    }

    /** ⚠ Unreadable RAM takes the SAFE side: slow, never killed. */
    @Test fun unknownRamIsLowRam() {
        assertTrue(Prefs.lowRamDefault(0L))
        assertTrue(Prefs.lowRamDefault(-1L))
    }
}
