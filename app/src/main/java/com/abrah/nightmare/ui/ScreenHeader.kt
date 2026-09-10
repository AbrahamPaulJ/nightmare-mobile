package com.abrah.nightmare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * ⭐⭐ The top of every screen that sits OVER the canvas — Models, Flows and the
 * op harness.
 *
 * ⚠⚠ One composable rather than three headers, because three had already
 * drifted into three different answers to the same question: Models said
 * "Back", Flows said "Back to canvas" beside a second button, and the harness
 * drew an arrow icon. Same gesture, same destination, three shapes and two
 * type sizes — a user learns the corner, not the wording, and the corner was
 * the only thing they agreed on.
 *
 * ⭐ **A close icon, not a back arrow.** None of these is a step in a journey;
 * each is a panel over the canvas, and the canvas is always what is behind it.
 * An arrow promises history that does not exist — there is nowhere else to go
 * back to — where an ✕ says "put this away", which is what the tap does.
 *
 * ⚠ The icon stays in the SAME CORNER the canvas puts the control that opened
 * the screen, so the trip out and the trip back are one thumb position.
 */
@Composable
fun ScreenHeader(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Shown just before the ✕ — the harness puts its version here. */
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ⚠⚠ The colour is EXPLICIT, and that is a bug fix. Models and Flows
        // set none, so their titles took `LocalContentColor`, which outside a
        // `Surface` is BLACK -- drawn on a 0xFF0B0B10 canvas. Both headlines
        // were very nearly invisible, on every build, and eleven goldens had
        // pinned them that way: a golden proves the pixels have not CHANGED,
        // never that they were right (docs/UI.md §5). Only the harness escaped,
        // because its header alone named a colour.
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            trailing()
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Filled.Close,
                    // ⚠ Names the DESTINATION, not the glyph. "close" tells a
                    // screen-reader user nothing they cannot already tell.
                    contentDescription = "close $title — back to the canvas",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
