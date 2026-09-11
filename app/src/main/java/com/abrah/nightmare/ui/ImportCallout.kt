package com.abrah.nightmare.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * ⭐⭐ "Bring something of your own in" — the one card shape every importer uses.
 *
 * ⚠⚠ **One definition, three callers.** Models, Flows and Settings each offer an
 * import, and they had drifted into three different things: a tinted callout
 * with a sentence and an Import button on Models, and on Flows a plain card with
 * two bare buttons labelled "Flow" and "Nodes" — labels that only parsed while
 * they sat side by side, and stopped meaning anything once each was alone.
 * Reported from the phone, 2026-09-11.
 *
 * ⚠ Deliberately NOT the same surface as the rows around it. An import card is
 * an ACTION sitting in a list of things; drawn identically it reads as one more
 * item — which is exactly how the Models one went unnoticed at the bottom of its
 * list. The outline and the tinted ground say "this one is different" without
 * shouting.
 *
 * @param warning shown in the error colour under [body], for an import whose
 *   consequence the user has to accept BEFORE tapping. Null for the ordinary
 *   case. ⚠ It lives here rather than in a doc because the only moment it is
 *   useful is the moment of the tap.
 */
@Composable
fun ImportCallout(
    title: String,
    body: String,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
    buttonLabel: String = "Import",
    enabled: Boolean = true,
    warning: String? = null,
) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    body,
                    style = LogTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                warning?.let {
                    Text(it, style = LogTextStyle, color = MaterialTheme.colorScheme.error)
                }
            }
            Button(onClick = onImport, enabled = enabled) { Text(buttonLabel) }
        }
    }
}
