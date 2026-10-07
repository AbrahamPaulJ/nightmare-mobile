package com.abrah.nightmare.canvas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.abrah.nightmare.NODE_TYPES
import com.abrah.nightmare.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ⭐⭐ The prompt's describe button (`docs/FLORENCE.md`) — that its dialog is
 * DRAWN and what it writes. ⚠ A tree test, not a golden: an `AlertDialog` is its
 * own window and a screenshot of the sheet cannot see it (`docs/UI.md` §8.9).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class DescribeDialogTest {

    @get:Rule
    val rule = createComposeRule()

    /** What the prompt holds after the test's taps. */
    private var written: String? = null

    /** What the fake model was asked for — (picture, mode). */
    private var asked: Pair<String, com.abrah.nightmare.DescribeMode>? = null

    /** The fake model's answer, held until [answer] — null = still describing. */
    private var pending: ((String?) -> Unit)? = null

    private fun show(
        prompt: String,
        default: com.abrah.nightmare.DescribeMode = com.abrah.nightmare.DescribeMode.DETAILED,
        ready: Boolean = true,
    ) {
        rule.setContent {
            var node by remember { mutableStateOf(Node("prompt", "core.prompt", params = mapOf("prompt" to prompt, "negative" to ""))) }
            NodeInspectorBody(
                nodeId = "prompt",
                node = node,
                type = NODE_TYPES["core.prompt"],
                onSetParam = { _, name, value ->
                    if (name == "prompt") written = value
                    node = node.copy(params = node.params + (name to value))
                },
                onDelete = {},
                onReset = {},
                onDescribe = { picture, mode, done -> asked = picture to mode; pending = done },
                describePictures = listOf(DescribePicture("photo", "img-1", null)),
                describeDefault = default,
                describeReady = { ready },
            )
        }
    }

    private fun answer(caption: String?) {
        rule.runOnIdle { pending!!(caption) }
        rule.waitForIdle()
    }

    @Test
    fun theButtonOpensAPickerOfCanvasPicturesAndThePhone() {
        show("")
        rule.onNodeWithContentDescription("Describe a picture").performClick()
        rule.onNodeWithText("photo").assertIsDisplayed()
        rule.onNodeWithText("From your phone").assertIsDisplayed()
        rule.onNodeWithText("Short").assertIsDisplayed()
        rule.onNodeWithText("Tags").assertIsDisplayed()
    }

    /** ⭐ An empty prompt has nothing to protect: the caption is written, no question. */
    @Test
    fun anEmptyPromptIsFilledStraightAway() {
        show("")
        rule.onNodeWithContentDescription("Describe a picture").performClick()
        rule.onNodeWithText("photo").performClick()
        assertEquals("img-1" to com.abrah.nightmare.DescribeMode.DETAILED, asked)
        answer("A green car parked in front of a building.")
        assertEquals("A green car parked in front of a building.", written)
    }

    /** ⭐⭐ Text already there is ASKED about, with the caption shown — the user's call. */
    @Test
    fun aPromptWithTextAsksAndAddsAfterIt() {
        show("masterpiece, best quality")
        rule.onNodeWithContentDescription("Describe a picture").performClick()
        rule.onNodeWithText("photo").performClick()
        answer("A green car.")
        assertNull("nothing is written before the answer", written)
        rule.onNodeWithText("A green car.").assertIsDisplayed()
        rule.onNodeWithText("Add after your prompt").performClick()
        assertEquals("masterpiece, best quality, A green car.", written)
    }

    @Test
    fun replaceTakesTheWholePrompt() {
        show("masterpiece, best quality")
        rule.onNodeWithContentDescription("Describe a picture").performClick()
        rule.onNodeWithText("photo").performClick()
        answer("A green car.")
        rule.onNodeWithText("Replace your prompt").performClick()
        assertEquals("A green car.", written)
    }

    @Test
    fun theChosenChipIsWhatTheModelIsAskedFor() {
        show("")
        rule.onNodeWithContentDescription("Describe a picture").performClick()
        rule.onNodeWithText("Short").performClick()
        rule.onNodeWithText("photo").performClick()
        assertEquals("img-1" to com.abrah.nightmare.DescribeMode.SHORT, asked)
    }

    /** ⭐ An anime checkpoint opens the dialog on Tags — the user's call, 2026-10-08. */
    @Test
    fun anAnimeCheckpointOpensOnTags() {
        show("", default = com.abrah.nightmare.DescribeMode.TAGS)
        rule.onNodeWithContentDescription("Describe a picture").performClick()
        rule.onNodeWithText("Danbooru tags, for anime models like Illustrious, Anima and Pony.").assertIsDisplayed()
        rule.onNodeWithText("photo").performClick()
        assertEquals("img-1" to com.abrah.nightmare.DescribeMode.TAGS, asked)
    }

    /** ⚠ Cancelled while describing: the caption that lands later must not be written. */
    @Test
    fun cancellingWhileDescribingWritesNothing() {
        show("")
        rule.onNodeWithContentDescription("Describe a picture").performClick()
        rule.onNodeWithText("photo").performClick()
        rule.onNodeWithText("Cancel").performClick()
        answer("A green car.")
        assertNull(written)
    }

    /** ⚠ A failure (toasted by the VM) closes the dialog and writes nothing. */
    @Test
    fun aFailedDescribeWritesNothing() {
        show("")
        rule.onNodeWithContentDescription("Describe a picture").performClick()
        rule.onNodeWithText("photo").performClick()
        answer(null)
        assertNull(written)
    }

    /** ⚠ Positive prompt only: the negative has no describe button. */
    @Test
    fun onlyThePositivePromptHasTheButton() {
        show("")
        rule.onNodeWithContentDescription("Describe a picture").assertIsDisplayed()
        assertEquals(
            1,
            rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Describe a picture"))
                .fetchSemanticsNodes().size,
        )
    }

    /**
     * ⚠⚠ The phone's report, 2026-10-08: with the model missing, "Describing…" sat
     * over the download popup. The dialog must step aside — and come back with the
     * caption once the download lands.
     */
    @Test
    fun aMissingModelShowsNoDescribingSpinner() {
        show("masterpiece", ready = false)
        rule.onNodeWithContentDescription("Describe a picture").performClick()
        rule.onNodeWithText("photo").performClick()
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasText("Describing", substring = true)).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasText("From your phone")).fetchSemanticsNodes().size)
        answer("A green car.")
        rule.onNodeWithText("Add after your prompt").performClick()
        assertEquals("masterpiece, A green car.", written)
    }

    /** ⚠ …and a download the person declined closes nothing that is still open. */
    @Test
    fun aDeclinedDownloadWritesNothing() {
        show("", ready = false)
        rule.onNodeWithContentDescription("Describe a picture").performClick()
        rule.onNodeWithText("photo").performClick()
        answer(null)
        assertNull(written)
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasText("From your phone")).fetchSemanticsNodes().size)
    }
}
