package com.abrah.nightmare.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.abrah.nightmare.ModelCatalog
import com.abrah.nightmare.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ⭐ GitHub #56: a model in Local Dream's folder had no ⋮ at all, and read as an import that cannot be deleted.
 * Its ⋮ now shows Delete greyed with why, as on the model in use; an import of ours still deletes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ModelMenuTest {

    @get:Rule
    val fastIdle = com.abrah.nightmare.FastIdle()

    @get:Rule
    val rule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val spec = ModelCatalog.builtIn.first { it.builds.isNotEmpty() }

    private fun open(fromLocalDream: Boolean) {
        val row = ModelRow(spec = spec, build = spec.builds.first(), installed = true, selected = false, fromLocalDream = fromLocalDream)
        rule.setContent {
            NightmareTheme { ModelCardV2(row, busy = false, onInstall = {}, onCancel = {}, onDelete = {}, onUse = {}) }
        }
        rule.onNodeWithContentDescription(app.getString(R.string.mb_more)).performClick()
    }

    @Test
    fun localDreamsModelSaysWhyItCannotBeDeleted() {
        open(fromLocalDream = true)
        rule.onNodeWithText(app.getString(R.string.delete) + ": " + app.getString(R.string.mb_kept_by_local_dream))
            .assertIsNotEnabled()
    }

    @Test
    fun ourOwnStillDeletes() {
        // ⚠ The control: the same card without Local Dream's folder.
        open(fromLocalDream = false)
        rule.onNodeWithText(app.getString(R.string.delete)).assertIsEnabled()
    }
}
