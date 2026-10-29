package com.abrah.nightmare.canvas

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.abrah.nightmare.R
import com.abrah.nightmare.npu.WanParts
import com.abrah.nightmare.ui.NoteTextStyle

/**
 * ⭐⭐ Under the Part prompts box: what EACH part will run with, live as it is typed (the user, 2026-10-30: "how is a user
 * supposed to know how to format the prompts, not even a hint or an invalid warning"). Part 1 is the Prompt node's, line
 * k is part k+1, a blank line repeats the part before ([WanParts.promptsFor]); lines past the last part are said to be
 * unused, in error red. Outside `NodeInspectorBody`, which ART's verifier rejects when it grows (`HANDOFF.md`).
 */
@Composable
internal fun PartPromptsNote(main: String, lines: String, parts: Int) {
    val plan = WanParts.readout(main, lines, parts)
    Column {
        for (row in plan.rows) {
            Text(
                when (row.source) {
                    WanParts.Source.MAIN -> stringResource(R.string.part_readout_main, row.part, row.text)
                    WanParts.Source.LINE -> stringResource(R.string.part_readout_line, row.part, row.text)
                    WanParts.Source.REPEAT -> stringResource(R.string.part_readout_same, row.part, row.part - 1)
                },
                style = NoteTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        if (plan.unused > 0) {
            Text(
                stringResource(R.string.part_readout_unused, plan.unused, plan.needed),
                style = NoteTextStyle,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
