package com.abrah.nightmare

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import com.abrah.nightmare.canvas.LoraPicker
import com.abrah.nightmare.canvas.NodeInspectorBody
import com.abrah.nightmare.ui.NightmareTheme
import com.abrah.nightmare.ui.OpenFlowDialog
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ⭐⭐ Dialog FOOTERS, drawn whole: the buttons are what broke. Three contained buttons in one row
 * crushed the last to an empty sliver (Open flow, 2026-10-23), and no golden drew a dialog's buttons.
 * ⚠ The whole screen ([com.github.takahirom.roborazzi.captureScreenRoboImage]): a dialog is its own
 * window, and a composable capture records a blank page (`UltraFixTest`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class DialogGoldenTest {
    @get:org.junit.Rule
    val fastIdle = FastIdle()

    @get:org.junit.Rule
    val rule = androidx.compose.ui.test.junit4.createComposeRule()

    /** ⚠ The Post-processing fold's open state is an app pref: opened for the card tests, put back after. */
    @org.junit.After
    fun closeTheFold() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        Prefs.setMoreOptionsOpen(ctx, false)
    }

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        Prefs.setMoreOptionsOpen(ctx, true)
        rule.setContent {
            NightmareTheme(darkTheme = true) {
                androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize()) { content() }
            }
        }
        rule.waitForIdle()
    }

    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    private fun capture(name: String) = com.github.takahirom.roborazzi.captureScreenRoboImage(goldenPath(this, name))

    /** ⭐ Save, Open anyway (red), Cancel: stacked full width, every one readable. */
    @Test
    fun openFlowStacksItsThreeButtons() {
        show { OpenFlowDialog("Text to image", onSave = {}, onOpenAnyway = {}, onCancel = {}) }
        capture("dialog-open-flow")
    }

    /** ⭐ Files and Search online as two contained halves, Done under them. */
    @Test
    fun theLoraPickersFooter() {
        show {
            LoraPicker(
                installed = listOf("film-grain.safetensors" to 42L * 1024 * 1024),
                spec = "", onSet = {}, onDismiss = {}, onImport = {}, onBrowse = {},
            )
        }
        capture("dialog-lora-picker")
    }

    /** ⭐ The Output's Upscale button opens its settings: the upscaler and scale live here, not on the page. */
    @Test
    fun theUpscaleCardOpensItsSettings() {
        show {
            NodeInspectorBody(
                nodeId = "out",
                node = Node("out", MediaOutputNode.name, params = mapOf(MediaOutputNode.UPSCALE to "true")),
                type = NODE_TYPES[MediaOutputNode.name],
                onSetParam = { _, _, _ -> },
                onDelete = {},
                onApplyPostNow = {},
            )
        }
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        rule.onNode(hasText(ctx.getString(R.string.post_upscale_now)) and hasClickAction()).performClick()
        rule.waitForIdle()
        capture("dialog-output-upscale")
    }

    /** ⭐ The library sheet's head: Models and Flows as two cards (Flows open), and the Flows "+" after them. */
    @Test
    fun theLibraryTogglesModelsAndFlows() {
        show {
            com.abrah.nightmare.ui.LibraryScreen(
                tab = com.abrah.nightmare.ui.LibraryTab.FLOWS, onTab = {}, showTabs = false,
                models = {}, flows = {},
                flowsAction = { com.abrah.nightmare.ui.ImportButton(enabled = true) {} },
            )
        }
        capture("library-toggle")
    }

    /** ⭐ …and Adjust colours' four sliders the same way. */
    @Test
    fun theAdjustCardOpensItsSliders() {
        show {
            NodeInspectorBody(
                nodeId = "out",
                node = Node("out", MediaOutputNode.name, params = mapOf(MediaOutputNode.BRIGHTNESS to "0.2")),
                type = NODE_TYPES[MediaOutputNode.name],
                onSetParam = { _, _, _ -> },
                onDelete = {},
                onApplyPostNow = {},
            )
        }
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        rule.onNode(hasText(ctx.getString(R.string.post_adjust_now)) and hasClickAction()).performClick()
        rule.waitForIdle()
        capture("dialog-output-adjust")
    }
}
