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
                    // ⭐ Tag autocomplete with the Danbooru list installed, no translations yet.
                    tags = TagDictionary.State(true, "danbooru.csv", 140_782, null, 0),
                    initialPage = page,
                    // ⭐ The models folder in Download/, access revoked, and
                    // models left behind in app storage — every line it can draw.
                    modelsPlace = if (downloadFolder) ModelStorage.Place.DOWNLOAD else ModelStorage.Place.APP,
                    storageAccess = !downloadFolder,
                    strandedModels = if (downloadFolder) 3 to (12L shl 30) else 0 to 0L,
                    // ⭐ The CivitAI section (LoRA browser key + mature switch), no key yet.
                    civitaiKey = "",
                    // ⭐ The local API, on: its address and token (`docs/AGENT-API.md` §3).
                    api = com.abrah.nightmare.ui.ApiState(true, "3f9c2a7e1d4b8c6a0e5f7d9b2c4a6e8f1b3d5c7a9e0f2d4b", listOf("192.168.0.4", "100.101.102.103")),
                )
                }
            }
        }

    // ⭐ The five groups (2026-10-10). ⚠ No Performance tab here: no memory switches are passed and
    // the battery is unrestricted, so it is hidden — Advanced is page 3.
    @Test fun appearance() = shoot("settings-appearance", 0)
    @Test fun prompts() = shoot("settings-prompts", 1)
    @Test fun models() = shoot("settings-models", 2, downloadFolder = true)
    @Test fun advanced() = shoot("settings-advanced", 3)
}
