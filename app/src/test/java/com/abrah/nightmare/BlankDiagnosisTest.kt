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
}
