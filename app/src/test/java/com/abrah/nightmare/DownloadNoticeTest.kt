package com.abrah.nightmare

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ⭐⭐ The shade's ONGOING row, and the one property the backstop rests on.
 *
 * ⚠⚠⚠ The bug this is here for shipped and was found by a person holding the
 * phone, 2026-09-21: a custom Z-Image import left "importing" pinned in the
 * notification shade after it had finished. Seven paths call
 * [DownloadNotice.progress] and only five ended it — the two that did not were
 * both imports — and `setOngoing(true)` means the user could not swipe it away
 * either. It is the N−1-of-N rule (`docs/ARCHITECTURE.md` §5.6) and the suite
 * saw none of it, because nothing here had ever looked at the shade.
 *
 * ⚠ What this can cover is the CONTRACT: progress arms, both endings disarm.
 * `HarnessViewModel.refreshModels` is what applies it to every installer, and
 * that needs the whole view model — the value of pinning the contract is that
 * the backstop cannot be quietly broken from underneath.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadNoticeTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun reset() = DownloadNotice.clear(ctx)

    @Test
    fun progressArmsTheBackstop() {
        assertFalse("a fresh notice must not read as live", DownloadNotice.live)
        DownloadNotice.progress(ctx, "Z-Image Turbo", "importing", 0.5f)
        assertTrue("an ongoing row must read as live", DownloadNotice.live)
    }

    /**
     * ⭐ A reported outcome is NOT live: it is dismissible and the user asked to
     * see it, so the backstop must leave it alone.
     */
    @Test
    fun anOutcomeDisarmsItButStaysInTheShade() {
        DownloadNotice.progress(ctx, "Z-Image Turbo", "importing", 0.5f)
        DownloadNotice.done(ctx, "Z-Image Turbo", ok = true)
        assertFalse("a finished download must not be swept up as stale", DownloadNotice.live)
    }

    @Test
    fun aFailureAlsoDisarmsIt() {
        DownloadNotice.progress(ctx, "mine", "importing", null)
        DownloadNotice.done(ctx, "mine", ok = false, detail = "import failed")
        assertFalse(DownloadNotice.live)
    }

    @Test
    fun aCancelDisarmsIt() {
        DownloadNotice.progress(ctx, "mine", "importing", 0.1f)
        DownloadNotice.clear(ctx)
        assertFalse(DownloadNotice.live)
    }

    /**
     * ⚠ The shape the bug actually had: progress ticks and then nothing. The row
     * stays live, which is precisely what lets `refreshModels` notice it.
     */
    @Test
    fun anImportThatReportsNoOutcomeIsLeftDetectable() {
        repeat(5) { DownloadNotice.progress(ctx, "myzit", "importing", it / 5f) }
        assertTrue(
            "a path that forgot to end its row must stay detectable",
            DownloadNotice.live,
        )
    }
}
