package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ⭐ FLUX.1 read as Klein 4B until it had a family of its own (S#7926, `docs/IMPORTS.md`). Names off city96's GGUF header. */
class Flux1FamilyTest {
    private fun header(vararg names: String) = names.joinToString(",", "{", "}") { "\"$it.weight\":{}" }

    private val flux1Dev = header(
        "double_blocks.0.img_attn.qkv", "single_blocks.0.linear1", "img_in", "txt_in", "time_in.in_layer", "vector_in.in_layer",
        "guidance_in.in_layer", "final_layer.linear",
    )

    @Test
    fun devReadsAsFlux1() = assertEquals(Family.FLUX1, CustomModels.ditFamilyOf(flux1Dev))

    /** schnell: no guidance embedding, the same CLIP input. */
    @Test
    fun schnellReadsAsFlux1() = assertEquals(
        Family.FLUX1,
        CustomModels.ditFamilyOf(header("double_blocks.0.img_attn.qkv", "single_blocks.0.linear1", "img_in", "txt_in", "time_in.in_layer", "vector_in.in_layer")),
    )

    /** ⚠ The control: a real Klein has `time_in.` and `guidance_in.` but no `vector_in.`, and must stay FLUX.2. */
    @Test
    fun kleinStaysFlux2() = assertEquals(
        Family.FLUX2,
        CustomModels.ditFamilyOf(header("double_blocks.0.img_attn.qkv", "single_blocks.0.linear1", "img_in", "txt_in", "time_in.in_layer", "guidance_in.in_layer")),
    )

    @Test
    fun theBuiltInCarriesItsEncoders() {
        val spec = ModelCatalog.byId("flux1_dev")!!
        assertEquals(Family.FLUX1, spec.family)
        assertEquals(20, spec.steps)
        val names = spec.active.map { it.name }
        for (f in listOf("dit.gguf", "llm.gguf", ModelCatalog.FLUX1_CLIP_L, "vae.safetensors", "tokenizer.json")) {
            assertTrue("$f in $names", f in names)
        }
        // ⚠ Q4_0, the NPU's matmul format: Q6_K is ~154 s a step.
        assertTrue(spec.active.first { it.name == "dit.gguf" }.url.contains("Q4_0"))
        assertNull(spec.partsFrom)
    }

    /**
     * ⚠ Krea's thresholds sent it whole with 7.3 GB free, and lmkd took the app 2 minutes in, with no error (S25, 2026-11-09).
     * Its T5 sits on the CPU beside the DiT, so only a phone that holds all of it runs whole.
     */
    @Test
    fun itStreamsUnlessThePhoneHoldsAllOfIt() {
        val spec = ModelCatalog.byId("flux1_dev")!!
        val gb = 1_000_000_000L
        assertEquals("the 12 GB S25, freshly cleared", "4", BackendProcess.kreaBudget(spec, 12 * gb, 7_300_000_000L))
        assertEquals("a 16 GB phone that looks roomy", "4", BackendProcess.kreaBudget(spec, 16 * gb, 11 * gb))
        assertNull("a 24 GB phone", BackendProcess.kreaBudget(spec, 24 * gb, 18 * gb))
    }

    /** ⭐ The 8 Gen 1 trial's chip test ([Prefs.v69Npu], off by default): the CPU route stays the default there. */
    @Test
    fun theTrialIsOffByDefault() {
        val gen1 = DeviceProbe.Caps(arch = 69, vtcmMb = 8, measured = true, soc = "SM8450")
        val elite = DeviceProbe.Caps(arch = 79, vtcmMb = 8, measured = true, soc = "SM8750")
        assertTrue(BackendProcess.belowDitNpu(gen1))
        assertTrue(BackendProcess.ditOnCpu(gen1))
        assertFalse(BackendProcess.belowDitNpu(elite))
        assertFalse(BackendProcess.ditOnCpu(elite))
    }
}
