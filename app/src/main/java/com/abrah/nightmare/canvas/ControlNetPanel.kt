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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.abrah.nightmare.AddObjects
import com.abrah.nightmare.Node
import com.abrah.nightmare.NodeType
import com.abrah.nightmare.R
import com.abrah.nightmare.SdSampler
import com.abrah.nightmare.SwapInputs
import com.abrah.nightmare.ui.ErrorNotice
import com.abrah.nightmare.ui.NoteTextStyle

/**
 * ⭐ The node's photo as its base will see it ([SwapInputs.framedPhoto]) — the
 * layer a ControlNet or IP-Adapter picture is cropped over. Null with no photo.
 */
@Composable
internal fun rememberFramedPhoto(node: Node, type: NodeType?, photo: ImageBitmap?): ImageBitmap? {
    val live = com.abrah.nightmare.applyDefaults(type?.widgets.orEmpty(), node)
    val frame = SdSampler.swapFrame(node.type, live)
    return rememberOffMain(node.id, "framed photo", photo, frame) {
        photo?.let { SwapInputs.framedPhoto(it.asAndroidBitmap(), frame).asImageBitmap() }
    }
}

/**
 * ⭐⭐ Crop a ControlNet / IP-Adapter picture — the SAME [CropEditor] as every
 * other crop window (`docs/UI.md` §8.8), square because both are read square,
 * over the framed photo when there is one so the two can be lined up.
 */
@Composable
internal fun PictureCrop(
    source: ImageBitmap,
    rect: CropRect,
    onChange: (CropRect) -> Unit,
    underlay: ImageBitmap?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CropEditor(
            source = source,
            rect = rect,
            onChange = onChange,
            interactive = true,
            outW = source.width,
            aspect = 1f,
            pad = null,
            rule = com.abrah.nightmare.PadRule.WHEN_TOO_SMALL,
            underlay = underlay,
        )
        Text(
            stringResource(if (underlay != null) R.string.crop_over_photo else R.string.crop_gestures),
            style = NoteTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** What the ControlNet tile shows: the hint, or which estimator it is waiting for. */
internal class ControlHint(val bitmap: ImageBitmap?, val missing: String?)

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
    depthInstalled: Boolean,
): ControlHint? {
    val ctx = LocalContext.current
    val live = com.abrah.nightmare.applyDefaults(type?.widgets.orEmpty(), node)
    val cn = live[SdSampler.CONTROLNET].orEmpty().ifBlank { SwapInputs.NONE }
    val uri = live[SdSampler.CONTROL_IMAGE].orEmpty()
    // ⚠ The run's own rule ([SdSampler.controlIsPhoto]): the photo follows the
    // node's frame, any other picture its own crop ([SdSampler.CTL_X]).
    val fromPhoto = SdSampler.controlIsPhoto(node)
    val frame = if (photo != null && fromPhoto) SdSampler.swapFrame(node.type, live) else null
    val region = listOf(SdSampler.CTL_X, SdSampler.CTL_Y, SdSampler.CTL_W, SdSampler.CTL_H).map { live[it] }
    return rememberOffMain(node.id, "control hint", cn, uri, wired, photo, frame, region, poseInstalled, depthInstalled) {
        if (cn == SwapInputs.NONE) return@rememberOffMain null
        val picked = if (wired == null) uri.takeIf { it.isNotBlank() }?.let { AddObjects.load(ctx, it) } else null
        val made = SdSampler.controlHintFor(ctx, node, live, wired?.asAndroidBitmap(), photo?.asAndroidBitmap(), picked)
            ?: return@rememberOffMain null
        ControlHint(made.bitmap?.asImageBitmap(), made.missing)
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
    depthRow: com.abrah.nightmare.ui.ToolRow? = null,
    onInstallDepth: (() -> Unit)? = null,
    onDeleteDepth: (() -> Unit)? = null,
    cnRows: Map<String, com.abrah.nightmare.ui.ToolRow> = emptyMap(),
    onInstallControlNet: ((String) -> Unit)? = null,
    onDeleteControlNet: ((String) -> Unit)? = null,
    /** ⚠ One write per crop gesture — four separate ones crashed a drag (`CropEditor`). */
    onSetParams: (Map<String, String>) -> Unit = {},
    onSetParam: (String, String) -> Unit,
) {
    val ctx = LocalContext.current
    val live = com.abrah.nightmare.applyDefaults(type?.widgets.orEmpty(), node)
    val cn = live[SdSampler.CONTROLNET].orEmpty().ifBlank { SwapInputs.NONE }
    val uri = live[SdSampler.CONTROL_IMAGE].orEmpty()
    // ⭐ A pick lands in the wired image node, or a new one (`SwapWiring.kt`).
    val pick = rememberImagePick { onSetParam(SdSampler.CONTROL_IMAGE, it) }
    val fromPhoto = SdSampler.controlIsPhoto(node)
    var cropping by androidx.compose.runtime.saveable.rememberSaveable(node.id) {
        androidx.compose.runtime.mutableStateOf(false)
    }
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
        // ⭐ The ControlNet itself: downloaded HERE when missing (`docs/UI.md` §8.1),
        // or said plainly when no build exists for this phone's chip.
        val cnRow = cnRows[cn]
        if (cnRow == null) {
            if (!SwapInputs.controlnetFile(ctx, cn).isFile) {
                ErrorNotice(stringResource(R.string.cn_not_for_chip, cn))
            }
        } else if (!cnRow.installed || cnRow.progress != null) {
            ErrorNotice(stringResource(R.string.cn_not_installed, cn))
            com.abrah.nightmare.ui.ToolCard(
                row = cnRow,
                busy = busy,
                onInstall = { onInstallControlNet?.invoke(cn) },
                onCancel = { onCancelPose?.invoke() },
                detail = stringResource(R.string.cn_about),
                onDelete = { onDeleteControlNet?.invoke(cn) },
            )
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
                style = NoteTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            uri.isBlank() && photo != null -> Text(
                stringResource(R.string.cn_from_photo),
                style = NoteTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // ⭐⭐ Always offered, wired or not (2026-10-01): Replace writes into the
        // wired picture node — or a new one, when the wire is the node's own
        // photo — and Clear unwires it. Crop is the picture's own region, for
        // any picture that is not the photo.
        val own = (wired != null && !fromPhoto) || uri.isNotBlank()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // ⚠ Destructive first, primary last (`docs/UI.md` §8.1).
            if (own) {
                OutlinedButton(onClick = { onSetParam(SdSampler.CONTROL_IMAGE, ""); cropping = false }) {
                    Text(stringResource(R.string.cn_clear))
                }
            }
            if (wired != null && !fromPhoto) {
                OutlinedButton(onClick = { cropping = !cropping }) {
                    Text(stringResource(if (cropping) R.string.crop_done else R.string.crop))
                }
            }
            Button(onClick = pick) {
                Text(stringResource(if (!own) R.string.cn_pick else R.string.cn_replace))
            }
        }
        if (cropping && wired != null && !fromPhoto) {
            PictureCrop(
                source = wired,
                rect = ctlCropRectOf(node),
                onChange = { r -> onSetParams(r.asCtlParams().toMap()) },
                underlay = rememberFramedPhoto(node, type, photo),
            )
        }
        // ⭐ The estimator this photo needs, offered HERE — the pose detector for
        // openpose, the depth estimator for depth. Also while one downloads, so
        // the progress stays in view.
        val depthKind = cn == SwapInputs.DEPTH
        val toolRow = if (depthKind) depthRow else poseRow
        if ((cn == SwapInputs.OPENPOSE || depthKind) &&
            (hint?.missing == cn || toolRow?.progress != null)
        ) {
            ErrorNotice(stringResource(if (depthKind) R.string.cn_depth_missing else R.string.cn_pose_missing))
            toolRow?.let { row ->
                com.abrah.nightmare.ui.ToolCard(
                    row = row,
                    busy = busy,
                    onInstall = { (if (depthKind) onInstallDepth else onInstallPose)?.invoke() },
                    onCancel = { onCancelPose?.invoke() },
                    detail = stringResource(if (depthKind) R.string.depth_about else R.string.pose_about),
                    onDelete = { (if (depthKind) onDeleteDepth else onDeletePose)?.invoke() },
                )
            }
        }
        val shown = hint?.bitmap
        if (shown != null) {
            Text(stringResource(R.string.cn_sees), style = MaterialTheme.typography.titleSmall)
            // ⭐ ⬇ Save the map itself — a canny / depth / pose picture is worth
            // keeping and loading back into an image node (asked for 2026-10-05).
            // The SAME `PictureActions` every picture's row is (`docs/UI.md` §8.3),
            // with only the download offered, and ABOVE the picture (§8.18).
            val savedMsg = stringResource(R.string.cn_saved)
            val failedMsg = stringResource(R.string.cn_save_failed)
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            Row {
                PictureActions(
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    deleteTint = MaterialTheme.colorScheme.error,
                    isClip = false,
                    onDelete = null,
                    onKeep = null,
                    onDownload = {
                        scope.launch {
                            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                runCatching {
                                    com.abrah.nightmare.ImageSaver.saveBitmap(
                                        ctx, shown.asAndroidBitmap(),
                                        "nightmare-control-$cn-" + System.currentTimeMillis(),
                                    )
                                }
                            }
                            android.widget.Toast.makeText(
                                ctx,
                                ok.fold({ savedMsg }, { failedMsg + " " + it.message }),
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    onShare = null,
                    onStar = null,
                    kept = false,
                    favourite = false,
                    starKeptTint = MaterialTheme.colorScheme.primary,
                    starIdleTint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                        SwapInputs.DEPTH -> R.string.cn_no_picture_depth
                        else -> R.string.cn_no_picture_hint
                    },
                ),
                style = NoteTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
