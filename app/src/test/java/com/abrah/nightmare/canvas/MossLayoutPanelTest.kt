package com.abrah.nightmare.canvas

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.abrah.nightmare.MossLayout
import com.abrah.nightmare.goldenPath
import com.abrah.nightmare.ui.NightmareTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** ⭐ A MOSS sampler's Layout tab (GitHub #77): the editor as a golden, and the tile's thumbnail. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h1400dp-xxhdpi")
class MossLayoutPanelTest {

    private val poster = MossLayout.write(
        MossLayout.Layout(
            listOf(
                MossLayout.Box("text", "Large white title", "EXPLORE", 0.12, 0.06, 0.88, 0.22),
                MossLayout.Box("character", "A hiker in a red jacket", "", 0.38, 0.40, 0.62, 0.92),
                MossLayout.Box("object", "Snowy mountains", "", 0.0, 0.30, 1.0, 0.75),
                MossLayout.Box("text", "Small caption", "", 0.30, 0.94, 0.70, 0.99),
            ),
        ),
    )

    /** ⭐ Four boxes in their three colours, numbered; the empty text box flagged under its card. */
    @Test
    fun theEditor() = captureRoboImage(filePath = goldenPath(this, "moss-layout")) {
        NightmareTheme(darkTheme = true) {
            androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize()) {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) {
                    MossLayoutPanel(poster, 768, 1024) {}
                }
            }
        }
    }

    @Test
    fun theTileIsThePicturesShape() {
        val t = layoutThumb(MossLayout.read(poster), 768, 1024, 0)
        assertEquals(144, t.width)
        assertEquals(192, t.height)
    }
}
