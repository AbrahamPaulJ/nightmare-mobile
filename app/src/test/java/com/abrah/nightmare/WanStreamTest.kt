package com.abrah.nightmare

import androidx.test.core.app.ApplicationProvider
import com.abrah.nightmare.npu.WanVideoNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ⭐ Settings → Memory → Video: stream the model (GitHub #48/#59, [Prefs.KEY_WAN_STREAM]): off unless set, and then
 * every clip budgeted BELOW the DiT's weights, so even a short clip streams instead of running resident.
 */
@RunWith(RobolectricTestRunner::class)
class WanStreamTest {

    private val gb16 = 15L shl 30
    private val wan = WanVideoNode

    @Test
    fun theSwitchIsHeldInMemoryForTheLaunchKey() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        try {
            Prefs.load(ctx)
            assertFalse(Prefs.wanStream)
            Prefs.setLowRam(ctx, Prefs.KEY_WAN_STREAM, true)
            assertTrue(Prefs.wanStream)
            assertTrue(Prefs.lowRam(ctx).wanStream)
            Prefs.load(ctx)
            assertTrue(Prefs.wanStream)
        } finally {
            Prefs.setLowRam(ctx, Prefs.KEY_WAN_STREAM, false)
        }
        assertFalse(Prefs.wanStream)
    }

    @Test
    fun everyClipStreamsWithTheSwitchOn() {
        // ⚠ The control: off on a 16 GB 8 Elite, nothing is budgeted, so a phone that renders clean resident keeps its speed.
        assertNull(wan.memoryBudget(wan.pinnedBytes(832, 480, 49), gb16, 79, stream = false))
        // #59: 2 s at 480p ran RESIDENT under the old fixed 4 GiB (2.9 GB of weights + 0.5 GB of compute fit it).
        assertEquals("2.75", wan.memoryBudget(wan.pinnedBytes(832, 480, 49), gb16, 79, stream = true))
        assertEquals("2.75", wan.memoryBudget(wan.pinnedBytes(832, 480, 121), gb16, 79, stream = true))
        // One block of 704p 5 s needs ~3.7 GB, over 2.75: 4, and the clip is over 4 GiB whole, so it streams there.
        assertEquals("4", wan.memoryBudget(wan.pinnedBytes(704, 1280, 121), gb16, 79, stream = true))
        assertTrue(wan.pinnedBytes(704, 1280, 121) > (4L shl 30))
        // v75 keeps its own 2.75 whatever the switch says.
        assertEquals("2.75", wan.memoryBudget(wan.pinnedBytes(704, 1280, 121), gb16, 75, stream = true))
    }
}
