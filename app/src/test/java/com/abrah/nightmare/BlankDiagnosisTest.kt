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

    @Test
    fun aCulpritLegIsJudgedAgainstTheShippedPicture() {
        // ⚠ The real lines (an 8s Gen 4, Z-Image, 1.6.195): every HMX leg "clean" by grain, and the report said
        // "only the decode needed HMX off". The shipped picture, the seed's noise decoded (nine legs byte for
        // byte), and the DiT on the CPU with the text encoder on HMX (a picture, another one).
        val legs = BlankDiagnosis.legsAfter(Verdict.CLEAN, shipped = true, hasVae = true).associateBy { it.id }
        fun o(id: String, thumb: String) = Outcome(legs.getValue(id), Verdict.CLEAN, 1000, "mean 128, spread 55.5, grain 0.34, 8x8 $thumb")
        val shipped = Outcome(
            BlankDiagnosis.SHIPPED, Verdict.CLEAN, 1000,
            "mean 144, spread 55.3, grain 0.05, 8x8 6e747573777674718b8a8c90938e908eb8b5aba4c37ca9aec8beb0a2653792c4cec98863373193caced39846342e5271d7d2cf6d27214a6bd1d0ccc1b5aeaab4",
        )
        val seedNoise = "84897c757d7c7975777d7b747c79817d7d847e778283757d86808b81837b83767982787b898581857a798282788a7a787c897a8e818384887d857f827e758494"
        val judged = listOf(
            o("vae_cpu", seedNoise), o("te_cpu", seedNoise), o("cpu_attention", seedNoise),
            o("dit_cpu", "0c2615061a110c020d251a0d1f110f021d4530112f2522052457431f4e424f0f214e3b245147461c1c322921433c321e92ad9fa7a4a8a6909da89fa0a89ba1b1"),
            o("hvx_matmul", "7170707875757b6c77687676767561776a6d78786a8f88777a74756e77827b876f71887b6574766c707481706c727a717379716c7681747a6a636f7071807e73"),
        ).map { BlankDiagnosis.against(shipped, it) }
        assertTrue(judged.map { it.verdict }.toString(), judged.all { it.verdict == Verdict.WRONG })
        val c = BlankDiagnosis.conclusion(listOf(shipped) + judged)
        assertEquals("With HMX the picture is wrong, whichever part is on the CPU. No single op group off HMX fixes it: keep HMX off.", c)
        // ⚠ The control: the shipped picture itself, a few greys off, is the same picture.
        val near = shipped.detail.substringAfter("8x8 ").chunked(2).joinToString("") { "%02x".format((it.toInt(16) + 5).coerceAtMost(255)) }
        assertEquals(Verdict.CLEAN, BlankDiagnosis.against(shipped, o("cpu_norm", near)).verdict)
    }
}
