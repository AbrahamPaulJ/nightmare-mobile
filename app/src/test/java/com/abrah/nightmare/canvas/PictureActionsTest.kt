package com.abrah.nightmare.canvas

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.abrah.nightmare.ui.NightmareTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ⭐⭐⭐ **The bin on a picture asks — on EVERY surface that draws one.**
 *
 * ⚠⚠ The inspector's bin cleared a render on the tap while the fullscreen
 * viewer's asked first; the design review found it on 2026-09-15. Both now draw
 * [PictureActions], so this drives that one composable's BUTTON, not a flag —
 * the shape `docs/UI.md` §7.5 requires, because a test that set the dialog's own
 * state would pass with the button wired straight to the delete.
 *
 * ⚠ And it pins the ORDER — bin, save, share, star — which four surfaces used to
 * draw four different ways (`docs/UI.md` §8.3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class PictureActionsTest {

    @get:Rule
    val rule = createComposeRule()

    private var deletes = 0

    private fun row() {
        deletes = 0
        rule.setContent {
            NightmareTheme(darkTheme = true) {
                Row {
                    PictureActions(
                        tint = Color.White,
                        deleteTint = Color.Red,
                        isClip = false,
                        onDelete = { deletes++ },
                        onKeep = {},
                        onDownload = {},
                        onShare = {},
                        onStar = {},
                        kept = false,
                        favourite = false,
                        starKeptTint = Color.Yellow,
                        starIdleTint = Color.Gray,
                    )
                }
            }
        }
    }

    @Test
    fun theBinAsksRatherThanDeleting() {
        row()
        rule.onNodeWithContentDescription("clear this picture").performClick()
        assertEquals("the bin must ask, not delete", 0, deletes)
        rule.onNodeWithText("Clear this picture?").assertExists()
        // ⚠ The body says whether a copy survives — the cost, not ceremony.
        rule.onNodeWithText("NOT been saved", substring = true).assertExists()
    }

    @Test
    fun cancelKeepsIt() {
        row()
        rule.onNodeWithContentDescription("clear this picture").performClick()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(0, deletes)
    }

    @Test
    fun confirmingDeletes() {
        row()
        rule.onNodeWithContentDescription("clear this picture").performClick()
        rule.onNodeWithText("Clear").performClick()
        assertEquals(1, deletes)
    }

    @Test
    fun theOrderIsBinSaveShareStar() {
        row()
        val labels = rule.onAllNodes(hasContentDescription("", substring = true))
            .fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() }
        assertEquals(
            listOf(
                "clear this picture",
                // ⭐ The disk KEEPS and the arrow DOWNLOADS since 2026-09-15 —
                // the two glyphs swapped jobs because the floppy was doing an
                // export. [PictureActions] has the table.
                "keep this picture in Results",
                "save this picture to the gallery",
                "share this picture",
                "keep and favourite",
            ),
            labels,
        )
    }
}
