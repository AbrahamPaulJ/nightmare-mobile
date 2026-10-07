package com.abrah.nightmare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.abrah.nightmare.DeviceProbe
import com.abrah.nightmare.ModelCatalog
import com.abrah.nightmare.R

/**
 * ⭐⭐ What this phone's NPU is, and what that means for the catalogue.
 *
 * ⚠⚠ It exists because **"Snapdragon 8 Gen 2 or newer" is not the requirement
 * and never was** — it is a date test, and the hardware is not ordered by date.
 * Two numbers decide everything: the HTP **arch** and its **VTCM**. Neither can
 * be read off the marketing name, and one of them (VTCM) is cut down on the "s"
 * tier of chips that are otherwise newer.
 *
 * ⚠ The measured/assumed distinction is shown, not hidden. An assumption is
 * good enough to pick a download and **not** good enough to tell someone their
 * phone works.
 */
@Composable
fun DeviceSheet(caps: DeviceProbe.Caps, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.device_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(androidx.compose.ui.unit.Dp(2f))) {
                Field(stringResource(R.string.device_chip), caps.soc.ifBlank { stringResource(R.string.device_unknown) })
                Field(stringResource(R.string.device_htp_arch), stringResource(R.string.device_arch_value, caps.arch))
                Field(stringResource(R.string.device_vtcm), stringResource(R.string.device_mb, caps.vtcmMb))
                Field(
                    stringResource(R.string.device_source),
                    // ⚠⚠ `--device_info` reads arch and VTCM from the hardware
                    // and needs no model, no context and no server — so the
                    // answer is available before a gigabyte is committed to.
                    if (caps.measured) stringResource(R.string.device_measured)
                    else stringResource(R.string.device_assumed),
                )
                Spacer()

                // ⚠ The one that actually strands a user: without a Skel for
                // this arch the NPU cannot initialise AT ALL, whatever model is
                // installed, and the error names neither the arch nor the file.
                if (!caps.staged) {
                    Text(
                        stringResource(
                            R.string.device_qnn_missing,
                            caps.arch,
                            DeviceProbe.STAGED_ARCHES.joinToString { "v$it" },
                        ),
                        style = NoteTextStyle,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer()
                }

                // ⭐ The catalogue, counted against THIS device — which is the
                // question the numbers above are being asked in service of.
                //
                // ⚠⚠ `builtIn`, NOT `all`: an imported model has no published
                // build, so `buildFor` answers null for it and it would drag
                // both halves of this fraction the wrong way — "4 of 16" when
                // the user imports one, as though importing it had cost them a
                // supported model. This line is about OUR catalogue against
                // this chip, and a custom model makes no arch claim at all.
                val runnable = ModelCatalog.builtIn.count { it.buildFor(caps) != null }
                Field(
                    stringResource(R.string.device_models_runnable),
                    stringResource(R.string.device_of_total, runnable, ModelCatalog.builtIn.size),
                )
                val tiers = ModelCatalog.sd15Models.firstOrNull()?.buildFor(caps)?.tier
                if (tiers != null) Field(stringResource(R.string.device_sd15_build), tiers.removePrefix("_"))
                Field(
                    "SDXL",
                    if (ModelCatalog.sdxlModels.first().buildFor(caps) != null) stringResource(R.string.device_supported)
                    // ⚠ Names the requirement rather than saying "no": xororz
                    // publishes SDXL as _8gen3 only, so this is a property of
                    // what exists upstream, not a choice of ours.
                    else stringResource(R.string.device_sdxl_requirement),
                )

                if (!caps.measured) {
                    Spacer()
                    Text(
                        stringResource(R.string.device_measure_note),
                        style = NoteTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun Field(name: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, style = NoteTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = NoteTextStyle, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Spacer() = Text("", style = NoteTextStyle)
