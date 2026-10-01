package com.abrah.nightmare.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ⭐⭐ The share icon on a FAILED notice opens the report — and a refusal has
 * none. A dialog is its own window, so this asserts on the tree, not a golden
 * (`docs/UI.md` §8.9).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ErrorDetailsTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theIconOpensTheReportForThatMessage() {
        var asked: String? = null
        rule.setContent {
            CompositionLocalProvider(LocalErrorReport provides { m -> asked = m; "REPORT BODY for $m" }) {
                ErrorNotice("generate failed", reportable = true)
            }
        }
        rule.onNodeWithContentDescription("Error details").performClick()
        assertEquals("generate failed", asked)
        rule.onNodeWithText("REPORT BODY for generate failed").assertIsDisplayed()
        rule.onNodeWithText("Share").assertIsDisplayed()
        rule.onNodeWithText("Copy").assertIsDisplayed()
    }

    /** ⚠ "That would make a loop" is a rule being learned, not a bug to report. */
    @Test
    fun aRefusalHasNoIcon() {
        rule.setContent {
            CompositionLocalProvider(LocalErrorReport provides { "x" }) {
                ErrorNotice("that would make a loop")
            }
        }
        rule.onNodeWithContentDescription("Error details").assertDoesNotExist()
    }
}
