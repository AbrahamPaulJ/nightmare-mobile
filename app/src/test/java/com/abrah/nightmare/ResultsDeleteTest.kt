package com.abrah.nightmare

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.abrah.nightmare.canvas.Result
import com.abrah.nightmare.canvas.ResultGroup
import com.abrah.nightmare.ui.NightmareTheme
import com.abrah.nightmare.ui.RESULT_THUMB_TAG
import com.abrah.nightmare.ui.ResultsScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ⭐ Delete the picture in the frame, then tap another thumb: the frame follows. Reported
 * 2026-10-11 from 1.6.131 — after a delete the All grid ignored every tap until the user
 * went to Favourites and back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ResultsDeleteTest {

    @get:Rule
    val fastIdle = com.abrah.nightmare.FastIdle()

    @get:Rule
    val rule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun afterDeletingTheShownPictureATapStillMovesTheFrame() {
        val results = mutableStateListOf<Result>().apply {
            for (i in 0 until 6) add(Result(id = "r$i", savedAt = 100L - i, seed = "$i", model = null, prompt = "p$i", width = 64, height = 64))
        }
        val thumb = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366CC.toInt()) }.asImageBitmap()
        rule.setContent {
            NightmareTheme(darkTheme = true) {
                ResultsScreen(
                    groups = results.map { ResultGroup(null, listOf(it)) },
                    results = results.toList(),
                    thumbnailFor = { thumb },
                    onOpenFlow = {},
                    onView = { _, _ -> },
                    onDelete = { r -> results.removeAll { it.id == r.id } },
                    onDiskBytes = 0,
                )
            }
        }
        fun thumbs() = rule.onAllNodesWithTag(RESULT_THUMB_TAG)

        thumbs()[3].performClick()
        rule.waitForIdle()
        thumbs()[3].assertIsSelected()

        rule.onNodeWithContentDescription(app.getString(R.string.cd_delete_result)).performClick()
        rule.onNodeWithText(app.getString(R.string.delete)).performClick()
        rule.waitForIdle()
        check(results.none { it.id == "r3" })

        thumbs()[1].performClick()
        rule.waitForIdle()
        thumbs()[1].assertIsSelected()
        thumbs()[4].performClick()
        rule.waitForIdle()
        thumbs()[4].assertIsSelected()
    }

    /**
     * ⭐ GitHub #119: "when an image has finished generating while in the gallery page, the image above the tools would
     * flash rapidly between the current output and the previous output". The frame stays on the picture being looked
     * at, every frame, while a new one lands in front of it (1.6.306 put r1 in the frame for a frame).
     */
    @Test
    fun aNewPictureLandingDoesNotMoveTheFrame() {
        val results = mutableStateListOf<Result>().apply {
            for (i in 0 until 6) add(Result(id = "r$i", savedAt = 100L - i, seed = "$i", model = null, prompt = "p$i", width = 64, height = 64, favourite = i % 2 == 0))
        }
        val thumb = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).asImageBitmap()
        var favs by androidx.compose.runtime.mutableStateOf(false)
        rule.setContent {
            NightmareTheme(darkTheme = true) {
                ResultsScreen(
                    groups = results.map { ResultGroup(null, listOf(it)) },
                    results = results.toList(),
                    favouritesOnly = favs,
                    onFavouritesOnly = { favs = it },
                    thumbnailFor = { thumb },
                    onOpenFlow = {},
                    onView = { _, _ -> },
                    onDelete = {},
                    onDiskBytes = 0,
                )
            }
        }
        fun thumbs() = rule.onAllNodesWithTag(RESULT_THUMB_TAG)
        // ⚠ Placed thumbs in grid order: a lazy grid keeps the thumbs a list change dropped, unplaced, for reuse.
        fun placed() = thumbs().fetchSemanticsNodes().filter { it.layoutInfo.isPlaced && !it.layoutInfo.isDeactivated }
            .sortedWith(compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left }))
        fun selected(): List<Int> = placed().withIndex().filter { (_, n) ->
            n.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Selected) { false }
        }.map { it.index }

        thumbs()[2].performClick()
        rule.waitForIdle()
        check(selected() == listOf(2)) { "tapped 2, selected ${selected()}" }

        // A render lands at the front: r2 is now the 4th thumb, and no other picture may be in the frame on the way.
        // ⚠ By id: a frame drawn before the recomposition still shows the old grid, where r2 is the 3rd.
        val before = results.map { it.id }
        rule.mainClock.autoAdvance = false
        results.add(0, Result(id = "new", savedAt = 200L, seed = "9", model = null, prompt = "new", width = 64, height = 64))
        val after = results.map { it.id }
        fun inFrame(): List<String> {
            val ids = if (placed().size == before.size) before else after
            return selected().map { ids[it] }
        }
        val seen = mutableListOf<List<String>>()
        repeat(30) { rule.mainClock.advanceTimeByFrame(); seen += inFrame() }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        check(seen.all { it == listOf("r2") }) { "the frame moved while a picture landed: $seen" }

        // ⚠ All / Favourites (its other half) is not asserted here: after the switch Robolectric's semantics kept the
        // old grid's 7 thumbs although the composition held 3. A traced run showed the new pager opening on r2.
    }

    /**
     * ⭐ GitHub #25: the frame took the screen and three thumbnails showed. Open, six or more sit
     * wholly inside the window; folded by the handle, a tap opens the picture full screen.
     */
    @Test
    fun theGridShowsSixAndTheFrameFolds() {
        val results = (0 until 16).map { i ->
            Result(id = "r$i", savedAt = 100L - i, seed = "$i", model = null, prompt = "p$i", width = 64, height = 64)
        }
        val thumb = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).asImageBitmap()
        val viewed = mutableListOf<String>()
        rule.setContent {
            NightmareTheme(darkTheme = true) {
                ResultsScreen(
                    groups = results.map { ResultGroup(null, listOf(it)) },
                    results = results,
                    thumbnailFor = { thumb },
                    onOpenFlow = {},
                    onView = { r, _ -> viewed += r.id },
                    onDelete = {},
                    onDiskBytes = 0,
                )
            }
        }
        rule.waitForIdle()
        val window = rule.onRoot().fetchSemanticsNode().boundsInRoot
        fun wholeThumbs() = rule.onAllNodesWithTag(RESULT_THUMB_TAG).fetchSemanticsNodes()
            .count { it.boundsInRoot.bottom <= window.bottom && it.boundsInRoot.height > 0f }
        check(wholeThumbs() >= 6) { "only ${wholeThumbs()} whole thumbnails under the open frame" }

        rule.onNodeWithContentDescription(app.getString(R.string.results_cd_fold_preview)).performClick()
        rule.waitForIdle()
        check(wholeThumbs() > 8) { "folding the frame gave the grid no room: ${wholeThumbs()}" }
        rule.onAllNodesWithTag(RESULT_THUMB_TAG)[2].performClick()
        rule.waitForIdle()
        check(viewed == listOf("r2")) { "a folded tap should open full screen, got $viewed" }
    }
}
