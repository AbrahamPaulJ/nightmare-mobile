package com.abrah.nightmare.canvas

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import com.abrah.nightmare.LoadImageNode
import com.abrah.nightmare.goldenPath
import com.abrah.nightmare.ui.NightmareTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ⭐ Doodle to image (`DrawWindow.kt`, the user's list 2026-10-09): the layer a stroke makes, the
 * eraser that takes it back, the empty check that drops a drawing, the composite the node renders —
 * and the window itself as a golden.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class DrawWindowTest {

    private val red = android.graphics.Color.RED

    // ⚠ An eraser wider than the stroke, as a finger erases: the same width leaves an AA fringe.
    private fun line(erase: Boolean = false, alpha: Float = 1f) =
        DrawStroke(listOf(Offset(10f, 50f), Offset(90f, 50f)), red, if (erase) 24f else 12f, alpha, 0f, erase)

    @Test
    fun aStrokePaintsTheLayerAndTheEraserTakesItBack() {
        val size = IntSize(100, 100)
        val drawn = DrawLayer.render(null, listOf(line()), size)
        assertEquals("the stroke is not on the layer", red, drawn.getPixel(50, 50))
        assertEquals("the layer is not transparent off the stroke", 0, drawn.getPixel(50, 10) ushr 24)
        val erased = DrawLayer.render(null, listOf(line(), line(erase = true)), size)
        assertEquals("the eraser left the stroke", 0, erased.getPixel(50, 50) ushr 24)
        assertTrue(DrawLayer.isEmpty(erased))
        assertFalse(DrawLayer.isEmpty(drawn))
    }

    /** ⚠ A half-strength eraser exists — DST_OUT, not CLEAR, which ignores alpha. */
    @Test
    fun aHalfEraserHalvesTheStroke() {
        val half = DrawLayer.render(null, listOf(line(), line(erase = true, alpha = 0.5f)), IntSize(100, 100))
        val a = half.getPixel(50, 50) ushr 24
        assertTrue("alpha $a after a 50% eraser", a in 100..160)
    }

    /** ⭐ The layer's shape is the picture's, capped at 2048 on the long edge. */
    @Test
    fun theLayerIsThePicturesShapeCapped() {
        assertEquals(IntSize(1024, 768), DrawLayer.sizeFor(1024, 768))
        assertEquals(IntSize(2048, 1536), DrawLayer.sizeFor(4096, 3072))
    }

    /** ⭐⭐ What the node renders: the picture with the layer stretched over it; a bad path changes nothing. */
    @Test
    fun theNodeCompositesItsDrawing() {
        val photo = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        val layer = DrawLayer.render(null, listOf(line()), IntSize(100, 100))
        val f = java.io.File.createTempFile("drawing", ".png")
        f.outputStream().use { layer.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val out = LoadImageNode.withDrawing(photo, f.absolutePath)
        assertEquals("the doodle is not over the photo", red, out.getPixel(100, 100))
        assertEquals("the photo was lost off the doodle", android.graphics.Color.BLUE, out.getPixel(100, 20))
        assertTrue("a missing layer must not fail the render", LoadImageNode.withDrawing(photo, "/nope.png") === photo)
        f.delete()
    }

    @Test
    fun theWindow() {
        val photo = Bitmap.createBitmap(768, 512, Bitmap.Config.ARGB_8888).apply {
            val c = android.graphics.Canvas(this)
            c.drawColor(android.graphics.Color.rgb(70, 110, 160))
            c.drawRect(0f, 340f, 768f, 512f, android.graphics.Paint().apply { color = android.graphics.Color.rgb(60, 120, 60) })
        }
        val strokes = listOf(
            DrawStroke(listOf(Offset(120f, 330f), Offset(200f, 180f), Offset(280f, 330f)), android.graphics.Color.rgb(121, 85, 72), 22f, 1f, 0f, false),
            DrawStroke(listOf(Offset(520f, 120f), Offset(560f, 100f), Offset(600f, 120f)), android.graphics.Color.YELLOW, 60f, 0.8f, 14f, false),
        )
        captureRoboImage(filePath = goldenPath(this, "draw-window")) {
            NightmareTheme(darkTheme = true) {
                androidx.compose.material3.Surface(
                    androidx.compose.ui.Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    DrawWindow(photo.asImageBitmap(), null, onDone = {}, onCancel = {}, initialStrokes = strokes)
                }
            }
        }
    }
}
