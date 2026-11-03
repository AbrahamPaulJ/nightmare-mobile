package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ⭐ GitHub #79: a sound when a long run ends, never for a short or cancelled one, and only when asked for. */
class RunAlertTest {

    @Test
    fun onlyALongFinishedRunChimesWhenAskedFor() {
        assertTrue(RunAlert.chimes(on = true, ms = RunAlert.MIN_MS, cancelled = false))
        assertFalse("off by default and when off", RunAlert.chimes(on = false, ms = 600_000, cancelled = false))
        assertFalse("a 3 s render run again and again", RunAlert.chimes(on = true, ms = 3_000, cancelled = false))
        assertFalse("the person who cancelled is looking at it", RunAlert.chimes(on = true, ms = 600_000, cancelled = true))
    }

    @Test
    fun durationsReadAsAPersonSaysThem() {
        assertEquals("45 s", RunAlert.formatTook(45_400))
        assertEquals("2 min 13 s", RunAlert.formatTook(133_000))
        assertEquals("1 h 4 min", RunAlert.formatTook(3_840_000))
    }
}
