package com.abrah.nightmare.ui

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** ⭐ How far a scroll viewport's edge fades: narrow, so little text is hidden (the user's 16-24 dp). */
val FADE_EDGE = 20.dp

/**
 * ⭐⭐ A scroll viewport whose content fades out over [fade] at an edge that has more beyond it, instead of
 * being cut off (the user's call, 2026-10-16, the Default view's node pages). An edge with nothing past it
 * stays sharp, so a page at rest reads as it did.
 *
 * ⚠ Put it on the VIEWPORT: before `verticalScroll` in the chain (or on the `LazyColumn` itself), after any
 * padding that is not scrolled, or it fades the padding and the content is cut as before. ⚠ Offscreen
 * compositing is what lets `DstIn` cut into the content alone, not whatever is drawn behind it.
 */
fun Modifier.fadingEdges(state: ScrollableState, fade: Dp = FADE_EDGE): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val f = fade.toPx().coerceAtMost(size.height / 2)
        if (f <= 0f) return@drawWithContent
        if (state.canScrollBackward) {
            drawRect(
                Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = 0f, endY = f),
                size = Size(size.width, f), blendMode = BlendMode.DstIn,
            )
        }
        if (state.canScrollForward) {
            drawRect(
                Brush.verticalGradient(0f to Color.Black, 1f to Color.Transparent, startY = size.height - f, endY = size.height),
                topLeft = Offset(0f, size.height - f), size = Size(size.width, f), blendMode = BlendMode.DstIn,
            )
        }
    }
