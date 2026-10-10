package com.abrah.nightmare

import androidx.compose.foundation.layout.fillMaxSize
import com.abrah.nightmare.ui.LoadState
import com.abrah.nightmare.ui.NightmareTheme
import com.abrah.nightmare.ui.ShellDrawer
import com.abrah.nightmare.ui.ShellLoad
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ⭐ The sidebar (`ui/ShellDrawer.kt`, the user's mock 2026-10-09): an unsaved flow on an idle
 * SDXL Swap model, and a saved one while a checkpoint is loaded.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ShellDrawerScreenshotTest {

    private fun shoot(name: String, flow: String, dirty: Boolean, load: ShellLoad?) =
        captureRoboImage(filePath = goldenPath(this, name)) {
            NightmareTheme(darkTheme = true) {
                androidx.compose.material3.Surface(
                    androidx.compose.ui.Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                ) {
                    ShellDrawer(
                        open = true, onClose = {}, version = "0.0.0",
                        onModels = {}, onFlows = {}, onResults = {},
                        flowName = flow, flowDirty = dirty, onSaveFlow = {},
                        load = load, onSettings = {}, onAbout = {},
                    )
                }
            }
        }

    @Test
    fun unsavedAndIdle() = shoot(
        "sidebar", "unsaved flow", dirty = true,
        ShellLoad("Illustrious-XL-v1.0_npuforge_swap", LoadState.IDLE, "5.5", "11.7"),
    )

    @Test
    fun savedAndLoaded() = shoot(
        "sidebar-loaded", "Portrait with LoRA", dirty = false,
        ShellLoad("AbsoluteReality", LoadState.LOADED, "3.9", "11.7"),
    )
}
