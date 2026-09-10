package com.abrah.nightmare

import androidx.compose.runtime.Composable
import com.abrah.nightmare.ui.NightmareTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the harness to PNG on the JVM -- no device, no Android Studio.
 *
 * ⚠ This exists because `render-compose-preview` turned out to be a bridge to a
 * running IDE rather than a standalone renderer (docs/UI.md section 2.1), and
 * because verifying UI by foregrounding the app on the user's phone captures
 * whatever they happen to have on screen.
 *
 * ⭐ The immediate question it answers: `android screen capture` returned an
 * all-black frame while the activity held focus. If these images have content,
 * the app draws and the capture path is the broken half.
 *
 * Output lands in app/build/outputs/roborazzi/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class HarnessScreenshotTest {

    /**
     * The content-lambda overload, deliberately: it needs no ComposeTestRule and
     * no `onRoot()`, so there is one less API surface to get wrong in a file
     * whose entire job is to be a reliable pair of eyes.
     */
    private fun shoot(name: String, body: @Composable () -> Unit) {
        // ⚠ Goldens live in src/, not build/. Under build/ they are throwaway
        // output and pin nothing; committed, they are the baseline that catches
        // an agent quietly drifting spacing or color on an unrelated change --
        // which is the entire reason docs/UI.md §3 asks for them.
        // Re-record with :app:recordRoborazziDebug, check with verifyRoborazziDebug.
        captureRoboImage(filePath = "src/test/screenshots/$name.png") {
            NightmareTheme(darkTheme = true) { body() }
        }
    }

    @Test
    fun backendUp() = shoot("harness-up") {
        HarnessContent(
            state = BackendState.UP,
            busy = false,
            log = listOf(
                LogLine("12:04:11", "/health 200 in 71 ms"),
                LogLine("12:04:09", "encode_text 129 ms (wire 131 ms)"),
            ),
            image = null,
            onStart = {},
            onStop = {},
            onHealth = {},
            onEncodeText = {},
            onVaeDecode = {},
            onSample = {},
            onGraph = {},
            // ⚠ Pinned: otherwise every versionCode bump fails these goldens.
            version = "harness x.y (0)",
            onNotWired = { _, _ -> },
        )
    }

    /** The state a first run actually shows: nothing staged, nothing running. */
    @Test
    fun backendDown() = shoot("harness-down") {
        HarnessContent(
            state = BackendState.DOWN,
            busy = false,
            log = listOf(
                LogLine("12:04:11", "  no backend on :8189. Stage and launch one first.", bad = true),
                LogLine(
                    "12:04:11",
                    "/health unreachable after 6001 ms -- ConnectException: failed to " +
                        "connect to /127.0.0.1 (port 8189) after 6000ms",
                    bad = true,
                ),
            ),
            image = null,
            onStart = {},
            onStop = {},
            onHealth = {},
            onEncodeText = {},
            onVaeDecode = {},
            onSample = {},
            onGraph = {},
            // ⚠ Pinned: otherwise every versionCode bump fails these goldens.
            version = "harness x.y (0)",
            onNotWired = { _, _ -> },
        )
    }

    @Test
    fun emptyAndBusy() = shoot("harness-empty") {
        HarnessContent(
            state = BackendState.UNKNOWN,
            busy = true,
            log = emptyList(),
            image = null,
            onStart = {},
            onStop = {},
            onHealth = {},
            onEncodeText = {},
            onVaeDecode = {},
            onSample = {},
            onGraph = {},
            // ⚠ Pinned: otherwise every versionCode bump fails these goldens.
            version = "harness x.y (0)",
            onNotWired = { _, _ -> },
        )
    }

    /**
     * Mid-render. ⭐ The state /sample?stream=1 made possible, and the one the
     * canvas will live in: a bar that moves, with the raw step and total beside
     * it. A golden of it is what turns "the bar drifted" into a failed verify
     * instead of a surprise on a device someone happened to be looking at.
     */
    @Test
    fun sampling() = shoot("harness-sampling") {
        HarnessContent(
            state = BackendState.UP,
            busy = true,
            log = listOf(
                LogLine("12:04:12", "sample: 20 steps, seed 42 -- streaming"),
            ),
            image = null,
            onStart = {},
            onStop = {},
            onHealth = {},
            onEncodeText = {},
            onVaeDecode = {},
            onSample = {},
            onGraph = {},
            // ⚠ Pinned: otherwise every versionCode bump fails these goldens.
            version = "harness x.y (0)",
            progress = 9 to 22,
            onNotWired = { _, _ -> },
        )
    }
}
