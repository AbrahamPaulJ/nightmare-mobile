package com.abrah.nightmare.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration

/** ⚠ Below this the portrait stack (brand row, views row, seed row, Run row) leaves no canvas. A tablet's landscape is taller. */
private const val SHORT_WINDOW_DP = 480

/**
 * ⭐ A phone held sideways (GitHub #95): one top row, one Run row, Results in two panes, the navigation buttons
 * hidden until swiped in. ⭐ The ONE test, so the shell, the sheets and Results cannot disagree about what landscape is.
 */
@Composable
@ReadOnlyComposable
internal fun shortWindow(): Boolean = LocalConfiguration.current.let {
    it.screenHeightDp < SHORT_WINDOW_DP && it.screenWidthDp > it.screenHeightDp
}

/**
 * ⭐ Sideways, the navigation buttons stay away until swiped in; upright they are back. ⚠ Per WINDOW: a sheet is its own
 * window, and its focus would bring them back over its left edge, so [PullDownSheet] calls this too.
 */
internal fun applySidewaysBars(window: android.view.Window, view: android.view.View, sideways: Boolean) {
    androidx.core.view.WindowCompat.getInsetsController(window, view).run {
        systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        val nav = androidx.core.view.WindowInsetsCompat.Type.navigationBars()
        if (sideways) hide(nav) else show(nav)
    }
}
