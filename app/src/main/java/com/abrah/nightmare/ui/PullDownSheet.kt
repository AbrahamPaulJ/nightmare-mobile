package com.abrah.nightmare.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * ⭐⭐⭐ **THE sheet that closes by pulling it down** — the node sheet, the
 * inpaint crop/mask window, Models / Flows / Results and Settings. The user's
 * call, 2026-09-26: *"we dont need close icons, just use same functionality of
 * other nodes where we pull down to close"*. One function, so the four cannot
 * disagree about how far a pull has to go (`docs/UI.md` §8).
 *
 * ⚠⚠⚠ **A far longer pull is needed to close it.** Reported 2026-09-21: *"sometimes
 * i try to swipe left right but it closes inspector instead"*. It is Compose
 * gesture arbitration, not a bug: a pager claims a drag once HORIZONTAL touch
 * slop is crossed and the sheet once VERTICAL slop is, and a slightly diagonal
 * swipe can cross the sheet's first. M3 also settles to Hidden on VELOCITY
 * alone, so a quick flick needs almost no distance.
 *
 * ⚠⚠ Material3 1.3.1 exposes neither a dismiss threshold nor
 * `sheetGesturesEnabled` (probed — it does not compile). It does expose
 * `confirmValueChange`, which a gesture-driven settle consults and a
 * programmatic `hide()` does not, so vetoing Hidden here lengthens the PULL and
 * leaves the scrim tap and the back gesture alone.
 *
 * ⚠ Content that scrolls pulls the sheet only once it is back at its TOP
 * (nested scroll) — which is what keeps scrolling down a list from closing it.
 * The crop/mask window's old drag-to-close did not work that way, and closed
 * while the user scrolled to its checkboxes (2026-09-23, `docs/LEGACY.md`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullDownSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dismissAfterPx = with(LocalDensity.current) { 180.dp.toPx() }
    // ⚠ A holder, because `confirmValueChange` is passed INTO the state it
    // needs to read — it cannot reference `sheet` before it exists.
    val held = remember { arrayOfNulls<SheetState>(1) }
    val sheet = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            // ⚠ Defaults to ALLOWING the change: an offset that is not yet
            // measurable must never leave a sheet that cannot be closed.
            target != SheetValue.Hidden ||
                held[0]?.let {
                    runCatching { it.requireOffset() > dismissAfterPx }.getOrDefault(true)
                } ?: true
        },
    )
    held[0] = sheet
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, content = content)
}
