package com.abrah.nightmare

import androidx.test.core.app.ApplicationProvider
import java.io.File
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
        DeviceProbe.measuredForTest(null)
        ModelCatalog.root(ctx).deleteRecursively()
        DitEngine.dir(ctx).deleteRecursively()
    }

    /** ⭐ The user, 2026-11-14: each flip of the GPU switch overwrote the other engine, and the next Run fetched it again. */
    @Test
    fun bothEnginesAreKeptSoTheSwitchDownloadsNothing() {
        DitGpu.openClForTest = true
        val dir = DitEngine.dir(ctx).apply { mkdirs() }
        for (t in DitGpu.ALL) sparseFile(DitGpu.taeFile(ctx, t), t.bytes)
        // The GPU engine is live (as on the S25 after the switch), the NPU one kept beside it from an earlier switch.
        Prefs.setLowRam(ctx, Prefs.KEY_DIT_GPU, true)
        val gpuUrl = DitEngine.URL
        val gpuBytes = DitEngine.FILE_BYTES
        sparseFile(DitEngine.file(ctx), gpuBytes)
        File(dir, ".dit-engine").writeText(gpuUrl)
        assertTrue(DitEngine.isInstalled(ctx))
        Prefs.setLowRam(ctx, Prefs.KEY_DIT_GPU, false)
        val npuUrl = DitEngine.URL
        val npuBytes = DitEngine.FILE_BYTES
        sparseFile(File(dir, DitEngine.FILE + ".npu"), npuBytes)
        File(dir, ".dit-engine.npu").writeText(npuUrl)
        assertFalse("off: the live one is the GPU's", DitEngine.isInstalled(ctx))
        // ⚠ No network in a JVM test: a download would throw, so passing proves two renames did it.
        DitEngine.install(ctx, onProgress = {})
        assertTrue(DitEngine.isInstalled(ctx))
        assertEquals(npuBytes, DitEngine.file(ctx).length())
        assertEquals("the GPU engine kept", gpuBytes, File(dir, DitEngine.FILE + ".gpu").length())
        // And back on.
        Prefs.setLowRam(ctx, Prefs.KEY_DIT_GPU, true)
        DitEngine.install(ctx, onProgress = {})
        assertTrue(DitEngine.isInstalled(ctx))
        assertEquals(gpuBytes, DitEngine.file(ctx).length())
        assertEquals(npuBytes, File(dir, DitEngine.FILE + ".npu").length())
        DitEngine.delete(ctx)
        assertFalse("delete takes the spares too", File(dir, DitEngine.FILE + ".npu").exists())
    }

    /** ⭐ The user, 2026-11-14: Klein installed with the GPU switch on, then switched off, asked for a 4 GB Repair. */
    @Test
    fun aQ4LeftByTheGpuSwitchRunsOnTheNpuWithNoRepairAndIsNeverReclaimed() {
        DitGpu.openClForTest = true
        DeviceProbe.measuredForTest(caps(79).copy(soc = "SM8750"))
        val d = klein.ownDir(ctx).apply { mkdirs() }
        val q4 = klein.builds.first { b -> b.files.any { it.name == "dit.gguf" } && b.bytes == klein.builds.filter { x -> x.files.any { it.name == "dit.gguf" } }.minOf { it.bytes } }
        for (f in q4.files) sparseFile(File(d, f.name), f.bytes)
        // Off the route the S25's build is FP8, and only the Q4_0 is here: it stands in.
        assertTrue("FP8 is the preferred build", klein.missingPreferred(ctx) == listOf("dit.safetensors"))
        assertEquals("no Repair: the NPU runs the Q4_0", emptyList<String>(), klein.missing(ctx))
        ModelCatalog.reclaimObsolete(ctx)
        assertTrue("the stand-in is never reclaimed as the other build", File(d, "dit.gguf").length() == q4.files.first { it.name == "dit.gguf" }.bytes)
        // ⚠ The reverse holds: on the route only a Q4_0 runs, so an FP8 alone is not installed.
        File(d, "dit.gguf").delete()
        sparseFile(File(d, "dit.safetensors"), klein.files.first { it.name == "dit.safetensors" }.bytes)
        assertEquals(emptyList<String>(), klein.missing(ctx))
        Prefs.setLowRam(ctx, Prefs.KEY_DIT_GPU, true)
        assertEquals("the Adreno cannot run FP8", listOf("dit.gguf"), klein.missing(ctx))
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
        assertTrue(DitEngine.URL.contains("-gpu"))
        assertEquals("one archive, two hosts", DitEngine.URL.substringAfterLast('/'), DitEngine.HF_URL.substringAfterLast('/'))
    }

    @Test
    fun theLaunchEnvironmentCarriesTheDeviceTheCutsAndTheDecoder() {
        DitGpu.openClForTest = true
        Prefs.setLowRam(ctx, Prefs.KEY_DIT_GPU, true)
        val tae = DitGpu.taeFile(ctx, DitGpu.TAEF2).apply { parentFile?.mkdirs(); writeBytes(ByteArray(4)) }
        val env = DitGpu.launchEnv(ctx, klein, caps(79))
        assertEquals(DitGpu.BACKEND, env["NM_DIT_BACKEND"])
        // ⚠ An SM8450 (Adreno 730) is not the measured GPU: plain kernels, smaller dispatches (grey and an ANR there).
        assertEquals("5", env["GGML_OPENCL_MM_MAX_GFLOP"])
        assertEquals("0", env["GGML_OPENCL_Q4_0_DENSE_DP4A"])
        assertEquals("0", env["GGML_OPENCL_XMEM_SDPA"])
        // ⭐ The S25's Adreno 830 keeps the settings measured on it.
        val s25 = DitGpu.launchEnv(ctx, klein, caps(79).copy(soc = "SM8750"))
        assertEquals("15", s25["GGML_OPENCL_MM_MAX_GFLOP"])
        assertEquals("Klein keeps the xmem attention", "1", s25["GGML_OPENCL_XMEM_SDPA"])
        assertEquals("1", s25["GGML_OPENCL_Q4_0_DENSE_DP4A"])
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
