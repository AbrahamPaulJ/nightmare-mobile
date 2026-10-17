package com.abrah.nightmare

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ⭐⭐ The `loras` string, both directions.
 *
 * ⚠⚠ [LoraSpec] is the ONE tokeniser: the picker sheet, the inspector's
 * summary line and [SdSampler.parseLoras] all read it. So a roundtrip that
 * loses a name or moves a decimal point is three bugs, not one.
 */
class LoraSpecTest {

    private val original = Locale.getDefault()

    @After fun restore() = Locale.setDefault(original)

    @Test
    fun blankIsNothing() {
        assertEquals(emptyList<LoraSpec.Entry>(), LoraSpec.parse(null))
        assertEquals(emptyList<LoraSpec.Entry>(), LoraSpec.parse("  "))
        assertEquals(emptyList<LoraSpec.Entry>(), LoraSpec.parse(" , , "))
        assertEquals("", LoraSpec.format(emptyList()))
    }

    /** ⚠ Full strength is written BARE, so an untouched slider adds no noise. */
    @Test
    fun fullStrengthIsNotWritten() {
        assertEquals("a.safetensors", LoraSpec.format(listOf(LoraSpec.Entry("a.safetensors", 1.0))))
    }

    @Test
    fun roundtrip() {
        val spec = "a.safetensors@0.8, b.safetensors, c.safetensors@1.65"
        val parsed = LoraSpec.parse(spec)
        assertEquals(listOf("a.safetensors", "b.safetensors", "c.safetensors"), parsed.map { it.name })
        assertEquals(listOf(0.8, 1.0, 1.65), parsed.map { it.strength })
        assertEquals(spec, LoraSpec.format(parsed))
    }

    /** ⚠ The LAST `@` — a filename may contain one, a strength never does. */
    @Test
    fun anAtInsideTheNameSurvives() {
        assertEquals(
            listOf(LoraSpec.Entry("v1@2.safetensors", 1.0)),
            LoraSpec.parse("v1@2.safetensors"),
        )
    }

    /** ⚠⚠ A `../` becomes a bare name here, so nothing downstream handles one. */
    @Test
    fun aPathIsReducedToItsName() {
        assertEquals("secret.safetensors", LoraSpec.parse("../secret.safetensors").single().name)
        assertEquals("x.safetensors", LoraSpec.parse("/data/local/tmp/x.safetensors").single().name)
    }

    /**
     * ⚠⚠⚠ **The separator is a comma, and half of Europe writes decimals with
     * one.** On a German phone a `%.2f` without [Locale.ROOT] turns 0.8 into
     * `0,80`, and one slider drag would split one LoRA into two names that do
     * not exist. This is the whole reason [LoraSpec.number] pins the locale.
     */
    @Test
    fun aCommaDecimalLocaleCannotSplitTheSpec() {
        Locale.setDefault(Locale.GERMANY)
        val spec = LoraSpec.format(listOf(LoraSpec.Entry("a.safetensors", 0.8)))
        assertEquals("a.safetensors@0.8", spec)
        assertEquals(1, LoraSpec.parse(spec).size)
    }

    /** ⚠ Trailing zeros go: the slider's 0.80 reads as 0.8. */
    @Test
    fun trailingZerosAreTrimmed() {
        assertEquals("0.8", LoraSpec.number(0.80))
        assertEquals("2", LoraSpec.number(2.0))
        assertEquals("0.05", LoraSpec.number(0.05))
        assertEquals("0", LoraSpec.number(0.0))
    }

    /** ⭐ GitHub #51: -2..2 where a negative weight works, 0..2 elsewhere; a drag near 0 is 0; typed to 0.01. */
    @Test
    fun negativeWeightsWhereTheEngineTakesThem() {
        assertEquals(-2.0, LoraSpec.range(true).start, 0.0)
        assertEquals(0.0, LoraSpec.range(false).start, 0.0)
        assertEquals(0.0, LoraSpec.snapped(0.04, true), 0.0)
        assertEquals(0.0, LoraSpec.snapped(-0.05, true), 0.0)
        assertEquals(-0.5, LoraSpec.snapped(-0.52, true), 1e-9)
        assertEquals(0.0, LoraSpec.snapped(-0.5, false), 0.0)
        assertEquals(0.05, LoraSpec.snapped(0.05, false), 1e-9)
        assertEquals(0.35, LoraSpec.typed("0,35", true)!!, 1e-9)
        assertEquals(-0.5, LoraSpec.typed(" -0.5 ", true)!!, 1e-9)
        assertEquals(0.0, LoraSpec.typed("-0.5", false)!!, 0.0)
        assertEquals(2.0, LoraSpec.typed("9", true)!!, 0.0)
        assertEquals(1.23, LoraSpec.typed("1.234", true)!!, 1e-9)
        assertEquals(null, LoraSpec.typed("abc", true))
        // ⚠ A negative weight survives the param string both ways.
        assertEquals(-0.5, LoraSpec.parse(LoraSpec.format(listOf(LoraSpec.Entry("a.safetensors", -0.5)))).single().strength, 1e-9)
    }
}
