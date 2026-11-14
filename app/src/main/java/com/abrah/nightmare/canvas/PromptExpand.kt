package com.abrah.nightmare.canvas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.abrah.nightmare.R

/**
 * ⭐ A long prompt in the whole screen (Saved #8030-8033, the user 2026-11-14), like a chat app's expand button on its
 * input: the button sits in the prompt box's top border with Translate and Describe; the editor opens on the text with
 * the caret at its end, Done writes it back, Cancel leaves the box as it was.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun PromptExpandButton(label: String, text: String, onDone: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Surface(
        color = androidx.compose.material3.BottomSheetDefaults.ContainerColor,
        shape = CircleShape,
        // ⚠ The same place as [TranslateToggle]: on the field's border, out of the text's way.
        modifier = Modifier.padding(end = 8.dp).offset(y = (-8).dp),
    ) {
        IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) {
            Icon(
                com.abrah.nightmare.ui.OpenInFullIcon,
                contentDescription = stringResource(R.string.prompt_expand),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
    if (open) PromptFullScreen(label, text, onDone = { open = false; onDone(it) }, onCancel = { open = false })
}

@Composable
private fun PromptFullScreen(label: String, text: String, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    val focus = remember { FocusRequester() }
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
                    .padding(horizontal = com.abrah.nightmare.ui.SCREEN_GUTTER, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(label, style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.weight(1f).fillMaxWidth().focusRequester(focus),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Spacer(Modifier.weight(1f))
                    com.abrah.nightmare.ui.StudioOutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                    com.abrah.nightmare.ui.StudioButton(onClick = { onDone(value.text) }) { Text(stringResource(R.string.prompt_expand_done)) }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
