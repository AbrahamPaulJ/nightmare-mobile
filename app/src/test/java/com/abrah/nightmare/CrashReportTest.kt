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
        assertEquals("rendering", crumb.readText())
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
        assertEquals("downloading FLUX.2 Klein 4B", crumb.readText())
    }

    /** ⚠ A different job replaces it, so the report names the RIGHT thing. */
    @Test
    fun aDifferentJobReplacesIt() {
        CrashReport.mark(ctx, "downloading FLUX.2 Klein 4B")
        CrashReport.mark(ctx, "rendering")
        assertEquals("rendering", crumb.readText())
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
