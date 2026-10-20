package com.abrah.nightmare

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers

/** ⭐ GitHub #61/#62: an SM8975 whose guessed V85 skel would not load runs again as v81 ([DeviceProbe.learnPackFallback]). */
@RunWith(RobolectricTestRunner::class)
class PackFallbackTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /** The lines the reporter's backend printed (1.6.203). */
    private val skel1002 = listOf(
        "[ERROR] QnnDsp <E> Failed to load skel, error: 1002",
        "[ERROR] QnnDsp <E> Transport layer setup failed: 14001",
        "[ERROR] Failed to create device",
    )

    private fun onChip(soc: String) {
        ReflectionHelpers.setStaticField(Build::class.java, "SOC_MODEL", soc)
        ctx.getSharedPreferences("device_probe", Context.MODE_PRIVATE).edit().clear().commit()
        DeviceProbe.loadHmxVerdict(ctx)
    }

    @After
    fun clear() = onChip("")

    @Test
    fun aSkelThatWouldNotLoadIsTheOnlyTrigger() {
        assertTrue(DeviceProbe.skelLoadFailed(skel1002))
        assertFalse(DeviceProbe.skelLoadFailed(listOf("[ERROR] Failed to create device", "out of memory")))
    }

    @Test
    fun aGuessedV85FallsBackToV81OnceAndRemembers() {
        onChip("SM8975")
        assertEquals(85, DeviceProbe.caps().arch)
        assertTrue(DeviceProbe.learnPackFallback(ctx, skel1002))
        assertEquals(DeviceProbe.PACK_FALLBACK_ARCH, DeviceProbe.caps().arch)
        // ⚠ Once: the relaunch fails the same way, and that is not a second fallback.
        assertFalse(DeviceProbe.learnPackFallback(ctx, skel1002))
        // ⭐ Kept across restarts, like the HMX verdict.
        DeviceProbe.loadHmxVerdict(ctx)
        assertEquals(DeviceProbe.PACK_FALLBACK_ARCH, DeviceProbe.packFallback)
    }

    @Test
    fun aTabledChipNeverFallsBack() {
        onChip("SM8750")
        assertFalse(DeviceProbe.learnPackFallback(ctx, skel1002))
        assertNull(DeviceProbe.packFallback)
        assertEquals(79, DeviceProbe.caps().arch)
    }
}
