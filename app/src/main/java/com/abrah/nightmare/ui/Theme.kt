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
import androidx.compose.ui.unit.sp

/**
 * ⚠ Dynamic color is deliberately absent, not forgotten (docs/UI.md section 1).
 * A wallpaper-derived palette makes every install look like a different app, and
 * node-category colors must be stable across devices or a shared screenshot
 * stops meaning anything.
 */
val NightSurface = Color(0xFF0B0B10)   // keep in step with res/values/colors.xml
private val NightSurfaceHigh = Color(0xFF14141C)
private val NightOutline = Color(0xFF2A2A38)
private val NightOn = Color(0xFFE6E6F0)
private val NightOnMuted = Color(0xFF8E8EA6)

/** The one accent. Node *category* is the only other thing that earns a hue. */
private val Ember = Color(0xFF9B6BFF)

private val NightmareDark = darkColorScheme(
    primary = Ember,
    onPrimary = Color(0xFF12061F),
    surface = NightSurface,
    onSurface = NightOn,
    surfaceVariant = NightSurfaceHigh,
    onSurfaceVariant = NightOnMuted,
    background = NightSurface,
    onBackground = NightOn,
    outline = NightOutline,
    error = Color(0xFFFF6B6B),
)

/**
 * ⚠ Light is a stub. Dark is the shipping theme (a graph canvas is dark
 * everywhere it exists); this exists so a light-mode device is merely plain
 * rather than unreadable.
 */
private val NightmareLight = lightColorScheme(primary = Ember)

/** Monospace for anything an agent or a human reads as a measurement. */
val LogTextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

@Composable
fun NightmareTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) NightmareDark else NightmareLight,
        typography = Typography(),
        content = content,
    )
}

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
