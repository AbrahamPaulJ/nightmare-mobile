package com.abrah.nightmare

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.abrah.nightmare.canvas.Result
import com.abrah.nightmare.canvas.ResultGroup
import com.abrah.nightmare.ui.NightmareTheme
import com.abrah.nightmare.ui.RESULT_THUMB_TAG
import com.abrah.nightmare.ui.ResultsScreen
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ⭐ GitHub #126, #130, Saved #8129/#8203 (1.6.307): star one picture, go to Favourites, tap: `IndexOutOfBoundsException:
 * Index 1 out of bounds for length 1` in the pager's keys, and the grid kept showing every picture. The page count and the
 * page keys each captured their own list; they read one state now.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ResultsFavouritesTest {

    @get:Rule
    val fastIdle = com.abrah.nightmare.FastIdle()

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun switchingBetweenAllAndFavouritesNeverReadsPastTheList() {
        val results = mutableStateListOf<Result>().apply {
            for (i in 0 until 8) add(Result(id = "r$i", savedAt = 100L - i, seed = "$i", model = null, prompt = "p$i", width = 64, height = 64))
        }
        var favouritesOnly by mutableStateOf(false)
        val thumb = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366CC.toInt()) }.asImageBitmap()
        rule.setContent {
            NightmareTheme(darkTheme = true) {
                ResultsScreen(
                    groups = results.map { ResultGroup(null, listOf(it)) },
                    results = results.toList(),
                    thumbnailFor = { thumb },
                    onOpenFlow = {},
                    onView = { _, _ -> },
                    onDelete = {},
                    onDiskBytes = 0,
                    favouritesOnly = favouritesOnly,
                    onFavouritesOnly = { favouritesOnly = it },
                )
            }
        }
        fun thumbs() = rule.onAllNodesWithTag(RESULT_THUMB_TAG)

        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        fun chip(fav: Boolean) = rule.onNodeWithText(app.getString(if (fav) R.string.results_favourites else R.string.all))
        // The last picture in view, then starred: the one favourite sits at index 0 of a one-item list.
        thumbs()[7].performClick()
        rule.waitForIdle()
        rule.runOnUiThread { results[7] = results[7].copy(favourite = true) }
        repeat(3) {
            chip(true).performClick()
            rule.waitForIdle()
            check(favouritesOnly)
            thumbs().assertCountEquals(1)
            thumbs()[0].performClick()
            rule.waitForIdle()
            chip(false).performClick()
            rule.waitForIdle()
            thumbs().assertCountEquals(8)
            thumbs()[5].performClick()
            rule.waitForIdle()
        }
    }

    /**
     * ⭐ GitHub #137: a favourite opened full screen swiped on into pictures that were not favourites, as the viewer
     * paged through every kept picture. It pages through what the grid shows now.
     */
    @Test
    fun aFavouriteOpensAmongFavouritesOnly() {
        val results = (0 until 8).map { i ->
            Result(id = "r$i", savedAt = 100L - i, seed = "$i", model = null, prompt = "p$i", width = 64, height = 64, favourite = i % 3 == 0)
        }
        val thumb = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).asImageBitmap()
        var swipesThrough: List<String>? = null
        rule.setContent {
            NightmareTheme(darkTheme = true) {
                ResultsScreen(
                    groups = results.map { ResultGroup(null, listOf(it)) },
                    results = results,
                    thumbnailFor = { thumb },
                    onOpenFlow = {},
                    onView = { _, shown -> swipesThrough = shown.map { it.id } },
                    onDelete = {},
                    onDiskBytes = 0,
                    favouritesOnly = true,
                )
            }
        }
        rule.waitForIdle()
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        // Folded, a tap opens the picture full screen (open, it shows in the frame instead).
        rule.onNodeWithContentDescription(app.getString(R.string.results_cd_fold_preview)).performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag(RESULT_THUMB_TAG)[1].performClick()
        rule.waitForIdle()
        assertEquals(listOf("r0", "r3", "r6"), swipesThrough)
    }
}
