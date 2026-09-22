package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ⭐⭐⭐ **Upscale enlarges the picture you were looking at.**
 *
 * ⚠⚠⚠ The seed pin was removed on 2026-09-22 on the reasoning that "nothing
 * upstream changes, so every node before this one comes back from the cache".
 * That is false for `seed = 0`, which is the default: a Run rolls a new seed,
 * the cache key differs, and the sampler renders something else. Reported from
 * the phone the same day — *"u rerun the generate and get different image with
 * its upscaled"*.
 */
class SeedToPinTest {

    /** ⭐ The bug: a loose seed is pinned to whatever actually made the picture. */
    @Test
    fun aLooseSeedIsPinnedToTheRolledOne() {
        assertEquals("1234", seedToPin("0", "1234"))
        assertEquals("1234", seedToPin("", "1234"))
        assertEquals("1234", seedToPin(null, "1234"))
        assertEquals("1234", seedToPin("  ", "1234"))
    }

    /** ⚠⚠ A seed the user typed is theirs — never overwritten. */
    @Test
    fun aTypedSeedIsLeftAlone() {
        assertNull(seedToPin("99", "1234"))
    }

    /**
     * ⚠ And with nothing on record about what made the picture, nothing is
     * written: guessing a seed would pin the WRONG one, which is worse than
     * leaving it loose.
     */
    @Test
    fun nothingIsPinnedWhenTheRollIsUnknown() {
        assertNull(seedToPin("0", null))
        assertNull(seedToPin("0", ""))
    }
}
