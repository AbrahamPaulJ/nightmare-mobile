package com.abrah.nightmare

import com.abrah.nightmare.BlankDiagnosis.Outcome
import com.abrah.nightmare.BlankDiagnosis.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ⭐ Diagnose's pure parts: reading a blank check, describing a picture, and what the legs conclude. */
class BlankDiagnosisTest {

    private fun leg(id: String) = BlankDiagnosis.LEGS.first { it.id == id }

    private fun run(vararg v: Pair<String, Verdict>) = v.map { (id, verdict) -> Outcome(leg(id), verdict, 1000, "") }

    @Test
    fun theBlankChecksColourComesBackOut() {
        // ⚠ The real message, from a tester's 8s Gen 4 report (1.6.175).
        val msg = "generate failed http 200. the engine returned a blank picture (every pixel #817E81). The render failed"
        assertEquals("#817E81", BlankDiagnosis.flatColour(msg))
        assertEquals(null, BlankDiagnosis.flatColour("the NPU stopped responding while sampling"))
    }

    @Test
    fun theLegsAreTheControlThenOneChangeEach() {
        assertEquals("baseline", BlankDiagnosis.LEGS.first().id)
        assertTrue(BlankDiagnosis.LEGS.first().env.isEmpty())
        assertTrue(BlankDiagnosis.LEGS.drop(1).all { it.env.size == 1 })
        assertEquals(BlankDiagnosis.LEGS.size, BlankDiagnosis.LEGS.map { it.id }.toSet().size)
    }

    @Test
    fun aPictureIsToldFromAFlatColour() {
        val w = 16
        val flat = BlankDiagnosis.describe(IntArray(w * w) { 0xFF808080.toInt() }, w, w)
        assertTrue(flat, "spread 0.0" in flat)
        val ramp = BlankDiagnosis.describe(IntArray(w * w) { i -> val g = (i % w) * 16; (0xFF shl 24) or (g shl 16) or (g shl 8) or g }, w, w)
        assertTrue(ramp, !ramp.contains("spread 0.0"))
        // 64 thumbnail bytes, left dark and right light.
        val thumb = ramp.substringAfter("8x8 ")
        assertEquals(128, thumb.length)
        assertTrue(thumb.take(2).toInt(16) < thumb.substring(14, 16).toInt(16))
    }

    @Test
    fun aCleanControlMeansNothingToFind() {
        val c = BlankDiagnosis.conclusion(run("baseline" to Verdict.CLEAN))
        assertTrue(c, c.startsWith("Not reproduced"))
    }

    @Test
    fun itNamesThePartAndTheOpChangesThatFixIt() {
        val c = BlankDiagnosis.conclusion(
            run(
                "baseline" to Verdict.FLAT, "te_cpu" to Verdict.FLAT, "dit_cpu" to Verdict.CLEAN,
                "vae_cpu" to Verdict.FLAT, "hvx_matmul" to Verdict.CLEAN, "no_fusion" to Verdict.FLAT,
                "cpu_norm" to Verdict.FLAT,
            ),
        )
        assertTrue(c, c.startsWith("The DiT on the NPU makes it flat."))
        assertTrue(c, "Clean with: matmuls on HVX only (no HMX)." in c)
        val te = BlankDiagnosis.conclusion(run("baseline" to Verdict.FLAT, "te_cpu" to Verdict.CLEAN, "dit_cpu" to Verdict.FLAT))
        assertTrue(te, te.startsWith("The text encoder on the NPU makes it flat.") && "No single op change" in te)
    }

    @Test
    fun theReportCarriesEveryLegAndTheConclusion() {
        val r = BlankDiagnosis.report("Z-Image Turbo", run("baseline" to Verdict.FLAT, "te_cpu" to Verdict.CLEAN))
        assertTrue(r, r.lines().first().startsWith("diagnosis (2 of ${BlankDiagnosis.LEGS.size} legs): Z-Image Turbo, 256x256"))
        assertTrue(r, r.lines().any { it.trim().startsWith("te_cpu") && "clean" in it })
        assertTrue(r, r.lines().last().startsWith("conclusion: The text encoder"))
    }

    @Test
    fun staticIsNoiseAndAPictureIsNot() {
        // ⚠ Measured on real renders (0.06-0.17) and a tester's Iris static (0.82-1.01): [BlankDiagnosis.NOISE_GRAIN].
        val w = 64
        val rnd = java.util.Random(1)
        val static = IntArray(w * w) { (0xFF shl 24) or rnd.nextInt(0x1000000) }
        assertEquals(Verdict.NOISE, BlankDiagnosis.verdictOf(static, w, w))
        val smooth = IntArray(w * w) { i -> val g = (i % w) * 4; (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
        assertEquals(Verdict.CLEAN, BlankDiagnosis.verdictOf(smooth, w, w))
        // ⚠ The control: a flat colour has no grain at all, and is no noise.
        assertEquals(Verdict.CLEAN, BlankDiagnosis.verdictOf(IntArray(w * w) { 0xFF808080.toInt() }, w, w))
        assertTrue(BlankDiagnosis.describe(static, w, w).contains("grain 1."))
    }

    @Test
    fun noiseKeepsTheShippedLaunchAndSkipsTheDecodeLegs() {
        val noise = BlankDiagnosis.legsAfter(Verdict.NOISE, shipped = true, hasVae = true)
        assertTrue(noise.none { it.id in BlankDiagnosis.DECODE_ONLY || it.id == "baseline" })
        assertTrue(noise.any { it.id == "te_cpu" } && noise.any { it.id == "dit_cpu" })
        // ⭐ A clean shipped render (HMX off) goes on to the CULPRIT legs: HMX on, the decode on the CPU in every one.
        val culprit = BlankDiagnosis.legsAfter(Verdict.CLEAN, shipped = true, hasVae = true)
        assertEquals("vae_cpu", culprit.first().id)
        assertTrue(culprit.all { it.env["NM_DIT_BACKEND"]?.endsWith("vae=CPU") == true })
        assertTrue(culprit.none { it.id in setOf("baseline", "no_hmx", "cpu_conv", "cpu_group_norm", "cpu_pad") })
        assertEquals("CPU_VAE plus its op group", "FLASH_ATTN_EXT|SOFT_MAX", culprit.first { it.id == "cpu_attention" }.env[BlankDiagnosis.OPFILTER_ADD])
        // Iris (no VAE): never the decode legs; a clean control elsewhere: nothing to find.
        val iris = BlankDiagnosis.legsAfter(Verdict.NOISE, shipped = false, hasVae = false)
        assertTrue(iris.isNotEmpty() && iris.none { it.id in BlankDiagnosis.DECODE_ONLY })
        assertEquals("diffusion=HTP0,te=CPU,vae=HTP0", iris.first { it.id == "te_cpu" }.env["NM_DIT_BACKEND"])
        assertTrue(BlankDiagnosis.legsAfter(Verdict.CLEAN, shipped = false, hasVae = true).isEmpty())
        assertEquals(BlankDiagnosis.LEGS.drop(1), BlankDiagnosis.legsAfter(Verdict.FLAT, shipped = false, hasVae = true))
    }

    @Test
    fun aNoisyShippedControlNamesThePartThatMakesNoise() {
        val shipped = Outcome(BlankDiagnosis.SHIPPED, Verdict.NOISE, 1000, "")
        val c = BlankDiagnosis.conclusion(listOf(shipped) + run("te_cpu" to Verdict.NOISE, "dit_cpu" to Verdict.CLEAN, "no_fusion" to Verdict.CLEAN))
        assertTrue(c, c.startsWith("The DiT on the NPU makes it noise.") && "Clean with: op fusions off." in c)
        val r = BlankDiagnosis.Run(listOf(shipped), 11, 1024).report("Iris 3B Turbo")
        assertTrue(r, r.lines().first() == "diagnosis (1 of 11 legs): Iris 3B Turbo, 1024x1024, 2 steps, seed 42")
    }

    @Test
    fun theCulpritLegsNameTheOpGroupToKeepOffHmx() {
        val legs = BlankDiagnosis.legsAfter(Verdict.CLEAN, shipped = true, hasVae = true).associateBy { it.id }
        fun o(id: String, v: Verdict) = Outcome(legs.getValue(id), v, 1000, "")
        val shipped = Outcome(BlankDiagnosis.SHIPPED, Verdict.CLEAN, 1000, "")
        val c = BlankDiagnosis.conclusion(
            listOf(shipped, o("vae_cpu", Verdict.NOISE), o("te_cpu", Verdict.NOISE), o("dit_cpu", Verdict.CLEAN),
                o("cpu_attention", Verdict.CLEAN), o("cpu_norm", Verdict.NOISE)),
        )
        assertTrue(c, c.startsWith("With HMX the DiT makes it noise.") && "Clean with HMX on and: attention on the CPU." in c)
        // ⚠ The control: HMX on and clean means only the decode needed it off.
        val fine = BlankDiagnosis.conclusion(listOf(shipped, o("vae_cpu", Verdict.CLEAN)))
        assertTrue(fine, fine.startsWith("With HMX the DiT is right here"))
    }
}
