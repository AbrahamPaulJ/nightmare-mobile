package com.abrah.nightmare.canvas

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * ⭐⭐ Face edit's node page (the user's call, 2026-10-21): the picture big, a tap on it opens the viewer, a line of
 * what is changed, and Edit face. NO sliders here: they are the viewer's. Once it has run, the inspector draws the
 * result above this with its actions UNDER it and no bin; before that, this draws the picture coming in.
 */
@Composable
internal fun FaceEditControls(
    nodeId: String,
    node: com.abrah.nightmare.Node,
    /** The picture coming in ([CanvasState.pictureInto]); null before there is one. */
    source: ImageBitmap?,
    onSetParams: (node: String, values: Map<String, String>) -> Unit,
    /** Whether the inspector already shows this node's result (then the picture is not drawn twice). */
    hasResult: Boolean,
    open: Boolean,
    onOpen: (Boolean) -> Unit,
) {
    if (source == null) {
        Text(
            stringResource(R.string.face_edit_no_picture),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    if (!hasResult) {
        Image(
            source,
            contentDescription = stringResource(R.string.face_edit_open),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(max = pictureCap())
                .clip(RoundedCornerShape(10.dp))
                .clickable { onOpen(true) },
        )
    }
    val context = LocalContext.current
    val changed = LivePortrait.SLIDERS.mapNotNull { n ->
        val v = node.params[n]?.toFloatOrNull() ?: 0f
        if (v == 0f) null else "${n.knobLabel(context)} ${"%.1f".format(java.util.Locale.ROOT, v)}"
    }
    Text(
        if (changed.isEmpty()) stringResource(R.string.face_edit_nothing_changed) else changed.joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    com.abrah.nightmare.ui.LabeledAction(Icons.Filled.Face, stringResource(R.string.face_edit_open), { onOpen(true) })
    if (open) FaceEditSheet(
        source = source,
        start = node.params,
        onDone = { values -> onSetParams(nodeId, values); onOpen(false) },
        onCancel = { onOpen(false) },
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
            put(FaceEditNode.LARGEST_ONLY, "false")
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
                FaceEditNode.edit(context, source.asAndroidBitmap(), sliders, largestOnly = false)
            }
        }
        working = false
        r.onSuccess { b -> preview = b?.asImageBitmap(); problem = if (b == null) noFace else null }
            .onFailure { problem = it.message ?: it.javaClass.simpleName }
    }

    com.abrah.nightmare.ui.PullDownSheet(onDismiss = onCancel) {
        // ⚠ No scroll: the picture stays in view while the sliders move (the user, 2026-10-21). ⭐ It takes ALL the
        // height the three rows and the buttons leave (weight), fitted to the width or the height, whichever it hits.
        Column(
            Modifier.weight(1f).padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            com.abrah.nightmare.ui.SectionHeading(
                stringResource(R.string.face_edit_title),
                if (installed) stringResource(R.string.face_edit_hold_hint) else stringResource(R.string.face_edit_not_installed),
            )
            Box(
                Modifier.weight(1f).fillMaxWidth()
                    .pointerInput(Unit) { detectTapGestures(onPress = { holding = true; tryAwaitRelease(); holding = false }) },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    if (holding) source else preview ?: source,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                )
                if (working) CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
            problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            FaceSliders(value = { values[it].orEmpty() }, onSet = { k, v -> values[k] = v })
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

/**
 * ⭐⭐ Face edit's controls as THREE rows, Eyes / Mouth / Head (the user's call, 2026-10-21): each a dropdown choosing
 * which control its one slider moves, so the 14 fit under a picture that stays in view. Every value is kept when the
 * dropdown moves on. ⚠ The dropdown is [ChoiceDropdown] and the slider [SliderRow] (`docs/UI.md` §8.9a).
 */
@Composable
internal fun FaceSliders(value: (String) -> String, onSet: (String, String) -> Unit) {
    for ((title, names) in GROUPS) {
        val labels = names.map { it.knobLabel(LocalContext.current) }
        // ⭐ Opens on a control that is moved, else the group's first.
        var picked by remember(title) { mutableStateOf(names.firstOrNull { (value(it).toFloatOrNull() ?: 0f) != 0f } ?: names.first()) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(0.45f)) {
                ChoiceDropdown(
                    label = stringResource(title),
                    hint = null,
                    options = labels,
                    current = labels[names.indexOf(picked)],
                    onPick = { l -> picked = names[labels.indexOf(l)] },
                )
            }
            Box(Modifier.weight(0.55f)) {
                SliderRow(FaceEditNode.widgets.first { it.name == picked }, value(picked), onSet = { onSet(picked, it) })
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
