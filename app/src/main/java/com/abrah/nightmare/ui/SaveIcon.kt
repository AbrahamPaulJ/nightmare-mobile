package com.abrah.nightmare.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * ⭐ The glyphs `material-icons-core` does not carry, drawn by hand.
 *
 * ⚠⚠ `material-icons-extended` is deliberately absent from this module — it is
 * thousands of generated vector classes costing ~55 MB of dex whether or not
 * one icon is referenced (`app/build.gradle.kts`). Core has `Lock` but no open
 * lock, and no save glyph at all, so the two that are needed live here at a few
 * hundred bytes each.
 */

/**
 * The floppy-disk save glyph, drawn here rather than pulled from a library.
 *
 * ⚠⚠ `material-icons-extended` is **deliberately absent** from this module —
 * it is thousands of generated vector classes and costs ~55 MB of dex whether
 * or not one icon is referenced (`app/build.gradle.kts`), and
 * `material-icons-core` has no save glyph. One hand-built [ImageVector] costs
 * a few hundred bytes and keeps that rule intact.
 *
 * ⚠ The path is Material's own `save` outline at the standard 24x24 viewport,
 * so it sits correctly beside `Icons.Filled.Info` and `Icons.Filled.Build`
 * without any per-icon padding.
 *
 * ⚠ Built once and cached: `ImageVector.Builder` walks the path string, and
 * rebuilding it on every recomposition of the top bar would parse it on every
 * frame of a canvas drag.
 */
val SaveIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Save",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).run {
        addPath(
            pathData = PathParser().parsePathString(
                "M17 3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V7l-4-4z" +
                    "m-5 16c-1.66 0-3-1.34-3-3s1.34-3 3-3 3 1.34 3 3-1.34 3-3 3z" +
                    "m3-10H5V5h10v4z"
            ).toNodes(),
            // ⚠ Tinted at the call site like every built-in icon, so it follows
            // the theme rather than pinning a colour here.
            fill = SolidColor(Color.White),
        )
        build()
    }
}

/**
 * An OPEN padlock — the released half of the seed lock.
 *
 * ⚠⚠ It must read as the same object as `Icons.Filled.Lock` with the shackle
 * lifted, because the two are a toggle: a user taps one and expects the other.
 * Material's own `lock_open` path is used for that reason rather than something
 * drawn to look nice on its own — the pair has to be recognisable as a pair.
 */
val LockOpenIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "LockOpen",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).run {
        addPath(
            pathData = PathParser().parsePathString(
                "M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6h1.9c0-1.71 1.39-3.1 3.1-3.1" +
                    "s3.1 1.39 3.1 3.1v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2" +
                    "V10c0-1.1-.9-2-2-2zm0 12H6V10h12v10z" +
                    "m-6-3c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2z"
            ).toNodes(),
            fill = SolidColor(Color.White),
        )
        build()
    }
}

/**
 * Two overlapping sheets — the universal "copy".
 *
 * ⚠ An ImageVector like its neighbours rather than the `ic_copy.xml` drawable
 * that already exists in `res/`: this one is tinted with the row it sits in and
 * drawn at 16dp beside a 16dp close glyph, and `painterResource` would pull a
 * second loading path into a file whose whole point is that these icons are
 * declared the same way. ⚠ The drawable stays — the Results card uses it at a
 * size where a resource is the right answer.
 */
val CopyIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Copy",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).run {
        addPath(
            pathData = PathParser().parsePathString(
                "M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1z" +
                    "m3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7" +
                    "c0-1.1-.9-2-2-2zm0 16H8V7h11v14z"
            ).toNodes(),
            fill = SolidColor(Color.White),
        )
        build()
    }
}
