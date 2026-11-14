package com.abrah.nightmare.ui

import com.abrah.nightmare.ResultTags
import com.abrah.nightmare.canvas.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** GitHub #117: the Results filter's rules ([ResultFilter]). */
class ResultFilterTest {
    private val utc = ZoneOffset.UTC
    private fun at(day: String) = LocalDate.parse(day).atStartOfDay(utc).toInstant().toEpochMilli() + 3_600_000

    private fun result(
        id: String = "r1", day: String = "2026-11-10", w: Int = 1024, h: Int = 1024, seed: String? = "123456",
        clip: Boolean = false, totalMs: Long? = 30_500,
    ) = Result(
        id = id, savedAt = at(day), seed = seed, model = null, prompt = null, width = w, height = h,
        times = totalMs?.let { mapOf(ResultFilter.TOTAL_MS_KEY to it) } ?: emptyMap(),
        videoPath = if (clip) "/x.mp4" else null,
    )

    private val sdxl = ResultTags(
        setOf("SDXL"), "", prompt = "a red cat ", negative = "blurry ", samplers = setOf("Euler A"),
        schedules = setOf("_karras"), steps = 20, cfg = 7.0,
    )
    private val wan = ResultTags(setOf("Wan 2.2 5B"), "", prompt = "a dog runs ")

    @Test
    fun aClearFilterShowsEverythingAndIsInactive() {
        assertFalse(ResultFilter().active)
        assertTrue(ResultFilter().matches(result(), sdxl, utc))
    }

    @Test
    fun noneUntickedEveryModelHidesEveryAttributedResult() {
        val f = ResultFilter(hiddenModels = setOf("SDXL", "Wan 2.2 5B"))
        assertFalse(f.matches(result(), sdxl, utc))
        assertFalse(f.matches(result(clip = true), wan, utc))
        // ⚠ A flow naming no model (an Upscale) is never hidden by the model list.
        assertTrue(f.matches(result(), ResultTags(emptySet(), ""), utc))
    }

    @Test
    fun promptAndNegativeSearchTheirOwnText() {
        assertTrue(ResultFilter(prompt = "RED").matches(result(), sdxl, utc))
        assertFalse(ResultFilter(prompt = "blurry").matches(result(), sdxl, utc))
        assertTrue(ResultFilter(negative = "blurry").matches(result(), sdxl, utc))
    }

    @Test
    fun aSeedMatchesWhatWasRolled() {
        assertTrue(ResultFilter(seed = "3456").matches(result(), sdxl, utc))
        assertFalse(ResultFilter(seed = "999").matches(result(), sdxl, utc))
        assertFalse(ResultFilter(seed = "1").matches(result(seed = null), sdxl, utc))
    }

    @Test
    fun theDateRangeIsInclusiveLocalDays() {
        val d = LocalDate.parse("2026-11-10").toEpochDay()
        assertTrue(ResultFilter(fromDay = d, toDay = d).matches(result(), sdxl, utc))
        assertFalse(ResultFilter(fromDay = d + 1).matches(result(), sdxl, utc))
        assertFalse(ResultFilter(toDay = d - 1).matches(result(), sdxl, utc))
    }

    @Test
    fun aNarrowedRangeAsksForAValue() {
        assertTrue(ResultFilter(steps = 10..20).matches(result(), sdxl, utc))
        assertFalse(ResultFilter(steps = 21..30).matches(result(), sdxl, utc))
        // ⚠ No steps known (a clip): not in a range the user narrowed.
        assertFalse(ResultFilter(steps = 1..60).matches(result(clip = true), wan, utc))
        assertTrue(ResultFilter(cfg = 6.5..7.0).matches(result(), sdxl, utc))
        assertTrue(ResultFilter(seconds = 31..40).matches(result(), sdxl, utc)) // 30.5 s rounds up to 31
        assertFalse(ResultFilter(seconds = 1..30).matches(result(), sdxl, utc))
        assertFalse(ResultFilter(seconds = 1..99).matches(result(totalMs = null), sdxl, utc))
    }

    @Test
    fun samplerScheduleSizeAndKind() {
        assertFalse(ResultFilter(hiddenSamplers = setOf("Euler A")).matches(result(), sdxl, utc))
        assertTrue(ResultFilter(hiddenSamplers = setOf("Euler A")).matches(result(clip = true), wan, utc))
        assertFalse(ResultFilter(hiddenSchedules = setOf("_karras")).matches(result(), sdxl, utc))
        assertFalse(ResultFilter(hiddenSizes = setOf("1024x1024")).matches(result(), sdxl, utc))
        assertTrue(ResultFilter(hiddenSizes = setOf("1024x1024")).matches(result(w = 768), sdxl, utc))
        assertFalse(ResultFilter(kind = ResultKind.CLIPS).matches(result(), sdxl, utc))
        assertTrue(ResultFilter(kind = ResultKind.CLIPS).matches(result(clip = true), wan, utc))
    }

    @Test
    fun yesNoQuestions() {
        val ref = sdxl.copy(reference = true, upscaled = true)
        assertTrue(ResultFilter(reference = Tri.YES, upscaled = Tri.YES).matches(result(), ref, utc))
        assertFalse(ResultFilter(reference = Tri.NO).matches(result(), ref, utc))
        assertFalse(ResultFilter(fromImage = Tri.YES).matches(result(), ref, utc))
    }

    @Test
    fun optionsListWhatTheResultsHold() {
        val all = listOf(result("a"), result("b", w = 512, h = 768, clip = true, totalMs = 2_000))
        val o = ResultFilterOptions.of(all) { if (it.id == "a") sdxl else wan }
        assertEquals(listOf("SDXL", "Wan 2.2 5B"), o.models)
        assertEquals(listOf("1024x1024", "512x768"), o.sizes)
        assertEquals(20..20, o.steps)
        assertEquals(2..31, o.seconds)
        assertTrue(o.hasClips)
    }
}
