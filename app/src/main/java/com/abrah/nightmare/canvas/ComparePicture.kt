package com.abrah.nightmare.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.abrah.nightmare.R
import kotlin.math.roundToInt

/** ⭐ The handle's size, and the room each side of the picture that lets it rest clear of every pixel. */
private val HANDLE = 28.dp
private val SIDE_ROOM = 32.dp

/**
 * ⭐⭐ **Before and after on the output** (a tester's wish, Saved #7180; the user's mock and calls, 2026-10-25): the
 * result, and the picture the flow started from (img2img, an edit, a style change) behind a divider. At rest the
 * handle sits in the room RIGHT of the picture, so the output shows whole and nothing covers it ("starting pos of
 * slider shouldnt obstruct the output image pixels"); drag it left and the input shows to the right of the line.
 * The room is on BOTH sides, so the picture stays centred.
 *
 * ⚠ [beforeRect] is the sampler's framing of its input (`x`/`y`/`w`/`h`, normalised), so both sides show the
 * same region; a framing that runs past the photo's edge shows the output there.
 * ⚠ Only a horizontal drag moves the line: a tap still opens the picture full screen ([onClick]), and the sheet
 * still scrolls vertically.
 */
@Composable
internal fun ComparePicture(
    after: ImageBitmap,
    before: ImageBitmap,
    beforeRect: CropRect,
    contentDescription: String,
    box: Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val aspect = after.width.toFloat() / after.height.coerceAtLeast(1)
    val density = LocalDensity.current
    val roomPx = with(density) { SIDE_ROOM.toPx() }
    val handlePx = with(density) { HANDLE.toPx() }
    var frame by remember { mutableStateOf(IntSize.Zero) }
    // The line's x within the picture, px. Past its width = at rest, in the room on the right.
    var lineX by remember(after, before) { mutableFloatStateOf(Float.MAX_VALUE) }
    val restX = frame.width + roomPx / 2f
    val x = lineX.coerceIn(0f, restX)
    val shownBefore = if (frame.width == 0) 0f else (1f - x / frame.width).coerceIn(0f, 1f)
    val label = stringResource(R.string.compare_handle_cd)
    val state = stringResource(R.string.compare_handle_state, (shownBefore * 100).roundToInt())

    Box(box.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                // ⚠ The drag BEFORE the padding: the handle rests in the room, which must be grabbable too.
                .pointerInput(restX) {
                    detectHorizontalDragGestures { change, dx ->
                        change.consume()
                        lineX = (lineX.coerceIn(0f, restX) + dx).coerceIn(0f, restX)
                    }
                }
                .padding(horizontal = SIDE_ROOM)
                .aspectRatio(aspect, matchHeightConstraintsFirst = true)
                .onSizeChanged { frame = it },
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                    .clickable(onClick = onClick)
                    .semantics { this.contentDescription = contentDescription },
            ) {
                val dst = IntSize(size.width.roundToInt(), size.height.roundToInt())
                drawImage(after, dstSize = dst)
                if (x < size.width) {
                    val r = beforeRect
                    val sx = (r.x.coerceIn(0f, 1f) * before.width).roundToInt()
                    val sy = (r.y.coerceIn(0f, 1f) * before.height).roundToInt()
                    val sw = (r.w.coerceIn(0f, 1f - r.x.coerceIn(0f, 1f)) * before.width).roundToInt().coerceAtLeast(1)
                    val sh = (r.h.coerceIn(0f, 1f - r.y.coerceIn(0f, 1f)) * before.height).roundToInt().coerceAtLeast(1)
                    clipRect(left = x) {
                        drawImage(before, srcOffset = IntOffset(sx, sy), srcSize = IntSize(sw, sh), dstSize = dst)
                    }
                }
            }
            // The line, over the picture while it divides it, in the room on the right at rest.
            val lineDp = with(density) { x.toDp() }
            Box(
                Modifier
                    .offset(x = lineDp - 1.dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.onSurface),
            )
            Row(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset((x - handlePx / 2f).roundToInt(), 0) }
                    .size(HANDLE)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    .semantics {
                        this.contentDescription = label
                        stateDescription = state
                    },
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tint = MaterialTheme.colorScheme.onSurface
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, Modifier.size(12.dp), tint = tint)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(12.dp), tint = tint)
            }
        }
    }
}
