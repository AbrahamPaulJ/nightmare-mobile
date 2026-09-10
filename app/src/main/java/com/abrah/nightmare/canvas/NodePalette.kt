package com.abrah.nightmare.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abrah.nightmare.NodeType
import com.abrah.nightmare.ui.LogTextStyle

/**
 * Every node type that can be placed, grouped by category.
 *
 * ⭐ **Plugins appear here with no special case.** The list is the executor's
 * registry, so a pack pushed to the device shows up beside `sample` and
 * `vae_decode` — which is the whole tier-0 promise made visible: a contributor's
 * node is not a second-class citizen in the palette either.
 *
 * ⚠ A bottom sheet rather than a side rail: a phone has no room for a permanent
 * palette, and docs/CLAUDE.md decided the palette is a sheet when the canvas was
 * still a plan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodePalette(
    types: Map<String, NodeType>,
    onPick: (NodeType) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("add a node", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)

            // ⚠ Sorted, and grouped by category. An unordered map means the
            // palette reshuffles between runs, and muscle memory is most of
            // what makes a node editor fast.
            val byCategory = types.values.sortedBy { it.name }.groupBy { it.category }
            for (category in byCategory.keys.sorted()) {
                Text(
                    category,
                    style = LogTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
                for (type in byCategory.getValue(category)) {
                    PaletteRow(type, onPick)
                }
            }
        }
    }
}

@Composable
private fun PaletteRow(type: NodeType, onPick: (NodeType) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onPick(type) }
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The same hue the node will have on the canvas, so the palette and the
        // graph agree at a glance.
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(CanvasColors.forCategory(type.category))
        )
        Column(Modifier.weight(1f)) {
            Text(type.name.nodeLabel, fontWeight = FontWeight.Medium)
            // ⚠ The qualified name, so two packs shipping a `Resize` are
            // distinguishable in the one place the user picks between them.
            if (type.name.contains(':')) {
                Text(
                    type.name.substringBeforeLast(':'),
                    style = LogTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            "${type.inputs.size} in · ${type.outputs.size} out",
            style = LogTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
