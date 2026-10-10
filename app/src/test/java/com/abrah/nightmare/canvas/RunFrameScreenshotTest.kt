package com.abrah.nightmare.canvas

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abrah.nightmare.goldenPath
import com.abrah.nightmare.ui.NightmareTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** ⭐ The run as its picture's frame, log filling it (2026-10-10) — a 3:4 render mid-run. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class RunFrameScreenshotTest {
    @Test
    fun midRun() = captureRoboImage(filePath = goldenPath(this, "run-frame")) {
        NightmareTheme(darkTheme = true) {
            Surface {
                RunFrame(
                    RunLogState(
                        lines = listOf(
                            RunLine("prompt", "0.0s"),
                            RunLine("backend", "starting the backend for Juggernaut XL Swap…"),
                            RunLine("backend", "serving after ~6s"),
                            RunLine("n2", "reading the prompt 0.4s"),
                            RunLine("n2", "rendering"),
                        ),
                        now = "Text to image",
                        step = 9 to 22,
                        startedAtMs = 1L,
                    ),
                    aspect = 3f / 4f,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    fullLogOnTap = false,
                )
            }
        }
    }
}
