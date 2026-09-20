package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐⭐⭐ A chip no table knows must not be told it cannot run anything.
 *
 * ⚠⚠ Reported 2026-09-20 by a Snapdragon 8 Gen 5 owner: every SDXL, Anima and
 * FLUX row read *"это устройство не сможет её запустить"* — this device cannot
 * run it. Their `Build.SOC_MODEL` is not one of the strings in `SOC_TO_ARCH`,
 * so `caps()` fell to the v68 / 2 MB floor and every gate above SD 1.5 `_min`
 * failed. The app stated as fact something it had only assumed.
 *
 * ⚠ We cannot hold an 8 Gen 5, so the gate is tested through the part-number
 * parse directly rather than through `Build.SOC_MODEL`. That is the whole of
 * the new behaviour; the rest of `caps()` is unchanged.
 */
class UnknownChipGateTest {

    /** The newest part number the tables know, whatever it is today. */
    private val newestKnown = 8850

    /**
     * ⭐ The reported case. An unrecognised variant of a chip at or above the
     * newest we know gets the newest arch, not the floor.
     */
    @Test
    fun anUnknownNewerChipIsNotFlooredToV68() {
        for (soc in listOf("SM8850-AC", "SM8860", "SM8950", "SM9050P")) {
            val arch = DeviceProbe.archFromPartNumber(soc)
            assertNotNull("$soc was not recognised as newer than $newestKnown", arch)
            assertTrue(
                "$soc resolved to v$arch, no better than the floor",
                arch!! > DeviceProbe.FLOOR_ARCH,
            )
        }
    }

    /**
     * ⚠⚠ And an OLDER unknown chip keeps the floor. Qualcomm's numbering is
     * only roughly chronological — the SM8635 is an "8s Gen 3", newer by name
     * than an 8 Gen 2 and with less than 8 MB of VTCM — so guessing upward on
     * anything below the newest known part is how a user is handed a gigabyte
     * their HTP then refuses. That failure is worse than the one being fixed.
     */
    @Test
    fun anUnknownOlderChipKeepsTheFloor() {
        for (soc in listOf("SM6375", "SM7325", "SM8635", "SM4350")) {
            assertNull(
                "$soc was guessed upward, which risks an unusable download",
                DeviceProbe.archFromPartNumber(soc),
            )
        }
    }

    /** ⚠ Anything that is not a Snapdragon part gets no guess at all. */
    @Test
    fun aNonQualcommOrMalformedChipGetsNoGuess() {
        for (soc in listOf("", "EXYNOS2400", "TENSOR G4", "SM", "SMXXXX")) {
            assertNull("$soc produced a guess", DeviceProbe.archFromPartNumber(soc))
        }
    }

    /**
     * ⭐⭐ The arch it lands on must be one the APK actually ships libraries
     * for. Guessing an arch whose Skel is absent makes NPU init fail outright
     * with an error that names neither the arch nor the file — strictly worse
     * than saying "unsupported".
     */
    @Test
    fun theGuessedArchIsOneWeShipLibrariesFor() {
        val arch = DeviceProbe.archFromPartNumber("SM8950")
        assertNotNull(arch)
        assertTrue(
            "guessed v$arch, which is not in STAGED_ARCHES ${DeviceProbe.STAGED_ARCHES}",
            arch in DeviceProbe.STAGED_ARCHES,
        )
    }

    /**
     * ⚠ A known chip must still come from the TABLE, not the parse — the
     * tables carry real per-chip facts the part number cannot imply.
     */
    @Test
    fun aKnownChipIsUnaffected() {
        assertEquals(
            "the parse overrode a chip the table knows",
            DeviceProbe.SOC_TO_ARCH["SM8750"],
            79,
        )
    }
}
