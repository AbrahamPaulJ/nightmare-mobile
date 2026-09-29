package com.abrah.nightmare.pose

import android.graphics.Bitmap
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Draws an OpenPose skeleton the way `controlnet_aux`'s `draw_bodypose` does.
 *
 * This is a port of `npuconvert/npuconvertv2/render_openpose.py`, which is the
 * spec -- it was written using only primitives an `IntArray` port can reproduce
 * exactly: no `cv2`, no antialiasing, no alpha blending, no Canvas.
 *
 * ⚠ The convention below was **measured off the reference skeletons**
 * (`../LocalDream/docs/CONTROLNET.md` §On-device pose hints), not taken from memory:
 *
 * | | |
 * |---|---|
 * | limbs | opaque `int(0.6 x joint_colour)` -- **not** an `addWeighted` blend |
 * | joints | full brightness, filled radius-4 circle = exactly 49 px |
 * | order | limbs first, joints on top |
 * | antialiasing | **none anywhere** -- which is what makes a bit-exact port possible |
 *
 * A real skeleton contains exactly 36 colours and nothing else: black, the 18
 * joint colours, the 17 limb colours. Zero pixels fall outside that set on any
 * reference. Anything that antialiases or blends would put the hint outside the
 * distribution the ControlNet was trained on.
 *
 * ⚠ `cv2` truncates toward zero when it rasterises (`int(mY)`, `int(length/2)`),
 * and that truncation is part of the convention, not an accident. Kotlin's
 * [Double.toInt] truncates toward zero too, so the arithmetic is kept in the
 * same shape as the Python rather than "cleaned up" into `roundToInt`.
 */
object OpenPoseRender {

    /** COCO-18, in the order `draw_bodypose` assigns colours. */
    const val JOINTS = 18

    /** Hint canvas edge. 512, matching [Canny.SIZE] -- the same hint slot. */
    const val SIZE = 512

    /**
     * The 18 colours `draw_bodypose` assigns, packed `0xRRGGBB`, joint order.
     * Limb `i` is drawn in `COLORS[i]` darkened; joint `i` at full brightness.
     */
    private val COLORS = intArrayOf(
        0xFF0000, 0xFF5500, 0xFFAA00, 0xFFFF00, 0xAAFF00, 0x55FF00,
        0x00FF00, 0x00FF55, 0x00FFAA, 0x00FFFF, 0x00AAFF, 0x0055FF,
        0x0000FF, 0x5500FF, 0xAA00FF, 0xFF00FF, 0xFF00AA, 0xFF0055,
    )

    /** Joint [index]'s colour, packed `0xRRGGBB`. */
    fun jointColour(index: Int): Int = COLORS[index]

    /**
     * `draw_bodypose`'s `limbSeq`, converted from 1-based to 0-based indices.
     * Seventeen limbs for eighteen joints -- limb `i` takes `COLORS[i]`.
     */
    private val LIMBS = arrayOf(
        intArrayOf(1, 2), intArrayOf(1, 5), intArrayOf(2, 3), intArrayOf(3, 4),
        intArrayOf(5, 6), intArrayOf(6, 7), intArrayOf(1, 8), intArrayOf(8, 9),
        intArrayOf(9, 10), intArrayOf(1, 11), intArrayOf(11, 12), intArrayOf(12, 13),
        intArrayOf(1, 0), intArrayOf(0, 14), intArrayOf(14, 16), intArrayOf(0, 15),
        intArrayOf(15, 17),
    )

    /** The limb ellipse's semi-minor axis, in pixels. */
    private const val STICKWIDTH = 4

    /** Filled joint radius; 49 px, matching the references. */
    private const val JOINT_RADIUS = 4

    /**
     * One figure's joints, normalised to `[0, 1]` over the source image.
     *
     * Missing joints are the norm, not an error -- MoveNet drops anything below
     * its confidence threshold, and a limb is skipped whenever either end is
     * absent, exactly as `draw_bodypose` does.
     */
    class Pose {
        val x = FloatArray(JOINTS)
        val y = FloatArray(JOINTS)
        val present = BooleanArray(JOINTS)

        fun set(joint: Int, px: Float, py: Float) {
            x[joint] = px
            y[joint] = py
            present[joint] = true
        }

        val count: Int get() = present.count { it }

        /**
         * A copy scaled about the **canvas centre** and then shifted.
         *
         * ⚠ About (0.5, 0.5), not about the figure's own centre, and that is
         * not a style choice: the editor previews the move with a
         * `graphicsLayer`, which scales about the layer's centre. Baking the
         * transform any other way makes the skeleton jump the moment the finger
         * lifts, by exactly the distance between the two origins.
         */
        fun transformed(scale: Float, dx: Float, dy: Float): Pose {
            val out = Pose()
            for (i in 0 until JOINTS) {
                if (!present[i]) continue
                out.set(i, 0.5f + (x[i] - 0.5f) * scale + dx, 0.5f + (y[i] - 0.5f) * scale + dy)
            }
            return out
        }
    }

    fun transform(people: List<Pose>, scale: Float, dx: Float, dy: Float): List<Pose> =
        people.map { it.transformed(scale, dx, dy) }

    /**
     * Renders every figure onto one black canvas.
     *
     * ⚠ All figures share the same 18 colours -- that is the convention, and it
     * is why recovering keypoints back out of a multi-person skeleton is
     * ambiguous (see `extract_ref_keypoints.py`). Drawing them is not: they are
     * simply painted in turn.
     */
    fun render(people: List<Pose>, width: Int = SIZE, height: Int = SIZE): Bitmap =
        render(people, width, height, BLACK)

    /**
     * [background] is the model's black for a hint, and **transparent** for the
     * editor overlay -- where the photo underneath is the whole point, and a
     * black canvas would hide the thing the pose is being matched to.
     *
     * ⚠ Only ever transparent for *display*. The hint that reaches the
     * ControlNet keeps its black field: that is what the graph was trained on,
     * and an alpha channel is not something it can read.
     */
    fun render(people: List<Pose>, width: Int, height: Int, background: Int): Bitmap {
        val px = IntArray(width * height) { background }
        for (pose in people) draw(px, width, height, pose)
        return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888)
    }

    const val TRANSPARENT = 0

    private fun draw(px: IntArray, w: Int, h: Int, pose: Pose) {
        for (i in LIMBS.indices) {
            val (j1, j2) = LIMBS[i].let { it[0] to it[1] }
            if (!pose.present[j1] || !pose.present[j2]) continue
            val x1 = pose.x[j1] * w
            val y1 = pose.y[j1] * h
            val x2 = pose.x[j2] * w
            val y2 = pose.y[j2] * h
            val dx = (x2 - x1).toDouble()
            val dy = (y2 - y1).toDouble()
            val length = hypot(dx, dy)
            if (length < 1e-6) continue
            fillEllipse(
                px, w, h,
                cx = ((x1 + x2) / 2).toInt(),
                cy = ((y1 + y2) / 2).toInt(),
                a = (length / 2).toInt(),
                b = STICKWIDTH,
                ex = dx / length,
                ey = dy / length,
                color = darken(COLORS[i]),
            )
        }

        for (i in 0 until JOINTS) {
            if (!pose.present[i]) continue
            fillCircle(
                px, w, h,
                cx = (pose.x[i] * w).toInt(),
                cy = (pose.y[i] * h).toInt(),
                radius = JOINT_RADIUS,
                color = opaque(COLORS[i]),
            )
        }
    }

    /**
     * Opaque filled rotated ellipse, semi-axes ([a], [b]), major axis along
     * ([ex], [ey]).
     *
     * Rasterised by an analytic point-in-ellipse test over the bounding box --
     * the one primitive that does not depend on any of Android's antialiasing
     * or path-filling behaviour, which is what keeps this identical to the
     * Python and therefore to `cv2.fillConvexPoly`'s non-antialiased output.
     */
    private fun fillEllipse(
        px: IntArray,
        w: Int,
        h: Int,
        cx: Int,
        cy: Int,
        a: Int,
        b: Int,
        ex: Double,
        ey: Double,
        color: Int,
    ) {
        // A zero-length semi-major axis is a degenerate ellipse that fills
        // nothing; the Python widens it to 1 rather than dropping the limb.
        val ax = if (a <= 0) 1 else a
        val r = ceil(max(ax, b).toDouble()).toInt() + 1
        val x0 = max(0, cx - r)
        val x1 = min(w - 1, cx + r)
        val y0 = max(0, cy - r)
        val y1 = min(h - 1, cy + r)
        if (x1 < x0 || y1 < y0) return

        val aa = (ax.toDouble() * ax)
        val bb = (b.toDouble() * b)
        for (y in y0..y1) {
            val dy = (y - cy).toDouble()
            val row = y * w
            for (x in x0..x1) {
                val dx = (x - cx).toDouble()
                val u = dx * ex + dy * ey        // along the limb
                val v = -dx * ey + dy * ex       // across it
                if ((u * u) / aa + (v * v) / bb <= 1.0) px[row + x] = color
            }
        }
    }

    /** Filled circle matching `cv2.circle`'s non-antialiased pixel set exactly. */
    private fun fillCircle(px: IntArray, w: Int, h: Int, cx: Int, cy: Int, radius: Int, color: Int) {
        val rr = radius * radius
        for (dy in -radius..radius) {
            val y = cy + dy
            if (y < 0 || y >= h) continue
            val row = y * w
            for (dx in -radius..radius) {
                if (dx * dx + dy * dy > rr) continue
                val x = cx + dx
                if (x in 0 until w) px[row + x] = color
            }
        }
    }

    /**
     * `int(0.6 * channel)` per channel, as opaque ARGB.
     *
     * ⚠ Written as the same double multiply the Python does rather than a
     * pre-computed table: the reference's darkened channels are what IEEE
     * rounding produces here (0.6 x 255 lands on exactly 153.0, not 152.999),
     * and reproducing the expression is what guarantees the same bytes.
     */
    private fun darken(rgb: Int): Int {
        val r = (0.6 * ((rgb shr 16) and 0xFF)).toInt()
        val g = (0.6 * ((rgb shr 8) and 0xFF)).toInt()
        val b = (0.6 * (rgb and 0xFF)).toInt()
        return BLACK or (r shl 16) or (g shl 8) or b
    }

    private fun opaque(rgb: Int): Int = BLACK or rgb

    private const val BLACK = 0xFF000000.toInt()
}
