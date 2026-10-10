package com.abrah.nightmare

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import com.abrah.nightmare.canvas.Result
import com.abrah.nightmare.ui.NightmareTheme
import com.abrah.nightmare.ui.ResultViewer
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ⭐ The Results fullscreen viewer — no golden held it until 2026-10-08, when the user found its
 * size, seed and copy button "visually poor" ([com.abrah.nightmare.ui.MetaPill] since).
 * ⚠ A portrait picture, so the pills sit under a picture that does not fill the width.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ViewerScreenshotTest {

    @Test
    fun resultViewer() {
        val bmp = android.graphics.Bitmap.createBitmap(768, 1024, android.graphics.Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(0x6A, 0x4C, 0x9C))
        }.asImageBitmap()
        val r = Result(
            id = "r1", savedAt = 0L, seed = "186592889", model = "Illustrious XL", prompt = "a lighthouse",
            width = 768, height = 1024,
        )
        captureRoboImage(filePath = goldenPath(this, "viewer-result")) {
            NightmareTheme(darkTheme = true) {
                androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.Black) {
                    ResultViewer(
                        items = listOf(r, r.copy(id = "r2")), startIndex = 0,
                        imageFor = { bmp }, detailsFor = { emptyList() },
                        onDismiss = {}, onOpenFlow = {}, onDelete = {},
                    )
                }
            }
        }
    }
}
