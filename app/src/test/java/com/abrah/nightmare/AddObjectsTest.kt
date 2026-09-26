package com.abrah.nightmare

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * ⭐⭐ Add Objects ([AddObjects]) — the record, the split into objects, the
 * paste and the band, on real bitmaps.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddObjectsTest {

    private val obj = AddObjects.Placed(
        uri = "content://media/picker/0/42",
        // ⚠ A real mask string: every separator a stroke uses.
        ops = "s0.3:0.5,0.5|0.6,0.6~0,0.02;pclothes;i",
        bx = 0.1f, by = 0.2f, bw = 0.3f, bh = 0.4f,
        x = 0.25f, y = 0.25f, w = 0.5f,
    )

    @Test
    fun theRecordSurvivesARoundTrip() {
        val two = listOf(obj, obj.copy(x = 0.1f, uri = "/sdcard/x.png"))
        assertEquals(two, AddObjects.decode(AddObjects.encode(two)))
    }

    @Test
    fun objectsCountOnlyWhileEnabled() {
        val params = mapOf(AddObjects.PARAM to AddObjects.encode(listOf(obj)))
        assertEquals(emptyList<AddObjects.Placed>(), AddObjects.of(params))
        assertEquals(listOf(obj), AddObjects.of(params + (AddObjects.ENABLE to "true")))
    }

    /** ⭐ Two separate masked areas are two objects, each placed on its own. */
    @Test
    fun twoMaskedAreasAreTwoObjects() {
        val m = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val c = Canvas(m)
        c.drawColor(Color.BLACK)
        val white = Paint().apply { color = Color.WHITE }
        c.drawRect(10f, 10f, 40f, 40f, white)   // 900 px
        c.drawRect(60f, 60f, 80f, 80f, white)   // 400 px
        val r = AddObjects.regions(m)
        assertEquals(2, r.size)
        assertEquals("largest first", 0.10f, r[0].left, 0.011f)
        assertEquals(0.60f, r[1].left, 0.011f)
    }

    /**
     * ⭐⭐⭐ The band is the EDGE, not the object: zero in the middle (the paste
     * stays pixel-exact), strong on the outline, and reaching past it.
     */
    @Test
    fun theBandIsTheEdgeNotTheMiddle() {
        val cut = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        cut.eraseColor(Color.RED) // fully opaque object
        val band = AddObjects.band(obj.copy(x = 0.25f, y = 0.25f, w = 0.5f), cut, 400, 400)
        assertTrue("reaches outside the object", band.x < 0.25f && band.x + band.w > 0.75f)
        val a = band.alpha
        fun at(fx: Float, fy: Float) =
            android.graphics.Color.alpha(a.getPixel((fx * (a.width - 1)).toInt(), (fy * (a.height - 1)).toInt()))
        assertEquals("the middle is untouched", 0, at(0.5f, 0.5f))
        // The object's left edge sits at the ring's padding in from its left.
        val edge = (0.25f - band.x) / band.w
        assertTrue("strong on the edge: ${at(edge, 0.5f)}", at(edge, 0.5f) > 200)
    }

    /**
     * ⭐⭐⭐ The band reaches FAR out onto the photo and BARELY into the object
     * — the user, 2026-09-24: *"just very very little to fix seams"*. It used
     * to reach ~10% of the object inward, and whole parts of a garment came
     * back repainted.
     */
    @Test
    fun theBandBarelyEntersTheObject() {
        val cut = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        cut.eraseColor(Color.RED)
        val band = AddObjects.band(obj.copy(x = 0.25f, y = 0.25f, w = 0.5f), cut, 400, 400)
        val a = band.alpha
        fun at(fx: Float, fy: Float) =
            android.graphics.Color.alpha(a.getPixel((fx * (a.width - 1)).toInt(), (fy * (a.height - 1)).toInt()))
        // The object spans 0.25..0.75 of the photo; 1% of it is 0.005.
        fun ofObject(f: Float) = (0.25f + 0.5f * f - band.x) / band.w
        assertEquals("nothing 3% inside the object", 0, at(ofObject(0.03f), 0.5f))
        assertTrue("still repainting 3% outside it: ${at(ofObject(-0.03f), 0.5f)}", at(ofObject(-0.03f), 0.5f) > 100)
        assertTrue("an exact op", band.exact)
    }

    /**
     * ⚠⚠ The node's feather must not carry a band into the object: an exact
     * op is drawn as it is. The default feather (0.02) alone reached 2% of the
     * frame further in.
     */
    @Test
    fun featherDoesNotSpreadAnExactBand() {
        val alpha = Bitmap.createBitmap(10, 10, Bitmap.Config.ALPHA_8).apply { eraseColor(Color.WHITE) }
        val placed = MaskOp.Placed(alpha, 0.4f, 0.4f, 0.2f, 0.2f, exact = true)
        fun rasterAt(op: MaskOp.Placed) = Color.red(
            MaskRaster.rasterise(MaskState(listOf(op), featherFrac = 0.1f, growFrac = 0.05f), 100, 100).getPixel(35, 50)
        )
        assertEquals("exact: nothing beyond its own edge", 0, rasterAt(placed))
        // Control: the same region NOT exact is spread by the feather.
        assertTrue("control: a loose region is feathered", rasterAt(placed.copy(exact = false)) > 0)
    }

    @Test
    fun thePasteLandsWherePlaced() {
        val photo = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val cut = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val out = AddObjects.composite(photo, listOf(obj.copy(x = 0.5f, y = 0.5f, w = 0.2f) to cut))
        assertEquals(Color.GREEN, out.getPixel(60, 60))
        assertEquals(Color.BLACK, out.getPixel(20, 20))
    }

    /**
     * ⭐⭐ A hole INSIDE an object gets no ring — only the outer outline is
     * repainted (the user's call, 2026-09-24).
     */
    @Test
    fun aHoleInsideTheObjectGetsNoBand() {
        val cut = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        cut.eraseColor(Color.RED)
        // A 60 px transparent hole in the middle, edges at 70 and 130.
        Canvas(cut).drawRect(
            70f, 70f, 130f, 130f,
            Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR) },
        )
        val band = AddObjects.band(obj.copy(x = 0.25f, y = 0.25f, w = 0.5f), cut, 400, 400)
        val a = band.alpha
        fun at(fx: Float, fy: Float) =
            android.graphics.Color.alpha(a.getPixel((fx * (a.width - 1)).toInt(), (fy * (a.height - 1)).toInt()))
        val hole = (0.25f + 0.5f * 70f / 200f - band.x) / band.w
        val outline = (0.25f - band.x) / band.w
        assertTrue("strong on the outer outline: ${at(outline, 0.5f)}", at(outline, 0.5f) > 200)
        assertEquals("nothing on the hole's edge", 0, at(hole, 0.5f))
    }

    /**
     * ⚠⚠ A NEW photo takes the placed objects with it — they were placed on
     * the old one. Reported 2026-09-23: old objects persisted and could not be
     * cleared.
     */
    @Test
    fun aNewPhotoDropsThePlacedObjects() {
        val inpaint = SdSampler.ALL.first { it.inpaint }.name
        val g = Graph(
            listOf(
                Node("image", "core.image", mapOf("uri" to "content://a")),
                Node(
                    "inp", inpaint,
                    mapOf(AddObjects.ENABLE to "true", AddObjects.PARAM to AddObjects.encode(listOf(obj))),
                    inputs = sources("image" to "image"),
                ),
            )
        )
        val after = g.withNewPicture("image", "content://b")
        assertEquals(null, after.byId["inp"]!!.params[AddObjects.PARAM])
        assertEquals("the tick stays", "true", after.byId["inp"]!!.params[AddObjects.ENABLE])
    }

    /**
     * ⚠⚠⚠ The white box: a JPEG-decoded source says it has NO alpha, and the
     * cut must still be transparent outside the chosen object.
     */
    @Test
    fun aCutFromAnOpaqueSourceIsTransparentOutsideTheObject() {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            setHasAlpha(false) // what BitmapFactory gives a JPEG
        }
        val mask = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        Canvas(mask).drawRect(40f, 40f, 60f, 60f, Paint().apply { color = Color.WHITE })
        val o = obj.copy(bx = 0.2f, by = 0.2f, bw = 0.6f, bh = 0.6f)
        val cut = AddObjects.cutout(source, mask, o)
        assertTrue("the cut carries alpha", cut.hasAlpha())
        val photo = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val out = AddObjects.composite(photo, listOf(o.copy(x = 0f, y = 0f, w = 1f) to cut))
        assertEquals("outside the object: the photo, not a white box", Color.BLACK, out.getPixel(5, 5))
        assertEquals("the object itself", Color.WHITE, out.getPixel(50, 50))
    }
}

