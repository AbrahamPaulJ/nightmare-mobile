package com.abrah.nightmare.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ⭐⭐⭐ **Delete asks before it throws away 8.6 GB.**
 *
 * ⚠⚠ The video models shipped with a Delete that fired on the tap, while a
 * checkpoint asked first for a ~1 GB re-download and an upscaler asked for
 * 24 MB. It was the biggest delete in the app and the only one that did not
 * ask. Reported from the phone, 2026-09-13.
 *
 * ⚠⚠⚠ **This drives the BUTTON, not the flag.** The dialog is local state
 * inside `ModelsScreen`, so a test that flipped that state itself would pass
 * with the button wired straight to `onDeleteVideo` — which is exactly the bug.
 * The screenshot tests cannot do this: `shoot` captures a composition and has
 * no way to click.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class VideoDeleteConfirmTest {

    @get:Rule
    val rule = createComposeRule()

    /** ⚠ Installed and complete, which is the only state that offers Delete. */
    private val installed = VideoRow(
        installedBytes = 8_596_825_402L,
        totalBytes = 8_596_825_402L,
        missing = emptyList(),
        weightsMissing = emptyList(),
        supported = true,
    )

    private var deletes = 0

    private fun screen() {
        deletes = 0
        rule.setContent {
            NightmareTheme(darkTheme = true) {
                ModelsScreen(
                    // ⚠ No checkpoints and no upscalers, so the video tab is
                    // page 0 and is what renders.
                    rows = emptyList(), busy = false, error = null,
                    onInstall = {}, onCancel = {}, onDelete = {}, onSelect = {},
                    video = installed,
                    onDeleteVideo = { deletes++ },
                )
            }
        }
    }

    @Test
    fun tappingDeleteAsksRatherThanDeleting() {
        screen()
        rule.onNodeWithText("Delete").performClick()
        // ⚠⚠ The assertion that matters: the tap alone must destroy nothing.
        assertEquals("Delete must ask, not delete", 0, deletes)
        // …and it must say what the tap would cost, or the dialog is ceremony.
        rule.onNodeWithText("Delete the video models?").assertExists()
        rule.onNodeWithText("8198 MB download", substring = true).assertExists()
    }

    @Test
    fun cancellingKeepsIt() {
        screen()
        rule.onNodeWithText("Delete").performClick()
        // ⚠ `Cancel`, as every confirm in the app says since the design review
        // (`ConfirmDelete`) — this one alone said `Keep`.
        rule.onNodeWithText("Cancel").performClick()
        assertEquals("Cancel must not delete", 0, deletes)
    }

    @Test
    fun confirmingActuallyDeletes() {
        screen()
        rule.onNodeWithText("Delete").performClick()
        // ⚠ The dialog's own Delete, which is the second node with that text.
        rule.onAllNodesWithText("Delete")[1].performClick()
        assertEquals(1, deletes)
    }
}
