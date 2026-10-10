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

    /** ⭐ The DiT engine's form: the first 8 Gen 3 report (OnePlus SM8650, 2026-10-10). */
    @Test
    fun aDitWhoseWeightsWouldNotMapIsSaidInWords() {
        val dit = listOf(
            "[ ERROR ] [dit] util.cpp:634  - ggml-hex: HTP0 buffer mapping failed : domain_id 3 size 90247168 fd 92 error 0x00000001",
            "[ ERROR ] [dit] model_manager.cpp:861  - model manager alloc params backend buffer failed, size = 86.06MB",
        )
        val s = npuSpaceRefused("DiT generation failed: generate_image failed", dit)!!
        assertTrue(s.contains("could not map the model's weights"))
        assertNull(npuSpaceRefused("DiT generation failed: generate_image failed", listOf("something else")))
    }

    @Test
    fun anyOtherFailureIsLeftAlone() {
        assertNull(npuSpaceRefused("Failed init QNN model: controlnet", listOf("QnnDsp <E> something else")))
        assertNull(npuSpaceRefused("sample failed: out of memory", log))
    }
}
