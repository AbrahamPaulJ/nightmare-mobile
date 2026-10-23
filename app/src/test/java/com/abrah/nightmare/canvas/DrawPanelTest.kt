package com.abrah.nightmare.canvas

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.abrah.nightmare.FastIdle
import com.abrah.nightmare.ui.NightmareTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ⚠⚠ The inpaint window's Draw tab crashed the app on open (1.6.225, the user's phone): the draw panel scrolled
 * on its own inside the window's page, which already scrolls ("measured with an infinity maximum height").
 * ⚠ The control, run once (2026-10-23): `scroll = true` in the same page throws exactly that; it is not kept as a
 * test because the compose rule rethrows the crash after any catch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class DrawPanelTest {
    @get:Rule
    val fastIdle = FastIdle()

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theDrawTabOpensInsideTheInpaintWindowsPage() {
        val photo = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).asImageBitmap()
        rule.setContent {
            NightmareTheme(darkTheme = true) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DrawPanelBody(photo, null, {}, title = null, note = "note", scroll = false)
                }
            }
        }
        rule.onNodeWithText("note").assertExists()
    }
}
