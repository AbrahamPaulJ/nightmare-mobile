package com.abrah.nightmare.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * An eraser — **the same glyph DreamUI draws**, deliberately.
 *
 * ⚠⚠ Ported rather than re-invented. The mask editor here and the one in
 * DreamUI are the same tool doing the same job, and a user who has both
 * installed should not have to learn two icons for "erase". Asked for from the
 * phone, 2026-09-10: use DreamUI's brush properties and its icons.
 *
 * ⚠ It is hand-built because the icon set genuinely does not ship one:
 * `material-icons-extended` is generated from the classic Material Icons set,
 * which has no eraser — `ink_eraser` is a Material *Symbols* glyph and
 * postdates it. The near misses all say something else next to a paint brush:
 * `AutoFixNormal` is a wand, `Deselect` is about selection, `CleaningServices`
 * is a spray bottle. Any of them reads as a second effect rather than as the
 * brush's opposite.
 *
 * Geometry: a 14.1 x 7.07 rectangle rotated 45°, cut across at 40% of its
 * length to separate rubber from sleeve, resting on a baseline bar. ⚠ The cut
 * is a **gap**, not a drawn line — a one-colour vector cannot draw a divider
 * inside its own fill.
 */
val EraserIcon: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    val black = SolidColor(Color.Black)
    ImageVector.Builder(
        name = "Eraser",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // The rubber end, tip pointing down-left at the baseline.
        path(fill = black) {
            moveTo(2f, 14f)
            lineTo(6f, 10f)
            lineTo(11f, 15f)
            lineTo(7f, 19f)
            close()
        }
        // The sleeve, offset 1.6 units up the long axis to leave the gap.
        path(fill = black) {
            moveTo(7.13f, 8.87f)
            lineTo(12f, 4f)
            lineTo(17f, 9f)
            lineTo(12.13f, 13.87f)
            close()
        }
        // The surface being rubbed. Without it the body alone reads as a
        // diamond at 18 dp.
        path(fill = black) {
            moveTo(3f, 19.6f)
            lineTo(21f, 19.6f)
            lineTo(21f, 21.6f)
            lineTo(3f, 21.6f)
            close()
        }
    }.build()
}

/**
 * A paint brush — Material's own `brush` path, hand-declared.
 *
 * ⚠⚠ DreamUI uses `Icons.Default.Brush` from `material-icons-extended`, which
 * **this project deliberately does not depend on** — `app/build.gradle.kts`
 * says why: it is thousands of vectors for the handful we use. So the same
 * glyph is declared here instead of taking the dependency for one icon.
 *
 * ⚠ The path is Material's, unchanged, so it IS the icon a DreamUI user
 * recognises rather than something that merely means "brush".
 */
val BrushIcon: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    val black = SolidColor(Color.Black)
    ImageVector.Builder(
        name = "Brush",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // The bristle tuft, splaying down-left.
        path(fill = black) {
            moveTo(7f, 14f)
            curveTo(5.34f, 14f, 4f, 15.34f, 4f, 17f)
            curveTo(4f, 18.31f, 2.84f, 19f, 2f, 19f)
            curveTo(2.92f, 20.22f, 4.49f, 21f, 6f, 21f)
            curveTo(8.21f, 21f, 10f, 19.21f, 10f, 17f)
            curveTo(10f, 15.34f, 8.66f, 14f, 7f, 14f)
            close()
        }
        // The handle, running up to the top-right corner.
        path(fill = black) {
            moveTo(20.71f, 4.63f)
            lineTo(19.37f, 3.29f)
            curveTo(18.98f, 2.9f, 18.35f, 2.9f, 17.96f, 3.29f)
            lineTo(9f, 12.25f)
            lineTo(11.75f, 15f)
            lineTo(20.71f, 6.04f)
            curveTo(21.1f, 5.65f, 21.1f, 5.02f, 20.71f, 4.63f)
            close()
        }
    }.build()
}

/**
 * ⚠ The three mask-editor actions, as Material draws them and as DreamUI uses
 * them: `Icons.Filled.Undo`, `Icons.Filled.InvertColors` (the half-filled drop)
 * and `Icons.Filled.LayersClear`. All three live in `material-icons-extended`,
 * which this project does not depend on, so the paths are declared here — the
 * same trade [BrushIcon] documents.
 *
 * ⚠⚠ They replace TEXT buttons ("undo", "clear") and a control that did not
 * exist at all (invert). `MaskOp.Invert` has been in the model and handled by
 * `MaskRaster.composite` the whole time with nothing to trigger it.
 */
val UndoIcon: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    materialIcon("Undo",
        "M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 " +
            "5.12-1.88 3.54 0 6.55 2.31 7.6 5.5l2.37-.78C21.08 11.03 17.15 8 12.5 8z")
}

/** ⭐ The half-filled drop — "invert what is masked so far". */
val InvertMaskIcon: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    materialIcon("InvertColors",
        "M17.66 7.93L12 2.27 6.34 7.93c-3.12 3.12-3.12 8.19 0 11.31C7.9 20.8 9.95 " +
            "21.58 12 21.58s4.1-.78 5.66-2.34c3.12-3.12 3.12-8.19 0-11.31zM12 " +
            "19.59c-1.6 0-3.11-.62-4.24-1.76C6.62 16.69 6 15.19 6 13.59s.62-3.11 " +
            "1.76-4.24L12 5.1v14.49z")
}

val ClearLayersIcon: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    materialIcon("LayersClear",
        "M19.81 14.99l1.19-.92-1.43-1.43-1.19.92 1.43 1.43zm-.45-4.72L21 9l-9-7-2.91 " +
            "2.27 7.87 7.88 2.4-1.88zM3.27 1L2 2.27l4.22 4.22L3 9l1.63 1.27L12 16l2.1-1.63 " +
            "1.81 1.81L12 19.09l-7.37-5.73L3 14.63l9 7 4.95-3.85L21.73 23 23 21.73 3.27 1z")
}

/** ⚠ One builder for the three above: a 24dp viewport and a single filled path. */
private fun materialIcon(name: String, path: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).run {
        addPath(
            pathData = androidx.compose.ui.graphics.vector.PathParser()
                .parsePathString(path).toNodes(),
            fill = SolidColor(Color.White),
        )
        build()
    }

/**
 * ⭐ The batch/sweep glyph — three stacked bars of rising length, "several of
 * this".
 *
 * ⚠ Drawn rather than borrowed: Material has no "sweep" and the near misses
 * (`Tune`, `FilterList`, `Repeat`) all already mean something else in an app
 * full of sliders. ⚠ It must read at 18dp beside a slider, so it is three
 * shapes and no detail.
 */
val BatchIcon: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    materialIcon("Batch", "M3 5h8v3H3zM3 10.5h14v3H3zM3 16h18v3H3z")
}

/**
 * ⭐ Material's `share` — three nodes joined by two edges.
 *
 * ⚠ Declared here like its neighbours rather than pulled from
 * `material-icons-extended`, which this project does not depend on
 * (`app/build.gradle.kts` says why).
 */
val ShareIcon: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    materialIcon("Share",
        "M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7" +
            "l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3" +
            "c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3" +
            "c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92" +
            "s2.92-1.31 2.92-2.92-1.31-2.92-2.92-2.92z")
}

/**
 * ⭐ Sharing the FLOW rather than the picture — Material's `schema`, a node
 * graph in miniature.
 *
 * ⚠⚠ A different glyph from [ShareIcon] on purpose. The two sit side by side
 * and send genuinely different things: one hands over a PNG, the other a graph
 * someone can run. One icon for both would make the app guess which was meant.
 */
val ShareFlowIcon: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    materialIcon("ShareFlow",
        "M9 3v4h2v3H4v4H2v6h6v-6H6v-2h12v2h-2v6h6v-6h-2v-4h-7V7h2V3H9zM6 20H4v-2h2v2z" +
            "m14 0h-2v-2h2v2z")
}
