package com.abrah.nightmare.canvas

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import com.abrah.nightmare.goldenPath
import com.abrah.nightmare.ui.NightmareTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ⭐ The output's before/after ([ComparePicture]; the user's mock, 2026-10-25). At rest the handle sits in the room
 * right of the picture and the output (green) shows whole; dragged left, the input (blue, a dot at its centre) shows
 * right of the line.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ComparePictureTest {

    @get:Rule
    val fastIdle = com.abrah.nightmare.FastIdle()

    @get:Rule
    val rule = createComposeRule()

    private fun solid(color: Int, dot: Boolean = false): ImageBitmap {
        val b = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        b.eraseColor(color)
        if (dot) for (x in 28 until 36) for (y in 28 until 36) b.setPixel(x, y, android.graphics.Color.WHITE)
        return b.asImageBitmap()
    }

    private fun show() = rule.setContent {
        NightmareTheme(darkTheme = true) {
            Surface {
                ComparePicture(
                    after = solid(android.graphics.Color.rgb(40, 160, 90)),
                    before = solid(android.graphics.Color.rgb(40, 90, 200), dot = true),
                    beforeRect = CropRect(0f, 0f, 1f, 1f),
                    contentDescription = "output",
                    box = Modifier.padding(12.dp).heightIn(max = 360.dp),
                    onClick = {},
                )
            }
        }
    }

    @Test
    fun atRestTheOutputShowsWhole() {
        show()
        rule.onRoot().captureRoboImage(goldenPath(this, "compare-rest"))
    }

    @Test
    fun draggedLeftTheInputShowsRightOfTheLine() {
        show()
        rule.onNodeWithContentDescription("output").performTouchInput { swipeLeft(startX = right - 2f, endX = centerX) }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(goldenPath(this, "compare-dragged"))
    }
}
