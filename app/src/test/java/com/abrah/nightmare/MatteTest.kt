package com.abrah.nightmare

import com.abrah.nightmare.canvas.REMOVE_BG
import com.abrah.nightmare.canvas.RECIPES
import com.abrah.nightmare.canvas.runsOn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** ⭐ RMBG 3.0 (GitHub #136): the matte's alpha, its catalogue entry, and the one flow it opens. */
@RunWith(RobolectricTestRunner::class)
class MatteTest {

    @Test
    fun theMattesBrightnessIsTheAlpha() {
        val photo = intArrayOf(0xFF102030.toInt(), 0xFF102030.toInt(), 0xFF102030.toInt())
        val matte = intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF808080.toInt())
        val out = Matte.alphaFrom(photo, matte)
        assertEquals(listOf(255, 0, 128), out.map { it ushr 24 })
        assertTrue("the photo's colour is kept", out.all { it and 0xFFFFFF == 0x102030 })
    }

    @Test
    fun rmbgIsAFixedInstructionEditorBorrowingEditTurbosParts() {
        val s = ModelCatalog.byId(ModelCatalog.RMBG3)!!
        assertTrue(s.matte)
        assertEquals(Family.FIBO2, s.family)
        assertEquals(Matte.INSTRUCTION, s.prompt)
        assertEquals(4, s.steps)
        assertEquals("fibo2_edit_turbo", s.partsFrom)
        assertEquals(ModelCatalog.byId("fibo2_edit_turbo")!!.files.map { it.name }.toSet(), s.files.map { it.name }.toSet())
        assertFalse("no other model is a matte", ModelCatalog.all.any { it.matte && it.id != ModelCatalog.RMBG3 })
    }

    /**
     * ⭐ The mask editor's Auto is stored as a word, like a tap, survives the string a flow saves, and becomes the matte
     * only when one is handed over; with none it draws nothing, which the run refuses by name before resolving.
     */
    @Test
    fun theSubjectOpIsStoredAndResolvedLikeATap() {
        val m = MaskState(listOf(MaskOp.Stroke(MaskStrokeData(listOf(0.5f to 0.5f), 0.1f)), MaskOp.Subject), 0f, 0f)
        val back = MaskState.decode(m.encode())
        assertEquals(m.ops, back.ops)
        assertTrue(MaskTaps.hasSubject(back))
        assertFalse(MaskTaps.hasPicks(back) || MaskTaps.hasTaps(back))
        val unresolved = MaskTaps.resolve(back) { _, _ -> null }
        assertEquals("with no matte it draws nothing, as an unresolved pick does", listOf(m.ops[0]), unresolved.ops)
        val alpha = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ALPHA_8)
        val resolved = MaskTaps.resolve(back, subject = { alpha }) { _, _ -> null }
        assertTrue(resolved.ops.last() is MaskOp.Placed)
    }

    /** ⭐ Use on RMBG offers Remove background alone, and no picture model offers it. */
    @Test
    fun removeBackgroundIsRmbgsOnlyFlow() {
        val rmbg = ModelCatalog.byId(ModelCatalog.RMBG3)!!
        assertEquals(listOf(REMOVE_BG), RECIPES.filter { it.runsOn(rmbg) }.map { it.id })
        val edit = ModelCatalog.byId("fibo2_edit_turbo")!!
        assertFalse(RECIPES.single { it.id == REMOVE_BG }.runsOn(edit))
        assertTrue(RECIPES.single { it.id == "flux_edit" }.runsOn(edit))
        val g = RECIPES.single { it.id == REMOVE_BG }.build().graph
        assertNull("no prompt: the instruction is fixed", g.nodes.firstOrNull { it.type == "core.prompt" })
        val s = g.byId.getValue("remove")
        assertEquals(ModelCatalog.RMBG3, s.params["model"])
        assertEquals("image", s.inputs["image"]?.node)
    }

    /** ⭐ RMBG 3.0 broke at 960x1280 on the S25 (2026-11-22): every render stays within a megapixel, in the photo's shape. */
    @Test
    fun theMatteRendersWithinAMegapixelInThePhotosShape() {
        assertEquals(768 to 1024, Matte.renderSize(960, 1280))
        assertEquals(768 to 1024, Matte.renderSize(3000, 4000))
        assertEquals(1024 to 1024, Matte.renderSize(2048, 2048))
        for ((w, h) in listOf(960 to 1280, 1920 to 1080, 1080 to 1920, 4000 to 3000, 1000 to 1500, 512 to 2048)) {
            val (rw, rh) = Matte.renderSize(w, h)
            assertTrue("$w x $h -> $rw x $rh", rw.toLong() * rh <= 1024L * 1024L)
            assertTrue("$w x $h -> $rw x $rh", kotlin.math.abs(rw.toDouble() / rh - w.toDouble() / h) < 0.05)
        }
    }
}
