package com.abrah.nightmare.pose

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * ⭐ Skeleton or photo? A rendered skeleton is used as it is; a photo goes to the
 * detector — and a DARK photo must not pass for a skeleton just by being dark.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PoseDetectorTest {

    private fun standing() = OpenPoseRender.Pose().apply {
        // nose, neck, shoulders, elbows, wrists, hips, knees, ankles (normalised)
        set(0, 0.5f, 0.15f); set(1, 0.5f, 0.25f)
        set(2, 0.4f, 0.25f); set(3, 0.35f, 0.4f); set(4, 0.33f, 0.55f)
        set(5, 0.6f, 0.25f); set(6, 0.65f, 0.4f); set(7, 0.67f, 0.55f)
        set(8, 0.45f, 0.55f); set(9, 0.45f, 0.72f); set(10, 0.45f, 0.9f)
        set(11, 0.55f, 0.55f); set(12, 0.55f, 0.72f); set(13, 0.55f, 0.9f)
    }

    @Test
    fun aRenderedSkeletonIsASkeleton() {
        assertTrue(PoseDetector.looksLikeSkeleton(OpenPoseRender.render(listOf(standing()), 512, 768)))
    }

    @Test
    fun aBrightPhotoIsNot() {
        val b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) for (x in 0 until 64) b.setPixel(x, y, Color.rgb(120 + x, 110 + y, 100))
        assertFalse(PoseDetector.looksLikeSkeleton(b))
    }

    /** ⭐ A smooth grey gradient is a depth map; grey NOISE (a textured B&W photo) is not. */
    @Test
    fun aSmoothGreyMapIsADepthMapAndGreyTextureIsNot() {
        val map = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        for (y in 0 until 256) for (x in 0 until 256) { val g = (x + y) / 2; map.setPixel(x, y, Color.rgb(g, g, g)) }
        assertTrue(DepthEstimator.looksLikeDepthMap(map))
        val rnd = java.util.Random(3)
        val bw = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        for (y in 0 until 256) for (x in 0 until 256) { val g = 100 + rnd.nextInt(60); bw.setPixel(x, y, Color.rgb(g, g, g)) }
        assertFalse(DepthEstimator.looksLikeDepthMap(bw))
        val colour = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        colour.eraseColor(Color.rgb(200, 90, 40))
        assertFalse(DepthEstimator.looksLikeDepthMap(colour))
    }

    /**
     * ⚠ The case the phone caught (2026-09-30): a FIGURE on black — smooth inside,
     * a sharp outline — is a depth map, although its outline pulls the mean step up.
     */
    @Test
    fun aFigureOnBlackIsADepthMap() {
        val m = Bitmap.createBitmap(512, 768, Bitmap.Config.ARGB_8888)
        m.eraseColor(Color.BLACK)
        for (y in 100 until 700) for (x in 150 until 360) {
            val g = 140 + (y - 100) / 6
            m.setPixel(x, y, Color.rgb(g, g, g))
        }
        assertTrue(DepthEstimator.looksLikeDepthMap(m))
    }

    /** ⚠ Mostly black, lit parts GREY — a night photo, not a drawing in pure hues. */
    @Test
    fun aDarkPhotoIsNot() {
        val b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        b.eraseColor(Color.rgb(10, 10, 12))
        for (y in 20 until 40) for (x in 10 until 50) b.setPixel(x, y, Color.rgb(170, 160, 150))
        assertFalse(PoseDetector.looksLikeSkeleton(b))
    }
}
