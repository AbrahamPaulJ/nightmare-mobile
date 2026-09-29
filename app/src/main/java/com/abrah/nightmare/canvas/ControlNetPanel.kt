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
import com.abrah.nightmare.Node
import com.abrah.nightmare.NodeType
import com.abrah.nightmare.R
import com.abrah.nightmare.SdSampler
import com.abrah.nightmare.SwapInputs
import com.abrah.nightmare.ui.ErrorNotice
import com.abrah.nightmare.ui.LogTextStyle

/** What the ControlNet tile shows: the hint, or why there is none. */
internal class ControlHint(val bitmap: ImageBitmap?, val needsPoseDetector: Boolean)

/**
 * ⭐⭐ The hint an SD 1.5 Swap node's ControlNet will see, made by the SAME
 * [SwapInputs.hint] the sampler sends, off the main thread: the `control` wire's
 * picture, else the one picked on the node, else the node's own [photo] — cut by
 * the node's crop window whenever a photo is wired ([SdSampler.swapFrame]).
 * ⚠ Keyed on the frame, so moving the crop redraws it. Null with ControlNet at
 * `none` or no picture.
 */
@Composable
internal fun rememberControlHint(
    node: Node,
    type: NodeType?,
    wired: ImageBitmap?,
    photo: ImageBitmap?,
    poseInstalled: Boolean,
): ControlHint? {
    val ctx = LocalContext.current
    val live = com.abrah.nightmare.applyDefaults(type?.widgets.orEmpty(), node)
    val cn = live[SdSampler.CONTROLNET].orEmpty().ifBlank { SwapInputs.NONE }
    val uri = live[SdSampler.CONTROL_IMAGE].orEmpty()
    val frame = if (photo != null) SdSampler.swapFrame(live) else null
    return rememberOffMain(node.id, "control hint", cn, uri, wired, photo, frame, poseInstalled) {
        if (cn == SwapInputs.NONE) return@rememberOffMain null
        val src = wired?.asAndroidBitmap()
            ?: uri.takeIf { it.isNotBlank() }?.let { AddObjects.load(ctx, it) }
            ?: photo?.asAndroidBitmap()
            ?: return@rememberOffMain null
        val made = SwapInputs.hint(ctx, src, cn, frame)
        ControlHint(made.bitmap?.asImageBitmap(), made.needsPoseDetector)
    }
}

/**
 * ⭐⭐ SD 1.5 Swap's **ControlNet** tab — one of the node's picture tiles
 * (`docs/UI.md` §8.12), because the control picture is a picture on the node.
 *
 * Type ([Chooser]), strength ([SliderRow]), where the picture comes from (the
 * `control` wire, a picked picture, or the node's own photo), and the hint
 * itself — canny's edges, the detected skeleton, or the depth map as sent —
 * so a person sees what the ControlNet will read. The pose detector's download
 * is offered HERE when a photo needs it ([com.abrah.nightmare.ui.ToolCard],
 * `docs/UI.md` §8.1). The user's design, 2026-09-29.
 */
@Composable
internal fun ControlNetPanel(
    node: Node,
    type: NodeType?,
    /** The picture wired into `control`, if any. */
    wired: ImageBitmap?,
    /** The node's own photo (the `image` wire), if any — the default control picture. */
    photo: ImageBitmap?,
    hint: ControlHint?,
    poseRow: com.abrah.nightmare.ui.ToolRow? = null,
    busy: Boolean = false,
    onInstallPose: (() -> Unit)? = null,
    onCancelPose: (() -> Unit)? = null,
    onDeletePose: (() -> Unit)? = null,
    onSetParam: (String, String) -> Unit,
) {
    val ctx = LocalContext.current
    val live = com.abrah.nightmare.applyDefaults(type?.widgets.orEmpty(), node)
    val cn = live[SdSampler.CONTROLNET].orEmpty().ifBlank { SwapInputs.NONE }
    val uri = live[SdSampler.CONTROL_IMAGE].orEmpty()
    val pick = rememberImagePick { onSetParam(SdSampler.CONTROL_IMAGE, it) }
    // ⚠⚠ NOT `verticalScroll`: the popup body already scrolls its panel, and a
    // scrolling column inside it crashes the tab on open (the golden caught it).
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Chooser(
            label = stringResource(R.string.cn_label),
            hint = stringResource(R.string.cn_hint),
            options = SwapInputs.TYPES,
            current = cn,
            onPick = { onSetParam(SdSampler.CONTROLNET, it) },
        )
        if (cn == SwapInputs.NONE) return@Column
        if (!SwapInputs.controlnetFile(ctx, cn).isFile) {
            ErrorNotice(stringResource(R.string.cn_not_installed, cn))
        }
        type?.widgets?.firstOrNull { it.name == SdSampler.CONTROL_STRENGTH }?.let { w ->
            SliderRow(
                widget = w,
                current = live[w.name].orEmpty(),
                onSet = { onSetParam(w.name, it) },
            )
        }
        when {
            wired != null -> Text(
                stringResource(R.string.cn_from_wire),
                style = LogTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            uri.isBlank() && photo != null -> Text(
                stringResource(R.string.cn_from_photo),
                style = LogTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (wired == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // ⚠ Destructive first, primary last (`docs/UI.md` §8.1).
                if (uri.isNotBlank()) {
                    OutlinedButton(onClick = { onSetParam(SdSampler.CONTROL_IMAGE, "") }) {
                        Text(stringResource(R.string.cn_clear))
                    }
                }
                Button(onClick = pick) {
                    Text(stringResource(if (uri.isBlank()) R.string.cn_pick else R.string.cn_replace))
                }
            }
        }
        if (hint?.needsPoseDetector == true || (cn == SwapInputs.OPENPOSE && poseRow?.progress != null)) {
            ErrorNotice(stringResource(R.string.cn_pose_missing))
            poseRow?.let { row ->
                com.abrah.nightmare.ui.ToolCard(
                    row = row,
                    busy = busy,
                    onInstall = { onInstallPose?.invoke() },
                    onCancel = { onCancelPose?.invoke() },
                    detail = stringResource(R.string.pose_about),
                    onDelete = { onDeletePose?.invoke() },
                )
            }
        }
        val shown = hint?.bitmap
        if (shown != null) {
            Text(stringResource(R.string.cn_sees), style = MaterialTheme.typography.titleSmall)
            Image(
                bitmap = shown,
                contentDescription = stringResource(R.string.cn_sees),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        } else if (wired == null && uri.isBlank() && photo == null) {
            Text(
                stringResource(
                    when (cn) {
                        SwapInputs.CANNY -> R.string.cn_no_picture_photo
                        SwapInputs.OPENPOSE -> R.string.cn_no_picture_pose
                        else -> R.string.cn_no_picture_hint
                    },
                ),
                style = LogTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
