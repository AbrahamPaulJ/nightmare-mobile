package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalog is data, and these are the invariants that make it usable data.
 *
 * ⚠ The byte counts cannot be checked here — they are facts about a remote
 * repo. What CAN be checked is that nothing is zero or duplicated, which is how
 * a transcription slip actually shows up: [ModelInstaller] compares the finished
 * download against `bytes`, so a wrong number fails every install of that model
 * with "size mismatch" and a right-looking file on disk.
 */
class ModelCatalogTest {

    @Test
    fun everyModelHasADistinctIdAndArchive() {
        val ids = ModelCatalog.all.map { it.id }
        assertEquals("duplicate model id", ids.size, ids.toSet().size)
        // ⚠ Across EVERY tier, not just the preferred one: two entries sharing
        // a `_min` archive would download one model's weights under another's
        // name, and only on the phones that take that tier.
        val archives = ModelCatalog.all.flatMap { m -> m.builds.map { it.archive } }
        assertEquals("duplicate archive", archives.size, archives.toSet().size)
    }

    @Test
    fun everyModelHasAPlausibleSize() {
        // An SD1.5 QNN build is ~1 GB; an SDXL one ~3.7 GB. A zero or a wildly
        // wrong figure is a transcription slip, and the installer enforces it as
        // a failure -- so the bound is per family, not one loose range that
        // would wave a mistyped SD1.5 size through as a small SDXL.
        for (spec in ModelCatalog.sd15Models) {
            for (b in spec.builds) {
                assertTrue("${spec.id} ${b.tier} ${b.bytes}", b.bytes in 900_000_000..1_500_000_000)
            }
        }
        for (spec in ModelCatalog.sdxlModels) {
            for (b in spec.builds) {
                assertTrue("${spec.id} ${b.tier} ${b.bytes}", b.bytes in 3_000_000_000..4_500_000_000)
            }
        }
    }

    /** ⚠ The tier is what decides which HTP the archive runs on. */
    @Test
    fun everyArchiveCarriesItsOwnTier() {
        // ⚠ `builtIn`, not `all`: this is about the archives WE publish, and an
        // imported model has none. `all` also varies with what is on the disk
        // of whoever is running the suite.
        for (spec in ModelCatalog.builtIn) {
            for (b in spec.builds) {
                assertTrue("${b.archive} is not ${b.tier}", b.archive.endsWith("${b.tier}.zip"))
            }
        }
        assertEquals(ModelCatalog.TIER, ModelCatalog.builtIn.first().best!!.tier)
    }

    /**
     * ⚠⚠ SDXL is published `_8gen3` only, which is a v75 context. The tier and
     * the arch gate are two statements of ONE fact, and the pair drifting apart
     * is how a 3.7 GB download reaches a phone that cannot load it.
     */
    @Test
    fun theArchGateAgreesWithTheTier() {
        for (spec in ModelCatalog.sdxlModels) {
            assertEquals("${spec.id} builds", 1, spec.builds.size)
            assertEquals("${spec.id} tier", ModelCatalog.SDXL_TIER, spec.best!!.tier)
            assertEquals("${spec.id} minArch", 75, spec.best!!.minArch)
        }
        for (spec in ModelCatalog.sd15Models) {
            assertEquals("${spec.id} tiers", 3, spec.builds.size)
            assertEquals("${spec.id} tier", ModelCatalog.TIER, spec.best!!.tier)
        }
    }

    @Test
    fun theDefaultModelIsInTheCatalog() {
        assertTrue("V1_MODEL is not a catalog entry", ModelCatalog.byId(V1_MODEL) != null)
        assertEquals("V1_MODEL should be listed first", V1_MODEL, ModelCatalog.all.first().id)
    }

    /**
     * ⚠⚠ The extractor matches on BASENAME, and this is the check that it keeps
     * the right things. Getting it wrong in the permissive direction unpacks a
     * gigabyte of files nothing reads; in the strict direction it silently
     * produces a model that is missing a file, which fails at first render.
     */
    @Test
    fun theExtractorKeepsTheRequiredFilesAndThePatches() {
        for (spec in ModelCatalog.all) {
            for (name in spec.requiredFiles) {
                assertTrue("${spec.id}: $name must be kept", spec.wanted(name))
            }
            // Every resolution patch, whatever it is called -- naming them
            // individually is how four rectangular patches per archive came to
            // be thrown away unnoticed in DreamUI.
            for (p in listOf("768.patch", "512x768.patch", "1024x768.patch")) {
                assertTrue("${spec.id}: $p must be kept", spec.wanted(p))
            }
            for (junk in listOf("README.md", "config.json", "unet.onnx", "")) {
                assertFalse("${spec.id}: $junk must not be kept", spec.wanted(junk))
            }
        }
    }

    /**
     * ⚠⚠ The one that would fail silently. `clip_2.mnn.weight` is CLIP-G's
     * EXTERNAL weight file -- MNN splits the bigger encoder into graph plus
     * weights, and `clip_2.mnn` will not load without it. Drop it and the
     * install passes every size check and dies at the first prompt.
     */
    @Test
    fun sdxlKeepsBothEncodersAndClipGsExternalWeights() {
        val spec = ModelCatalog.sdxlModels.first()
        for (name in listOf(
            "clip.mnn", "clip_2.mnn", "clip_2.mnn.weight",
            "pos_emb.bin", "token_emb.bin", "pos_emb_2.bin", "token_emb_2.bin",
        )) {
            assertTrue("$name must be required", name in spec.requiredFiles)
            assertTrue("$name must be extracted", spec.wanted(name))
        }
        // ⚠ And NOT SD1.5's single encoder, which is a different file entirely.
        assertFalse("clip_v2.mnn" in spec.requiredFiles)
    }

    /** ⚠ img2img is a graph edge here, so the encoder is required in BOTH families. */
    @Test
    fun everyFamilyRequiresTheVaeEncoder() {
        for (spec in ModelCatalog.all) {
            assertTrue(spec.id, "vae_encoder.bin" in spec.requiredFiles)
        }
    }

    /**
     * ⭐ SDXL lives in a DIFFERENT repository. Assuming one base URL is how a
     * second family produces 404s that read as network trouble.
     */
    @Test
    fun eachFamilyFetchesFromItsOwnRepository() {
        for (spec in ModelCatalog.sd15Models) {
            for (b in spec.builds) {
                assertTrue(spec.url(b), spec.url(b).startsWith(ModelCatalog.SD15_BASE_URL))
            }
        }
        for (spec in ModelCatalog.sdxlModels) {
            for (b in spec.builds) {
                assertTrue(spec.url(b), spec.url(b).startsWith(ModelCatalog.SDXL_BASE_URL))
            }
        }
    }

    /**
     * ⚠⚠ 1024, and `--lowram`, on every SDXL entry. The resolution is not a
     * default that can be overridden: the backend forces 1024 inside its request
     * parser for `--type sdxl` whatever the client sends, so a 512 here renders
     * 1024 and reports success. And `--lowram` is not tuning -- a ~3x UNet at
     * 1024 does not fit beside its VAE and two encoders.
     */
    @Test
    fun everySdxlEntryIs1024AndLowram() {
        for (spec in ModelCatalog.sdxlModels) {
            assertEquals("${spec.id} family", Family.SDXL, spec.family)
            assertEquals("${spec.id} --type", "sdxl", spec.backendType)
            assertEquals("${spec.id} native", Res(1024, 1024), spec.native)
            assertTrue("${spec.id} needs --lowram", spec.lowram)
        }
    }

    // ---- family, runtime, backend type, resolution ------------------------

    /** Every SD 1.5 entry is still exactly that: one `--type`, one size. */
    @Test
    fun everySd15EntryIsSd15NpuAt512() {
        for (spec in ModelCatalog.sd15Models) {
            assertEquals("${spec.id} family", Family.SD15, spec.family)
            assertEquals("${spec.id} runtime", Runtime.NPU, spec.runtime)
            assertEquals("${spec.id} --type", ModelCatalog.SD15_NPU, spec.backendType)
            assertEquals("${spec.id} native size", Res(512, 512), spec.native)
            assertFalse("${spec.id} must not ask for --lowram", spec.lowram)
        }
    }

    /**
     * ⚠ Every family in the catalogue must be one the BACKEND knows, and one
     * whose files we actually list. A family added as data with no required-file
     * list is the failure that reports "not installed" forever.
     */
    @Test
    fun everyEntryBelongsToAFamilyTheBackendAndTheInstallerBothKnow() {
        for (spec in ModelCatalog.all) {
            assertTrue(
                "${spec.id} --type ${spec.backendType}",
                spec.backendType in listOf("sd15npu", "sdxl"),
            )
            assertTrue("${spec.id} has no required files", spec.requiredFiles.isNotEmpty())
            assertTrue("${spec.id} has no resolution", spec.resolutions.isNotEmpty())
        }
    }

    /**
     * ⭐⭐ The launch and every `contextKey()` read ONE function, so they cannot
     * disagree. ⚠ The mismatch they would produce is silent: `sdxl` forces 1024
     * inside the backend's request parser whatever the client sends.
     */
    @Test
    fun theBackendTypeComesFromTheCatalogEntry() {
        assertEquals(ModelCatalog.SD15_NPU, ModelCatalog.backendTypeOf(V1_MODEL))
        assertEquals(Res(512, 512), ModelCatalog.resolutionOf(V1_MODEL))
    }

    /**
     * ⚠ An id with no catalogue entry keeps today's answer rather than throwing:
     * a model directory pushed by hand over adb is a real developer path, and
     * the tests and harness fixtures use ids like `"m"`.
     */
    @Test
    fun anUncataloguedIdFallsBackToTheSd15Default() {
        assertEquals(ModelCatalog.SD15_NPU, ModelCatalog.backendTypeOf("pushed-by-hand"))
        assertEquals(Res(512, 512), ModelCatalog.resolutionOf("pushed-by-hand"))
    }

    // ---- which build a given phone gets ----------------------------------

    private fun caps(arch: Int, vtcm: Int) =
        DeviceProbe.Caps(arch = arch, vtcmMb = vtcm, measured = true, soc = "TEST")

    /**
     * ⭐⭐ The whole point of the tiers. ⚠ Contexts run FORWARD ONLY, so an
     * 8 Gen 1 (v69, 8 MB) must NOT be handed the v73 `_8gen2` build: it would
     * fail at load after a gigabyte, not run slowly.
     */
    @Test
    fun eachHtpGetsTheBestBuildItCanActuallyLoad() {
        val m = ModelCatalog.byId(V1_MODEL)!!
        assertEquals(ModelCatalog.TIER, m.buildFor(caps(79, 8))!!.tier)   // 8 Elite
        assertEquals(ModelCatalog.TIER, m.buildFor(caps(73, 8))!!.tier)   // 8 Gen 2
        assertEquals(ModelCatalog.TIER_8GEN1, m.buildFor(caps(69, 8))!!.tier) // 8 Gen 1
        assertEquals(ModelCatalog.TIER_MIN, m.buildFor(caps(68, 2))!!.tier)   // 888
    }

    /**
     * ⚠⚠ VTCM is a gate of its own and is NOT implied by the arch. The 8s Gen 3
     * is newer than an 8 Gen 2, reports a v73 HTP, and has under 8 MB — so it
     * takes `_min` despite an arch that would otherwise allow `_8gen2`.
     */
    @Test
    fun aBigArchWithSmallVtcmStillFallsToMin() {
        val m = ModelCatalog.byId(V1_MODEL)!!
        assertEquals(ModelCatalog.TIER_MIN, m.buildFor(caps(73, 2))!!.tier)
    }

    /**
     * ⚠⚠ SDXL publishes `_8gen3` only, so below v75 the honest answer is NONE.
     * A fallback here would spend 3.5 GB on a file the chip rejects at load.
     */
    @Test
    fun sdxlHasNothingToOfferAnOlderHtp() {
        val x = ModelCatalog.sdxlModels.first()
        assertNull(x.buildFor(caps(73, 8)))
        assertNull(x.buildFor(caps(69, 8)))
        assertNotNull(x.buildFor(caps(75, 8)))
        assertNotNull(x.buildFor(caps(79, 8)))
    }

    /**
     * ⚠ Every arch a build can demand must be one the APK ships libraries for,
     * or the device fails NPU init before the model is even reached — with an
     * error naming neither.
     */
    @Test
    fun everyBuildsArchIsOneWeStageLibrariesFor() {
        for (spec in ModelCatalog.all) {
            for (b in spec.builds) {
                assertTrue(
                    "${spec.id} ${b.tier} wants v${b.minArch}, not staged",
                    b.minArch in DeviceProbe.STAGED_ARCHES,
                )
            }
        }
    }

    /** ⚠ Best-first is [ModelSpec.buildFor]'s contract, not a nicety. */
    @Test
    fun buildsAreOrderedBestFirst() {
        for (spec in ModelCatalog.all) {
            val arches = spec.builds.map { it.minArch }
            assertEquals("${spec.id} builds out of order", arches.sortedDescending(), arches)
        }
    }

    /** [ModelSpec.native] is the FIRST resolution, not the smallest or the last. */
    @Test
    fun nativeIsTheFirstResolutionListed() {
        val spec = ModelCatalog.all.first().copy(
            resolutions = listOf(Res(1024, 1024), Res(512, 512)),
        )
        assertEquals(Res(1024, 1024), spec.native)
    }
}
