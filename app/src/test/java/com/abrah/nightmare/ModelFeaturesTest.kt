package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ⭐ [ModelFeatures] against the `swap_features.json` the phone's conversions really carry
 * (2026-10-07: Illustrious inpaint `["inp"]`, Juggernaut `["lora"]`, SD 1.5 v3 all four)
 * and npuforge 1.0.12's schema 2. ⚠ Robolectric for the real `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
class ModelFeaturesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(vararg files: Pair<String, String>) = tmp.newFolder().apply {
        for ((n, t) in files) resolve(n).writeText(t)
    }

    @Test
    fun schemaOneListsWhatTheConversionKept() {
        val d = dir(ModelFeatures.FILE to """{"template":"sdxl_swap_v2","features":["inp"]}""")
        assertEquals(setOf("inp"), ModelFeatures.of(d, Family.SDXL_SWAP))
    }

    @Test
    fun schemaTwoReadsFeaturesNotTheDetailKeys() {
        val d = dir(
            ModelFeatures.FILE to """{"schema":2,"producer":"npuforge 1.0.12-preview","template":"sdxl_swap_v2",""" +
                """"family":"sdxl","kind":"swap","prediction":"eps","text_tokens":462,"size":1024,"soc":"SM8750",""" +
                """"detail":{"lora":{"targets":"lora_targets.json","rank":64},"cn":{"residuals":10,"hint":1024}},""" +
                """"features":["lora","cn"]}""",
        )
        assertEquals(setOf("lora", "cn"), ModelFeatures.of(d, Family.SDXL_SWAP))
    }

    @Test
    fun anOlderSd15SwapWithoutTheFileIsInferredFromItsIpMarker() {
        assertEquals(setOf("lora", "cn"), ModelFeatures.of(dir(), Family.SD15_SWAP))
        assertEquals(
            setOf("lora", "cn", "ip"),
            ModelFeatures.of(dir(IpAdapter.TARGETS_FILE to "[]"), Family.SD15_SWAP),
        )
    }

    @Test
    fun aBrokenFileFallsBackRatherThanThrowing() {
        assertNull(ModelFeatures.read(dir(ModelFeatures.FILE to "{not json")))
        assertEquals(setOf("lora"), ModelFeatures.of(dir(ModelFeatures.FILE to "{not json"), Family.SDXL_SWAP))
    }

    @Test
    fun aMissingFeatureSaysWhyAndAPresentOneSaysNothing() {
        val spec = ModelSpec(
            id = "x", label = "x", builds = emptyList(), prompt = "", negative = "",
            family = Family.SDXL_SWAP, features = setOf("lora"),
        )
        assertNull(ModelFeatures.missingReason(spec, ModelFeatures.LORA))
        assertTrue(ModelFeatures.missingReason(spec, ModelFeatures.CONTROLNET)!!.contains("no ControlNet"))
        // ⚠ Not a Swap family: no such control, so nothing to explain.
        assertNull(ModelFeatures.missingReason(spec.copy(family = Family.SDXL), ModelFeatures.CONTROLNET))
    }

    /**
     * ⭐ The hosted Illustrious XL Swap gained ControlNet + IP-Adapter in 1.6.122. A copy
     * downloaded before keeps a LoRA-only graph, and its own file wins over the spec: the
     * tile stays dim and says to download again — not to convert, which a built-in cannot.
     */
    @Test
    fun anOldDownloadOfABuiltInIsToldToDownloadAgain() {
        val illustrious = ModelCatalog.byId("illustrious_xl_swap")!!
        assertEquals(setOf("lora", "cn", "ip"), illustrious.featureSet)
        try {
            ModelFeatures.setInstalledForTest(illustrious.id, setOf("lora"))
            assertEquals(setOf("lora"), illustrious.featureSet)
            assertTrue(ModelFeatures.missingReason(illustrious, ModelFeatures.CONTROLNET)!!.contains("download it again"))
            // ⚠ Juggernaut's hosted zip is still LoRA-only: converting again is the answer there.
            val juggernaut = ModelCatalog.byId("juggernaut_xl_swap")!!
            assertTrue(ModelFeatures.missingReason(juggernaut, ModelFeatures.CONTROLNET)!!.contains("Convert it again"))
        } finally {
            ModelFeatures.setInstalledForTest(illustrious.id, null)
        }
    }
}
