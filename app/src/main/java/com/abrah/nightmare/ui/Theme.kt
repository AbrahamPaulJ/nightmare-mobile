package com.abrah.nightmare.ui

import androidx.compose.foundation.isSystemInDarkTheme
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
 * ⚠ Dynamic color is deliberately absent, not forgotten (docs/UI.md section 1).
 * A wallpaper-derived palette makes every install look like a different app, and
 * node-category colors must be stable across devices or a shared screenshot
 * stops meaning anything.
 */
/**
 * ⭐⭐ The 2026-10-05 look — the user's concept art: a deep violet-black ground,
 * cards one step lighter, a violet primary. ⚠ Every container role is SET:
 * Material's dark defaults are blue-grey and showed through on any surface
 * that asked for one this scheme left out.
 */
val NightSurface = Color(0xFF0D0914)   // keep in step with res/values/colors.xml

// ⭐⭐ Violet Studio (the user's design pack, 2026-10-08) — its exact tokens. Plum cards a step
// over a near-black ground, one violet for filled actions (#8B4DEE: white on it is 4.78:1;
// the brighter #985CFF is the ACCENT — focus, accent text — never a button fill).
private val NightCard = Color(0xFF1B1428)        // surface: cards, fields, navigation
private val NightRaised = Color(0xFF251B36)      // surfaceRaised: sheets, selected secondary
private val NightBorder = Color(0xFF3A2B50)
private val NightOn = Color(0xFFF4EFFA)
private val NightOnMuted = Color(0xFFB6A6C8)     // 7.88:1 on a card

/** The one accent. Node *category* and model *family* are the only other hues. */
private val Ember = Color(0xFF8B4DEE)
/** ⭐ Focus outlines and accent text on dark — not a fill. */
val Accent = Color(0xFF985CFF)
/** ⭐ Ready / succeeded. ⚠ Readiness only — never unsaved work. */
val Success = Color(0xFF6FE3AE)
/** ⭐ Unsaved changes, caution. */
val Warning = Color(0xFFF4C16C)

/**
 * ⭐ The Black theme (a tester's wish, 2026-10-24: "an AMOLED black theme"): [NightmareDark] with true-black
 * background and surfaces, so an OLED screen's unlit pixels stay off. Cards and sheets step up in near-black
 * neutrals so they still read as layers; the violet accents are unchanged.
 */
private val NightmareBlack by lazy {
    NightmareDark.copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF0A080E),
        surfaceContainer = Color(0xFF131018),
        surfaceVariant = Color(0xFF131018),
        surfaceContainerHigh = Color(0xFF1C1724),
        surfaceContainerHighest = Color(0xFF26202F),
    )
}

private val NightmareDark = darkColorScheme(
    primary = Ember,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF35204F),
    onPrimaryContainer = NightOn,
    secondary = Accent,
    secondaryContainer = Color(0xFF35204F),
    onSecondaryContainer = NightOn,
    surface = NightSurface,
    onSurface = NightOn,
    surfaceVariant = NightCard,
    onSurfaceVariant = NightOnMuted,
    surfaceTint = Ember,
    surfaceContainerLowest = Color(0xFF09060F),
    surfaceContainerLow = Color(0xFF140F1F),
    surfaceContainer = NightCard,
    surfaceContainerHigh = NightRaised,
    surfaceContainerHighest = Color(0xFF2E2242),
    background = NightSurface,
    onBackground = NightOn,
    outline = NightBorder,
    outlineVariant = Color(0xFF2C2140),
    // ⚠⚠ Every remaining role SET (the review, 2026-10-22): an unset `tertiary` fell back
    // to Material's baseline PINK on the LoRA picker and Models, and the default `onError`
    // is WHITE, which on #FF7B92 is 2.47:1, the label of every confirmed delete.
    tertiary = Accent,
    onTertiary = NightSurface,
    tertiaryContainer = Color(0xFF35204F),
    onTertiaryContainer = NightOn,
    onSecondary = NightSurface,
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
 * ⚠ The accent is still [Ember], deepened a step so it holds contrast on white.
 */
private val LightOn = Color(0xFF16161D)
private val LightOnMuted = Color(0xFF5E5E6E)

private val NightmareLight = lightColorScheme(
    primary = Color(0xFF7C4DFF),
    // ⚠ The containers SET too: left out, Material's lavender came back in a selected card.
    primaryContainer = Color(0xFFEDE5FF),
    onPrimaryContainer = Color(0xFF21143F),
    secondary = Color(0xFF6E3BD8),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE5FF),
    onSecondaryContainer = Color(0xFF21143F),
    tertiary = Color(0xFF6E3BD8),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEDE5FF),
    onTertiaryContainer = Color(0xFF21143F),
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

private val DarkStudio = StudioColors(
    accentText = Color(0xFFB08AFF),
    controlBorder = Color(0xFF74609A),
    success = Success,
    warning = Warning,
)

private val LightStudio = StudioColors(
    accentText = Color(0xFF6E3BD8),
    controlBorder = Color(0xFF85859A),
    success = Color(0xFF1B7F52),
    warning = Color(0xFF8A5A00),
)

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
    /** ⭐ The Black theme ([NightmareBlack]); only with [darkTheme]. */
    black: Boolean = false,
    content: @Composable () -> Unit,
) {
    // ⚠ Before the content composes, so the canvas's first frame is already
    // the right one. A state write: flipping the theme redraws the canvas.
    com.abrah.nightmare.canvas.CanvasColors.light = !darkTheme
    com.abrah.nightmare.canvas.CanvasColors.black = black && darkTheme
    MaterialTheme(
        colorScheme = if (!darkTheme) NightmareLight else if (black) NightmareBlack else NightmareDark,
        typography = InterTypography,
        shapes = StudioShapes,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalStudioColors provides if (darkTheme) DarkStudio else LightStudio,
            content = content,
        )
    }
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
