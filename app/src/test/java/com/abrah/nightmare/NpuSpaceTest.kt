package com.abrah.nightmare

import org.junit.Assert.assertEquals
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
        assertTrue(s.contains("run without it", ignoreCase = true))
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

    /** ⭐ GitHub #97: the phone learns its ceiling from the failure, down to a floor, and then says so no more. */
    @Test
    fun aMapFailureLowersThePhonesBudgetAndThePersonIsToldToRunAgain() {
        val dit = listOf("[ ERROR ] [dit] util.cpp:634  - ggml-hex: HTP0 buffer mapping failed : domain_id 3 size 64626688")
        val told = npuSpaceRefused("DiT generation failed: generate_image failed", dit) { "1.5" }!!
        assertTrue(told, told.contains("1.5 GiB") && told.contains("Run again"))
        val floor = npuSpaceRefused("DiT generation failed: generate_image failed", dit) { null }!!
        assertTrue(floor, floor.contains("smaller canvas"))

        // ⭐ GitHub #115: the engine died of it (the socket's EOF, http -1): still learned.
        val died = npuSpaceRefused("EOFException: no message", dit, died = true) { "2.25" }!!
        assertTrue(died, died.contains("2.25 GiB"))
        assertNull(npuSpaceRefused("EOFException: no message", dit) { "2.25" })

        assertEquals("2.25", DeviceProbe.nextDitMapCap("2.75"))
        assertEquals("1.75", DeviceProbe.nextDitMapCap("2.25"))
        assertEquals("1.5", DeviceProbe.nextDitMapCap("2.0"))
        // ⭐ GitHub #144: Anima 2.9B on an SM8650 refused at the old 1.75 floor and ran at 1.25 (1.6.307).
        assertEquals("1.25", DeviceProbe.nextDitMapCap("1.75"))
        assertEquals("the floor", "1.25", DeviceProbe.nextDitMapCap("1.5"))
        assertNull(DeviceProbe.nextDitMapCap("1.25"))
        assertNull("no budget, nothing to lower", DeviceProbe.nextDitMapCap(null))
        assertEquals("not under a block a lift was made for", "2.0", DeviceProbe.nextDitMapCap("2.25", needFloor = 2.0))
        assertNull(DeviceProbe.nextDitMapCap("2.0", needFloor = 2.0))
    }

    /**
     * ⭐ Saved #8083, GitHub #123: an 8 Gen 3 that had learned 1.25 GiB failed every Z-Image run in segment 1, before
     * mapping anything. The engine's "need X MB" lifts the cap and the person is told to run again.
     */
    @Test
    fun aCapSmallerThanOneBlockIsLifted() {
        val zit = listOf(
            "Req Rcvd: P:[prompt removed] NP:[prompt removed] S:7 CFG:1",
            "10167.2ms [ ERROR ] [dit] model_manager.cpp:1748 - model manager cannot make enough memory available on HTP0: " +
                "need 1324.99 MB device / 1324.99 MB budget, available 17592186044416.00 MB device / 1280.00 MB budget",
            "10167.3ms [ ERROR ] [dit] ggml_runner.cpp:875  - z_image segment 1/36 (z_image.prelude) failed during weight preparation",
        )
        var asked = 0.0
        val s = npuSpaceRefused("DiT generation failed: generate_image failed", zit, lift = { asked = it; "1.75" })!!
        assertEquals(1324.99, asked, 0.001)
        assertTrue(s, s.contains("1.75 GiB") && s.contains("Run again") && s.contains("1.29 GB"))
        // No learned cap to lift (a model simply too big at the default budget): not this message.
        assertNull(npuSpaceRefused("DiT generation failed: generate_image failed", zit))

        assertEquals("1.75", DeviceProbe.liftedDitMapCap("1.25", 1324.99))
        assertEquals("2.25", DeviceProbe.liftedDitMapCap("1.75", 1800.0))
        assertEquals("past the top: no cap", "", DeviceProbe.liftedDitMapCap("2.25", 3047.72))
        assertNull("already fits", DeviceProbe.liftedDitMapCap("1.75", 1522.79))
        assertNull("nothing learned", DeviceProbe.liftedDitMapCap(null, 1522.79))
    }

    /** ⭐ GitHub #144: budgets are kept per model, so Anima learning 1.25 never strands Krea 2's 1.41 GB blocks. */
    @Test
    fun budgetsAreKeptPerModel() {
        val m = mapOf("anima_silvermoon" to "1.25", "krea2_turbo" to "1.75", "zimage" to "")
        val s = DeviceProbe.encodeDitMapCaps(m)
        assertEquals(m, DeviceProbe.decodeDitMapCaps(s))
        assertEquals(emptyMap<String, String>(), DeviceProbe.decodeDitMapCaps(null))
        assertEquals("a bad entry is dropped", mapOf("a" to "1.5"), DeviceProbe.decodeDitMapCaps("a=1.5;b=x;=2"))
    }

    /** ⚠ GitHub #125: a map failure in an EARLIER render of the same launch does not lower the budget on a later death. */
    @Test
    fun onlyThisRendersLinesTeach() {
        val launch = listOf(
            "Req Rcvd: P:a",
            "[ ERROR ] [dit] util.cpp:634  - ggml-hex: HTP0 failed to allocate device buffer context: ggml-hex: fastrpc_mmap failed",
            "Req Rcvd: P:b",
            "[  INFO ] [dit] ggml_runner.cpp:946  - z_image executing segment 27/36",
        )
        assertNull(npuSpaceRefused("EOFException: no message", launch, died = true) { "2.25" })
        val now = launch.take(2)
        assertTrue(npuSpaceRefused("EOFException: no message", now, died = true) { "2.25" }!!.contains("2.25 GiB"))
    }

    @Test
    fun aWorkingBufferThatFailedTeachesNothing() {
        // ⭐ GitHub #125: the last step's working buffer, not the weights; the engine then died.
        val crash = listOf(
            "Req Rcvd: P:a",
            "[  INFO ] [dit] ggml_runner.cpp:946  - z_image executing segment 36/36: ggml_runner.final",
            "[ ERROR ] [dit] util.cpp:634  - ggml-hex: HTP0 buffer mapping failed : domain_id 3 size 768942080 fd 34 error 0x00000001",
            "[ ERROR ] [dit] util.cpp:634  - ggml-hex: HTP0 failed to allocate device buffer context: ggml-hex: fastrpc_mmap failed (see log for details)",
            "[ ERROR ] [dit] util.cpp:634  - ggml_gallocr_reserve_n_impl: failed to allocate HTP0 buffer of size 768937984",
        )
        var learned = false
        val said = npuSpaceRefused("EOFException: no message", crash, died = true) { learned = true; "2.25" }!!
        assertTrue(said, said.contains("733 MB of working memory"))
        assertTrue("the budget is not lowered", !learned)
    }

    @Test
    fun aLearnedCapOnlyEverLowersALaunchsBudget() {
        assertEquals("nothing learned", "2.25", BackendProcess.cappedDitBudget("2.25", null))
        assertNull(BackendProcess.cappedDitBudget(null, null))
        assertEquals("stands in for no budget", "1.5", BackendProcess.cappedDitBudget(null, "1.5"))
        assertEquals("lowers", "1.5", BackendProcess.cappedDitBudget("2.0", "1.5"))
        assertEquals("never raises", "1.25", BackendProcess.cappedDitBudget("1.25", "1.5"))
    }

    @Test
    fun anyOtherFailureIsLeftAlone() {
        assertNull(npuSpaceRefused("Failed init QNN model: controlnet", listOf("QnnDsp <E> something else")))
        assertNull(npuSpaceRefused("sample failed: out of memory", log))
    }
}
