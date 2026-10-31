package com.abrah.nightmare

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ⭐ The GPU route ([DitGpu], `docs/GPU-ROUTES.md` §8): who takes it, which build and engine they get, and the launch
 * environment. The user's calls, 2026-10-31: automatic where the NPU cannot run a DiT, opt-in elsewhere, taef2 default.
 */
@RunWith(RobolectricTestRunner::class)
class DitGpuTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun caps(arch: Int) = DeviceProbe.Caps(arch = arch, vtcmMb = 8, measured = true, soc = "SM8450")
    private val klein = ModelCatalog.byId("flux2_klein_4b")!!

    @After
    fun reset() {
        DitGpu.openClForTest = null
        Prefs.setLowRam(ctx, Prefs.KEY_DIT_GPU, false)
        Prefs.setLowRam(ctx, Prefs.KEY_DIT_TAESD, true)
    }

    @Test
    fun theBuildThisPhoneDoesNotRunHasItsDitReclaimedButAGpuModelKeepsItsFp8() {
        DitGpu.openClForTest = true
        // ⚠ Off the route on an 8 Elite: FP8 runs, and a Q4_0 left by the GPU switch would shadow it (backend 014).
        val off = ModelCatalog.obsoleteDits(klein, caps(79)).map { it.name }
        assertEquals(listOf("dit.gguf"), off.distinct())
        // ⚠ On the route: Q4_0 runs and FP8 stays, so the switch turned off again needs no 4 GB download.
        Prefs.setLowRam(ctx, Prefs.KEY_DIT_GPU, true)
        assertTrue("the FP8 under the route's Q4_0 is kept", ModelCatalog.obsoleteDits(klein, caps(79)).isEmpty())
        // Qwen (no GPU route) reclaims both ways, as it did.
        val qwen = ModelCatalog.byId("qwen_image_2_1")!!
        assertTrue(ModelCatalog.obsoleteDits(qwen, caps(79)).all { it.name.startsWith("dit.") })
        assertTrue(ModelCatalog.obsoleteDits(qwen, caps(79)).isNotEmpty())
    }

    @Test
    fun belowV73ItTakesTheGpuOnItsOwnAndOnlyWithAnOpenClDriver() {
        DitGpu.openClForTest = true
        assertTrue("an 8 Gen 1 (v69) went to the CPU; the GPU takes it", DitGpu.wanted(caps(69)))
        assertFalse("an 8 Elite keeps its NPU unless asked", DitGpu.wanted(caps(79)))
        DitGpu.openClForTest = false
        assertFalse("no driver, no route (a Pixel)", DitGpu.wanted(caps(69)))
    }

    @Test
    fun theSwitchSendsAnyPhoneToTheGpu() {
        DitGpu.openClForTest = true
        Prefs.setLowRam(ctx, Prefs.KEY_DIT_GPU, true)
        assertTrue(DitGpu.wanted(caps(79)))
        assertTrue(DitGpu.runs(klein, caps(79)))
        assertFalse("a model not measured there stays where it was", DitGpu.runs(ModelCatalog.byId("anima_turbo_1_1"), caps(79)))
    }

    @Test
    fun onTheGpuKleinIsItsQ4ggufBuildWhateverTheArch() {
        DitGpu.openClForTest = true
        val b = klein.buildFor(caps(69))
        assertTrue("a v69 phone gets a build at all", b != null)
        assertTrue(b!!.files.any { it.name == "dit.gguf" })
        assertEquals("the smallest GGUF: Q4_0", klein.builds.filter { x -> x.files.any { it.name == "dit.gguf" } }.minOf { it.bytes }, b.bytes)
        DitGpu.openClForTest = false
        assertEquals("off the route, a v69 phone gets no Klein build, as before", null, klein.buildFor(caps(69)))
    }

    @Test
    fun theEngineVariantFollowsTheRoute() {
        DitGpu.openClForTest = false
        val npu = DitEngine.URL
        DitGpu.openClForTest = true
        Prefs.setLowRam(ctx, Prefs.KEY_DIT_GPU, true)
        assertNotEquals(npu, DitEngine.URL)
        assertTrue(DitEngine.URL.endsWith("-gpu.zip"))
        assertEquals("one archive, two hosts", DitEngine.URL.substringAfterLast('/'), DitEngine.HF_URL.substringAfterLast('/'))
    }

    @Test
    fun theLaunchEnvironmentCarriesTheDeviceTheCutsAndTheDecoder() {
        DitGpu.openClForTest = true
        Prefs.setLowRam(ctx, Prefs.KEY_DIT_GPU, true)
        val tae = DitGpu.taeFile(ctx, DitGpu.TAEF2).apply { parentFile?.mkdirs(); writeBytes(ByteArray(4)) }
        val env = DitGpu.launchEnv(ctx, klein, caps(79))
        assertEquals(DitGpu.BACKEND, env["NM_DIT_BACKEND"])
        assertEquals("15", env["GGML_OPENCL_MM_MAX_GFLOP"])
        assertEquals("Klein keeps the xmem attention", "1", env["GGML_OPENCL_XMEM_SDPA"])
        // ⚠ Z-Image is off the route until its faster attention ships (the user, 2026-10-31); its xmem switch waits.
        val zimage = ModelCatalog.byId("z_image_turbo")
        assertTrue("Z-Image stays off the GPU", DitGpu.launchEnv(ctx, zimage, caps(79)).isEmpty())
        assertEquals("Z-Image renders garbage with it", "0", DitGpu.envFor(zimage)["GGML_OPENCL_XMEM_SDPA"])
        assertEquals(tae.absolutePath, env["NM_DIT_TAESD"])
        assertTrue("the CPU's cores, not the backend's 4", env.getValue("NM_DIT_THREADS").toInt() in 4..8)
        assertFalse("an 8 GB phone keeps the small decode buffer", "NM_DIT_VAE_DIRECT" in env)
        val roomy = DitGpu.launchEnv(ctx, klein, caps(79).copy(ramBytes = 12L shl 30))
        assertEquals("a 12 GB phone takes the faster decode", "0", roomy["NM_DIT_VAE_DIRECT"])
        Prefs.setLowRam(ctx, Prefs.KEY_DIT_TAESD, false)
        assertFalse("the full VAE when the switch is off", "NM_DIT_TAESD" in DitGpu.launchEnv(ctx, klein, caps(79)))
        Prefs.setLowRam(ctx, Prefs.KEY_DIT_GPU, false)
        assertTrue("off the route: nothing changes", DitGpu.launchEnv(ctx, klein, caps(79)).isEmpty())
    }
}
