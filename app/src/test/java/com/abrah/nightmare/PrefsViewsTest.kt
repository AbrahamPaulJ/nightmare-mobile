package com.abrah.nightmare

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** ⭐ The view settings of 2026-10-13: top to bottom for NEW users only, and Hide chat view. */
@RunWith(RobolectricTestRunner::class)
class PrefsViewsTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    @Before
    fun clean() {
        // ⚠ `Prefs.FILE`'s value: a fresh install has none of these keys.
        ctx.getSharedPreferences("nightmare.prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun aNewInstallOpensTheDefaultViewTopToBottom() {
        Prefs.load(ctx)
        assertTrue(Prefs.nodesVertical)
    }

    @Test
    fun someoneAlreadyAskedKeepsSideToSide() {
        Prefs.setDefaultView(ctx, MainView.NODES)
        Prefs.load(ctx)
        assertFalse(Prefs.nodesVertical)
    }

    @Test
    fun hideChatIsRemembered() {
        Prefs.setHideChat(ctx, true)
        Prefs.load(ctx)
        assertTrue(Prefs.hideChat)
    }
}
