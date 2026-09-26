package com.abrah.nightmare.segment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ⭐⭐ The four rules of [ParseMask], each on a map built to break it — and,
 * when a real parser output is on disk, the port against its Python reference.
 */
class ParseMaskTest {

    private val classes = 18
    private val n = 32
    private val plane = n * n
    private val groups = Parser.TARGETS.map { it.labels }
    private val clothes = Parser.target("clothes")!!.labels
    private val shoes = Parser.target("shoes")!!.labels
    private val face = Parser.target("face")!!

    /** Every cell background with certainty, then [paint] overrides. */
    private fun map(paint: (FloatArray) -> Unit): FloatArray {
        val p = FloatArray(classes * plane)
        for (i in 0 until plane) p[i] = 1f
        paint(p)
        return p
    }

    private fun FloatArray.cell(x: Int, y: Int, vararg lp: Pair<Int, Float>) {
        val i = y * n + x
        for (c in 0 until classes) this[c * plane + i] = 0f
        var rest = 1f
        for ((l, v) in lp) {
            this[l * plane + i] = v
            rest -= v
        }
        this[i] += rest
    }

    private fun count(m: ByteArray?) = m?.count { it.toInt() != 0 } ?: 0

    @Test
    fun aTargetWithNoConfidentCoreIsNotInThePicture() {
        // ⚠ The phone's phantom shoes: a patch that WINS the argmax at 0.85
        // and is never sure. The user: "id rather they detect nothing".
        val p = map { m ->
            for (y in 20 until 30) for (x in 5 until 15) m.cell(x, y, 9 to 0.85f)
        }
        assertNull(ParseMask.mask(p, classes, n, n, groups, shoes, out = 64))
    }

    @Test
    fun aConfidentTargetIsFound() {
        val p = map { m ->
            for (y in 5 until 25) for (x in 5 until 25) m.cell(x, y, 4 to 0.98f)
        }
        val mask = ParseMask.mask(p, classes, n, n, groups, clothes, out = 64)
        assertNotNull(mask)
        // 20/32 of each edge is 39%, give or take the soft edge.
        val frac = count(mask) / (64f * 64f)
        assertTrue("clothes covered $frac", frac in 0.33f..0.45f)
    }

    @Test
    fun anAbsentTargetsPixelsGoToWhatIsLeft() {
        // ⚠ The jeans: a certain shirt, and a band below it split Pants 0.30 /
        // Shoe 0.50 / background 0.20. Shoes have no core, so the band
        // renormalises to Pants 0.60 and joins Clothes.
        val p = map { m ->
            for (y in 2 until 16) for (x in 4 until 28) m.cell(x, y, 4 to 0.99f)
            for (y in 16 until 22) for (x in 4 until 28) m.cell(x, y, 6 to 0.30f, 9 to 0.50f)
        }
        val mask = ParseMask.mask(p, classes, n, n, groups, clothes, out = n)!!
        assertEquals("the band's middle row is clothes", 255.toByte(), mask[19 * n + 16])
        assertNull(ParseMask.mask(p, classes, n, n, groups, shoes, out = n))
    }

    @Test
    fun faceKeepsOnlyTheTopmostRegion() {
        // ⚠ ATR labels a bare midriff Face. Two equal certain blobs: the upper
        // one is the face, the lower one is not.
        val p = map { m ->
            for (y in 2 until 10) for (x in 10 until 20) m.cell(x, y, 11 to 0.99f)
            for (y in 20 until 28) for (x in 10 until 20) m.cell(x, y, 11 to 0.99f)
        }
        val mask = ParseMask.mask(p, classes, n, n, groups, face.labels, face.topmostOnly, out = n)!!
        assertEquals(255.toByte(), mask[5 * n + 15])
        assertEquals(0.toByte(), mask[24 * n + 15])
    }

    @Test
    fun headStillOpensAsFace() {
        // A flow saved while the chip was Head carries `phead`.
        assertEquals("face", Parser.target("head")?.id)
    }

    /**
     * ⚠ The real thing: the int8 parser's output on the photo the user
     * reported, against the Python measurement of the same rules. Not
     * committed — the photo is theirs and this repo is public — so it runs
     * only when `NM_PARSE_FIXTURE` names the folder.
     */
    @Test
    fun theReportedPhotoMatchesItsPythonReference() {
        val dir = System.getenv("NM_PARSE_FIXTURE")?.let(::File)
        assumeTrue(dir != null && File(dir, "probs.f32").isFile)
        val bytes = File(dir, "probs.f32").readBytes()
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val probs = FloatArray(fb.remaining()).also { fb.get(it) }
        val w = 128
        for (id in listOf("clothes", "hair")) {
            val t = Parser.target(id)!!
            val got = ParseMask.mask(probs, classes, w, w, groups, t.labels)!!
            val want = File(dir, "ref_$id.u8").readBytes()
            var both = 0
            var either = 0
            for (i in got.indices) {
                val a = got[i].toInt() != 0
                val b = want[i].toInt() != 0
                if (a && b) both++
                if (a || b) either++
            }
            assertTrue("$id IoU ${both.toFloat() / either}", both.toFloat() / either > 0.999f)
        }
        for (id in listOf("shoes", "bag")) {
            assertNull(id, ParseMask.mask(probs, classes, w, w, groups, Parser.target(id)!!.labels))
        }
        val f = ParseMask.mask(probs, classes, w, w, groups, face.labels, face.topmostOnly)!!
        val want = File(dir, "ref_face.u8").readBytes()
        var ySum = 0L
        var k = 0
        for (i in f.indices) if (f[i].toInt() != 0) {
            assertTrue("face pixel outside the reference", want[i].toInt() != 0)
            ySum += i / ParseMask.OUT
            k++
        }
        assertTrue("face is in the upper half", ySum / k < ParseMask.OUT / 2)
        assertTrue("face dropped the midriff: $k of ${count(want)}", k < count(want))
    }
}
