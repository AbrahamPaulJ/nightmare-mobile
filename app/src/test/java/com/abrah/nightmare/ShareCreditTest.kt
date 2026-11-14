package com.abrah.nightmare

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** ⭐ The credit strip under a shared picture ([ShareCredit], the user 2026-11-14). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShareCreditTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun theWordsAreTheAppAloneOrTheNameWithTheAppAndOnlyTheNamesAreBold() {
        assertEquals(listOf("Made with " to false, "Nightmare Mobile" to true), ShareCredit.runsFor(ctx, "  "))
        assertEquals(
            listOf("Created by " to false, "Ana" to true, " with " to false, "Nightmare Mobile" to true),
            ShareCredit.runsFor(ctx, " Ana "),
        )
        // ⚠ A name over the limit is cut, whatever the Settings field let through.
        val long = "x".repeat(Prefs.SHARE_NAME_MAX + 9)
        assertEquals(Prefs.SHARE_NAME_MAX, ShareCredit.runsFor(ctx, long)[1].first.length)
        // ⚠ A language that puts the name first (zh) keeps the order of its own format.
        assertEquals(listOf("Ana" to true, " 使用 " to false, "NM" to true, " 创作" to false), ShareCredit.runs("%1\$s 使用 %2\$s 创作", listOf("Ana", "NM")))
    }

    @Test
    fun theStripGoesUnderThePictureAndCoversNoneOfIt() {
        val src = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val out = ShareCredit.apply(ctx, src, "Ana")
        val band = ShareCredit.bandFor(400, 300)
        assertEquals(400, out.width)
        assertEquals(300 + band, out.height)
        for (y in listOf(0, 150, 299)) assertEquals(Color.RED, out.getPixel(399, y))
        // The band's far right is the band colour (the text ends well before it), dark.
        val c = out.getPixel(399, 300 + band / 2)
        assertTrue("dark band, got ${Integer.toHexString(c)}", Color.red(c) < 40 && Color.green(c) < 40 && Color.blue(c) < 40)
        // Something light was drawn into the band (the words).
        var light = 0
        for (x in 0 until 400) for (y in 300 until 300 + band) if (Color.red(out.getPixel(x, y)) > 150) light++
        assertTrue("text drawn: $light light pixels", light > 50)
        assertEquals("the source is left as it was", 300, src.height)
    }
}
