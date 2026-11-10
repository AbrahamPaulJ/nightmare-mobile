package com.abrah.nightmare.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.abrah.nightmare.R

/**
 * ⭐⭐ **Review**: a batch, or any pictures picked in Results, one at a time, each kept or marked to delete (GitHub #101,
 * "a slide show with a green tick and red cross to go through many images").
 *
 * ⚠ Nothing is deleted while you go through: the marks are collected and ONE [ConfirmDelete] at the end names the
 * count, as Delete on a selection does (the same strings). Leaving early with marks asks the same question; leaving
 * with none just closes. ⚠ Colour is never the only signal: each button says Keep or Delete.
 *
 * @param ids the pictures in the order they are shown.
 * @param onDelete the marked ids, after the confirm.
 */
@Composable
fun ReviewSheet(
    ids: List<String>,
    imageFor: (String) -> ImageBitmap?,
    onDelete: (List<String>) -> Unit,
    onClose: () -> Unit,
) {
    var index by remember { mutableStateOf(0) }
    val marked = remember { mutableStateListOf<String>() }
    var confirming by remember { mutableStateOf(false) }
    fun finish() { if (marked.isEmpty()) onClose() else confirming = true }
    fun decide(delete: Boolean) {
        val id = ids.getOrNull(index) ?: return
        if (delete) { if (id !in marked) marked.add(id) } else marked.remove(id)
        if (index + 1 < ids.size) index++ else finish()
    }

    Dialog(onDismissRequest = ::finish, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()
                .padding(horizontal = SCREEN_GUTTER, vertical = Space.s),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.review_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.review_progress, (index + 1).coerceAtMost(ids.size), ids.size, marked.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { if (index > 0) index-- }, enabled = index > 0) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.review_cd_back))
                }
                IconButton(onClick = ::finish) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.review_cd_close))
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = Space.s), contentAlignment = Alignment.Center) {
                val id = ids.getOrNull(index)
                val img = id?.let(imageFor)
                if (img != null) {
                    Image(img, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                } else {
                    Text(stringResource(R.string.review_loading), style = NoteTextStyle)
                }
                // ⭐ A picture already marked says so, going back through them.
                if (id != null && id in marked) {
                    Text(
                        stringResource(R.string.review_marked),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.align(Alignment.TopStart)
                            .background(MaterialTheme.colorScheme.error, MaterialTheme.shapes.small)
                            .padding(horizontal = Space.s, vertical = Space.xs),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                StudioOutlinedButton(
                    onClick = { decide(delete = true) },
                    colors = dangerOutlinedColors(),
                    modifier = Modifier.weight(1f).height(56.dp),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.delete), modifier = Modifier.padding(start = Space.xs))
                }
                StudioButton(
                    onClick = { decide(delete = false) },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.studio.success,
                        contentColor = MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier.weight(1f).height(56.dp),
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.review_keep), modifier = Modifier.padding(start = Space.xs))
                }
            }
        }
    }
    if (confirming) {
        ConfirmDelete(
            title = stringResource(R.string.results_delete_selected_title, marked.size),
            body = stringResource(R.string.results_delete_selected_body),
            confirmLabel = stringResource(R.string.results_delete_count, marked.size),
            onConfirm = { onDelete(marked.toList()); onClose() },
            onDismiss = { confirming = false },
        )
    }
}
