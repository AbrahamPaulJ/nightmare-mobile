package com.abrah.nightmare

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** ⭐ Saved #8052: a cut-out keeps what the mask covers, transparent elsewhere, cropped to it ([MaskCutout]). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaskCutoutTest {

    @Test
    fun theMaskBecomesTheAlphaAndThePictureIsCroppedToIt() {
        val photo = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 30, 40)) }
        val mask = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        for (y in 20 until 50) for (x in 10 until 40) mask.setPixel(x, y, Color.WHITE)
        mask.setPixel(40, 30, Color.rgb(128, 128, 128))
        val cut = MaskCutout.cut(photo, mask)!!
        assertEquals("cropped to the covered box", 31, cut.width)
        assertEquals(30, cut.height)
        assertEquals(255, Color.alpha(cut.getPixel(0, 0)))
        assertEquals(200, Color.red(cut.getPixel(5, 5)))
        assertEquals("a soft edge stays soft", 128, Color.alpha(cut.getPixel(30, 10)))
        assertEquals("outside the mask, transparent", 0, Color.alpha(cut.getPixel(30, 0)))
    }

    @Test
    fun anEmptyMaskCutsNothing() {
        val photo = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val mask = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        assertNull(MaskCutout.cut(photo, mask))
    }
}
