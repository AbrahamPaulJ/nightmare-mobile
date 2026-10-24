package com.abrah.nightmare.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import com.abrah.nightmare.Prefs
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ⭐ Wallpaper colours (Material You) are an OPTION, not the look (`Prefs.Accent.WALLPAPER`, a tester's wish,
 * 2026-10-24): the default stays violet, and node-category and family colours never follow the wallpaper, so a
 * shared screenshot still means the same thing (docs/UI.md section 1).
 */
/**
 * ⭐⭐ The 2026-10-05 look — the user's concept art: a deep violet-black ground,
 * cards one step lighter, a violet primary. ⚠ Every container role is SET:
 * Material's dark defaults are blue-grey and showed through on any surface
 * that asked for one this scheme left out.
 */
val NightSurface = Color(0xFF0D0914)   // keep in step with res/values/colors.xml

// ⭐⭐ Violet Studio (the user's design pack, 2026-10-08): plum cards a step over a near-black ground, one violet
// for filled actions (#8B4DEE: white on it is 4.78:1; the brighter #985CFF is the ACCENT, focus and accent text,
// never a button fill). Its exact tokens are [VioletPalette]; the other accents turn its hue (`Accents.kt`).
/** ⭐ Focus outlines and accent text on dark — not a fill. */
val Accent = Color(0xFF985CFF)
/** ⭐ Ready / succeeded. ⚠ Readiness only — never unsaved work. */
val Success = Color(0xFF6FE3AE)
/** ⭐ Unsaved changes, caution. */
val Warning = Color(0xFFF4C16C)

/**
 * ⭐ The Black theme (a tester's wish, 2026-10-24: "an AMOLED black theme"): [darkScheme] with true-black
 * background and surfaces, so an OLED screen's unlit pixels stay off. Cards and sheets step up in near-black
 * neutrals so they still read as layers; the accents are unchanged.
 */
private fun ColorScheme.blacked(p: AccentPalette) = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = p.bLow,
    surfaceContainer = p.bCont,
    surfaceVariant = p.bCont,
    surfaceContainerHigh = p.bHigh,
    surfaceContainerHighest = p.bHighest,
)

/** ⭐ The dark scheme in [p]'s accent; [VioletPalette] is Violet Studio's exact tokens (above). */
private fun darkScheme(p: AccentPalette) = darkColorScheme(
    primary = p.fill,
    onPrimary = Color.White,
    primaryContainer = p.container,
    onPrimaryContainer = p.on,
    secondary = p.accent,
    secondaryContainer = p.container,
    onSecondaryContainer = p.on,
    surface = p.surface,
    onSurface = p.on,
    surfaceVariant = p.card,
    onSurfaceVariant = p.onMuted,
    surfaceTint = p.fill,
    surfaceContainerLowest = p.lowest,
    surfaceContainerLow = p.low,
    surfaceContainer = p.card,
    surfaceContainerHigh = p.raised,
    surfaceContainerHighest = p.highest,
    background = p.surface,
    onBackground = p.on,
    outline = p.border,
    outlineVariant = p.outlineVar,
    // ⚠⚠ Every remaining role SET (the review, 2026-10-22): an unset `tertiary` fell back
    // to Material's baseline PINK on the LoRA picker and Models, and the default `onError`
    // is WHITE, which on #FF7B92 is 2.47:1, the label of every confirmed delete.
    tertiary = p.accent,
    onTertiary = p.surface,
    tertiaryContainer = p.container,
    onTertiaryContainer = p.on,
    onSecondary = p.surface,
    error = Color(0xFFFF7B92),
    onError = Color(0xFF3B0A14),
    errorContainer = Color(0xFF4A1622),
    onErrorContainer = Color(0xFFFFD9DF),
)

/**
 * ⭐⭐ **Light is WHITE** — the user's call, 2026-09-26: users found light mode
 * *"too dull"*. It was Material's default scheme, whose every surface carries a
 * lavender tint, under a graph canvas that stayed dark. Now the surfaces are
 * white and the containers neutral greys, and the canvas turns white with them
 * ([com.abrah.nightmare.canvas.CanvasColors.light]). Dark is untouched.
 * ⚠ The accent is [AccentPalette.lightFill], deepened a step so it holds contrast on white.
 */
private val LightOn = Color(0xFF16161D)
private val LightOnMuted = Color(0xFF5E5E6E)

private fun lightScheme(p: AccentPalette) = lightColorScheme(
    primary = p.lightFill,
    // ⚠ The containers SET too: left out, Material's lavender came back in a selected card.
    primaryContainer = p.lightContainer,
    onPrimaryContainer = p.lightOnContainer,
    secondary = p.lightText,
    onSecondary = Color.White,
    secondaryContainer = p.lightContainer,
    onSecondaryContainer = p.lightOnContainer,
    tertiary = p.lightText,
    onTertiary = Color.White,
    tertiaryContainer = p.lightContainer,
    onTertiaryContainer = p.lightOnContainer,
    onPrimary = Color.White,
    background = Color.White,
    onBackground = LightOn,
    surface = Color.White,
    onSurface = LightOn,
    surfaceVariant = Color(0xFFF1F1F5),
    onSurfaceVariant = LightOnMuted,
    surfaceTint = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F7FA),
    surfaceContainer = Color(0xFFF2F2F6),
    surfaceContainerHigh = Color(0xFFECECF1),
    surfaceContainerHighest = Color(0xFFE6E6EC),
    outline = Color(0xFFC9C9D4),
    outlineVariant = Color(0xFFE2E2E9),
    error = Color(0xFFD93838),
    onError = Color.White,
    errorContainer = Color(0xFFFDE7E9),
    onErrorContainer = Color(0xFF5C0A14),
)

/**
 * ⭐⭐ The tokens Material has no role for, per theme (`MaterialTheme.studio`). ⚠ Use these,
 * not the fixed [Accent] / [Success] / [Warning]: those are the DARK values, and on white
 * Success is 1.58:1 and Warning 1.65:1.
 */
@androidx.compose.runtime.Immutable
data class StudioColors(
    /** ⭐ Violet TEXT on a surface: text buttons, links, accent labels. ⚠ `primary` is a FILL
     *  colour; as text it is 3.41:1 on a dialog (#251B36). This is 6.15:1 there. */
    val accentText: Color,
    /** ⭐ The edge of a CONTROL (an outlined button, a field): 3:1 against a card, where the
     *  quiet `outline` (a card's border, a divider) is 1.39:1. */
    val controlBorder: Color,
    val success: Color,
    val warning: Color,
)

private fun darkStudio(p: AccentPalette) = StudioColors(
    accentText = p.text,
    controlBorder = p.controlBorder,
    success = Success,
    warning = Warning,
)

private fun lightStudio(p: AccentPalette) = StudioColors(
    accentText = p.lightText,
    controlBorder = Color(0xFF85859A),
    success = Color(0xFF1B7F52),
    warning = Color(0xFF8A5A00),
)

private val DarkStudio = darkStudio(VioletPalette)

val LocalStudioColors = androidx.compose.runtime.staticCompositionLocalOf { DarkStudio }

/** ⭐ `MaterialTheme.studio.accentText` and friends. */
val MaterialTheme.studio: StudioColors
    @Composable @androidx.compose.runtime.ReadOnlyComposable get() = LocalStudioColors.current

/**
 * ⭐⭐ Violet Studio's shape and spacing scale, ONE place (the user's calls, 2026-10-22: 12dp
 * buttons and fields, 16dp cards, the 12dp screen gutter kept). The theme's [Shapes] carry
 * them, so a default `Card`, field, menu or dialog already matches; a call that needs a
 * corner by NAME uses [Radius].
 */
object Radius {
    val badge = 8.dp
    val control = 12.dp
    val card = 16.dp
    val sheet = 24.dp
}

object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
}

private val StudioShapes = androidx.compose.material3.Shapes(
    // ⚠ extraSmall is what an OutlinedTextField and a menu read: 4dp by default, so 22 of
    // the 23 fields had square-ish corners beside 12dp buttons.
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(Radius.control),
    small = androidx.compose.foundation.shape.RoundedCornerShape(Radius.control),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(Radius.card),
    large = androidx.compose.foundation.shape.RoundedCornerShape(Radius.sheet),
    // ⚠ AlertDialog's corner (28 by default): the same as a sheet's.
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(Radius.sheet),
)

/**
 * ⭐ The small Inter text every panel, card and note uses. Was monospace
 * (`LogTextStyle`) until the user found Inter "not changed" on 1.6.082 —
 * 2026-10-06 they chose Inter everywhere except true measurements.
 */
val NoteTextStyle: TextStyle by lazy {
    // ⚠ 16sp line height (the user, 2026-10-22): 12sp with none packed wrapped lines tight;
    // the SIZE stays 12 because node knobs already take too much height.
    TextStyle(fontFamily = Inter, fontSize = 12.sp, lineHeight = 16.sp)
}

/**
 * ⚠ Monospace ONLY for a true measurement — the run log, the backend log,
 * a progress counter, a size in pixels or MB. Prose goes in [NoteTextStyle].
 */
val MeasureTextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

@Composable
fun NightmareTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** ⭐ The Black theme ([blacked]); only with [darkTheme]. */
    black: Boolean = false,
    /** ⭐ The accent colour (`Accents.kt`); [Prefs.Accent.WALLPAPER] is Android 12+'s, violet before it. */
    accent: Prefs.Accent = Prefs.Accent.VIOLET,
    content: @Composable () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val wallpaper = accent == Prefs.Accent.WALLPAPER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val p = paletteOf(accent)
    val scheme = when {
        wallpaper && !darkTheme -> dynamicLightColorScheme(context)
        wallpaper -> dynamicDarkColorScheme(context).let { if (black) it.blacked(NeutralBlack) else it }
        !darkTheme -> lightScheme(p)
        black -> darkScheme(p).blacked(p)
        else -> darkScheme(p)
    }
    // ⚠ The wallpaper's own roles stand in for the ones Material has no slot for: `primary` is its text-strength
    // tone on a surface (80 dark, 40 light) and `outline` its 3:1 control edge.
    val studio = when {
        wallpaper -> (if (darkTheme) darkStudio(p) else lightStudio(p)).copy(accentText = scheme.primary, controlBorder = scheme.outline)
        darkTheme -> darkStudio(p)
        else -> lightStudio(p)
    }
    // ⚠ Before the content composes, so the canvas's first frame is already
    // the right one. A state write: flipping the theme redraws the canvas.
    com.abrah.nightmare.canvas.CanvasColors.light = !darkTheme
    com.abrah.nightmare.canvas.CanvasColors.black = black && darkTheme
    com.abrah.nightmare.canvas.CanvasColors.selection = when {
        wallpaper -> scheme.primary to if (darkTheme) p.canvasSel else scheme.secondaryContainer
        darkTheme -> p.canvasStroke to p.canvasSel
        else -> p.lightCanvasStroke to p.lightCanvasSel
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = InterTypography,
        shapes = StudioShapes,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalStudioColors provides studio,
            content = content,
        )
    }
}

internal fun paletteOf(a: Prefs.Accent): AccentPalette = when (a) {
    Prefs.Accent.BLUE -> BluePalette
    Prefs.Accent.TEAL -> TealPalette
    Prefs.Accent.ORANGE -> OrangePalette
    Prefs.Accent.PINK -> PinkPalette
    Prefs.Accent.VIOLET, Prefs.Accent.WALLPAPER -> VioletPalette
}

/** ⚠ The wallpaper's Black layers: plain near-black greys, since its hue is not known here. */
private val NeutralBlack = VioletPalette.let {
    AccentPalette(
        it.fill, it.accent, it.text, it.lightFill, it.lightText, it.surface, it.card, it.raised, it.lowest, it.low,
        it.highest, it.container, it.border, it.outlineVar, it.onMuted, it.on, it.controlBorder,
        bLow = Color(0xFF0A0A0A), bCont = Color(0xFF121212), bHigh = Color(0xFF1B1B1B), bHighest = Color(0xFF252525),
        canvasSel = it.canvasSel, lightContainer = it.lightContainer, lightOnContainer = it.lightOnContainer,
        canvasStroke = it.canvasStroke, lightCanvasStroke = it.lightCanvasStroke, lightCanvasSel = it.lightCanvasSel,
    )
}

/**
 * ⭐⭐ **Inter** — the concept art's typeface (the user's ask, 2026-10-05),
 * bundled (`res/font`, SIL OFL, `NOTICE`) rather than a downloadable Google
 * font: a sideloaded phone may have no Play services to fetch one.
 * ⚠ [MeasureTextStyle] stays monospace — it is for measurements and logs.
 */
val Inter = FontFamily(
    androidx.compose.ui.text.font.Font(com.abrah.nightmare.R.font.inter_regular, FontWeight.Normal),
    androidx.compose.ui.text.font.Font(com.abrah.nightmare.R.font.inter_medium, FontWeight.Medium),
    androidx.compose.ui.text.font.Font(com.abrah.nightmare.R.font.inter_semibold, FontWeight.SemiBold),
    androidx.compose.ui.text.font.Font(com.abrah.nightmare.R.font.inter_bold, FontWeight.Bold),
)

private val InterTypography: Typography = Typography().let { t ->
    fun TextStyle.inter() = copy(fontFamily = Inter)
    Typography(
        displayLarge = t.displayLarge.inter(), displayMedium = t.displayMedium.inter(),
        displaySmall = t.displaySmall.inter(), headlineLarge = t.headlineLarge.inter(),
        headlineMedium = t.headlineMedium.inter(),
        // ⭐ Violet Studio's scale: screen title 24/30, section 18/24, card title 16/22 (all
        // semibold), label 14/20 medium, supporting 13/18, metadata 12/16.
        headlineSmall = t.headlineSmall.inter().copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.inter().copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.inter().copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.inter(),
        bodyLarge = t.bodyLarge.inter(), bodyMedium = t.bodyMedium.inter(),
        bodySmall = t.bodySmall.inter().copy(fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = t.labelLarge.inter().copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        labelMedium = t.labelMedium.inter(),
        labelSmall = t.labelSmall.inter().copy(fontSize = 12.sp, lineHeight = 16.sp),
    )
}

/**
 * ⭐⭐ One colour per model FAMILY — the concept art's badges (the user's
 * call, 2026-10-05): SD 1.5 blue, the Swaps green, SDXL amber, Anima violet,
 * the DiT families teal, video pink. ⚠ Wherever a family is NAMED — a card's
 * badge, a chip — it wears this, so the colour means the same thing app-wide.
 */
fun familyColor(f: com.abrah.nightmare.Family): Color = when (f) {
    com.abrah.nightmare.Family.SD15 -> Color(0xFF5B8DEF)
    com.abrah.nightmare.Family.SD15_SWAP, com.abrah.nightmare.Family.SDXL_SWAP -> Color(0xFF34C38F)
    com.abrah.nightmare.Family.SDXL -> Color(0xFFF2A93B)
    com.abrah.nightmare.Family.ANIMA -> Color(0xFFA77BFF)
    else -> if (f.dit) Color(0xFF2EC4C4) else Color(0xFF9C95B3)
}

/** ⭐ The video models' badge — not a [com.abrah.nightmare.Family]. */
val VideoColor = Color(0xFFFF6FAE)

/**
 * ⭐ The star that keeps a picture in Results.
 *
 * ⚠⚠ **A colour, not a second glyph.** `material-icons-core` has no outlined
 * star and the extended set costs ~55 MB of dex for one, so the KEPT state is
 * carried by tint instead — amber when kept, grey when not. Asked for from the
 * phone, 2026-09-11.
 *
 * ⚠ Fixed values rather than theme roles: this pair must read the same on the
 * light canvas, the dark canvas and the black fullscreen viewer, and a scheme
 * colour would drift between them.
 */
val StarKept = androidx.compose.ui.graphics.Color(0xFFFFC107)
val StarIdle = androidx.compose.ui.graphics.Color(0xFF9E9E9E)
