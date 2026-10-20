package com.abrah.nightmare.canvas

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.abrah.nightmare.goldenPath
import com.abrah.nightmare.ui.NightmareTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** ⭐ A DiT edit's References row (2026-10-21): two pictures numbered 2 and 3, and the + tile; then full, + dimmed. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ReferencesRowScreenshotTest {

    private fun pic(c: Int) = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        .apply { eraseColor(c) }.asImageBitmap()

    private fun shot(name: String, n: Int) = captureRoboImage(filePath = goldenPath(this, name)) {
        NightmareTheme(darkTheme = true) {
            androidx.compose.material3.Surface {
                androidx.compose.foundation.layout.Box(Modifier.padding(16.dp)) {
                    ReferencesRow(
                        slots = List(n) { i -> ReferenceSlot(listOf("reference", "reference_3", "reference_4")[i], "r$i", pic(listOf(0xFF8E5BD8, 0xFF3FA796, 0xFFD8A23F)[i].toInt())) },
                        roomless = null, onAdd = {}, onReplace = { _, _ -> }, onRemove = {},
                    )
                }
            }
        }
    }

    @Test fun twoReferences() = shot("references-row", 2)

    @Test fun full() = shot("references-row-full", 3)
}
