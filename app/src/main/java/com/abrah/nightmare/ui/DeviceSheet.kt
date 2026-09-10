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
import androidx.compose.ui.text.font.FontWeight
import com.abrah.nightmare.DeviceProbe
import com.abrah.nightmare.ModelCatalog

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
        title = { Text("This device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(androidx.compose.ui.unit.Dp(2f))) {
                Field("chip", caps.soc.ifBlank { "unknown" })
                Field("HTP arch", "v${caps.arch}")
                Field("VTCM", "${caps.vtcmMb} MB")
                Field(
                    "source",
                    // ⚠⚠ `--device_info` reads arch and VTCM from the hardware
                    // and needs no model, no context and no server — so the
                    // answer is available before a gigabyte is committed to.
                    if (caps.measured) "measured from the HTP" else "assumed from the chip name",
                )
                Spacer()

                // ⚠ The one that actually strands a user: without a Skel for
                // this arch the NPU cannot initialise AT ALL, whatever model is
                // installed, and the error names neither the arch nor the file.
                if (!caps.staged) {
                    Text(
                        "⚠ This build ships no QNN libraries for v${caps.arch}, " +
                            "so the NPU cannot start. Staged: " +
                            DeviceProbe.STAGED_ARCHES.joinToString { "v$it" } + ".",
                        style = LogTextStyle,
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
                Field("models it can run", "$runnable of ${ModelCatalog.builtIn.size}")
                val tiers = ModelCatalog.sd15Models.firstOrNull()?.buildFor(caps)?.tier
                if (tiers != null) Field("SD 1.5 build", tiers.removePrefix("_"))
                Field(
                    "SDXL",
                    if (ModelCatalog.sdxlModels.first().buildFor(caps) != null) "supported"
                    // ⚠ Names the requirement rather than saying "no": xororz
                    // publishes SDXL as _8gen3 only, so this is a property of
                    // what exists upstream, not a choice of ours.
                    else "needs v75+ with 8 MB — no other build is published",
                )

                if (!caps.measured) {
                    Spacer()
                    Text(
                        "Measured on the first backend start; until then this is " +
                            "a table lookup, and an unlisted chip is assumed to be " +
                            "the oldest we support.",
                        style = LogTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun Field(name: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, style = LogTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = LogTextStyle, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Spacer() = Text("", style = LogTextStyle)
