package com.abrah.nightmare

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐ SDXL low RAM cannot be turned off under 16 GB (LIST-N4 item 39): on the 12 GB S25 with the switch off, SDXL filled
 * memory in 20 s and lmkd closed the person's apps, then this one.
 */
class SdxlLowRamTest {
    @Test
    fun aTwelveGbPhoneAlwaysRunsSdxlInLowRam() {
        assertTrue(Prefs.forcesSdxlLowRam(11_652_825_088L)) // the S25 Ultra's MemTotal
        assertTrue(Prefs.forcesSdxlLowRam(7L shl 30))
    }

    @Test
    fun aSixteenGbPhoneKeepsTheChoice() {
        assertFalse(Prefs.forcesSdxlLowRam(15L shl 30))
        // ⚠ Unknown RAM is the default's business (on), not a lock on the switch.
        assertFalse(Prefs.forcesSdxlLowRam(0L))
    }
}
