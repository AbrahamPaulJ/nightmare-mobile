package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠⚠ These cover the part that decides HOW MANY RENDERS a person spends. A
 * cartesian expansion off by one is minutes on a phone, and it never throws —
 * which is why it is arithmetic in a Compose-free file with a test, like
 * `Framing.kt`.
 */
class BatchTest {

    @Test
    fun oneAxisIsOneRunPerValue() {
        val spec = BatchSpec(listOf(BatchAxis("sample", "seed", listOf("1", "2", "3"))))
        assertEquals(3, spec.runCount)
        assertEquals(
            listOf("1", "2", "3"),
            spec.expand().map { it.getValue("sample").getValue("seed") },
        )
    }

    /** ⚠ Two axes are a PRODUCT, not a zip. 4 seeds at 2 cfgs is 8 renders. */
    @Test
    fun twoAxesAreACartesianProduct() {
        val spec = BatchSpec(
            listOf(
                BatchAxis("sample", "seed", listOf("1", "2", "3", "4")),
                BatchAxis("sample", "cfg", listOf("1.5", "3")),
            )
        )
        assertEquals(8, spec.runCount)
        assertEquals(8, spec.expand().size)
        // Both params land on the same node without one clobbering the other.
        val first = spec.expand().first().getValue("sample")
        assertEquals("1", first.getValue("seed"))
        assertEquals("1.5", first.getValue("cfg"))
    }

    /**
     * ⭐⭐ The FIRST axis varies SLOWEST — the one the user thinks of as the
     * outer loop is the outer loop. §4's pathological case is alternating a
     * costly thing per iteration, and a sweep is where that gets written by
     * accident.
     */
    @Test
    fun theFirstAxisVariesSlowest() {
        val spec = BatchSpec(
            listOf(
                BatchAxis("sample", "seed", listOf("1", "2")),
                BatchAxis("sample", "cfg", listOf("a", "b")),
            )
        )
        assertEquals(
            listOf("1a", "1b", "2a", "2b"),
            spec.expand().map {
                it.getValue("sample").getValue("seed") + it.getValue("sample").getValue("cfg")
            },
        )
    }

    /** ⚠ An axis with no values is not "one run", it is a broken sweep. */
    @Test
    fun anEmptyAxisExpandsToNothing() {
        val spec = BatchSpec(listOf(BatchAxis("sample", "seed", emptyList())))
        assertTrue(spec.isEmpty)
        assertEquals(emptyList<Any>(), spec.expand())
    }

    /**
     * ⚠⚠ The context-key params are REFUSED, and the message says why. Varying
     * one costs a backend relaunch per iteration; the executor would then
     * refuse the graph outright with "needs 2 backend contexts", which
     * describes our roadmap rather than the user's sweep.
     */
    @Test
    fun theContextKeyCannotBeSwept() {
        for (p in listOf("model", "width", "height")) {
            val why = BatchSpec.refusalFor(p)
            assertTrue("$p must be refused", why != null && why.contains(p))
        }
        assertNull(BatchSpec.refusalFor("seed"))
        assertNull(BatchSpec.refusalFor("cfg"))
    }

    @Test
    fun commaListsAndRangesBothParse() {
        assertEquals(listOf("1", "2", "3"), BatchValues.parse("1, 2, 3"))
        assertEquals(listOf("1", "2", "3", "4"), BatchValues.parse("1..4"))
        assertEquals(listOf("1", "4", "7", "10"), BatchValues.parse("1..10 by 3"))
        // ⚠ Whole stays whole — a seed of "3.0" is not a seed.
        assertEquals(listOf("1", "2"), BatchValues.parse("1..2"))
        assertEquals(listOf("1.5", "2", "2.5"), BatchValues.parse("1.5..2.5 by 0.5"))
    }

    /**
     * ⚠⚠ Nonsense returns EMPTY rather than throwing, so the sheet shows
     * "0 runs" and the button stays disabled. A sweep that started and failed
     * on iteration one would have spent a render to say the same thing.
     */
    @Test
    fun nonsenseAndRunawayRangesYieldNothing() {
        assertEquals(emptyList<String>(), BatchValues.parse(""))
        assertEquals(emptyList<String>(), BatchValues.parse("4..1"))
        assertEquals(emptyList<String>(), BatchValues.parse("1..2 by 0"))
        // ⚠ A million iterations is one keystroke away, on a phone.
        assertEquals(emptyList<String>(), BatchValues.parse("1..100000"))
        assertEquals(emptyList<String>(), BatchValues.parse("1..10 by 0.001"))
    }

    /**
     * ⭐⭐⭐ **A seed sweep must not be the same sweep twice.**
     *
     * ⚠⚠ Reported by a user, 2026-09-13: seeds 1..10 run twice gave the same
     * ten pictures, so the second sweep said nothing. ⇒ [BatchSpec.rollSeeds].
     */
    @Test
    fun twoSweepsDrawDifferentSeeds() {
        val spec = BatchSpec(listOf(BatchAxis("s", "seed", listOf("1", "2", "3", "4", "5"))))
        val a = spec.rollSeeds().axes.first().values
        val b = spec.rollSeeds().axes.first().values
        assertNotEquals("a second sweep must not repeat the first", a, b)
        // ⚠ …and neither is the literal 1..5 that was reported.
        assertNotEquals(listOf("1", "2", "3", "4", "5"), a)
    }

    /**
     * ⚠⚠ **The COUNT survives**, or the number of renders shown to the user
     * before they press Run is a lie.
     */
    @Test
    fun rollingSeedsKeepsTheRunCount() {
        val spec = BatchSpec(
            listOf(
                BatchAxis("s", "seed", listOf("1", "2", "3")),
                BatchAxis("s", "cfg", listOf("7.0", "8.0")),
            )
        )
        val rolled = spec.rollSeeds()
        assertEquals(spec.runCount, rolled.runCount)
        assertEquals(6, rolled.runCount)
        // ⚠ Only the seed axis moves; a cfg sweep is a cfg sweep.
        assertEquals(listOf("7.0", "8.0"), rolled.axes[1].values)
    }

    /**
     * ⚠⚠⚠ **Distinct**, or the sweep spends a render to make a duplicate of
     * another card.
     */
    @Test
    fun rolledSeedsAreDistinct() {
        val spec = BatchSpec(listOf(BatchAxis("s", "seed", List(10) { "$it" })))
        val v = spec.rollSeeds().axes.first().values
        assertEquals(10, v.size)
        assertEquals(10, v.toSet().size)
        // ⚠ Positive: a seed is read back with `toLongOrNull` and a negative
        // one reads as a flag to anyone scanning the card.
        v.forEach { assertTrue(it, it.toLong() > 0) }
    }

    /**
     * ⭐⭐ **Every card is labelled with the seed that MADE it** — which is
     * the half the 2026-09-10 report was about, and the half a "count of
     * placeholder zeros" got wrong.
     */
    @Test
    fun eachRunIsLabelledWithItsRealSeed() {
        val rolled = BatchSpec(listOf(BatchAxis("s", "seed", listOf("1", "2", "3")))).rollSeeds()
        val labels = rolled.expand().map { rolled.labelFor(it) }
        assertEquals(3, labels.toSet().size)
        labels.forEach { l ->
            assertTrue(l, l.startsWith("seed "))
            assertTrue(l, l.removePrefix("seed ").toLong() > 0)
        }
        // ⚠ The label names a seed the run ACTUALLY used, not one from the spec
        // the user typed.
        val used = rolled.expand().map { it.getValue("s").getValue("seed") }
        assertEquals(rolled.axes.first().values, used)
    }

    /** ⚠ A sweep with no seed axis is untouched. */
    @Test
    fun aSweepWithoutSeedsIsUnchanged() {
        val spec = BatchSpec(listOf(BatchAxis("s", "cfg", listOf("7.0", "8.0"))))
        assertEquals(spec, spec.rollSeeds())
    }
}
