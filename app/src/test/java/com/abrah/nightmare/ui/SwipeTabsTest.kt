package com.abrah.nightmare.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ⭐ A page chosen from outside (Run turning the Default view to the output) must LAND there, with the
 * caller wired the way the node pages are: every reported page becomes the next [SwipeTabs] initialPage.
 * ⚠ The user, 2026-10-20: it stopped half-swiped on the node before the output.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SwipeTabsTest {

    @get:Rule
    val fastIdle = com.abrah.nightmare.FastIdle()

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun aTurnFromOutsideLandsOnItsPage() {
        var here by mutableIntStateOf(0)
        val reported = mutableListOf<Int>()
        rule.setContent {
            Box(Modifier.size(400.dp, 600.dp)) {
                SwipeTabs(
                    labels = List(5) { "node $it" },
                    fillHeight = true,
                    initialPage = here,
                    onPage = { reported += it; here = it },
                ) { i -> Text("page $i") }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { here = 4 }
        rule.waitForIdle()
        rule.onNodeWithText("page 4").assertIsDisplayed()
        assertEquals(4, here)
        assertEquals(listOf(0, 4), reported)
    }
}
