package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Test

/** ⭐ The run's line in the notification shade ([RunNotice], the user 2026-11-14). */
class RunNoticeTest {
    @Test
    fun theLineIsTheStepThenTheTimeSoFar() {
        assertEquals("5/8 · 62% · 1 min 3 s", RunNotice.textFor("5/8 · 62%", 63_000))
        assertEquals("12 s", RunNotice.textFor(null, 12_400))
    }
}
