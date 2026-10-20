package com.abrah.nightmare.canvas

import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.abrah.nightmare.NODE_TYPES
import com.abrah.nightmare.Node
import com.abrah.nightmare.portrait.FaceEditNode
import com.abrah.nightmare.portrait.LivePortrait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ⭐ Face edit's viewer ([FaceEditControls] → [FaceEditSheet]): DRAWN from the node page, and Done writes every slider
 * in ONE change. ⚠ A tree test, not a golden: the sheet is its own window (`docs/UI.md` §8.9).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class FaceEditSheetTest {

    @get:Rule
    val fastIdle = com.abrah.nightmare.FastIdle()

    @get:Rule
    val rule = createComposeRule()

    private var written: Map<String, String>? = null

    private fun show(withPicture: Boolean) {
        val picture = android.graphics.Bitmap.createBitmap(64, 96, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
        rule.setContent {
            NodeInspectorBody(
                nodeId = "face",
                node = Node("face", FaceEditNode.name, params = mapOf("mouth_smile" to "0.6")),
                type = NODE_TYPES[FaceEditNode.name],
                onSetParam = { _, _, _ -> },
                onSetParams = { _, v -> written = v },
                onDelete = {},
                faceSource = if (withPicture) picture else null,
            )
        }
    }

    @Test
    fun theViewerOpensWithItsThreeGroupsAndDoneWritesEverySlider() {
        show(withPicture = true)
        rule.onNodeWithText("Edit face").performClick()
        for (g in listOf("Eyes", "Mouth", "Head")) rule.onNodeWithText(g).performScrollTo().assertIsDisplayed()
        // ⚠ The click ACTION, not a tap at coordinates: the sheet is a window Robolectric lays out off the root.
        rule.onNodeWithText("Done").performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitForIdle()
        val w = checkNotNull(written) { "Done wrote nothing" }
        assertTrue(LivePortrait.SLIDERS.all { it in w })
        assertEquals("0.6", w["mouth_smile"])
        assertEquals("true", w[FaceEditNode.LARGEST_ONLY])
    }

    /** ⚠ No picture yet: a line saying what to do, no button that opens onto nothing. */
    @Test
    fun withNoPictureItSaysWhatToDo() {
        show(withPicture = false)
        rule.onNodeWithText("Pick or render the picture first, then edit its face here with a live preview.").assertIsDisplayed()
        assertNull(written)
    }
}
