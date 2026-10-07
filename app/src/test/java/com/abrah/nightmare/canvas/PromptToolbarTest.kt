package com.abrah.nightmare.canvas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import com.abrah.nightmare.NODE_TYPES
import com.abrah.nightmare.Node
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ⭐ The prompt box's tag toolbar (`PromptTags`, local-dream's editor) — shown only
 * while the box is being edited, and its buttons reach the node's param.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class PromptToolbarTest {

    @get:Rule
    val rule = createComposeRule()

    private var written: String? = null

    private fun show(prompt: String) {
        rule.setContent {
            var node by remember { mutableStateOf(Node("prompt", "core.prompt", params = mapOf("prompt" to prompt, "negative" to "blurry"))) }
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
            )
        }
    }

    private fun focusPrompt(text: String) =
        rule.onNode(hasSetTextAction() and hasText(text)).performSemanticsAction(SemanticsActions.RequestFocus)

    private fun toolbarCount() =
        rule.onAllNodes(hasContentDescription("Heavier: weight +0.1")).fetchSemanticsNodes().size

    @Test
    fun theToolbarShowsOnlyWhileEditing() {
        show("1girl, hat")
        assertEquals(0, toolbarCount())
        focusPrompt("1girl, hat")
        rule.onNodeWithContentDescription("Heavier: weight +0.1").assertIsDisplayed()
        assertEquals("one box focused, one toolbar", 1, toolbarCount())
    }

    /** The caret starts at the end, so the buttons act on the LAST tag. */
    @Test
    fun heavierLighterDeleteAndUndo() {
        show("1girl, hat")
        focusPrompt("1girl, hat")
        rule.onNodeWithContentDescription("Heavier: weight +0.1").performClick()
        assertEquals("1girl, (hat:1.1)", written)
        rule.onNodeWithContentDescription("Heavier: weight +0.1").performClick()
        assertEquals("1girl, (hat:1.2)", written)
        rule.onNodeWithContentDescription("Lighter: weight −0.1").performClick()
        assertEquals("1girl, (hat:1.1)", written)
        rule.onNodeWithContentDescription("Undo").performClick()
        assertEquals("1girl, (hat:1.2)", written)
        rule.onNodeWithContentDescription("Redo").performClick()
        assertEquals("1girl, (hat:1.1)", written)
        rule.onNodeWithContentDescription("Delete this tag").performClick()
        assertEquals("1girl", written)
    }

    @Test
    fun addOpensASlot() {
        show("1girl, hat")
        focusPrompt("1girl, hat")
        rule.onNodeWithContentDescription("Add a tag after it").performClick()
        assertEquals("1girl, hat, ", written)
    }
}
