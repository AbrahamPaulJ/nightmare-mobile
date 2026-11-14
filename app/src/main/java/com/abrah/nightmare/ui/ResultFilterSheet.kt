package com.abrah.nightmare.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.abrah.nightmare.R
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/**
 * ⭐⭐ **The Results filter sheet** (GitHub #117). Every section edits one [ResultFilter]; a section whose values no
 * kept result has is not drawn (no Sampler list for a gallery of clips). Each list has All and None, the reporter's
 * first ask: unticking forty models one by one to see one was the complaint.
 * ⚠ A [PullDownSheet], not the old dialog: fifteen sections do not fit an AlertDialog's body.
 */
@Composable
internal fun ResultFilterSheet(
    filter: ResultFilter,
    options: ResultFilterOptions,
    shown: Int,
    total: Int,
    onChange: (ResultFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    val dialogH = com.abrah.nightmare.canvas.rememberDialogHeight()
    PullDownSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = dialogH * 0.92f).padding(horizontal = SCREEN_GUTTER)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.results_filter), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text(
                    stringResource(R.string.results_filter_shown, shown, total),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterBody(filter, options, onChange)
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StudioOutlinedButton(onClick = { onChange(ResultFilter()) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.picture_clear))
                }
                StudioButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.crop_done)) }
            }
        }
    }
}

@Composable
private fun FilterBody(f: ResultFilter, o: ResultFilterOptions, onChange: (ResultFilter) -> Unit) {
    if (o.hasClips) {
        Heading(stringResource(R.string.results_filter_kind))
        Chips(
            listOf(ResultKind.ANY to stringResource(R.string.all), ResultKind.PICTURES to stringResource(R.string.results_kind_pictures),
                ResultKind.CLIPS to stringResource(R.string.results_kind_clips)),
            f.kind,
        ) { onChange(f.copy(kind = it)) }
    }
    OutlinedTextField(
        value = f.prompt, onValueChange = { onChange(f.copy(prompt = it)) }, singleLine = true,
        label = { Text(stringResource(R.string.results_filter_prompt)) }, modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = f.negative, onValueChange = { onChange(f.copy(negative = it)) }, singleLine = true,
        label = { Text(stringResource(R.string.results_filter_negative)) }, modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = f.seed, onValueChange = { v -> onChange(f.copy(seed = v.filter { it.isDigit() })) }, singleLine = true,
        label = { Text(stringResource(R.string.results_filter_seed)) }, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    DateSection(f, onChange)
    if (o.models.isEmpty()) {
        Text(stringResource(R.string.results_no_models), style = NoteTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        TickList(stringResource(R.string.nav_models), o.models, f.hiddenModels, { it }) { onChange(f.copy(hiddenModels = it)) }
    }
    if (o.samplers.isNotEmpty()) {
        TickList(stringResource(R.string.results_filter_sampler), o.samplers, f.hiddenSamplers, { it }) { onChange(f.copy(hiddenSamplers = it)) }
    }
    if (o.schedules.size > 1) {
        val labels = o.schedules.associateWith { com.abrah.nightmare.canvas.scheduleLabel(it) }
        TickList(stringResource(R.string.results_filter_schedule), o.schedules, f.hiddenSchedules, { labels[it] ?: it }) {
            onChange(f.copy(hiddenSchedules = it))
        }
    }
    o.steps?.takeIf { it.first < it.last }?.let { b ->
        val cur = f.steps ?: b
        RangeRow(stringResource(R.string.results_filter_steps), rangeText(cur.first, cur.last),
            cur.first.toFloat()..cur.last.toFloat(), b.first.toFloat()..b.last.toFloat(), steps = b.last - b.first - 1) { r ->
            val v = r.start.roundToInt()..r.endInclusive.roundToInt()
            onChange(f.copy(steps = v.takeIf { it != b }))
        }
    }
    o.cfg?.takeIf { it.start < it.endInclusive }?.let { b ->
        val cur = f.cfg ?: b
        RangeRow(stringResource(R.string.results_filter_cfg), rangeText(cur.start, cur.endInclusive),
            cur.start.toFloat()..cur.endInclusive.toFloat(), b.start.toFloat()..b.endInclusive.toFloat()) { r ->
            // ⚠ To one decimal, the way CFG is set; the whole span again means no filter.
            val lo = (r.start * 10).roundToInt() / 10.0
            val hi = (r.endInclusive * 10).roundToInt() / 10.0
            onChange(f.copy(cfg = (lo..hi).takeIf { lo > b.start + 1e-6 || hi < b.endInclusive - 1e-6 }))
        }
    }
    o.seconds?.takeIf { it.first < it.last }?.let { b ->
        val cur = f.seconds ?: b
        RangeRow(
            stringResource(R.string.results_filter_time),
            stringResource(R.string.results_filter_seconds, cur.first, cur.last),
            cur.first.toFloat()..cur.last.toFloat(), b.first.toFloat()..b.last.toFloat(),
        ) { r ->
            val v = r.start.roundToInt()..r.endInclusive.roundToInt()
            onChange(f.copy(seconds = v.takeIf { it != b }))
        }
    }
    if (o.sizes.size > 1) {
        TickList(stringResource(R.string.results_filter_size), o.sizes, f.hiddenSizes, { it }) { onChange(f.copy(hiddenSizes = it)) }
    }
    TriRow(stringResource(R.string.results_filter_upscaled), f.upscaled) { onChange(f.copy(upscaled = it)) }
    TriRow(stringResource(R.string.results_filter_from_image), f.fromImage) { onChange(f.copy(fromImage = it)) }
    TriRow(stringResource(R.string.results_filter_reference), f.reference) { onChange(f.copy(reference = it)) }
    Spacer(Modifier.height(4.dp))
}

/** ⚠ Pure text for a numeric range, one decimal where the values have one. */
private fun rangeText(a: Number, b: Number): String {
    fun t(n: Number) = if (n is Double) "%.1f".format(java.util.Locale.ROOT, n) else n.toString()
    return "${t(a)} - ${t(b)}"
}

@Composable
private fun Heading(text: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Chips(choices: List<Pair<T, String>>, current: T, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((v, label) in choices) {
            FilterChip(selected = v == current, onClick = { onPick(v) }, label = { Text(label) })
        }
    }
}

/** ⭐ Any / Yes / No for a question a picture answers. */
@Composable
private fun TriRow(label: String, value: Tri, onPick: (Tri) -> Unit) {
    Heading(label)
    Chips(
        listOf(Tri.ANY to stringResource(R.string.results_tri_any), Tri.YES to stringResource(R.string.results_tri_yes),
            Tri.NO to stringResource(R.string.results_tri_no)),
        value, onPick,
    )
}

/**
 * ⭐ A list of values with All and None (GitHub #117's first ask), every box ticked to begin with. [hidden] is the
 * UNTICKED set ([ResultFilter]).
 */
@Composable
private fun TickList(
    title: String,
    values: List<String>,
    hidden: Set<String>,
    label: @Composable (String) -> String,
    onHidden: (Set<String>) -> Unit,
) {
    HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        StudioTextButton(onClick = { onHidden(hidden - values.toSet()) }) { Text(stringResource(R.string.all)) }
        Spacer(Modifier.width(8.dp))
        StudioTextButton(onClick = { onHidden(hidden + values) }) { Text(stringResource(R.string.results_filter_none)) }
    }
    for (v in values) {
        val on = v !in hidden
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(role = Role.Checkbox) { onHidden(if (on) hidden + v else hidden - v) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = on, onCheckedChange = null)
            Spacer(Modifier.width(8.dp))
            Text(label(v), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun RangeRow(
    title: String,
    value: String,
    current: ClosedFloatingPointRange<Float>,
    bounds: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (ClosedFloatingPointRange<Float>) -> Unit,
) {
    HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(value, style = metaValueStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    RangeSlider(value = current, onValueChange = onChange, valueRange = bounds, steps = steps.coerceAtLeast(0))
}

/** ⭐ The date: presets for the usual questions, and a calendar for a day, a month or a year long ago. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateSection(f: ResultFilter, onChange: (ResultFilter) -> Unit) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone).toEpochDay()
    val year = LocalDate.now(zone).withDayOfYear(1).toEpochDay()
    var picking by remember { mutableStateOf(false) }
    Heading(stringResource(R.string.results_filter_date))
    val presets = listOf(
        (null to null) to stringResource(R.string.results_date_any),
        (today to today) to stringResource(R.string.results_date_today),
        (today - 6 to today) to stringResource(R.string.results_date_week),
        (today - 29 to today) to stringResource(R.string.results_date_month),
        (year to today) to stringResource(R.string.results_date_year),
    )
    val current = f.fromDay to f.toDay
    val custom = presets.none { it.first == current }
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((range, label) in presets) {
            FilterChip(selected = range == current, onClick = { onChange(f.copy(fromDay = range.first, toDay = range.second)) }, label = { Text(label) })
        }
        FilterChip(
            selected = custom,
            onClick = { picking = true },
            label = {
                Text(
                    if (custom) {
                        val fmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                        stringResource(
                            R.string.results_date_range,
                            f.fromDay?.let { LocalDate.ofEpochDay(it).format(fmt) } ?: "",
                            f.toDay?.let { LocalDate.ofEpochDay(it).format(fmt) } ?: "",
                        )
                    } else stringResource(R.string.results_date_pick),
                )
            },
        )
    }
    if (picking) {
        // ⚠ The picker's millis are UTC midnights, so the day is millis / a day, never through the phone's zone.
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = f.fromDay?.let { it * DAY_MS },
            initialSelectedEndDateMillis = f.toDay?.let { it * DAY_MS },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                StudioButton(onClick = {
                    val a = state.selectedStartDateMillis?.let { it / DAY_MS }
                    val b = state.selectedEndDateMillis?.let { it / DAY_MS } ?: a
                    onChange(f.copy(fromDay = a, toDay = b))
                    picking = false
                }) { Text(stringResource(R.string.crop_done)) }
            },
            dismissButton = { StudioOutlinedButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } },
        ) {
            Box(Modifier.weight(1f)) { DateRangePicker(state = state, showModeToggle = true) }
        }
    }
}

private const val DAY_MS = 86_400_000L
