package com.abrah.nightmare.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.abrah.nightmare.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * ⭐⭐⭐ **THE sheet over the canvas** — the node sheet, the inpaint crop/mask
 * window, Models / Flows / Results and Settings. One function, so the four
 * cannot disagree about how they close (`docs/UI.md` §8).
 *
 * ⭐⭐ **Three ways to close, and only three** (the user's call, 2026-10-05):
 *  1. **pull down the TOP BAR** — the rounded strip with the handle;
 *  2. **the ✕** at the right of that bar;
 *  3. **a tap on the GAP above the sheet**, where the canvas shows through
 *     (and the system back gesture, as every screen over the canvas must).
 *
 * ⚠⚠⚠ **The BODY never moves the sheet.** It replaced a Material
 * `ModalBottomSheet` that the whole surface dragged: a horizontal swipe
 * (the node pager, Models' sub-tabs, a slider) and a vertical scroll competed
 * with the sheet's own pull, and a slightly diagonal swipe closed it — reported
 * 2026-09-21 and again 2026-10-05 (*"the sideway and vertical scrolls competing
 * is not ideal"*). A longer pull threshold only made the race rarer; a sheet
 * whose body cannot drag it removes it. `docs/LEGACY.md` §9.
 */
/**
 * ⭐⭐ Scrolling inside stays inside: what a list cannot use — its overscroll at
 * the top, a fling past its end — is consumed here. ⚠ Kept since the sheet
 * stopped listening to its body (it is a no-op for the sheet now): a list
 * nested in another scrolling parent still must not hand its overscroll on.
 */
fun Modifier.keepScrollInside(): Modifier = nestedScroll(ConsumeRemaining)

private object ConsumeRemaining : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = available
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

/** ⚠ The gap above the sheet, under the status bar: tall enough to aim a thumb at. */
private val GAP = 56.dp

/**
 * ⚠ The top bar — the ONLY part that drags. 64dp since 1.6.081: the user asked
 * for a bigger target than 44 (2026-10-05).
 */
private val BAR = 64.dp

/** ⚠ Space between the bar and the content (asked for with the bigger bar). */
private val BAR_GAP = 12.dp

@Composable
fun PullDownSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // ⭐ Slides up from below on open, down on close; the drag writes it too.
    val offset = remember { Animatable(with(density) { 2000.dp.toPx() }) }
    var sheetH by remember { mutableStateOf(0f) }
    var closing by remember { mutableStateOf(false) }
    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            offset.animateTo(sheetH.coerceAtLeast(1f), tween(180))
            onDismiss()
        }
    }
    Dialog(
        onDismissRequest = ::close,
        // ⚠⚠⚠ EDGE TO EDGE, padded by the measured bottom inset (1.6.083).
        // 1.6.082 fitted the window above the bars (`dumpsys`: h=2109 of 2340)
        // — but targetSdk 35 IGNORES `navigationBarColor`, so a black band sat
        // under the sheet and the list ran flush into it (phone, 2026-10-06,
        // 3-button nav). Now the sheet's colour runs under the nav bar and its
        // CONTENT is padded by [bottomInset]. `NmSheet` logs the numbers.
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // ⚠ The window's own dim off: the scrim below is drawn here so it can
        // fade with the slide, and a tap on it is OUR close (with the animation).
        // ⚠ …and the window the full screen, which a Compose dialog is not.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val navColor = MaterialTheme.colorScheme.surfaceContainerLow
        SideEffect {
            window?.setDimAmount(0f)
            // ⚠⚠⚠ Set HERE, on the window: `DialogProperties(decorFitsSystemWindows
            // = false)` alone left it fitted (`dumpsys`: fitTypes=10f, h=2109 —
            // phone, 1.6.084), so the band under the sheet stayed black.
            window?.let {
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(it, false)
                it.attributes = it.attributes.apply { fitInsetsTypes = 0 }
            }
            window?.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            )
            @Suppress("DEPRECATION")
            window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            // ⚠ Pre-35 only — the sheet itself is drawn under the bar now.
            @Suppress("DEPRECATION")
            window?.navigationBarColor = navColor.toArgb()
            window?.isNavigationBarContrastEnforced = false
        }
        val bottomPad = bottomInset(window)
        LaunchedEffect(Unit) { offset.animateTo(0f, tween(220)) }
        val shown = if (sheetH > 0f) (1f - offset.value / sheetH).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxSize()) {
            // ③ the gap and everything around the sheet: a tap closes.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.40f * shown))
                    .pointerInput(Unit) { detectTapGestures { close() } },
            )
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(top = GAP)
                    .fillMaxWidth()
                    .onSizeChanged { sheetH = it.height.toFloat() }
                    .offset { IntOffset(0, offset.value.roundToInt()) }
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    // ⚠ Swallows taps so a tap on the BODY never reaches the scrim.
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                // ①② The top bar: the handle drags, the ✕ closes.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(BAR)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { d ->
                                scope.launch { offset.snapTo((offset.value + d).coerceAtLeast(0f)) }
                            },
                            onDragStopped = { v ->
                                // ⚠ A third of the sheet, or a real flick, closes it.
                                if (offset.value > sheetH / 3f || v > 1800f) close()
                                else offset.animateTo(0f, tween(160))
                            },
                        ),
                ) {
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .width(56.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)),
                    )
                    IconButton(
                        onClick = ::close,
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp).size(40.dp),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.cd_close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.height(BAR_GAP))
                // ⚠ CONSUMED as well as padded: the bodies' own `navigationBarsPadding()`
                // (Library, node sheet, Settings) then read zero instead of doubling it.
                val padded = androidx.compose.foundation.layout.PaddingValues(bottom = bottomPad)
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .padding(padded)
                        .consumeWindowInsets(padded),
                ) { content() }
            }
        }
    }
}

/**
 * ⭐ The sheet's bottom inset: the nav bar, or the keyboard when it is up.
 *
 * ⚠⚠⚠ From a LISTENER on the dialog's decor view — measured on the phone
 * (1.6.083, `NmSheet`): inside this dialog Compose's `WindowInsets` stay 0
 * for its whole life (as 1.6.080 found), and `view.rootWindowInsets` read at
 * composition is null before the window attaches. The listener records the
 * insets and CONSUMES them, so no layout under the decor pads a second time.
 */
@Composable
private fun bottomInset(window: android.view.Window?): androidx.compose.ui.unit.Dp {
    val density = LocalDensity.current
    var px by remember { mutableStateOf(0) }
    androidx.compose.runtime.DisposableEffect(window) {
        val decor = window?.decorView
        if (decor != null) {
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(decor) { v, insets ->
                val nav = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
                val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
                // ⭐⭐ The user's words, 2026-10-06: *"leave some space alone at
                // the bottom the size of a nav bar"* — the list ending flush at
                // the bar read as the bar covering it. So: the bar, PLUS a clear
                // band the bar's height above it. With the keyboard up, its top.
                val want = if (ime > nav) ime else nav * 2
                if (want != px) {
                    px = want
                    android.util.Log.i("NmSheet", "inset nav=$nav ime=$ime -> $px px")
                }
                // ⚠⚠ CONSUMED here: the dialog decor's own layout is
                // `fitsSystemWindows`, and handed these it padded the content
                // 135 px above the nav bar — the black band again (1.6.085).
                // The sheet is the only thing that pads, by [px].
                androidx.core.view.WindowInsetsCompat.CONSUMED
            }
            decor.requestApplyInsets()
        }
        onDispose { decor?.let { androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(it, null) } }
    }
    return with(density) { px.toDp() }
}
