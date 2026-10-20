package com.abrah.nightmare.canvas

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.abrah.nightmare.R
import com.abrah.nightmare.portrait.FaceEditNode
import com.abrah.nightmare.portrait.LivePortrait

/**
 * ⭐⭐ Face edit's own viewer (the user's ask, 2026-10-21: "its own sheet viewer like crop, mask"): the picture coming
 * in, the 14 sliders in three groups, a LIVE preview (LivePortrait runs ~0.25 s an edit on an 8 Elite), hold the
 * picture to see the original. Done writes the sliders to the node in one change; Cancel leaves it as it was.
 * ⚠ The sliders are [SliderRow]s and the switch a [BoolKnobRow] (`docs/UI.md` §8.9a), never copies.
 */
@Composable
internal fun FaceEditControls(
    nodeId: String,
    node: com.abrah.nightmare.Node,
    /** The picture coming in ([CanvasState.pictureInto]); null before there is one. */
    source: ImageBitmap?,
    onSetParams: (node: String, values: Map<String, String>) -> Unit,
) {
    var open by remember(nodeId) { mutableStateOf(false) }
    if (source == null) {
        Text(
            stringResource(R.string.face_edit_no_picture),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    com.abrah.nightmare.ui.LabeledAction(Icons.Filled.Face, stringResource(R.string.face_edit_open), { open = true })
    if (open) FaceEditSheet(
        source = source,
        start = node.params,
        onDone = { values -> onSetParams(nodeId, values); open = false },
        onCancel = { open = false },
    )
}

/** The viewer itself; also what Results opens ([onDone] gets the params). */
@Composable
internal fun FaceEditSheet(
    source: ImageBitmap,
    start: Map<String, String>,
    onDone: (Map<String, String>) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val values = remember {
        mutableStateMapOf<String, String>().apply {
            for (w in FaceEditNode.widgets) put(w.name, start[w.name] ?: w.default.orEmpty())
        }
    }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var working by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var holding by remember { mutableStateOf(false) }
    val installed = remember { LivePortrait.isInstalled(context) }
    val noFace = stringResource(R.string.face_edit_no_face)

    // ⭐ The live preview: the LATEST values only, after a short settle, one edit at a time (the native side holds
    // one lock). ⚠ All sliders at 0 is the picture itself, with no NPU work.
    val key = FaceEditNode.widgets.joinToString(",") { values[it.name].orEmpty() }
    LaunchedEffect(key, installed) {
        if (!installed) return@LaunchedEffect
        kotlinx.coroutines.delay(120)
        val node = com.abrah.nightmare.Node("face", FaceEditNode.name, params = values.toMap())
        val sliders = FaceEditNode.slidersOf(node)
        if (sliders.all { it == 0f }) { preview = null; problem = null; return@LaunchedEffect }
        working = true
        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                FaceEditNode.edit(context, source.asAndroidBitmap(), sliders, values[FaceEditNode.LARGEST_ONLY] != "false")
            }
        }
        working = false
        r.onSuccess { b -> preview = b?.asImageBitmap(); problem = if (b == null) noFace else null }
            .onFailure { problem = it.message ?: it.javaClass.simpleName }
    }

    com.abrah.nightmare.ui.PullDownSheet(onDismiss = onCancel) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            com.abrah.nightmare.ui.SectionHeading(
                stringResource(R.string.face_edit_title),
                if (installed) stringResource(R.string.face_edit_hold_hint) else stringResource(R.string.face_edit_not_installed),
            )
            Box(
                Modifier.fillMaxWidth().heightIn(max = 420.dp)
                    .aspectRatio(source.width.toFloat() / source.height, matchHeightConstraintsFirst = true)
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(12.dp))
                    .pointerInput(Unit) { detectTapGestures(onPress = { holding = true; tryAwaitRelease(); holding = false }) },
                contentAlignment = Alignment.Center,
            ) {
                Image(if (holding) source else preview ?: source, contentDescription = null, modifier = Modifier.fillMaxWidth())
                if (working) CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
            problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            for ((title, names) in GROUPS) {
                com.abrah.nightmare.ui.SectionHeading(stringResource(title))
                for (name in names) {
                    val w = FaceEditNode.widgets.first { it.name == name }
                    SliderRow(w, values[name].orEmpty(), onSet = { values[name] = it })
                }
            }
            BoolKnobRow(
                label = stringResource(R.string.knob_lp_largest_only),
                hint = null,
                checked = values[FaceEditNode.LARGEST_ONLY] != "false",
                onChange = { values[FaceEditNode.LARGEST_ONLY] = it.toString() },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { for (n in LivePortrait.SLIDERS) values[n] = "0.0" }) {
                    Text(stringResource(R.string.inspector_reset))
                }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                Button(onClick = { onDone(values.toMap()) }) { Text(stringResource(R.string.face_edit_done)) }
            }
        }
    }
}

private val GROUPS = listOf(
    R.string.face_edit_group_eyes to listOf("eyebrow_direction", "eye_gaze_horizontal", "eye_gaze_vertical", "eye_open_ratio"),
    R.string.face_edit_group_mouth to listOf(
        "mouth_smile", "lip_open_ratio", "mouth_grim", "mouth_pout", "mouth_purse",
        "mouth_position_horizontal", "mouth_position_vertical",
    ),
    R.string.face_edit_group_head to listOf("head_yaw", "head_pitch", "head_roll"),
)
