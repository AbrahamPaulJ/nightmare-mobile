package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ⭐ The battery-heat pause ([BatteryHeat]): on at 45 °C, off only at 40, never flapping in between. */
class BatteryHeatTest {

    @Test
    fun itPausesAtFortyFiveAndCarriesOnAtForty() {
        assertFalse(BatteryHeat.nextPaused(false, 44.9))
        assertTrue(BatteryHeat.nextPaused(false, 45.0))
        // Between the edges it keeps what it was.
        assertTrue(BatteryHeat.nextPaused(true, 42.0))
        assertFalse(BatteryHeat.nextPaused(false, 42.0))
        assertTrue(BatteryHeat.nextPaused(true, 40.1))
        assertFalse(BatteryHeat.nextPaused(true, 40.0))
        assertEquals("43", BatteryHeat.format(43.2))
    }
}
