package com.abrah.nightmare

import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ⭐⭐⭐ **The breadcrumb, which is what keeps the crash report honest.**
 *
 * ⚠⚠⚠ The user's condition, 2026-09-21: *"make sure its only for app opens
 * after crashes, false positives would be annoying"*. Android reclaims
 * backgrounded apps constantly, so "was the app doing anything" is the clause
 * that separates a real report from a nuisance — see [CrashReport].
 *
 * ⚠⚠ What these can cover is the breadcrumb half. `ApplicationExitInfo` cannot
 * be forged in Robolectric — `getHistoricalProcessExitReasons` returns an empty
 * list here — so the reason half is asserted by construction in [CrashReport]'s
 * two sets and by the shape of `worthReporting`. ⇒ These pin the half that has
 * state and can rot; the half that is a `when` over constants is read.
 */
@RunWith(RobolectricTestRunner::class)
class CrashReportTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val crumb get() = File(ctx.filesDir, "in-flight.txt")

    /** ⚠ Absent for an instant while a write replaces it (on Windows `copyTo` deletes first): read as empty then. */
    private fun lines(): List<String> = runCatching { crumb.readLines() }.getOrDefault(emptyList())

    @Before
    fun clean() {
        CrashReport.clear(ctx)
        ctx.getSharedPreferences("crash-report", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    @Test
    fun anIdleAppLeavesNoBreadcrumb() {
        assertTrue("nothing should be marked before any work", !crumb.exists())
    }

    @Test
    fun markRecordsWhatTheAppIsDoing() {
        CrashReport.mark(ctx, "rendering")
        assertTrue(crumb.isFile)
        assertEquals("rendering", crumb.readLines().first())
    }

    /**
     * ⭐ The S24 Ultra reboot (Telegram #6269): the report must say which model the backend was loading
     * or releasing, and how low the RAM went. Only `[lowram]` lines count; the rest of the log does not.
     */
    @Test
    fun theBackendsModelEventsAndTheLowestRamGoIn() {
        CrashReport.mark(ctx, "rendering")
        CrashReport.backendEvent(ctx, "  6306.4ms [  INFO ] [lowram] SDXL UNET loaded")
        CrashReport.backendEvent(ctx, "  6400.0ms [  INFO ] [dit] flux executing segment 3/34")
        val deadline = System.currentTimeMillis() + 3_000
        while (lines().none { it.startsWith("lowest ") } && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val lines = crumb.readLines()
        assertTrue(lines.toString(), lines.any { "backend SDXL UNET loaded" in it })
        assertTrue(lines.toString(), lines.none { "executing segment" in it })
        assertTrue(lines.toString(), lines.any { it.startsWith("lowest ") && "MB free at" in it })
        CrashReport.clear(ctx)
    }

    /** ⭐ A clean finish erases it, which is what makes its presence mean something. */
    @Test
    fun aCleanFinishErasesIt() {
        CrashReport.mark(ctx, "rendering")
        CrashReport.clear(ctx)
        assertTrue("a finished job must leave nothing behind", !crumb.exists())
    }

    /**
     * ⚠⚠ Called on every progress tick — 150 ms apart through a multi-gigabyte
     * download. It must not be a file write each time.
     */
    @Test
    fun markIsIdempotentForTheSameJob() {
        CrashReport.mark(ctx, "downloading FLUX.2 Klein 4B")
        val first = crumb.lastModified()
        repeat(50) { CrashReport.mark(ctx, "downloading FLUX.2 Klein 4B") }
        assertEquals("the file should be written once per job", first, crumb.lastModified())
        assertEquals("downloading FLUX.2 Klein 4B", crumb.readLines().first())
    }

    /** ⚠ A different job replaces it, so the report names the RIGHT thing. */
    @Test
    fun aDifferentJobReplacesIt() {
        CrashReport.mark(ctx, "downloading FLUX.2 Klein 4B")
        CrashReport.mark(ctx, "rendering")
        assertEquals("rendering", crumb.readLines().first())
    }

    /**
     * ⭐⭐⭐ **The false-positive guarantee, end to end.** No exit info exists in
     * Robolectric, so `pending` must say nothing — and it must say nothing
     * whether or not a breadcrumb is lying around. A breadcrumb ALONE is not a
     * crash.
     */
    @Test
    fun aBreadcrumbAloneIsNotACrash() {
        CrashReport.mark(ctx, "rendering")
        assertNull("a breadcrumb with no exit must report nothing", CrashReport.pending(ctx))
    }

    /**
     * ⭐ A breadcrumb older than this BOOT is a phone restart mid-job (a tester's S24 Ultra,
     * 2026-10-13): no exit record exists, and the report carries the per-node RAM lines.
     */
    @Test
    fun aRestartMidJobIsReportedWithItsStages() {
        CrashReport.mark(ctx, "rendering SDXL")
        CrashReport.stage(ctx, "generate")
        // ⚠ The RAM sampler's first write first: landing after the aging below, it made the crumb new again (the suite
        // on a loaded PC, 2026-10-31). Robolectric's free RAM never changes, so it writes once.
        val deadline = System.currentTimeMillis() + 3_000
        while (lines().none { it.startsWith("lowest ") } && System.currentTimeMillis() < deadline) Thread.sleep(20)
        // ⚠ A minute before BOOT, not ten minutes before now: `pending` compares with now - elapsedRealtime, and
        // Robolectric's elapsedRealtime is a fake clock that a long test JVM can carry past ten minutes (it failed so).
        val bootAt = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
        crumb.setLastModified(bootAt - 60_000L)
        val r = CrashReport.pending(ctx)
        assertEquals("rendering SDXL", r?.doing)
        assertTrue("the stage line should be in the details", r?.trace.orEmpty().contains("generate"))
        assertTrue("and the free RAM", r?.trace.orEmpty().contains("MB free"))
        assertNull("shown once", CrashReport.pending(ctx))
    }

    @Test
    fun anOrdinaryLaunchReportsNothing() {
        assertNull(CrashReport.pending(ctx))
    }

    /**
     * ⚠⚠ `pending` CONSUMES the breadcrumb. "Was it mid-job" is a question about
     * the exit being reported now; leaving the file would make the next routine
     * reclaim read as a crash — the exact false positive this design exists to
     * avoid.
     */
    @Test
    fun pendingConsumesTheBreadcrumb() {
        CrashReport.mark(ctx, "rendering")
        CrashReport.pending(ctx)
        assertTrue("the breadcrumb must not survive being read", !crumb.exists())
    }
}
