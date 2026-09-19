package com.abrah.nightmare

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐⭐⭐ The residency gate, against the numbers it was derived from.
 *
 * ⚠⚠ Every figure here is a DEVICE MEASUREMENT from 2026-09-20, not a
 * plausible-looking constant — `Residency` has the table. The point of the test
 * is that the threshold cannot be nudged without one of these failing, because
 * the margin it sits in is the whole reason the gate is a footprint ratio
 * rather than a free-memory reading.
 */
class ResidencyTest {

    /** The dev phone: `MemTotal: 11379968 kB`. */
    private val phone12 = 11_379_968L * 1024

    /** A 16 GB Gen 5, taking the same ~5% firmware carve-out this phone shows. */
    private val phone16 = 15_500_000L * 1024

    /** Weights on disk, summed from the catalogue's `files`. */
    private val flux = 6_680_930_778L
    private val zimage = 8_767_512_164L

    /**
     * ⭐⭐ The pair the gate exists to separate, and the ONLY two DiT models
     * there are. FLUX.2 rendered three times back to back (49 s / 37 s / 38 s);
     * Z-Image rendered once and its second request killed the process.
     */
    @Test fun theTwoDitModelsFallOnOppositeSides() {
        assertFalse(
            "FLUX.2 renders repeatedly on this phone — releasing it would be a regression",
            Residency.releaseAfterRun(flux, phone12),
        )
        assertTrue(
            "Z-Image dies on its second render on this phone",
            Residency.releaseAfterRun(zimage, phone12),
        )
    }

    /**
     * ⭐⭐⭐ **The Gen 5 case, which is why there is no toggle.** More RAM is the
     * whole difference, and the gate reads it — so a 16 GB phone keeps both
     * models loaded without anyone opting out, and nothing here needs editing
     * when a bigger phone ships.
     */
    @Test fun aBiggerPhoneKeepsBothResident() {
        assertFalse(Residency.releaseAfterRun(flux, phone16))
        assertFalse(Residency.releaseAfterRun(zimage, phone16))
    }

    /**
     * ⚠⚠ The margin, asserted as a margin. Z-Image is 75% of this phone and
     * FLUX.2 is 57%; a threshold that drifted to either side of that gap would
     * silently start tearing down a working model or stop saving a broken one,
     * and both failures look like "the app got slower" rather than like a bug.
     */
    @Test fun theThresholdSitsInTheGapWithRoomOnBothSides() {
        val fluxShare = flux * 100 / phone12
        val zShare = zimage * 100 / phone12
        assertTrue("FLUX.2 measured 57% of RAM, got $fluxShare", fluxShare in 55..59)
        assertTrue("Z-Image measured 75% of RAM, got $zShare", zShare in 73..77)
        assertTrue(
            "the threshold must sit strictly between them",
            Residency.RESIDENT_SHARE_PERCENT in (fluxShare + 4)..(zShare - 4),
        )
    }

    /**
     * ⚠⚠ An unknown size answers FALSE. A wrong `true` costs a backend reload on
     * every Run for as long as it is wrong, so "I don't know" must mean "carry
     * on", never "tear down". Reachable for real: an imported checkpoint whose
     * directory cannot be walked reports 0 bytes.
     */
    @Test fun nothingKnownMeansCarryOn() {
        assertFalse(Residency.releaseAfterRun(0, phone12))
        assertFalse(Residency.releaseAfterRun(-1, phone12))
        assertFalse(Residency.releaseAfterRun(zimage, 0))
        assertFalse(Residency.releaseAfterRun(zimage, -1))
    }

    /** ⚠ Every SD/SDXL/Anima checkpoint we ship is far under the gate. */
    @Test fun ordinaryCheckpointsStayResident() {
        for (bytes in listOf(1_700_000_000L, 3_700_000_000L, 4_900_000_000L)) {
            assertFalse("$bytes must stay resident", Residency.releaseAfterRun(bytes, phone12))
        }
    }

    /** ⚠ The line a person reads names the model and both numbers. */
    @Test fun theReasonNamesTheModelAndTheNumbers() {
        val why = Residency.why("Z-Image Turbo", zimage, phone12)
        assertTrue(why, why.contains("Z-Image Turbo"))
        assertTrue(why, why.contains("8.2 GB"))
        assertTrue(why, why.contains("10.9 GB"))
    }
}
