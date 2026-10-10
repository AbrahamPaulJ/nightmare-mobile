package com.abrah.nightmare

import androidx.compose.foundation.layout.fillMaxSize
import com.abrah.nightmare.ui.NightmareTheme
import com.abrah.nightmare.ui.UltraFixDialog
import com.abrah.nightmare.ui.UpscalePicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** ⭐ UltraFix (`UltraFix.kt`): Local Dream's numbers, the size rules, and the two surfaces. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class UltraFixTest {

    /** ⚠ Local Dream's `ultrafixDenoiseStrength`, exactly: (denoise − 0.5) / steps, clamped. */
    @Test
    fun theStrengthIsLocalDreams() {
        assertEquals(0.35, UltraFix.strength(4, 10), 1e-9)
        assertEquals(0.0, UltraFix.strength(0, 10), 1e-9)
        assertEquals(0.95, UltraFix.strength(20, 10), 1e-9)
        assertEquals(0.0, UltraFix.strength(3, 0), 1e-9)
    }

    @Test
    fun sizesSnapToEightAndTilesFollowTheFamily() {
        assertEquals(2048 to 1528, UltraFix.snap(2050, 1535))
        assertEquals(512, UltraFix.tileFor(Family.SD15))
        assertEquals(1024, UltraFix.tileFor(Family.SDXL))
        assertNull(UltraFix.tileFor(Family.ZIMAGE))
        assertNull(UltraFix.tileFor(Family.SD15_SWAP))
    }

    @get:org.junit.Rule
    val rule = androidx.compose.ui.test.junit4.createComposeRule()

    /**
     * ⚠⚠ The WHOLE SCREEN, dialogs included: an `AlertDialog` is a window of its own, and the
     * composable capture recorded a blank page for both of these — a golden that passed while
     * showing nothing (2026-10-09).
     */
    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    /** ⭐ Hires fix ([SdSampler.HIRES]) is offered where UltraFix can run: SD 1.5 / SDXL, not inpaint, not Swap. */
    @Test
    fun hiresFixIsOnTheSd15AndSdxlSamplersOnly() {
        fun has(t: String) = NODE_TYPES[t]!!.widgets.any { it.name == SdSampler.HIRES }
        assertEquals(true, has("sd15.sample"))
        assertEquals(true, has("sdxl.sample"))
        for (t in listOf("sd15.inpaint", "sdxl.inpaint", "sd15swap.sample", "sdxlswap.sample", "anima.sample")) {
            assertEquals(t, false, has(t))
        }
    }

    private fun shoot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent {
            NightmareTheme(darkTheme = true) {
                androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize()) { content() }
            }
        }
        rule.waitForIdle()
        com.github.takahirom.roborazzi.captureScreenRoboImage(goldenPath(this, name))
    }

    @Test
    fun theDialog() = shoot("ultrafix-dialog") {
        UltraFixDialog(UltraFix.Params(), onConfirm = {}, onDismiss = {})
    }

    /** ⭐ A 4096² picture: no scale fits, Use is dimmed — and UltraFix is still offered. */
    @Test
    fun theChooserOffersUltraFixOnAPictureTooBigToEnlarge() = shoot("upscale-picker-ultrafix") {
        UpscalePicker(upscalers = emptyList(), onPick = { _, _ -> }, onInstall = {}, onDismiss = {}, width = 4096, height = 4096, onUltraFix = {})
    }
}
