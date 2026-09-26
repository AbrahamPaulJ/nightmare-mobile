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

/** ⭐ Material's redo — [UndoIcon] mirrored. */
val RedoIcon: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    materialIcon("Redo",
        "M18.4 10.6C16.55 8.99 14.15 8 11.5 8c-4.65 0-8.58 3.03-9.96 7.22L3.9 16c1.05-3.19 " +
            "4.05-5.5 7.6-5.5 1.95 0 3.73.72 5.12 1.88L13 16h9V7l-3.6 3.6z")
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

/**
 * ⭐ The pointing hand — DreamUI's Tap tool (`Icons.Default.TouchApp`), drawn
 * from its path because `material-icons-extended` is deliberately absent.
 */
val TapObjectIcon: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    materialIcon("TouchApp",
        "M9 11.24V7.5C9 6.12 10.12 5 11.5 5S14 6.12 14 7.5v3.74c1.21-.81 2-2.18 " +
            "2-3.74C16 5.01 13.99 3 11.5 3S7 5.01 7 7.5c0 1.56.79 2.93 2 3.74zm9.84 " +
            "4.63l-4.54-2.26c-.17-.07-.35-.11-.54-.11H13v-6c0-.83-.67-1.5-1.5-1.5S10 " +
            "6.67 10 7.5v10.74l-3.43-.72c-.08-.01-.15-.03-.24-.03-.31 0-.59.13-.79.33l-.79.8 " +
            "4.94 4.94c.27.27.65.44 1.06.44h6.79c.75 0 1.33-.55 1.44-1.28l.75-5.27c.01-.07.02-.14.02-.2 " +
            "0-.62-.38-1.16-.91-1.38z")
}

/**
 * ⭐ Pick by name — Material's `Label`, a name tag. The sibling of
 * [TapObjectIcon] in the mask editor's tool row: that one is a finger on the
 * picture, this one is a word for what is in it (`docs/SEGMENTER.md` §8).
 */
val PickByNameIcon: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    materialIcon("Label",
        "M17.63 5.84C17.27 5.33 16.67 5 16 5L5 5.01C3.9 5.01 3 5.9 3 7v10c0 " +
            "1.1.9 1.99 2 1.99L16 19c.67 0 1.27-.33 1.63-.84L22 12l-4.37-6.16z")
}

/** ⭐ Enlarge — Material's PhotoSizeSelectLarge, DreamUI's upscale glyph. */
val UpscaleIcon: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    materialIcon("PhotoSizeSelectLarge",
        "M21 15h2v2h-2v-2zm0-4h2v2h-2v-2zm2 8h-2v2c1 0 2-1 2-2zM13 3h2v2h-2V3zm8 " +
            "4h2v2h-2V7zm0-4v2h2c0-1-1-2-2-2zM1 7h2v2H1V7zm16-4h2v2h-2V3zm0 16h2v2h-2v-2zM3 " +
            "3C2 3 1 4 1 5h2V3zm6 0h2v2H9V3zM5 3h2v2H5V3zm-4 8v8c0 1.1.9 2 2 2h12V11H1zm2 " +
            "8l2.5-3.21 1.79 2.15 2.5-3.22L13 19H3z")
}

/**
 * ⭐ Duplicate — Material's ContentCopy: two overlapping sheets.
 *
 * ⚠ Borrowed rather than drawn, unlike the locks and the sweep: "a copy of
 * this" is a glyph everyone already reads, and `material-icons-core` has it, so
 * inventing one would cost recognition for nothing.
 */
val CloneIcon: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    materialIcon("ContentCopy",
        "M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 " +
            "2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z")
}

/** ⭐ Filter — Material's FilterList: three bars of falling length. */
val FilterIcon: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    materialIcon("FilterList", "M10 18h4v-2h-4v2zM3 6v2h18V6H3zm3 7h12v-2H6v2z")
}

/** ⚠ One builder for the icons above: a 24dp viewport and a single filled path. */
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

/**
 * ⭐⭐ The object layers' four tools (`docs/ADD-OBJECTS.md`, the user's ask
 * 2026-09-26: *"new icons needed"*) — Material's `rotate_right`, `flip`,
 * `restart_alt` and `highlight_off`, declared here for the reason [BrushIcon]
 * gives. ⚠ Remove-object is the ✕ in a circle, NOT a bin: the bin on an object
 * layer deletes the whole LAYER, and two bins doing two things is the
 * confusion `docs/UI.md` §8.18 already paid for.
 */
val RotateRightIcon: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    materialIcon("RotateRight",
        "M15.55 5.55L11 1v3.07C7.06 4.56 4 7.92 4 12s3.05 7.44 7 7.93v-2.02c-2.84-.48-5-2.94-5-5.91" +
            "s2.16-5.43 5-5.91V10l4.55-4.45zM19.93 11c-.17-1.39-.72-2.73-1.62-3.89l-1.42 1.42c.54.75.88 " +
            "1.6 1.02 2.47h2.02zM13 17.9v2.02c1.39-.17 2.74-.71 3.9-1.61l-1.44-1.44c-.75.54-1.59.89-2.46 " +
            "1.03zm3.89-2.42l1.42 1.41c.9-1.16 1.45-2.5 1.62-3.89h-2.02c-.14.87-.48 1.72-1.02 2.48z")
}

val FlipIcon: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    materialIcon("Flip",
        "M15 21h2v-2h-2v2zm4-12h2V7h-2v2zM3 5v14c0 1.1.9 2 2 2h4v-2H5V5h4V3H5c-1.1 0-2 .9-2 2z" +
            "m16-2v2h2c0-1.1-.9-2-2-2zm-8 20h2V1h-2v22zm8-6h2v-2h-2v2zM15 5h2V3h-2v2zm4 8h2v-2h-2v2z" +
            "m0 8c1.1 0 2-.9 2-2h-2v2z")
}

val ResetIcon: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    materialIcon("RestartAlt",
        "M12 5V2L8 6l4 4V7c3.31 0 6 2.69 6 6 0 2.97-2.17 5.43-5 5.91v2.02c3.95-.49 7-3.85 7-7.93 " +
            "0-4.42-3.58-8-8-8zm-6 8c0-1.65.67-3.15 1.76-4.24L6.34 7.34C4.9 8.79 4 10.79 4 13c0 " +
            "4.08 3.05 7.44 7 7.93v-2.02c-2.83-.48-5-2.94-5-5.91z")
}

val RemoveObjectIcon: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    materialIcon("HighlightOff",
        "M14.59 8L12 10.59 9.41 8 8 9.41 10.59 12 8 14.59 9.41 16 12 13.41 14.59 16 16 14.59 13.41 12 " +
            "16 9.41 14.59 8zM12 2C6.47 2 2 6.47 2 12s4.47 10 10 10 10-4.47 10-10S17.53 2 12 2zm0 18" +
            "c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z")
}

/**
 * ⭐ Material's `translate` (文A) — the prompt box's translate button
 * (`docs/TRANSLATE.md`). Declared, not depended on, like every icon here.
 */
val TranslateIcon: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    materialIcon("Translate",
        "M12.87 15.07l-2.54-2.51.03-.03c1.74-1.94 2.98-4.17 3.71-6.53H17V4h-7V2H8v2H1v1.99h11.17" +
            "C11.5 7.92 10.44 9.75 9 11.35 8.07 10.32 7.3 9.19 6.69 8h-2c.73 1.63 1.73 3.17 2.98 4.56" +
            "l-5.09 5.02L4 19l5-5 3.11 3.11.76-2.04zM18.5 10h-2L12 22h2l1.12-3h4.75L21 22h2l-4.5-12z" +
            "m-2.62 7l1.62-4.33L19.12 17h-3.24z")
}
