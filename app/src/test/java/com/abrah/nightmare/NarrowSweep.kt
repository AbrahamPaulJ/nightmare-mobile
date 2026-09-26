package com.abrah.nightmare

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/*
 * ⭐⭐⭐ EVERY golden class, again, at 360dp and 320dp.
 *
 * ⚠⚠ Reported from the phone 2026-09-23: the Settings gear and "+ Node"
 * vanished on a 384dp S25 Ultra while every golden — all at 411dp — stayed
 * green. The user then asked whether "the icons/btns on all pages will fit all
 * screen sizes", and nothing could answer that. These subclasses re-run each
 * class's tests unchanged at the narrow widths; the pictures land in
 * `screenshots/narrow/` ([goldenPath]) and are verified like any other.
 *
 * ⚠ 360dp is the common narrow Android width, and the one that caught the
 * old canvas bar (Robolectric's font is narrower than Samsung's, so 384dp
 * here passes what 384dp on the phone did not). 320dp is margin.
 */

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h780dp-xxhdpi")
class Narrow360Canvas : com.abrah.nightmare.canvas.CanvasScreenshotTest()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h780dp-xxhdpi")
class Narrow360Palette : com.abrah.nightmare.canvas.PaletteScreenshotTest()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h780dp-xxhdpi")
class Narrow360InpaintInspector : com.abrah.nightmare.canvas.InpaintInspectorScreenshotTest()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h780dp-xxhdpi")
class Narrow360Img2ImgInspector : com.abrah.nightmare.canvas.Img2ImgInspectorScreenshotTest()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h780dp-xxhdpi")
class Narrow360ReferenceInspector : com.abrah.nightmare.canvas.ReferenceInspectorScreenshotTest()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h780dp-xxhdpi")
class Narrow360Models : com.abrah.nightmare.ModelsScreenshotTest()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w320dp-h690dp-xxhdpi")
class Narrow320Canvas : com.abrah.nightmare.canvas.CanvasScreenshotTest()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w320dp-h690dp-xxhdpi")
class Narrow320Palette : com.abrah.nightmare.canvas.PaletteScreenshotTest()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w320dp-h690dp-xxhdpi")
class Narrow320InpaintInspector : com.abrah.nightmare.canvas.InpaintInspectorScreenshotTest()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w320dp-h690dp-xxhdpi")
class Narrow320Img2ImgInspector : com.abrah.nightmare.canvas.Img2ImgInspectorScreenshotTest()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w320dp-h690dp-xxhdpi")
class Narrow320ReferenceInspector : com.abrah.nightmare.canvas.ReferenceInspectorScreenshotTest()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w320dp-h690dp-xxhdpi")
class Narrow320Models : com.abrah.nightmare.ModelsScreenshotTest()
