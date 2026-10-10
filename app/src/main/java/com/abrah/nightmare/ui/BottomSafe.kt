package com.abrah.nightmare.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * ⭐⭐ **The bottom edge every bar and sheet keeps clear of** — call this, not
 * `navigationBarsPadding()` (`docs/UI.md` §8).
 *
 * ⚠ The navigation-bar inset alone is not enough: some OEM gesture modes report it as ZERO
 * while the bottom strip still belongs to the system's swipe (an iQOO 12 cut the node sheet's
 * Run button in half, 2026-10-10), and a rounded screen corner eats a flush button too. So: the
 * larger of the navigation bar and the mandatory gesture zone, and never less than [MIN_BOTTOM].
 */
val MIN_BOTTOM = 12.dp

val BottomSafe: WindowInsets
    @Composable get() = WindowInsets.navigationBars
        .union(WindowInsets.mandatorySystemGestures.only(WindowInsetsSides.Bottom))
        .union(WindowInsets(bottom = MIN_BOTTOM))

/** ⭐ The same rule from raw window insets, for code that reads them off a decor view (px). */
fun bottomSafePx(navPx: Int, gesturePx: Int, minPx: Int): Int = maxOf(navPx, gesturePx, minPx)
