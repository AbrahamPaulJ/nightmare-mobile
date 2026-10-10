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
 * ⭐⭐ The DOWNLOAD glyph — an arrow into a tray.
 *
 * ⚠⚠ It exists because the disk changed jobs on 2026-09-15. The floppy used
 * to write a PNG to the gallery, which is a download wearing a save icon —
 * reported as confusing, and it was: the same glyph meant "export" here and
 * "keep" everywhere else in the app. ⇒ [SaveIcon] now KEEPS a picture in
 * Results and this one exports it, which is what each glyph already looks like.
 *
 * ⚠ Material's own `file_download` outline at 24x24, so it lines up with the
 * rest of the row without per-icon padding.
 */
val DownloadIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Download",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).run {
        addPath(
            pathData = PathParser().parsePathString(
                "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z"
            ).toNodes(),
            fill = SolidColor(Color.White),
        )
        build()
    }
}

/**
 * ⭐ SEND TO a flow — Material's `input`: an arrow going INTO a frame, which
 * is what happens to the picture (asked for 2026-09-17). ⚠ Not the share arrow:
 * share hands it to another APP, this puts it into one of ours.
 */
val SendToIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "SendTo",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).run {
        addPath(
            pathData = PathParser().parsePathString(
                "M21 3.01H3c-1.1 0-2 .9-2 2V9h2V4.99h18v14.03H3V15H1v4.01c0 1.1.9 1.98 2 " +
                    "1.98h18c1.1 0 2-.88 2-1.98v-14c0-1.11-.9-2-2-2zM11 16l4-4-4-4v3H1v2h10v3z"
            ).toNodes(),
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

/**
 * ⭐ A picture with a plus — Material's `add_photo_alternate`. The Add Objects
 * tool in the mask editor: pick a photo to take objects from (2026-09-23).
 */
val AddObjectIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "AddObject",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).run {
        addPath(
            pathData = PathParser().parsePathString(
                "M19 7v2.99s-1.99.01-2 0V7h-3s.01-1.99 0-2h3V2h2v3h3v2h-3z" +
                    "m-3 4V8h-3V5H5c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2v-8h-3z" +
                    "M5 19l3-4 2 3 3-4 4 5H5z"
            ).toNodes(),
            fill = SolidColor(Color.White),
        )
        build()
    }
}


/** ⭐ Stop (Material's `stop` square) — the agent's input row while it works. */
val StopIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Stop",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).run {
        addPath(pathData = PathParser().parsePathString("M6 6h12v12H6z").toNodes(), fill = SolidColor(Color.White))
        build()
    }
}

/** ⭐ One glyph per shell tile (Material shapes, drawn here — no icons-extended, docs/UI.md). */
private fun glyph(name: String, path: String): ImageVector = ImageVector.Builder(
    name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).run {
    addPath(pathData = PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.White))
    build()
}

/** Models — stacked layers. */
val ModelsIcon: ImageVector by lazy {
    glyph("Models", "M11.99 18.54l-7.37-5.73L3 14.07l9 7 9-7-1.63-1.27-7.38 5.74zM12 16l7.36-5.73L21 9l-9-7-9 7 1.63 1.27L12 16z")
}

/** Flows — a dashboard of cards. */
val FlowsIcon: ImageVector by lazy {
    glyph("Flows", "M3 13h8V3H3v10zm0 8h8v-6H3v6zm10 0h8V11h-8v10zm0-18v6h8V3h-8z")
}

/** Results — a picture. */
val ResultsIcon: ImageVector by lazy {
    glyph("Results", "M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z")
}

/** Agent — a chat bubble. */
val AgentIcon: ImageVector by lazy {
    glyph("Agent", "M20 2H4c-1.1 0-1.99.9-1.99 2L2 22l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zM6 9h12v2H6V9zm8 5H6v-2h8v2zm4-6H6V6h12v2z")
}

/** Nodes — settings sliders: one step's knobs at a time. */
val NodesIcon: ImageVector by lazy {
    glyph("Nodes", "M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z")
}

/** Graph — connected nodes. */
val GraphIcon: ImageVector by lazy {
    glyph("Graph", "M22 11V3h-7v3H9V3H2v8h7V8h2v10h4v3h7v-8h-7v3h-2V8h2v3z")
}

/** Expand — a chevron down. */
val ChevronDownIcon: ImageVector by lazy { glyph("ChevronDown", "M16.59 8.59L12 13.17 7.41 8.59 6 10l6 6 6-6z") }

/** Collapse — a chevron up. */
val ChevronUpIcon: ImageVector by lazy { glyph("ChevronUp", "M12 8l-6 6 1.41 1.41L12 10.83l4.59 4.58L18 14z") }

/** A model — a cube (the sidebar's Model card). */
val CubeIcon: ImageVector by lazy {
    glyph("Cube", "M12 2 3 7v10l9 5 9-5V7l-9-5zm0 2.3 6.9 3.8L12 11.9 5.1 8.1 12 4.3zM5 9.8l6 3.4v6.6l-6-3.4V9.8zm8 10v-6.6l6-3.4v6.6l-6 3.4z")
}

/** RAM — a chip (Material `memory`). */
val ChipIcon: ImageVector by lazy {
    glyph("Chip", "M15 9H9v6h6V9zm-2 4h-2v-2h2v2zm8-2V9h-2V7c0-1.1-.9-2-2-2h-2V3h-2v2h-2V3H9v2H7c-1.1 0-2 .9-2 2v2H3v2h2v2H3v2h2v2c0 1.1.9 2 2 2h2v2h2v-2h2v2h2v-2h2c1.1 0 2-.9 2-2v-2h2v-2h-2v-2h2zm-4 6H7V7h10v10z")
}

/** A flow file — a page with a folded corner (Material `insert_drive_file`, outlined). */
val FileIcon: ImageVector by lazy {
    glyph("File", "M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zM6 20V4h7v5h5v11H6z")
}
