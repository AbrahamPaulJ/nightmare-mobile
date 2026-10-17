package com.abrah.nightmare.canvas

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.abrah.nightmare.R
import com.abrah.nightmare.ui.NoteTextStyle
import com.abrah.nightmare.Widget
import com.abrah.nightmare.detail.Detailer
import com.abrah.nightmare.detail.PartDetector
import com.abrah.nightmare.detail.YoloDecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ⭐⭐ **Detection settings** for Fix faces / Fix hands (GitHub #46, the user's pick 2026-10-17: "a sheet like
 * Mask/Crop"): Faces | Hands tabs, each the node's last picture with what the detector finds drawn on it, live,
 * and three sliders under it ([Detailer.Settings]). The row that opens it sits above Fix strength in More options,
 * shown while either fix is on (Fix strength's rule, `hiddenKnob`).
 *
 * ⚠ Called, not copied (`docs/UI.md` §8): the tabs and panel are [InpaintPopupBody], the window [PullDownSheet] at
 * [rememberDialogHeight], the picture [pictureCap] tall, the sliders [SliderRow].
 */
@Composable
internal fun DetectSettingsRow(
    params: Map<String, String>,
    /** The node's last picture ([NodeInspectorBody]'s preview); null before its first render. */
    picture: ImageBitmap?,
    onSetParam: (name: String, value: String) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf<Int?>(null) }
    Row(
        Modifier.fillMaxWidth().clickable { open = 0 }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.detect_settings), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.detect_settings_sub),
                style = NoteTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
    val tab = open ?: return
    val parts = listOf(PartDetector.Part.FACE, PartDetector.Part.HAND)
    val labels = listOf(stringResource(R.string.detect_tab_faces), stringResource(R.string.detect_tab_hands))
    val dialogH = rememberDialogHeight()
    com.abrah.nightmare.ui.PullDownSheet(onDismiss = { open = null }) {
        Box(Modifier.fillMaxWidth().height(dialogH * 0.92f)) {
            InpaintPopupBody(tab, labels, onTab = { open = it }) {
                DetectPanel(parts[tab], params, picture, onSetParam)
            }
        }
    }
}

/** ⭐ One part's tab: the picture with its boxes, what will be fixed, the three sliders, and a reset. */
@Composable
private fun DetectPanel(
    part: PartDetector.Part,
    params: Map<String, String>,
    picture: ImageBitmap?,
    onSetParam: (String, String) -> Unit,
) {
    val context = LocalContext.current
    val set = Detailer.settings(params, part)
    val (kConf, kPad, kBlur) = Detailer.keys(part)
    // ⭐ Detected ONCE per picture at the lowest confidence the slider allows; the slider then only filters, so a
    // drag redraws at once instead of running the model (~100 ms a part) per frame.
    val found by produceState<List<YoloDecode.Box>?>(null, picture, part) {
        value = picture?.let { p ->
            withContext(Dispatchers.Default) {
                PartDetector.detect(context, p.asAndroidBitmap(), part, Detailer.CONF_RANGE.start)
            }
        }
    }
    val installed = remember(part) { PartDetector.isInstalled(context) }
    if (picture == null) {
        Note(stringResource(R.string.detect_render_first))
    } else if (!installed) {
        Note(stringResource(R.string.detect_no_detector))
    } else {
        val boxes = found.orEmpty()
        val kept = Detailer.chosen(boxes.filter { it.score >= set.conf }, picture.width, picture.height)
        DetectPicture(picture, boxes, kept, set)
        Text(
            if (found == null) stringResource(R.string.detect_looking)
            else stringResource(R.string.detect_found, boxes.count { it.score >= set.conf }, kept.size),
            style = MaterialTheme.typography.bodyMedium,
        )
        Note(stringResource(R.string.detect_legend))
    }
    SliderRow(
        Widget(kConf, "float", Detailer.Settings().conf.toString(), Detailer.CONF_RANGE.start.toDouble(), Detailer.CONF_RANGE.endInclusive.toDouble(), hintRes = R.string.hint_detect_conf),
        set.conf.toString(),
        { onSetParam(kConf, it) },
    )
    SliderRow(
        Widget(kPad, "float", Detailer.Settings().pad.toString(), Detailer.PAD_RANGE.start.toDouble(), Detailer.PAD_RANGE.endInclusive.toDouble(), hintRes = R.string.hint_detect_pad),
        set.pad.toString(),
        { onSetParam(kPad, it) },
    )
    SliderRow(
        Widget(kBlur, "float", Detailer.Settings().blur.toString(), Detailer.BLUR_RANGE.start.toDouble(), Detailer.BLUR_RANGE.endInclusive.toDouble(), hintRes = R.string.hint_detect_blur),
        set.blur.toString(),
        { onSetParam(kBlur, it) },
    )
    // ⭐ This part's own strength (GitHub #46): shows the node's Fix strength until it is moved.
    val kDen = Detailer.denoiseKey(part)
    val followed = Detailer.denoiseOf(params - kDen, part)
    SliderRow(
        Widget(kDen, "float", followed.toString(), 0.0, 1.0, hintRes = R.string.hint_detect_denoise),
        Detailer.denoiseOf(params, part).toString(),
        { onSetParam(kDen, it) },
    )
    OutlinedButton(
        onClick = {
            val d = Detailer.Settings()
            onSetParam(kConf, d.conf.toString())
            onSetParam(kPad, d.pad.toString())
            onSetParam(kBlur, d.blur.toString())
            // ⚠ Blank, not a number: back to following Fix strength.
            onSetParam(kDen, "")
        },
        enabled = set != Detailer.Settings(),
    ) { Text(stringResource(R.string.detect_reset)) }
}

/**
 * ⭐ The picture at [pictureCap], every box drawn over it: solid and with its padded oval when it will be fixed,
 * dashed when it is found but skipped. The shaded ring outside a kept oval is the soft edge ([Detailer.Settings.blur]),
 * drawn at its share of the oval's short side (the paste measures it on the cut around it, a little wider).
 */
@Composable
private fun DetectPicture(
    picture: ImageBitmap,
    boxes: List<YoloDecode.Box>,
    kept: List<YoloDecode.Box>,
    set: Detailer.Settings,
) {
    val ratio = picture.width.toFloat() / picture.height
    val cap = pictureCap()
    val keep = MaterialTheme.colorScheme.primary
    val skip = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val w = minOf(maxWidth, cap * ratio)
        val h = w / ratio
        Box(Modifier.size(w, h).clip(RoundedCornerShape(10.dp))) {
            Image(picture, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.size(w, h))
            Canvas(Modifier.size(w, h)) {
                val s = size.width / picture.width
                val line = with(density) { 2.dp.toPx() }
                for (b in boxes) {
                    if (b in kept) continue
                    drawRect(
                        skip, Offset(b.x1 * s, b.y1 * s), Size(b.w * s, b.h * s),
                        style = Stroke(line, pathEffect = PathEffect.dashPathEffect(floatArrayOf(line * 3, line * 3))),
                    )
                }
                for (b in kept) {
                    val g = Detailer.grown(b, picture.width, picture.height, set.pad)
                    val ow = (g[2] - g[0]) * s
                    val oh = (g[3] - g[1]) * s
                    val ring = set.blur * minOf(ow, oh)
                    if (ring > 0.5f) {
                        drawOval(
                            keep.copy(alpha = 0.25f), Offset(g[0] * s - ring / 2, g[1] * s - ring / 2), Size(ow + ring, oh + ring),
                            style = Stroke(ring),
                        )
                    }
                    drawOval(keep, Offset(g[0] * s, g[1] * s), Size(ow, oh), style = Stroke(line))
                    drawRect(keep.copy(alpha = 0.6f), Offset(b.x1 * s, b.y1 * s), Size(b.w * s, b.h * s), style = Stroke(line / 2))
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = NoteTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
