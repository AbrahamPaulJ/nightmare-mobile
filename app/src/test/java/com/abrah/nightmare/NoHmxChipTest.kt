package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐ The 8s Gen 4 / 8s Gen 3 grey (then noisy) picture: HMX off there since 1.6.193 (the CPU decode of 1.6.185 gave
 * noise), a way to test the NPU on demand, and the engine's own reading of the NPU kept for the report.
 */
class NoHmxChipTest {

    private fun caps(soc: String) = DeviceProbe.Caps(arch = 73, vtcmMb = 8, measured = false, soc = soc)

    @Test
    fun theTwoSChipsRunWithHmxOffAndThe8Gen2DoesNot() {
        assertTrue(caps("SM8735").ditNoHmx)
        assertTrue(caps("SM8635").ditNoHmx)
        // ⚠ The control: the 8 Gen 2 runs the same v73 skel clean.
        assertFalse(caps("SM8550").ditNoHmx)
        val zit = ModelCatalog.byId("z_image_turbo")!!
        assertTrue(zit.noHmxOn(caps("SM8735")))
        assertFalse(zit.noHmxOn(caps("SM8550")))
    }

    @Test
    fun irisKeepsHmxButIsOfferedTheTestAndWanIsNotOffered() {
        // ⚠ Iris ABORTS with HMX off (BF16 rows, `dsp-rsp NO-SUPPORT`): it keeps HMX, and gets the test.
        val iris = ModelCatalog.byId("iris_3b_turbo")!!
        assertFalse(iris.noHmxOn(caps("SM8735")))
        assertTrue(iris.npuTestOn(caps("SM8735")))
        assertFalse(iris.npuTestOn(caps("SM8550")))
        assertFalse(ModelCatalog.WAN22.noHmxOn(caps("SM8635")))
    }

    @Test
    fun irisIsNotOfferedOnTheTwoSChips() {
        // ⭐ Noise in every leg of an 8s Gen 4's ⋮ Test the NPU, and an abort with HMX off: no build there.
        val iris = ModelCatalog.byId("iris_3b_turbo")!!
        assertTrue(iris.chipBlocked(caps("SM8735")))
        assertTrue(iris.chipBlocked(caps("SM8635")))
        assertNull(iris.buildFor(caps("SM8735")))
        // ⚠ The controls: the 8 Gen 2 keeps it, and the other DiT models stay offered on the two chips.
        assertFalse(iris.chipBlocked(caps("SM8550")))
        assertTrue(iris.buildFor(caps("SM8550")) != null)
        val zit = ModelCatalog.byId("z_image_turbo")!!
        assertFalse(zit.chipBlocked(caps("SM8735")))
        assertTrue(zit.buildFor(caps("SM8735")) != null)
    }

    @Test
    fun theLaunchTurnsHmxOffByGgmlsOwnSwitch() {
        // ⚠ The leg that came back with a picture on both chips set exactly this ([BlankDiagnosis.LEGS] `no_hmx`).
        val leg = BlankDiagnosis.LEGS.first { it.id == "no_hmx" }
        assertEquals(mapOf(BackendProcess.NO_HMX_KEY to "0"), leg.env)
    }

    @Test
    fun theDecodeLegsKeepTheDecodeOnTheNpu() {
        // ⭐ Every leg after the parts tests the NPU decode, so none may set the device spec.
        val decode = listOf("no_hmx", "cpu_conv", "cpu_group_norm", "cpu_pad")
        val legs = BlankDiagnosis.LEGS.associateBy { it.id }
        for (id in decode) {
            val leg = legs[id] ?: error("missing leg $id")
            assertNull(id, leg.env["NM_DIT_BACKEND"])
        }
        assertEquals("CONV_2D.*|IM2COL", legs.getValue("cpu_conv").env[BlankDiagnosis.OPFILTER_ADD])
    }

    @Test
    fun theEnginesNpuLinesAreKept() {
        // ⚠ The real lines, from a v75 report (1.6.175).
        assertEquals(
            "arch" to "v75",
            BackendProcess.engineNpuLine("   784.7ms [  INFO ] [dit] util.cpp:628  - ggml-hex: Hexagon Arch version v75"),
        )
        assertEquals(
            "hwinfo" to "threads 4, hvx 4, hmx 1, vtcm 8 MB",
            BackendProcess.engineNpuLine("   821.3ms [  INFO ] [dit] util.cpp:628  - ggml-hex: HTP0 hwinfo: threads 4, hvx 4, hmx 1, vtcm 8 MB"),
        )
        assertNull(BackendProcess.engineNpuLine("   784.7ms [  INFO ] [dit] util.cpp:628  - ggml-hex: device 0: HTP0 (phys=0, virt=0, domain=cdsp:3)"))
        // ⭐ Engine `dit/030`'s self-test line (its format, `ggml-hexagon.cpp`).
        assertEquals(
            "hmx" to "HMX self-test WRONG (zeros), rel-err 1.0000, out/ref 0.000, max-err 41.2",
            BackendProcess.engineNpuLine("   830.0ms [  INFO ] [dit] util.cpp:628  - ggml-hex: HTP0 HMX self-test: WRONG (zeros), rel-err 1.0000, out/ref 0.000, max-err 41.2"),
        )
    }

    /**
     * ⭐ The user's call (2026-10-19): a WRONG or FAILED self-test turns HMX off on this phone; ok and "skipped"
     * (a launch with HMX already off) do not. The two SM8735 reports' line is the first case.
     */
    @Test
    fun aFailedSelfTestTurnsHmxOff() {
        assertTrue(DeviceProbe.hmxSelfTestFailed("HMX self-test WRONG (zeros), rel-err 1.0000, out/ref 0.000, max-err 22"))
        assertTrue(DeviceProbe.hmxSelfTestFailed("HMX self-test WRONG, rel-err 0.4100"))
        assertTrue(DeviceProbe.hmxSelfTestFailed("HMX self-test FAILED"))
        assertFalse(DeviceProbe.hmxSelfTestFailed("HMX self-test ok, rel-err 0.0003"))
        assertFalse(DeviceProbe.hmxSelfTestFailed("HMX self-test skipped (HMX off)"))
    }
}
