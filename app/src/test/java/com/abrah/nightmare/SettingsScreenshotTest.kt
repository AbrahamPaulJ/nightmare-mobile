package com.abrah.nightmare

import androidx.compose.foundation.layout.fillMaxSize
import com.abrah.nightmare.ui.NightmareTheme
import com.abrah.nightmare.ui.SettingsScreen
import com.abrah.nightmare.ui.ToolRow
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ⭐ Settings' pill tabs (2026-09-26). The Translation page is the one with
 * downloads on it: one language installed, one not.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
open class SettingsScreenshotTest {
    private val rows = mapOf(
        PromptTranslate.Source.RU to ToolRow("Russian → English", PromptTranslate.Source.RU.bytes, installed = true,
            onDisk = PromptTranslate.Source.RU.bytes),
        PromptTranslate.Source.ZH to ToolRow("Chinese → English", PromptTranslate.Source.ZH.bytes, installed = false),
    )

    private fun shoot(name: String, page: Int, downloadFolder: Boolean = false) =
        captureRoboImage(filePath = goldenPath(this, name)) {
            NightmareTheme(darkTheme = true) {
                // ⚠ The sheet supplies the content colour in the app; without a
                // Surface every heading here draws black on black.
                androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize()) {
                SettingsScreen(
                    theme = Prefs.Theme.SYSTEM,
                    onTheme = {},
                    loras = emptyList(),
                    onImportLora = {},
                    embeddings = emptyList(),
                    onImportEmbedding = {},
                    translateRows = rows,
                    initialPage = page,
                    // ⭐ The models folder in Download/, access revoked, and
                    // models left behind in app storage — every line it can draw.
                    modelsPlace = if (downloadFolder) ModelStorage.Place.DOWNLOAD else ModelStorage.Place.APP,
                    storageAccess = !downloadFolder,
                    strandedModels = if (downloadFolder) 3 to (12L shl 30) else 0 to 0L,
                )
                }
            }
        }

    @Test fun general() = shoot("settings-general", 0)
    @Test fun translation() = shoot("settings-translation", 2)
    @Test fun downloads() = shoot("settings-downloads", 3, downloadFolder = true)
}
