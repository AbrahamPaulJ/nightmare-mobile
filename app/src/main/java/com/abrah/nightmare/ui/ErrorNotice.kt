package com.abrah.nightmare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * ⭐⭐ THE way this app says "that did not happen" — a Run that failed, a wire
 * refused at the drop, a download or an import that broke, a flow that would not
 * open.
 *
 * ⚠⚠ There were five treatments for it (`docs/UI.md` §8.5): a red banner, red
 * text in a dark chip, a bare red line above a tab row, red under a node, and a
 * disabled knob's hint in error red. One style now, by the user's call in the
 * design review, 2026-09-15. ⚠ The one exception is a node that failed ON THE
 * CANVAS: that message is drawn under the node it belongs to, because where it is
 * says which node — a chip elsewhere could not.
 *
 * ⚠ A LOCKED knob is not a failure and does not use this.
 */
@Composable
fun ErrorNotice(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = LogTextStyle,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
