package com.abrah.nightmare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.abrah.nightmare.R
import com.abrah.nightmare.UltraFix
import kotlin.math.roundToInt

/**
 * ⭐⭐ UltraFix's settings (Local Dream's dialog, the user's screenshot 2026-10-09): Steps, Denoise
 * steps (never more than the steps, at most [UltraFix.DENOISE_STEPS_MAX]), the quality-prompt
 * switch and Restore defaults. Remembered between runs ([UltraFix.save], by the view model).
 */
@Composable
fun UltraFixDialog(start: UltraFix.Params, onConfirm: (UltraFix.Params) -> Unit, onDismiss: () -> Unit) {
    var p by remember { mutableStateOf(start) }
    val denoiseMax = minOf(UltraFix.DENOISE_STEPS_MAX, p.steps)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ultrafix_title)) },
        text = {
            Column(androidx.compose.ui.Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.ultrafix_about), style = NoteTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.ultrafix_steps, p.steps), style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = p.steps.toFloat(),
                    onValueChange = { v ->
                        val s = v.roundToInt().coerceIn(UltraFix.STEPS_MIN, UltraFix.STEPS_MAX)
                        p = p.copy(steps = s, denoiseSteps = p.denoiseSteps.coerceAtMost(minOf(UltraFix.DENOISE_STEPS_MAX, s)))
                    },
                    valueRange = UltraFix.STEPS_MIN.toFloat()..UltraFix.STEPS_MAX.toFloat(),
                )
                Text(stringResource(R.string.ultrafix_denoise_steps, p.denoiseSteps.coerceAtMost(denoiseMax)), style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = p.denoiseSteps.coerceAtMost(denoiseMax).toFloat(),
                    onValueChange = { v -> p = p.copy(denoiseSteps = v.roundToInt().coerceIn(0, denoiseMax)) },
                    valueRange = 0f..denoiseMax.toFloat().coerceAtLeast(1f),
                )
                // ⭐ The app's one bool control (`docs/UI.md` §8.9a).
                SwitchRow(
                    label = stringResource(R.string.ultrafix_quality),
                    hint = null,
                    checked = p.qualityPrompt,
                    onChange = { p = p.copy(qualityPrompt = it) },
                )
                TextButton(onClick = { p = UltraFix.Params() }) { Text(stringResource(R.string.ultrafix_restore)) }
                Text(stringResource(R.string.ultrafix_note), style = NoteTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        // ⚠ Cancel left, the doing one right (`docs/UI.md` §8.1).
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        confirmButton = {
            TextButton(onClick = { onConfirm(p.copy(denoiseSteps = p.denoiseSteps.coerceAtMost(denoiseMax))) }) {
                Text(stringResource(R.string.ultrafix_title))
            }
        },
    )
}
