package com.abrah.nightmare.canvas

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.abrah.nightmare.Family
import com.abrah.nightmare.HarnessViewModel
import com.abrah.nightmare.ModelCatalog
import com.abrah.nightmare.NODE_TYPES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ⭐⭐⭐ **The checkpoint swap's dialog — that it is DRAWN, and what it returns.**
 *
 * ⚠⚠⚠ It exists because of a bug no other kind of test could have caught.
 * `pendingSwap`, `onConfirmSwap` and `onCancelSwap` were parameters of
 * [CanvasScreen] for a day and **no line of its body read any of them**, so
 * picking a checkpoint from another family set the state and drew nothing at
 * all. Reported from the phone, 2026-09-15, as *"the checkpoint picker is
 * broken"*.
 *
 * ⚠⚠ A dead parameter is invisible to the compiler, to every JVM test, and to
 * the goldens as well — a Compose `AlertDialog` is its own WINDOW, so Roborazzi
 * capturing the screen composable does not photograph it. A golden was written
 * first and came back showing a tidy canvas with no dialog on it, which is
 * exactly the false green `docs/UI.md` §5 is about. ⇒ This drives the real
 * composable and asserts on the tree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ModelSwapDialogTest {

    @get:Rule
    val rule = createComposeRule()

    private val sdxl = ModelCatalog.byId("sdxl_dreamshaper")!!

    /** The three-part case: a family change, a prompt to offer, and a recipe. */
    private fun swap(
        promptNode: String? = "prompt",
        recipe: String? = "20 steps, cfg 7.5, dpmpp",
    ) = HarnessViewModel.ModelSwap(
        nodeId = "sample",
        spec = sdxl,
        newType = "sdxl.sample",
        fromFamily = Family.SD15,
        promptNode = promptNode,
        prompt = sdxl.starterPrompt,
        negative = sdxl.starterNegative,
        recipe = recipe,
    )

    private fun show(
        s: HarnessViewModel.ModelSwap,
        onConfirm: (HarnessViewModel.ModelSwap, Boolean, Boolean) -> Unit = { _, _, _ -> },
        onCancel: () -> Unit = {},
    ) {
        rule.setContent {
            CanvasScreen(
                state = CanvasState(defaultWorkflow()).withView(null),
                types = NODE_TYPES,
                status = emptyMap(),
                busy = false,
                image = null,
                onGesture = {}, onRun = {}, onBack = {},
                pendingSwap = s,
                onConfirmSwap = onConfirm,
                onCancelSwap = onCancel,
            )
        }
    }

    /** ⚠ The regression itself: a pending swap must put a dialog on the screen. */
    @Test
    fun aPendingSwapDrawsItsDialog() {
        show(swap())
        rule.onNodeWithText("Switch to ${sdxl.label}").assertIsDisplayed()
        rule.onNodeWithText("Switch").assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    /**
     * ⭐⭐⭐ **The recommended answer is the DEFAULT one**, or the word is
     * decoration — pressing Switch without touching anything takes the
     * checkpoint's own prompt and its own settings.
     *
     * ⚠⚠ This is a REVERSAL of the same day's first shape, which was two
     * checkboxes starting off. A checkbox's unticked state is "no", so the
     * recommended answer needed two extra taps and the dialog said nothing
     * about which answer is usually right. ⚠ It is safe to reverse only
     * because the dialog SHOWS the text it would write — the argument for
     * defaulting to "keep mine" was that silently overwriting a typed sentence
     * is the worst trade in the app, and nothing here is silent.
     */
    @Test
    fun switchingWithoutChoosingTakesTheCheckpointsOwn() {
        var got: Triple<String, Boolean, Boolean>? = null
        show(swap(), onConfirm = { s, r, p -> got = Triple(s.nodeId, r, p) })
        rule.onNodeWithText("Switch").performClick()
        assertEquals(Triple("sample", true, true), got)
    }

    /** ⭐ …and the other half of the pair is what declines it. */
    @Test
    fun keepingMyPromptDeclinesOnlyThePrompt() {
        var got: Pair<Boolean, Boolean>? = null
        show(swap(), onConfirm = { _, r, p -> got = r to p })
        rule.onNodeWithText("Keep the prompt I have").performClick()
        rule.onNodeWithText("Switch").performClick()
        assertEquals("the settings must be untouched by the prompt choice", true to false, got)
    }

    @Test
    fun keepingMySettingsDeclinesOnlyTheSettings() {
        var got: Pair<Boolean, Boolean>? = null
        show(swap(), onConfirm = { _, r, p -> got = r to p })
        rule.onNodeWithText("Keep my steps, CFG and scheduler").performClick()
        rule.onNodeWithText("Switch").performClick()
        assertEquals(false to true, got)
    }

    /** ⚠ A radio is a pair, so declining and re-choosing must come back. */
    @Test
    fun aChoiceCanBeChangedBackBeforeSwitching() {
        var got: Pair<Boolean, Boolean>? = null
        show(swap(), onConfirm = { _, r, p -> got = r to p })
        rule.onNodeWithText("Keep the prompt I have").performClick()
        rule.onNodeWithText("Use ${sdxl.label}\u2019s prompts (recommended)").performClick()
        rule.onNodeWithText("Switch").performClick()
        assertEquals(true to true, got)
    }

    /**
     * ⚠⚠ A row appears only when it has something to offer. A tick labelled
     * "use its settings" that changes nothing is what teaches people to tap
     * through a dialog without reading it.
     */
    @Test
    fun aRowWithNothingToOfferIsNotDrawn() {
        show(swap(promptNode = null, recipe = null))
        rule.onNodeWithText("Use its sampling settings").assertDoesNotExist()
        rule.onNodeWithText("Use its prompt on “prompt”").assertDoesNotExist()
        // ⚠ …but the family line still explains why it is asking at all.
        rule.onNodeWithText("Switch to ${sdxl.label}").assertIsDisplayed()
    }

    /**
     * ⭐⭐ The new prompt is SHOWN, not merely named — ticking that box
     * overwrites a sentence the user typed, and nothing else in this app does
     * that. The only honest way to ask is to put the replacement in front of
     * them.
     */
    @Test
    fun theReplacementPromptIsOnScreen() {
        show(swap())
        assertNotNull("the family default must be non-empty", sdxl.starterPrompt.ifBlank { null })
        rule.onNodeWithText(sdxl.starterPrompt, substring = true).assertIsDisplayed()
    }

    // ---- the picker that opens it ------------------------------------------

    private val choices = listOf(
        CheckpointChoice("absolutereality", "AbsoluteReality", Family.SD15),
        CheckpointChoice("qteamix", "QteaMix", Family.SD15),
        CheckpointChoice("intorealism", "intorealism", Family.SDXL),
    )

    private fun picker(onSetModel: (String, String) -> Unit) {
        rule.setContent {
            NodeInspectorBody(
                nodeId = "sample",
                node = com.abrah.nightmare.Node(
                    "sample", "sd15.sample",
                    params = mapOf("model" to "absolutereality", "width" to "512", "height" to "512"),
                ),
                type = NODE_TYPES["sd15.sample"],
                onSetParam = { _, _, _ -> },
                onDelete = {},
                installedModels = choices,
                onSetModel = onSetModel,
            )
        }
    }

    /**
     * ⭐⭐⭐ **One dropdown, grouped by family — and NO second control.**
     *
     * ⚠⚠⚠ A family chip strip beside the field was tried three times in two
     * days and failed every time, because it is a second control that can
     * disagree with the first: filtering left the field stale, committing fired
     * the switch dialog on what looks like a filter, and browsing-with-a-caption
     * was still two states. The user's call, 2026-09-16: *"lets not do chips
     * just do dropdown that shows by family"*. A heading over rows has no state
     * to keep in step.
     */
    @Test
    fun theFamilyIsAHeadingNotAControl() {
        picker { _, _ -> }
        // ⚠ Closed, the family appears ONLY in the field's label.
        rule.onNodeWithText("Checkpoint · SD 1.5").assertIsDisplayed()
        // ⚠⚠ The control: a chip would be a standalone "SDXL" before the menu
        // is ever opened, and that is exactly what must not exist.
        rule.onNodeWithText("SDXL").assertDoesNotExist()
    }

    /** ⚠⚠ Opened, every installed family is a heading over its own checkpoints. */
    @Test
    fun theOpenMenuGroupsCheckpointsUnderTheirFamily() {
        picker { _, _ -> }
        rule.onNodeWithText("Checkpoint · SD 1.5").performClick()
        rule.onNodeWithText("SDXL").assertIsDisplayed()
        rule.onNodeWithText("QteaMix").assertIsDisplayed()
        rule.onNodeWithText("intorealism").assertIsDisplayed()
    }

    /** ⭐ Picking a row is the only thing that commits, whatever family it is in. */
    @Test
    fun pickingFromAnotherFamilyCommitsIt() {
        var picked: Pair<String, String>? = null
        picker { node, id -> picked = node to id }
        rule.onNodeWithText("Checkpoint · SD 1.5").performClick()
        rule.onNodeWithText("intorealism").performClick()
        assertEquals("sample" to "intorealism", picked)
    }

    /**
     * ⚠⚠ A HEADING is not a checkpoint. Tapping one must write nothing — it
     * is `enabled = false` for exactly that reason, and a menu row that looks
     * tappable and silently is not would be its own bug.
     */
    @Test
    fun tappingAFamilyHeadingCommitsNothing() {
        var picked: Pair<String, String>? = null
        picker { node, id -> picked = node to id }
        rule.onNodeWithText("Checkpoint · SD 1.5").performClick()
        rule.onNodeWithText("SDXL").performClick()
        assertNull("a heading is not a choice", picked)
    }

    /**
     * ⚠⚠ A sampler ALWAYS has a Checkpoint field — with nothing installed it
     * shows the model the node names, marked "not installed".
     *
     * ⚠⚠⚠ It used to be gated on `installedModels.isNotEmpty()`, so an empty
     * list and "no models on this phone" were indistinguishable: both drew
     * nothing at all. That gate is what turned a view-model wiring bug into a
     * missing control with no explanation.
     */
    @Test
    fun aSamplerAlwaysHasACheckpointField() {
        rule.setContent {
            NodeInspectorBody(
                nodeId = "sample",
                node = com.abrah.nightmare.Node(
                    "sample", "sd15.sample",
                    params = mapOf("model" to "absolutereality", "width" to "512", "height" to "512"),
                ),
                type = NODE_TYPES["sd15.sample"],
                onSetParam = { _, _, _ -> },
                onDelete = {},
                installedModels = emptyList(),
            )
        }
        rule.onNodeWithText("Checkpoint").assertIsDisplayed()
        rule.onNodeWithText("absolutereality").assertIsDisplayed()
    }
}
