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
val NightSurface = Color(0xFF0D0A14)   // keep in step with res/values/colors.xml
private val NightSurfaceHigh = Color(0xFF1C1729)
private val NightOutline = Color(0xFF2F2840)
private val NightOn = Color(0xFFECE8F6)
private val NightOnMuted = Color(0xFF9C95B3)

/** The one accent. Node *category* and model *family* are the only other hues. */
private val Ember = Color(0xFF8B5CF6)

private val NightmareDark = darkColorScheme(
    primary = Ember,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3B2A6B),
    onPrimaryContainer = Color(0xFFE9DDFF),
    secondaryContainer = Color(0xFF2A2140),
    onSecondaryContainer = Color(0xFFE6DEFF),
    surface = NightSurface,
    onSurface = NightOn,
    surfaceVariant = NightSurfaceHigh,
    onSurfaceVariant = NightOnMuted,
    surfaceTint = Ember,
    surfaceContainerLowest = Color(0xFF09070F),
    surfaceContainerLow = Color(0xFF13101C),
    surfaceContainer = Color(0xFF181424),
    surfaceContainerHigh = Color(0xFF201A2E),
    surfaceContainerHighest = Color(0xFF282138),
    background = NightSurface,
    onBackground = NightOn,
    outline = NightOutline,
    outlineVariant = Color(0xFF2A2338),
    error = Color(0xFFFF6B6B),
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
    secondaryContainer = Color(0xFFEDE5FF),
    onSecondaryContainer = Color(0xFF21143F),
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
)

/**
 * ⭐ The small Inter text every panel, card and note uses. Was monospace
 * (`LogTextStyle`) until the user found Inter "not changed" on 1.6.082 —
 * 2026-10-06 they chose Inter everywhere except true measurements.
 */
val NoteTextStyle: TextStyle by lazy { TextStyle(fontFamily = Inter, fontSize = 12.sp) }

/**
 * ⚠ Monospace ONLY for a true measurement — the run log, the backend log,
 * a progress counter, a size in pixels or MB. Prose goes in [NoteTextStyle].
 */
val MeasureTextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

@Composable
fun NightmareTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // ⚠ Before the content composes, so the canvas's first frame is already
    // the right one. A state write: flipping the theme redraws the canvas.
    com.abrah.nightmare.canvas.CanvasColors.light = !darkTheme
    MaterialTheme(
        colorScheme = if (darkTheme) NightmareDark else NightmareLight,
        typography = InterTypography,
        shapes = androidx.compose.material3.Shapes(
            small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        ),
        content = content,
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
        headlineMedium = t.headlineMedium.inter(), headlineSmall = t.headlineSmall.inter(),
        titleLarge = t.titleLarge.inter().copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.inter().copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.inter(),
        bodyLarge = t.bodyLarge.inter(), bodyMedium = t.bodyMedium.inter(), bodySmall = t.bodySmall.inter(),
        labelLarge = t.labelLarge.inter(), labelMedium = t.labelMedium.inter(), labelSmall = t.labelSmall.inter(),
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
