package com.abrah.nightmare.canvas

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.abrah.nightmare.AddObjects
import com.abrah.nightmare.IpAdapter
import com.abrah.nightmare.ModelCatalog
import com.abrah.nightmare.Node
import com.abrah.nightmare.NodeType
import com.abrah.nightmare.R
import com.abrah.nightmare.SdSampler
import com.abrah.nightmare.ui.ErrorNotice
import com.abrah.nightmare.ui.LogTextStyle

/**
 * ⭐⭐ The square IP-Adapter will read — [IpAdapter.square] of the `reference`
 * wire's picture (cut by the node's REF region, as the run cuts it), else of the
 * picture picked on the node. Off the main thread; null with no picture.
 */
@Composable
internal fun rememberIpSquare(node: Node, type: NodeType?, wired: ImageBitmap?): ImageBitmap? {
    val ctx = LocalContext.current
    val live = com.abrah.nightmare.applyDefaults(type?.widgets.orEmpty(), node)
    val uri = live[SdSampler.IP_IMAGE].orEmpty()
    val region = listOf(SdSampler.REF_X, SdSampler.REF_Y, SdSampler.REF_W, SdSampler.REF_H).map { live[it] }
    return rememberOffMain(node.id, "ip square", uri, wired, region) {
        val src = wired?.asAndroidBitmap()?.let { b ->
            com.abrah.nightmare.CropNode.render(
                b,
                region[0]?.toFloatOrNull() ?: 0f, region[1]?.toFloatOrNull() ?: 0f,
                region[2]?.toFloatOrNull() ?: 1f, region[3]?.toFloatOrNull() ?: 1f,
                0, 0, com.abrah.nightmare.CropNode.PAD_BLACK,
            ).first
        } ?: uri.takeIf { it.isNotBlank() }?.let { AddObjects.load(ctx, it) }
            ?: return@rememberOffMain null
        IpAdapter.square(src).asImageBitmap()
    }
}

/**
 * ⭐⭐ SD 1.5 Swap's **IP-Adapter** tab — a picture tile beside ControlNet
 * (`docs/UI.md` §8.12): the adapter ([Chooser]), strength ([SliderRow]), where
 * the reference comes from (the `reference` wire or a picture picked here), the
 * download ([com.abrah.nightmare.ui.ToolCard], §8.1) and the square the encoder
 * reads. ControlNetPanel's shape, control for control.
 */
@Composable
internal fun IpAdapterPanel(
    node: Node,
    type: NodeType?,
    /** The picture wired into `reference`, if any. */
    wired: ImageBitmap?,
    square: ImageBitmap?,
    busy: Boolean = false,
    rows: Map<String, com.abrah.nightmare.ui.ToolRow> = emptyMap(),
    onInstall: ((String) -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    onDelete: ((String) -> Unit)? = null,
    onSetParam: (String, String) -> Unit,
) {
    val ctx = LocalContext.current
    val live = com.abrah.nightmare.applyDefaults(type?.widgets.orEmpty(), node)
    val adapter = live[SdSampler.IP_ADAPTER].orEmpty().ifBlank { IpAdapter.PLUS }
    val uri = live[SdSampler.IP_IMAGE].orEmpty()
    val pick = rememberImagePick { onSetParam(SdSampler.IP_IMAGE, it) }
    val spec = ModelCatalog.byId(live["model"].orEmpty())
    val modelDir = spec?.dir(ctx)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ⚠ A Swap model converted before IP-Adapter has no K/V inputs: say so
        // first, since nothing below would reach the render. A built-in one is
        // re-downloaded, not reconverted.
        if (modelDir != null && !IpAdapter.supports(modelDir)) {
            ErrorNotice(
                if (spec in ModelCatalog.builtIn) stringResource(R.string.ip_old_builtin, spec!!.label)
                else stringResource(R.string.ip_old_model),
            )
        }
        Chooser(
            label = stringResource(R.string.ip_label),
            hint = stringResource(R.string.ip_hint),
            options = IpAdapter.ADAPTERS,
            current = adapter,
            onPick = { onSetParam(SdSampler.IP_ADAPTER, it) },
        )
        val row = rows[IpAdapter.rowKey(adapter)]
        if (row != null && (!row.installed || row.progress != null)) {
            ErrorNotice(stringResource(R.string.ip_not_installed, IpAdapter.label(adapter)))
            com.abrah.nightmare.ui.ToolCard(
                row = row,
                busy = busy,
                onInstall = { onInstall?.invoke(IpAdapter.rowKey(adapter)) },
                onCancel = { onCancel?.invoke() },
                detail = stringResource(R.string.ip_about),
                onDelete = { onDelete?.invoke(IpAdapter.rowKey(adapter)) },
            )
        }
        type?.widgets?.firstOrNull { it.name == SdSampler.IP_SCALE }?.let { w ->
            SliderRow(widget = w, current = live[w.name].orEmpty(), onSet = { onSetParam(w.name, it) })
        }
        if (wired != null) {
            Text(
                stringResource(R.string.ip_from_wire),
                style = LogTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // ⚠ Destructive first, primary last (`docs/UI.md` §8.1).
                if (uri.isNotBlank()) {
                    OutlinedButton(onClick = { onSetParam(SdSampler.IP_IMAGE, "") }) {
                        Text(stringResource(R.string.cn_clear))
                    }
                }
                Button(onClick = pick) {
                    Text(stringResource(if (uri.isBlank()) R.string.cn_pick else R.string.cn_replace))
                }
            }
        }
        if (square != null) {
            Text(stringResource(R.string.ip_sees), style = MaterialTheme.typography.titleSmall)
            Image(
                bitmap = square,
                contentDescription = stringResource(R.string.ip_sees),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        } else {
            Text(
                stringResource(R.string.ip_no_picture),
                style = LogTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
