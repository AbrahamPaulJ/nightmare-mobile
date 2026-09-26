package com.abrah.nightmare.canvas

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.abrah.nightmare.NODE_TYPES
import com.abrah.nightmare.ui.NightmareTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ⚠⚠⚠ **Every control on the canvas bars is DRAWN on a narrow phone.**
 *
 * Reported from the phone 2026-09-23 with a screenshot: no Settings gear in the
 * header and "+ Node" an empty outlined box. The phone is 1080px at 450dpi —
 * 384dp — and every golden was 411dp, where both rows have just enough room. A
 * golden only fails if somebody looks at it, so this ASSERTS a width: a control
 * squeezed to zero or clipped to nothing is a number, not a picture.
 *
 * ⚠⚠ 384dp is the phone this was found on, but Robolectric's font is
 * NARROWER than the phone's, so the old layout passes at 384dp here. Checked
 * against the old layout 2026-09-23: it fails at 360dp, which is therefore the
 * case that guards this, and 320dp is margin.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NarrowPhoneTest {

    @get:Rule
    val rule = createComposeRule()

    private fun screen() {
        rule.setContent {
            NightmareTheme(darkTheme = true) {
                CanvasScreen(
                    version = "1.6.013",
                    state = CanvasState(defaultWorkflow()).withView(null),
                    types = NODE_TYPES,
                    status = emptyMap(),
                    busy = false,
                    image = null,
                    onGesture = {}, onRun = {}, onBack = {},
                    backendUp = true,
                    flowName = "unsaved flow",
                    flowDirty = true,
                    loadLine = "Z-Image Turbo (idle) · 4.7/11.7 GB free",
                )
            }
        }
    }

    private fun assertDrawn() {
        screen()
        for (cd in listOf("save this flow", "Settings")) {
            val b = rule.onNodeWithContentDescription(cd).getBoundsInRoot()
            assertTrue("$cd squeezed to ${b.width}", b.width >= 24.dp)
        }
        // ⚠ The LABEL's width, not the button's: a clipped label inside a
        // button that kept its min width is exactly the empty box reported.
        val add = rule.onNodeWithText("+ Node").getBoundsInRoot()
        assertTrue("+ Node clipped to ${add.width}", add.width >= 30.dp)
        val run = rule.onNodeWithText("Run").getBoundsInRoot()
        assertTrue("Run clipped to ${run.width}", run.width >= 20.dp)
    }

    @Test @Config(qualifiers = "w384dp-h832dp-xxhdpi")
    fun theS25UltraDrawsEveryControl() = assertDrawn()

    @Test @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun a360dpPhoneDrawsEveryControl() = assertDrawn()

    @Test @Config(qualifiers = "w320dp-h690dp-xhdpi")
    fun a320dpPhoneDrawsEveryControl() = assertDrawn()

    /**
     * ⚠⚠⚠ The mask window with BOTH Tap and Auto segment shown. Reported
     * 2026-09-23: seven controls in one Row and the Clear button shrank to a
     * sliver. The row wraps now; every control must keep a real width.
     */
    private fun assertMaskToolbarDrawn() {
        rule.setContent {
            NightmareTheme(darkTheme = true) {
                androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize()) {
                    NodeInspectorBody(
                        nodeId = "inpaint",
                        node = com.abrah.nightmare.Node(
                            "inpaint", "sd15.inpaint",
                            params = mapOf(
                                "x" to "0.1", "y" to "0.1", "w" to "0.8", "h" to "0.8",
                                "width" to "512", "height" to "512", "model" to "absolutereality",
                                com.abrah.nightmare.SdSampler.TAP_SELECT to "true",
                                com.abrah.nightmare.SdSampler.PICK_SELECT to "true",
                                com.abrah.nightmare.MaskNode.OPS to "s0.3:0.35,0.6~0,0.09",
                            ),
                            inputs = com.abrah.nightmare.sources("image" to "photo"),
                        ),
                        type = NODE_TYPES["sd15.inpaint"],
                        onSetParam = { _, _, _ -> },
                        onDelete = {},
                        onReset = {},
                        cropSource = stripes(),
                        maskSource = stripes(),
                        inlinePopupTab = 1,
                        segmenterInstalled = true,
                        parserInstalled = true,
                        onPickMask = { _, _, _ -> },
                    )
                }
            }
        }
        for (cd in listOf(
            "Paint", "Erase", "Select an object", "Auto segment",
            "Undo the last stroke", "Invert the mask", "Clear the mask",
        )) {
            val b = rule.onNodeWithContentDescription(cd).getBoundsInRoot()
            assertTrue("$cd squeezed to ${b.width}", b.width >= 24.dp)
        }
    }

    private fun stripes(): androidx.compose.ui.graphics.ImageBitmap {
        val b = android.graphics.Bitmap.createBitmap(300, 220, android.graphics.Bitmap.Config.ARGB_8888)
        b.eraseColor(android.graphics.Color.rgb(60, 110, 170))
        return b.asImageBitmap()
    }

    // ⚠ TALL on purpose: the row wraps, and a second line below the bottom
    // of a short screen reads as zero width — off screen, not squeezed.
    @Test @Config(qualifiers = "w320dp-h1400dp-xhdpi")
    fun theMaskToolbarWrapsAt320dp() = assertMaskToolbarDrawn()

    @Test @Config(qualifiers = "w360dp-h1400dp-xxhdpi")
    fun theMaskToolbarWrapsAt360dp() = assertMaskToolbarDrawn()

    /** ⭐ And the picture, at the width it was reported on. */
    @Test @Config(qualifiers = "w384dp-h832dp-xxhdpi")
    fun screenAt384dp() {
        screen()
        rule.onRoot().captureRoboImage("src/test/screenshots/screen-384dp.png")
    }
}
