package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ⭐ GitHub #78: the S25's sensor names (the `thermal` harness op, 2026-11-04) sorted into CPU, GPU and NPU. */
class ChipTempsTest {

    @Test
    fun theS25sZonesFallIntoTheirGroups() {
        assertEquals("cpu", ChipTemps.groupOf("cpu-0-3-1"))
        assertEquals("cpu", ChipTemps.groupOf("cpuss-1-0"))
        assertEquals("gpu", ChipTemps.groupOf("gpuss-5"))
        assertEquals("npu", ChipTemps.groupOf("nsphvx-0"))
        assertEquals("npu", ChipTemps.groupOf("nsphmx-3"))
        assertNull(ChipTemps.groupOf("battery"))
        assertNull(ChipTemps.groupOf("pm8550-bcl-lvl0"))
    }

    @Test
    fun aSwitchedOffSensorIsNotAReading() {
        assertEquals(52.9f, ChipTemps.celsius("52900\n")!!, 0.01f)
        assertNull("mmw0 reads -273 when the modem is off", ChipTemps.celsius("-273000"))
        assertNull(ChipTemps.celsius("n/a"))
    }
}
