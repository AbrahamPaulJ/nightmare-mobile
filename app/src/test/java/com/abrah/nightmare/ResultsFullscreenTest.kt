package com.abrah.nightmare

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.abrah.nightmare.canvas.ResultsStore
import com.abrah.nightmare.canvas.defaultWorkflow
import com.abrah.nightmare.ui.NightmareTheme
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * ⭐⭐⭐ **Results → tap the picture → the fullscreen viewer is ON SCREEN.** A
 * priority test (the user's call, 2026-10-01): this path broke TWICE with the
 * whole suite green, because the goldens compose `ResultsScreen` and
 * `ResultViewer` SEPARATELY and nothing ran the path between them — the
 * library's pull-down sheet, the tap, the view model, the viewer's own Dialog.
 * ⇒ The REAL `HarnessScreen`, a real view model, a real kept result.
 *
 * ⚠⚠ The tap is the click ACTION, not an injected touch. Measured 2026-10-01:
 * Robolectric delivers NO injected touch into the library's ModalBottomSheet —
 * its own Models tab ignores `performClick` too, while the same tabs work on
 * the phone — so a touch-based test fails for a reason that is not the app's.
 * The touch layer is checked on the phone: `logcat -s NmViewer` logs every
 * down/up on the big frame and every open/close, with who closed it
 * (`docs/UI.md` §5).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ResultsFullscreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    /**
     * ⚠⚠ The view model through a STORE, cleared after each test, as Android
     * clears it. Built bare, its `viewModelScope` outlived the test and the
     * NEXT classes in this JVM failed "Compose did not get idle" (measured
     * 2026-10-01: CanvasTouchTest, PictureActionsTest, ErrorDetailsTest).
     */
    private val store = androidx.lifecycle.ViewModelStore()

    @After
    fun clean() {
        store.clear()
        File(app.filesDir, "results").deleteRecursively()
    }

    private fun opened(): HarnessViewModel {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366CC.toInt()) }
        ResultsStore(File(app.filesDir, "results")).keep(
            bmp, imageId = null, workflow = defaultWorkflow(), types = NODE_TYPES,
            seed = "1", model = null, prompt = "a test",
        )
        val vm = androidx.lifecycle.ViewModelProvider(
            store, androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(app),
        )[HarnessViewModel::class.java]
        vm.setResultsVisible(true)
        rule.setContent { NightmareTheme(darkTheme = true) { HarnessScreen(vm = vm) } }
        // ⚠ The thumbnail decodes on IO, which `waitForIdle` does not wait for.
        rule.waitUntil(10_000) {
            rule.onAllNodesWithContentDescription(app.getString(R.string.cd_open_fullscreen))
                .fetchSemanticsNodes().isNotEmpty()
        }
        return vm
    }

    /**
     * ⚠ ONE test, deliberately: a second test in this class could not load its
     * kept picture (state that outlives a view model in one JVM — measured
     * 2026-10-01), and a flaky priority test would get ignored.
     */
    @Test
    fun tappingTheBigPictureOpensTheViewerAndItStaysOpen() {
        val vm = opened()
        rule.onAllNodesWithContentDescription(app.getString(R.string.cd_open_fullscreen))
            .onFirst().performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertNotNull("the tap must reach the view model", vm.viewingResult)
        // ⚠⚠ ON SCREEN, not merely in state: the 2026-09-27 break had the state
        // right and the viewer laid out below a sheet that fills the window.
        // ⚠ "share this picture" is the viewer's alone (the Results row has none).
        rule.onNodeWithContentDescription("share this picture").assertIsDisplayed()
        rule.onNodeWithContentDescription("the kept picture").assertIsDisplayed()
        // ⚠ …and still there a second later: a viewer closed by something on
        // the next frame reads as "fullscreen does nothing" on the phone.
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertNotNull(vm.viewingResult)
        rule.onNodeWithContentDescription("share this picture").assertIsDisplayed()
        vm.closeResult()
        rule.waitForIdle()
        assertNull(vm.viewingResult)
        rule.onNodeWithContentDescription("share this picture").assertDoesNotExist()
        // ⚠⚠ Close the SHEET before the test ends: its window outlived the
        // composition in Robolectric, and every later Compose class waited on it
        // ("Compose did not get idle", 2026-10-01).
        vm.closeLibrary()
        rule.waitForIdle()
    }
}
