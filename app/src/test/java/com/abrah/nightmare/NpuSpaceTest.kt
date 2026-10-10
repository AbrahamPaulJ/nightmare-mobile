package com.abrah.nightmare

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ⭐ A ControlNet that found no NPU space beside SDXL Swap's UNet (an HONOR SM8650, 2026-10-10). */
class NpuSpaceTest {

    private val log = listOf(
        "QnnDsp <E> openSessionForPriority failed: devKey={deviceId=0 coreId=0 pdId=2} priority=100",
        "QnnDsp <E> Failed to find available PD for contextId 2 on deviceId 0 coreId 0with context size estimate 1568580864",
        "QnnDsp <E> Fail to create context from binary with err 1002",
    )

    @Test
    fun theControlNetThatFoundNoRoomIsSaidInWords() {
        val s = npuSpaceRefused("Failed init QNN model: controlnet", log)!!
        assertTrue(s.contains("no room for the ControlNet"))
        assertTrue(s.contains("run without it"))
        // ⚠ The backend's own words are kept.
        assertTrue(s.contains("Failed init QNN model: controlnet"))
    }

    @Test
    fun anyOtherFailureIsLeftAlone() {
        assertNull(npuSpaceRefused("Failed init QNN model: controlnet", listOf("QnnDsp <E> something else")))
        assertNull(npuSpaceRefused("sample failed: out of memory", log))
    }
}
