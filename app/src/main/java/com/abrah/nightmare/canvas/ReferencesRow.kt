package com.abrah.nightmare.canvas

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.abrah.nightmare.R

/** One reference on the row: its port, the image node holding it, and its picture once loaded. */
internal data class ReferenceSlot(val port: String, val sourceNode: String, val picture: ImageBitmap?)

/**
 * ⭐⭐ A DiT edit's References row, the Default view's way to add images 2 to 4 (the user's call, 2026-10-21):
 * numbered thumbnails, tap one to replace its picture, x to remove it, and a + tile for the next. Advanced shows the
 * same pictures as image nodes on growing `reference` ports ([com.abrah.nightmare.SdSampler.inputsFor]).
 * ⚠ The + tile is DIMMED with its reason, never hidden (`docs/UI.md` §8.18): full, or [roomless] when free RAM
 * cannot take another at this size.
 */
@Composable
internal fun ReferencesRow(
    slots: List<ReferenceSlot>,
    /** Why another cannot be added, or null. */
    roomless: String?,
    onAdd: (uri: String) -> Unit,
    onReplace: (sourceNode: String, uri: String) -> Unit,
    onRemove: (port: String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // ⚠ A toast, as the mask window and the LoRA picker say why a control is dimmed.
    val onToast = { text: String -> android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show() }
    // ⚠ Saveable: the picker is another activity, and a recreation while it is open dropped the chosen photo.
    var replacing by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val pickNew = rememberImagePick { uri -> onAdd(uri) }
    val pickReplace = rememberImagePick { uri -> replacing?.let { onReplace(it, uri) }; replacing = null }
    val full = stringResource(R.string.references_full, com.abrah.nightmare.SdSampler.MAX_REFERENCES)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.references_title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.references_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            slots.forEachIndexed { i, s ->
                Box(
                    Modifier.size(64.dp).clip(RoundedCornerShape(10.dp))
                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(10.dp))
                        .clickable { replacing = s.sourceNode; pickReplace() },
                ) {
                    s.picture?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp)) }
                    // ⭐ The number the prompt uses: the base picture is image 1.
                    Text(
                        "${i + 2}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                            .padding(horizontal = 5.dp),
                    )
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.references_remove, i + 2),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(20.dp)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(10.dp))
                            .clickable { onRemove(s.port) },
                    )
                }
            }
            val dead = when {
                slots.size >= com.abrah.nightmare.SdSampler.MAX_REFERENCES - 1 -> full
                else -> roomless
            }
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(10.dp))
                    .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(10.dp))
                    .clickable { if (dead != null) onToast(dead) else pickNew() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = dead ?: stringResource(R.string.references_add),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dead != null) 0.38f else 1f),
                )
            }
        }
    }
}

/**
 * ⭐ Why one more reference would not fit in RAM at [w]x[h], or null. ⚠ An ESTIMATE until measured on a phone
 * (PROGRESS): each reference lengthens the DiT sequence by the canvas's tokens, and its VAE encode runs at the
 * canvas size (`docs/DIT.md` §9: 1536 MB for an encode at 1024²; the flux buffer 491 → 1599 MB from 512² to 1024²
 * with one reference). Taken as ~0.9 GB per megapixel per reference, against what is free with 1 GB kept back.
 */
internal fun referenceRoomless(context: android.content.Context, w: Int, h: Int, wired: Int): String? {
    val perRef = (w.toLong() * h / (1024.0 * 1024.0) * 900.0 * (1L shl 20)).toLong()
    val free = com.abrah.nightmare.ErrorReport.availableRamBytes()
    if (free <= 0L) return null
    val need = perRef * (wired + 1) + (1L shl 30)
    return if (need <= free) null
    else context.getString(R.string.references_no_room, (free shr 20).toInt(), "${w}x$h")
}

/** ⭐ Whether [node] gets the References row: a DiT edit sampler, the kind with [com.abrah.nightmare.SdSampler.EXTRA_REFS]. */
internal fun nodeShowsReferences(node: com.abrah.nightmare.Node, types: Map<String, com.abrah.nightmare.NodeType>): Boolean =
    types[node.type]?.inputs?.any { it.name == com.abrah.nightmare.SdSampler.EXTRA_REFS.first() } == true
