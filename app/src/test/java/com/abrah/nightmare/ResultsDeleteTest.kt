package com.abrah.nightmare

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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
                    onView = {},
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
}
