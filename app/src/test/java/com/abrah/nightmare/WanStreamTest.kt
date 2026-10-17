package com.abrah.nightmare

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ⭐ Settings → Memory → Video: stream the model (GitHub #48, [Prefs.KEY_WAN_STREAM]): off unless set, and then a
 * budget for Wan launches only.
 */
@RunWith(RobolectricTestRunner::class)
class WanStreamTest {

    @Test
    fun offByDefaultThenABudgetForWanOnly() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val wan = ModelCatalog.WAN22
        val zit = ModelCatalog.byId("z_image_turbo")!!
        // ⚠ The control: unset, nothing changes, so a phone that renders clean resident keeps its speed.
        assertNull(Prefs.wanStreamBudget(ctx, wan))
        Prefs.setLowRam(ctx, Prefs.KEY_WAN_STREAM, true)
        assertEquals(Prefs.WAN_STREAM_GIB, Prefs.wanStreamBudget(ctx, wan))
        assertNull(Prefs.wanStreamBudget(ctx, zit))
        assertNull(Prefs.wanStreamBudget(ctx, null))
        Prefs.setLowRam(ctx, Prefs.KEY_WAN_STREAM, false)
        assertNull(Prefs.wanStreamBudget(ctx, wan))
    }
}
