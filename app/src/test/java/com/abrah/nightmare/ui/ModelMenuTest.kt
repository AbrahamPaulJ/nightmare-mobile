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

    private fun openImport(s: com.abrah.nightmare.ModelSpec, fromLocalDream: Boolean = false) {
        val row = ModelRow(spec = s, build = null, installed = true, selected = false, fromLocalDream = fromLocalDream)
        rule.setContent {
            NightmareTheme { ModelCardV2(row, busy = false, onInstall = {}, onCancel = {}, onDelete = {}, onUse = {}) }
        }
        rule.onNodeWithContentDescription(app.getString(R.string.mb_more)).performClick()
    }

    /**
     * ⭐ Saved #7783: an SDXL import's ⋮ showed only Delete. Rename asked for `spec.home`, which only Local Dream's
     * models carry, so it was hidden on every import of ours from 1.6.163 on.
     */
    @Test
    fun anImportOfOursCanBeRenamed() {
        val sdxl = ModelCatalog.builtIn.first { it.family == com.abrah.nightmare.Family.SDXL }
        openImport(sdxl.copy(id = "anthroblendPlush_v20", isCustom = true, files = emptyList()))
        rule.onNodeWithText(app.getString(R.string.flows_rename)).assertIsEnabled()
    }

    @Test
    fun localDreamsImportIsNotRenamedHere() {
        // ⚠ The control: its name is Local Dream's files.
        val sdxl = ModelCatalog.builtIn.first { it.family == com.abrah.nightmare.Family.SDXL }
        openImport(sdxl.copy(id = "ld_import", isCustom = true, files = emptyList()), fromLocalDream = true)
        rule.onNodeWithText(app.getString(R.string.flows_rename)).assertDoesNotExist()
    }

    /** ⭐ The sibling: a MOSS import's 8-step speed-up asked for the same `spec.home`, so it was never offered. */
    @Test
    fun aMossImportOffersTheSpeedUpWhenItsDonorHasTheLora() {
        val root = com.abrah.nightmare.ModelCatalog.root(app)
        val dir = java.io.File(root, "moody").apply { mkdirs() }
        java.io.File(dir, com.abrah.nightmare.CustomModels.MOSS_MARK).writeText("")
        java.io.File(dir, com.abrah.nightmare.CustomModels.PARTS_FROM_FILE).writeText("moss_image_turbo")
        java.io.File(root, "moss_image_turbo").mkdirs()
        java.io.File(java.io.File(root, "moss_image_turbo"), ModelCatalog.MOSS_LORA).writeText("x")
        val moss = ModelCatalog.builtIn.first { it.family == com.abrah.nightmare.Family.MOSS }
        openImport(moss.copy(id = "moody", isCustom = true, files = emptyList(), ownLora = null))
        rule.onNodeWithText(app.getString(R.string.mb_moss_speed)).assertExists()
    }
}
