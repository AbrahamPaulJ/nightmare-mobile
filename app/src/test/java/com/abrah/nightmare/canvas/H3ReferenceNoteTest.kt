package com.abrah.nightmare.canvas

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.abrah.nightmare.NODE_TYPES
import com.abrah.nightmare.Node
import com.abrah.nightmare.Source
import com.abrah.nightmare.npu.H3Node
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ⭐ The MiniMax H3 node says what a wired reference costs (GitHub #82, the user 2026-11-04): MiniMax H3 Reference
 * runs a 2 GB LoRA on every clip, 1.5 to 2.5 times slower. Shown in the node's sheet with a reference, never without.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class H3ReferenceNoteTest {

    @get:Rule
    val fastIdle = com.abrah.nightmare.FastIdle()

    @get:Rule
    val rule = createComposeRule()

    private val note = "Reference wired: this clip runs on MiniMax H3 Reference"

    private fun show(node: Node) = rule.setContent {
        NodeInspectorBody(nodeId = node.id, node = node, type = NODE_TYPES[H3Node.name], onSetParam = { _, _, _ -> }, onDelete = {}, onReset = {})
    }

    @Test
    fun aWiredReferenceSaysItIsSlower() {
        show(Node("video", H3Node.name, inputs = mapOf(H3Node.REFERENCE to Source("img"))))
        rule.onNodeWithText(note, substring = true).performScrollTo()
    }

    @Test
    fun noReferenceNoNote() {
        show(Node("video", H3Node.name, inputs = mapOf("image" to Source("img"))))
        assertEquals(0, rule.onAllNodesWithText(note, substring = true).fetchSemanticsNodes().size)
    }
}
