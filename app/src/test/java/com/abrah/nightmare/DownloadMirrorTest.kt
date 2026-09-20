package com.abrah.nightmare

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ⭐⭐ The download mirror: what is STORED, what is SENT, and what the screen
 * is told.
 *
 * ⚠⚠⚠ The third is the one that broke. Shipped 2026-09-20, the radios looked
 * dead: the preference was written and every download honoured it, but the
 * screen read `Prefs.downloadBase` — a plain `var` — so nothing recomposed and
 * the selection never moved. A test of the preference alone would have passed
 * throughout. `docs/UI.md`: `Prefs` owns the disk, the view model owns the
 * frame.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadMirrorTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    @After
    fun reset() {
        Prefs.setDownloadBase(ctx, Prefs.HF_ORIGIN)
    }

    @Test
    fun theDefaultRewritesNothing() {
        val url = Prefs.HF_ORIGIN + "org/repo/resolve/main/model.safetensors"
        assertEquals(url, Prefs.apply(url))
    }

    @Test
    fun theMirrorRewritesOnlyTheOrigin() {
        Prefs.setDownloadBase(ctx, Prefs.HF_MIRROR)
        assertEquals(
            Prefs.HF_MIRROR + "org/repo/resolve/main/model.safetensors",
            Prefs.apply(Prefs.HF_ORIGIN + "org/repo/resolve/main/model.safetensors"),
        )
    }

    /**
     * ⚠⚠ A GitHub release — the DiT engine — must be left alone. A Hugging
     * Face mirror says nothing about GitHub, and silently pointing a release
     * download at it would 404.
     */
    @Test
    fun aNonHuggingFaceUrlIsUntouched() {
        Prefs.setDownloadBase(ctx, Prefs.HF_MIRROR)
        val gh = "https://github.com/AbrahamPaulJ/nightmare-mobile/releases/download/v1/engine.zip"
        assertEquals(gh, Prefs.apply(gh))
    }

    /** ⚠ A custom address is normalised: trailing slash added, blank refused. */
    @Test
    fun aCustomAddressIsNormalised() {
        Prefs.setDownloadBase(ctx, "https://example.test/hf")
        assertEquals("https://example.test/hf/", Prefs.downloadBase)
        Prefs.setDownloadBase(ctx, "   ")
        assertEquals(Prefs.HF_ORIGIN, Prefs.downloadBase)
    }

    /** ⭐ It survives a restart — the whole point of a preference. */
    @Test
    fun itIsRemembered() {
        Prefs.setDownloadBase(ctx, Prefs.HF_MIRROR)
        Prefs.load(ctx)
        assertEquals(Prefs.HF_MIRROR, Prefs.downloadBase)
    }
}
