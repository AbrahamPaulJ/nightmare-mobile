package com.abrah.nightmare

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * ⭐⭐ The mask's two LAYERS, the Add Objects ring, and grow acting on regions
 * only — the user's design, 2026-09-24 (`docs/ADD-OBJECTS.md`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaskLayersTest {

    private fun region(x: Float, y: Float, w: Float, h: Float, exact: Boolean = false) = MaskOp.Placed(
        Bitmap.createBitmap(8, 8, Bitmap.Config.ALPHA_8).apply { eraseColor(Color.WHITE) },
        x, y, w, h, exact,
    )

    private fun at(state: MaskState, x: Int, y: Int) =
        Color.red(MaskRaster.rasterise(state, 100, 100).getPixel(x, y))

    /** ⭐ Grow widens a tapped region… */
    @Test
    fun growWidensARegion() {
        val s = MaskState(listOf(region(0.4f, 0.4f, 0.2f, 0.2f)), growFrac = 0.05f)
        assertTrue("grown past its edge", at(s, 37, 50) > 200)
        assertEquals("control: not with no grow", 0, at(s.copy(growFrac = 0f), 37, 50))
    }

    /** ⭐ …shrinks it when negative (−20 px at 512 is the slider's floor)… */
    @Test
    fun negativeGrowShrinksARegion() {
        val s = MaskState(listOf(region(0.4f, 0.4f, 0.2f, 0.2f)), growFrac = -0.05f)
        assertEquals("shrunk inside its edge", 0, at(s, 42, 50))
        assertTrue("its middle stays", at(s, 50, 50) > 200)
        assertTrue("control: kept with no grow", at(s.copy(growFrac = 0f), 42, 50) > 200)
    }

    /** ⭐ …and never touches a brush stroke, which is already the size shown. */
    @Test
    fun growLeavesAStrokeAlone() {
        val stroke = MaskOp.Stroke(MaskStrokeData(listOf(0.5f to 0.5f), 0.1f)) // radius 10 px
        val s = MaskState(listOf(stroke), growFrac = 0.05f)
        assertEquals("nothing past the brush", 0, at(s, 50, 36))
        assertTrue("the dab itself", at(s, 50, 50) > 200)
    }

    /** ⭐⭐ A layer's Invert stays inside it; Layer 1's does not reach in. */
    @Test
    fun eachLayersInvertStaysInsideIt() {
        val b = region(0.6f, 0.6f, 0.2f, 0.2f)
        // Layer 1: nothing, inverted -> everything. Layer 2: just B.
        val one = MaskState(listOf(MaskOp.Invert, MaskOp.Layer(listOf(b))))
        assertTrue(at(one, 10, 10) > 200)
        // Layer 1: nothing. Layer 2: B, inverted -> everything but B.
        val two = MaskState(listOf(MaskOp.Layer(listOf(b, MaskOp.Invert))))
        assertEquals("B is out of Layer 2 after its own Invert", 0, at(two, 70, 70))
        assertTrue("and the rest is in", at(two, 10, 10) > 200)
        // Layer 1 paints B's area: the union keeps it whatever Layer 2 did.
        val union = MaskState(listOf(region(0.6f, 0.6f, 0.2f, 0.2f), MaskOp.Layer(listOf(b, MaskOp.Invert))))
        assertTrue("Layer 1 still counts", at(union, 70, 70) > 200)
    }

    /** ⭐ A layer's Erase stays inside it too. */
    @Test
    fun layerTwosEraseLeavesLayerOne() {
        val erase = MaskOp.Erase(MaskStrokeData(listOf(0.5f to 0.5f), 0.2f))
        val s = MaskState(listOf(region(0.4f, 0.4f, 0.2f, 0.2f), MaskOp.Layer(listOf(erase))))
        assertTrue("Layer 1's region survives Layer 2's eraser", at(s, 50, 50) > 200)
    }

    private val obj = AddObjects.Placed("u", "s0.1:0.5,0.5", 0f, 0f, 1f, 1f, 0.25f, 0.25f, 0.5f)

    /** ⭐ The record keeps its layer, turn, flip and ring width. */
    @Test
    fun aTurnedObjectSurvivesTheString() {
        val o = obj.copy(layer = 3, rot = 37.5f, flip = true, ring = 0.03f)
        assertEquals(listOf(o), AddObjects.decode(AddObjects.encode(listOf(o))))
    }

    /** ⚠ A record from before object layers: Layer 2, unturned, the old shared ring. */
    @Test
    fun aNineFieldRecordLandsOnLayerTwo() {
        val old = listOf("u", "s0.1:0.5,0.5", 0, 0, 1, 1, 0.25, 0.25, 0.5).joinToString("")
        val p = mapOf(AddObjects.ENABLE to "true", AddObjects.PARAM to old, AddObjects.RING to "0.04")
        assertEquals(listOf(obj.copy(ring = 0.04f, id = 1)), AddObjects.of(p))
    }

    /** ⭐⭐ Two object layers, then no more — the Add button's toast. */
    @Test
    fun threeLayersAndNoMore() {
        assertEquals(2, AddObjects.nextLayer(emptyList()))
        assertEquals(3, AddObjects.nextLayer(listOf(obj)))
        assertEquals(null, AddObjects.nextLayer(listOf(obj, obj.copy(layer = 3))))
    }

    /** ⭐⭐ Reset puts ONE layer back as ✓ left it — removed objects included. */
    @Test
    fun resetPutsOneLayerBack() {
        var p: Map<String, String> = emptyMap()
        p = p + AddObjects.added(p, obj)
        p = p + AddObjects.added(p, obj.copy(layer = 3))
        p = p + AddObjects.changed(p, 0, obj.copy(x = 0.1f, rot = 90f))
        p = p + AddObjects.changed(p, 1, obj.copy(layer = 3, flip = true))
        p = p + AddObjects.removed(p, 0)
        assertEquals(listOf(obj.copy(layer = 3, flip = true)), AddObjects.decode(p[AddObjects.PARAM]))
        val back = p + AddObjects.reset(p, 2)
        assertEquals(
            "layer 2 as placed, layer 3 untouched",
            listOf(obj, obj.copy(layer = 3, flip = true)),
            AddObjects.decode(back[AddObjects.PARAM]),
        )
    }

    /** ⭐⭐ Deleting Layer 2 takes its reset point too, and Layer 3 becomes Layer 2. */
    @Test
    fun deletingALayerMovesTheNextDown() {
        var p: Map<String, String> = emptyMap()
        p = p + AddObjects.added(p, obj)
        p = p + AddObjects.added(p, obj.copy(layer = 3, x = 0.6f))
        p = p + AddObjects.layerDeleted(p, 2)
        assertEquals(listOf(obj.copy(x = 0.6f)), AddObjects.decode(p[AddObjects.PARAM]))
        assertEquals(listOf(obj.copy(x = 0.6f)), AddObjects.originalsOf(p))
        assertEquals("a layer is free again", 3, AddObjects.nextLayer(AddObjects.decode(p[AddObjects.PARAM])))
    }

    /** ⭐ The slider sets the width of every ring on its layer, and only there. */
    @Test
    fun aRingWidthIsPerLayer() {
        val p = mapOf(AddObjects.PARAM to AddObjects.encode(listOf(obj, obj.copy(layer = 3))))
        val after = AddObjects.decode(AddObjects.ringed(p, 3, 0.05f)[AddObjects.PARAM])
        assertEquals(listOf(AddObjects.RING_DEFAULT, 0.05f), after.map { it.ring })
    }

    /**
     * ⚠⚠ The 2026-09-24 Layer 2: what was PAINTED there moves to the image
     * layer; its rings, erases and inverts do not; the shared ring width goes
     * onto each object; what is there becomes the reset point — and each
     * object's ring lands on the image layer, after the strokes.
     */
    @Test
    fun theOldLayerTwoIsMigrated() {
        val stroke = MaskOp.Stroke(MaskStrokeData(listOf(0.5f to 0.5f), 0.1f))
        val erase = MaskOp.Erase(MaskStrokeData(listOf(0.2f to 0.2f), 0.1f))
        // ⚠ "r0" is the retired ring op, as the old layer stored it.
        val oldLayer = "r0;" + MaskState(listOf(stroke, erase, MaskOp.Invert)).encode()
        // ⚠ A 9-field record, as every build before object layers wrote one.
        val nine = listOf("u", "s0.1:0.5,0.5", 0, 0, 1, 1, 0.25, 0.25, 0.5).joinToString("")
        val old = mapOf(
            AddObjects.PARAM to nine,
            AddObjects.LAYER to oldLayer,
            AddObjects.RING to "0.04",
            MaskNode.OPS to MaskState(listOf(MaskOp.Invert)).encode(),
        )
        val p = AddObjects.migrated(old, MaskNode.OPS)!!
        assertTrue(AddObjects.LAYER !in p && AddObjects.RING !in p)
        assertEquals(listOf(MaskOp.Invert, stroke, MaskOp.ObjectRing(1)), MaskState.decode(p[MaskNode.OPS]).ops)
        assertEquals(listOf(obj.copy(ring = 0.04f, id = 1)), AddObjects.decode(p[AddObjects.PARAM]))
        assertEquals(listOf(obj.copy(ring = 0.04f, id = 1)), AddObjects.originalsOf(p))
        assertEquals("control: a current node is left alone", null, AddObjects.migrated(p, MaskNode.OPS))
    }

    /** ⭐ Within 5° of a right angle it snaps; further off it does not. */
    @Test
    fun anglesSnapToRightAngles() {
        assertEquals(90f, AddObjects.snapAngle(93f), 0f)
        assertEquals(0f, AddObjects.snapAngle(-4f), 0f)
        assertEquals(0f, AddObjects.snapAngle(358f), 0f)
        assertEquals(30f, AddObjects.snapAngle(30f), 0f)
        assertEquals(270f, AddObjects.snapAngle(-90f), 0f)
    }

    /** ⭐⭐ Tap-to-select follows the TURN: a tall box turned 90° is wide. */
    @Test
    fun selectingFollowsTheTurn() {
        // A 100x200 cut placed 100 px wide on a 400x400 picture: 100x200 at the centre.
        val tall = obj.copy(x = 0.375f, y = 0.25f, w = 0.25f)
        val right = 0.5f + 80f / 400f // 80 px right of centre
        assertTrue("control: outside the upright box", !AddObjects.contains(tall, 100, 200, 400, 400, right, 0.5f))
        assertTrue("inside once turned", AddObjects.contains(tall.copy(rot = 90f), 100, 200, 400, 400, right, 0.5f))
    }

    /** ⭐⭐ The ring is HARD — no feathering (the user's call, 2026-09-24). */
    @Test
    fun theRingHasNoFeather() {
        val cut = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val band = AddObjects.band(obj, cut, 400, 400, ring = 0.03f)
        val a = band.alpha
        val values = mutableSetOf<Int>()
        for (y in 0 until a.height step 3) for (x in 0 until a.width step 3) values += Color.alpha(a.getPixel(x, y))
        assertEquals("only fully on or fully off", setOf(0, 255), values)
    }

    /** ⭐⭐ The Size slider sets the ring's width: twice the radius, twice the ring. */
    @Test
    fun theRingIsAsWideAsTheBrush() {
        val cut = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        fun widthOf(ring: Float): Float {
            val band = AddObjects.band(obj, cut, 400, 400, ring)
            val a = band.alpha
            val y = a.height / 2
            // Count the ring's pixels left of the object, in photo px.
            var on = 0
            val objLeft = ((0.25f - band.x) / band.w * a.width).toInt()
            for (x in 0 until objLeft) if (Color.alpha(a.getPixel(x, y)) > 0) on++
            return on * band.w * 400 / a.width
        }
        val thin = widthOf(0.02f)  // 16 px across on a 400 px photo
        val thick = widthOf(0.04f) // 32 px
        assertEquals(16f, thin, 3f)
        assertEquals(32f, thick, 3f)
    }

    /** ⭐⭐ A TURNED object's ring is turned with it: a wide object turned 90° has a tall ring. */
    @Test
    fun theRingTurnsWithTheObject() {
        val cut = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val flat = AddObjects.band(obj, cut, 400, 400)
        val turned = AddObjects.band(obj.copy(rot = 90f), cut, 400, 400)
        assertTrue("control: the upright ring is wide", flat.w > flat.h)
        assertTrue("the turned ring is tall", turned.h > turned.w)
        assertEquals("…about the same centre", flat.x + flat.w / 2f, turned.x + turned.w / 2f, 0.01f)
        assertEquals(flat.y + flat.h / 2f, turned.y + turned.h / 2f, 0.01f)
    }

    /** ⭐⭐ Flip mirrors the paste: a cut red on its left lands red on its right. */
    @Test
    fun flipMirrorsThePaste() {
        val cut = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
            for (y in 0 until 100) for (x in 0 until 50) setPixel(x, y, Color.RED)
        }
        val photo = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val o = obj.copy(x = 0.25f, y = 0.25f, w = 0.5f)
        fun leftIsRed(flip: Boolean) =
            Color.red(AddObjects.composite(photo, listOf(o.copy(flip = flip) to cut)).getPixel(120, 200)) > 200
        assertTrue("control: unflipped, the left is red", leftIsRed(false))
        assertTrue("flipped, the left is blue", !leftIsRed(true))
    }

    /** ⭐ A 1.6.034 record (13 fields, no id) gets an id and its ring on the image layer. */
    @Test
    fun aRecordWithoutAnIdGetsItsRing() {
        val thirteen = listOf("u", "s0.1:0.5,0.5", 0, 0, 1, 1, 0.25, 0.25, 0.5, 3, 90, 1, 0.03).joinToString("\u001F")
        val p = AddObjects.migrated(mapOf(AddObjects.PARAM to thirteen), MaskNode.OPS)!!
        val o = AddObjects.decode(p[AddObjects.PARAM]).single()
        assertEquals(obj.copy(layer = 3, rot = 90f, flip = true, ring = 0.03f, id = 1), o)
        assertEquals(listOf(MaskOp.ObjectRing(1)), MaskState.decode(p[MaskNode.OPS]).ops)
        assertEquals("control: migrated once, left alone after", null, AddObjects.migrated(p, MaskNode.OPS))
    }

    /** ⭐ A new object's id is one no object — placed or remembered — has. */
    @Test
    fun aNewIdIsUnused() {
        var p: Map<String, String> = emptyMap()
        val a = AddObjects.withNewId(p, obj)
        p = p + AddObjects.added(p, a)
        val b = AddObjects.withNewId(p, obj)
        p = p + AddObjects.added(p, b)
        p = p + AddObjects.removed(p, 1)
        val c = AddObjects.withNewId(p, obj)
        assertEquals(listOf(1, 2, 3), listOf(a.id, b.id, c.id))
    }

    /**
     * ⭐⭐ The ring is ordinary IMAGE-layer mask: an Erase after it cuts it; a
     * move puts a CLEAN ring at the top, and the old erase stays where it was
     * painted (the user's call, 2026-09-26).
     */
    @Test
    fun aMovedObjectGetsAFreshRing() {
        val cut = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val o = obj.copy(x = 0.2f, y = 0.2f, w = 0.2f, id = 7, ring = 0.03f)
        val cuts = listOf(o to cut)
        // The ring's left edge, just outside the object: 0.2 of 100 px, minus a few.
        val onRing = 17
        val erase = MaskOp.Erase(MaskStrokeData(listOf(0.17f to 0.3f), 0.05f))
        val image = MaskState(listOf(MaskOp.ObjectRing(7), erase))
        fun at(objs: List<AddObjects.Placed>, m: MaskState, x: Int, y: Int) =
            Color.red(MaskRaster.rasterise(AddObjects.resolveRings(m, objs, cuts, 100, 100), 100, 100).getPixel(x, y))
        assertTrue("control: the ring is there without the erase",
            at(listOf(o), MaskState(listOf(MaskOp.ObjectRing(7))), onRing, 30) > 200)
        assertEquals("an erase after the ring cuts it", 0, at(listOf(o), image, onRing, 30))
        // Moved right by 0.4: a fresh ring at the top, the erase left behind.
        val moved = o.copy(x = 0.6f)
        val after = AddObjects.freshRings(image, listOf(7))
        assertEquals(listOf(erase, MaskOp.ObjectRing(7)), after.ops)
        assertTrue("the new ring is whole", at(listOf(moved), after, 57, 30) > 200)
        assertEquals("nothing where the old ring was", 0, at(listOf(moved), after, onRing, 30))
    }

    /** ⭐ A ring whose object is gone draws nothing — and does not count as masked. */
    @Test
    fun aDeadRingIsNoMask() {
        val m = MaskState(listOf(MaskOp.ObjectRing(4)))
        assertTrue(AddObjects.withoutDeadRings(m, emptyList()).isEmpty)
        assertTrue("control: kept while its object is there", !AddObjects.withoutDeadRings(m, listOf(obj.copy(id = 4))).isEmpty)
        assertEquals(MaskState(), AddObjects.dropRings(m, listOf(4)))
    }

    @Test
    fun aRingOpSurvivesTheString() {
        val m = MaskState(listOf(MaskOp.Invert, MaskOp.ObjectRing(12)))
        assertEquals(m.ops, MaskState.decode(m.encode()).ops)
    }
}
