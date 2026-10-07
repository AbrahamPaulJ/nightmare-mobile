package com.abrah.nightmare.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abrah.nightmare.Family
import com.abrah.nightmare.ModelCatalog
import com.abrah.nightmare.ModelFeatures
import com.abrah.nightmare.ModelSpec
import com.abrah.nightmare.R

// ============================================================================
// ⭐⭐⭐ The Models browser — the user's concept art, 2026-10-05.
//
// Capability chips replaced the swipeable family tabs; a search box and a
// Family menu sit under them; the list is "Installed" first, then "Available",
// then "Not for this phone" collapsed at the bottom (the user: *"put all
// unsupported models in its own section so user only sees whats supported"*).
// A family opens its own page. The pure half (what is shown, in which section)
// is here and tested in `ModelBrowserTest`; the composables draw it.
// ============================================================================

/** ⭐ The chips at the top of Models. */
enum class ModelKind { ALL, GENERATE, EDIT, INPAINT, VIDEO, UPSCALE, TOOLS }

/** ⭐ A family page's sub-chips; only the non-empty ones are drawn. */
enum class SubKind { ALL, BASE, INPAINT, IMPORTED }

/**
 * ⭐ A DEDICATED inpainting checkpoint — the 9-channel ones and an npuforge
 * Swap conversion with Inpaint ticked. ⚠ Not "can inpaint": nearly every family
 * can (by blend), so a chip on that would list everything.
 */
fun ModelSpec.isInpaintModel(): Boolean = when (family) {
    // ⭐ A Swap conversion says so itself ([ModelFeatures]) — never its name, which the user may change.
    Family.SD15_SWAP, Family.SDXL_SWAP -> ModelFeatures.INPAINT in featureSet
    else -> backendType == ModelCatalog.SD15_NPU_INPAINT ||
        id.contains("inpaint", ignoreCase = true) ||
        label.contains("inpaint", ignoreCase = true)
}

/** ⭐ Whether a CHECKPOINT row belongs under [kind]. Video/Upscale/Tools hold no checkpoints. */
fun matchesKind(spec: ModelSpec, kind: ModelKind): Boolean = when (kind) {
    ModelKind.ALL, ModelKind.GENERATE -> true
    ModelKind.EDIT -> spec.family.edit
    ModelKind.INPAINT -> spec.isInpaintModel()
    ModelKind.VIDEO, ModelKind.UPSCALE, ModelKind.TOOLS -> false
}

fun matchesSub(spec: ModelSpec, sub: SubKind): Boolean = when (sub) {
    SubKind.ALL -> true
    SubKind.BASE -> !spec.isInpaintModel() && !spec.isCustom
    SubKind.INPAINT -> spec.isInpaintModel()
    SubKind.IMPORTED -> spec.isCustom
}

/**
 * ⭐ Every word of [query] must appear in the model's name, id, family or
 * capability words — or its starter prompt, which is what makes "anime" find
 * the anime checkpoints without a tag list nobody maintains.
 */
fun matchesQuery(spec: ModelSpec, query: String): Boolean {
    val words = query.lowercase().split(' ', ',').filter { it.isNotBlank() }
    if (words.isEmpty()) return true
    val hay = buildString {
        append(spec.label).append(' ').append(spec.id).append(' ').append(spec.family.label)
        if (spec.isInpaintModel()) append(" inpaint")
        if (spec.family.edit) append(" edit")
        if (spec.family.dit) append(" dit")
        if (spec.isCustom) append(" imported")
        append(' ').append(ModelCatalog.styleWords(spec))
        append(' ').append(spec.prompt)
    }.lowercase()
    return words.all { it in hay }
}

/** ⭐ What the list shows, in its three sections. */
data class ModelSections(
    val installed: List<ModelRow>,
    val available: List<ModelRow>,
    val unsupported: List<ModelRow>,
) {
    val isEmpty get() = installed.isEmpty() && available.isEmpty() && unsupported.isEmpty()
}

/** ⭐ A row this phone cannot run at all: no build for its chip (or its RAM). */
fun ModelRow.unsupported(): Boolean = !installed && !spec.isCustom && build == null

fun sectionsOf(
    rows: List<ModelRow>,
    kind: ModelKind,
    query: String,
    family: Family? = null,
    sub: SubKind = SubKind.ALL,
): ModelSections {
    val shown = rows.filter {
        (family == null || it.spec.family == family) &&
            matchesKind(it.spec, kind) && matchesSub(it.spec, sub) && matchesQuery(it.spec, query)
    }
    return ModelSections(
        // ⚠ The one in use first; otherwise the catalogue's curated order.
        installed = shown.filter { it.installed }.sortedByDescending { it.selected },
        available = shown.filter { !it.installed && !it.unsupported() },
        unsupported = shown.filter { it.unsupported() },
    )
}

/** ⭐ The chip generation a build needs, as people name them. */
fun archLabel(arch: Int): String = when {
    arch >= 81 -> "8 Elite Gen 5+"
    arch >= 79 -> "8 Elite+"
    arch >= 75 -> "8 Gen 3+"
    arch >= 73 -> "8 Gen 2+"
    arch >= 69 -> "8 Gen 1+"
    else -> "888+"
}

/** ⭐ Which group a family sits in on the Family menu. */
fun Family.isModern(): Boolean = dit

private fun gb2(bytes: Long): String =
    String.format(java.util.Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024))

// ---------------------------------------------------------------------------
// Composables
// ---------------------------------------------------------------------------

@Composable
internal fun kindLabel(k: ModelKind): String = stringResource(
    when (k) {
        ModelKind.ALL -> R.string.mb_kind_all
        ModelKind.GENERATE -> R.string.mb_kind_generate
        ModelKind.EDIT -> R.string.mb_kind_edit
        ModelKind.INPAINT -> R.string.mb_kind_inpaint
        ModelKind.VIDEO -> R.string.video
        ModelKind.UPSCALE -> R.string.upscalers
        ModelKind.TOOLS -> R.string.tools
    },
)

/** ⭐ A pill: filled violet when on, a quiet outline when off — the concept's chips. */
@Composable
internal fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
        )
    }
}

@Composable
internal fun <T> PillRow(items: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (k in items) Pill(label(k), k == selected) { onSelect(k) }
    }
}

@Composable
internal fun SearchBox(
    query: String,
    onQuery: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** ⭐ The placeholder; the Models browser's when null. The LoRA browser passes its own. */
    hint: String? = null,
    /** ⭐ Enter — the LoRA browser searches at once instead of waiting out its debounce. */
    onSubmit: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        // ⚠ Only with [onSubmit]: the Models browser's keyboard stays as it was.
        keyboardOptions = if (onSubmit == null) androidx.compose.foundation.text.KeyboardOptions.Default
        else androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSubmit?.invoke() }),
        placeholder = {
            Text(
                hint ?: stringResource(R.string.mb_search_hint),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

/** ⭐ A coloured family badge — [familyColor] at low alpha behind its own colour. */
@Composable
internal fun Badge(text: String, color: Color? = null) {
    val c = color ?: MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = c,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (color != null) c.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * ⭐ "Family: All families ▾" — a plain list (the user turned down a filter
 * sheet, 2026-10-05). Picking a family opens its page.
 * ⚠ Full width, like the [SearchBox] above it, and the menu as wide as the
 * button: wrapped to its text it read as cramped on the phone (1.6.082).
 */
@Composable
internal fun FamilyMenu(counts: List<Pair<Family, Int>>, onPick: (Family) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var widthPx by remember { mutableStateOf(0) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { open = true },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().onSizeChanged { widthPx = it.width },
        ) {
            Text(
                stringResource(R.string.mb_family, stringResource(R.string.mb_all_families)),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.width(with(LocalDensity.current) { widthPx.toDp() }),
        ) {
            for ((group, members) in counts.groupBy { it.first.isModern() }.toSortedMap()) {
                Text(
                    stringResource(if (group) R.string.mb_group_dit else R.string.mb_group_classic),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                for ((f, n) in members) {
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(familyColor(f)))
                                Spacer(Modifier.width(10.dp))
                                Text(f.label, modifier = Modifier.weight(1f))
                                Text(
                                    n.toString(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        },
                        onClick = { open = false; onPick(f) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun SectionHeader(text: String, collapsed: Boolean? = null, onToggle: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ⚠ The colour SET: this sits in a LazyColumn with no Surface above it,
        // where the inherited content colour is black on the dark ground.
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (collapsed != null) {
            Icon(
                if (collapsed) Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * ⭐⭐⭐ The concept's model card: name and ⋮ on top, badges, then
 * "8 Elite+ · ● Installed · 3.62 GB", and the one action on the right.
 *
 * ⚠⚠ **The model IN USE keeps a live Use button** (the user, 2026-10-05: *"dont
 * grey out the in use model completely. i cant switch to a different flow of
 * same model"*). Use means "open a flow with this", which is still a choice when
 * it is already selected; "In use" moved to a badge and the status dot.
 * ⚠ Delete lives in ⋮, and on the model in use it is refused there — removing
 * it would leave the backend pointed at a directory that is gone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelCardV2(
    row: ModelRow,
    busy: Boolean,
    onInstall: (ModelSpec) -> Unit,
    onCancel: () -> Unit,
    onDelete: (ModelSpec) -> Unit,
    onUse: (ModelSpec) -> Unit,
) {
    val spec = row.spec
    val dim = row.unsupported()
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (row.selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainer,
        ),
        border = if (row.selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)) else null,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        spec.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                    FlowRow(
                        Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Badge(spec.family.label, familyColor(spec.family))
                        Badge("${spec.native.width}×${spec.native.height}")
                        if (spec.isInpaintModel()) Badge(stringResource(R.string.mb_kind_inpaint), MaterialTheme.colorScheme.primary)
                        // ⭐ What a Swap conversion takes per render ([ModelFeatures]) — what its node offers.
                        if (spec.family == Family.SD15_SWAP || spec.family == Family.SDXL_SWAP) {
                            if (ModelFeatures.LORA in spec.featureSet) Badge("LoRA")
                            if (ModelFeatures.CONTROLNET in spec.featureSet) Badge(stringResource(R.string.cn_label))
                            if (ModelFeatures.IP_ADAPTER in spec.featureSet) Badge(stringResource(R.string.ip_label))
                        }
                        if (spec.isCustom) Badge(stringResource(R.string.mb_sub_imported))
                    }
                }
                ModelMenu(row, busy, onInstall, onDelete)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusLine(row, Modifier.weight(1f))
                ModelAction(row, busy, onInstall, onCancel, onUse)
            }
            row.progress?.let { p ->
                if (p.total > 0) {
                    LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp))
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp))
                }
                Text(
                    if (p.total > 0) stringResource(R.string.mb_of_total, p.done shr 20, p.total shr 20) + " · " + p.phase else p.phase,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusLine(row: ModelRow, modifier: Modifier) {
    val spec = row.spec
    // ⚠ With no build for THIS phone (a RAM gate: Krea 2 on 12 GB) the label is the
    // lowest arch any build needs — `minHtpArch` alone is 68 for a DiT and read "888+".
    val arch = row.build?.minArch ?: spec.builds.minOfOrNull { it.minArch } ?: spec.minHtpArch
    val (dot, word) = when {
        row.selected && row.installed -> MaterialTheme.colorScheme.primary to stringResource(R.string.mb_status_in_use)
        row.installed -> Color(0xFF34C38F) to stringResource(R.string.mb_status_installed)
        spec.isCustom || row.partial -> MaterialTheme.colorScheme.error to stringResource(R.string.mb_status_incomplete)
        else -> MaterialTheme.colorScheme.onSurfaceVariant to stringResource(R.string.mb_status_not_installed)
    }
    val size = when {
        row.installed -> gb2(row.onDisk)
        row.partial -> gb2(row.fetchBytes)
        row.build != null -> gb2(row.build.bytes)
        else -> null
    }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!spec.isCustom) {
                Text(archLabel(arch), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
            }
            Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(5.dp))
            Text(
                listOfNotNull(word, size).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // ⚠ The facts the old card said in words, kept where they matter.
        val note = when {
            row.ramTight -> stringResource(R.string.ram_tight).trim().removePrefix("·").trim()
            spec.isCustom && row.missing.isNotEmpty() -> stringResource(R.string.incomplete_missing, row.missing.joinToString())
            row.unsupported() && row.needsRam > 0 -> stringResource(R.string.needs_16gb_ram)
            row.unsupported() -> stringResource(R.string.cannot_run_it)
            else -> null
        }
        note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ModelAction(
    row: ModelRow,
    busy: Boolean,
    onInstall: (ModelSpec) -> Unit,
    onCancel: () -> Unit,
    onUse: (ModelSpec) -> Unit,
) {
    when {
        row.progress != null -> OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        row.installed -> Button(onClick = { onUse(row.spec) }, enabled = !busy) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.use))
        }
        row.spec.isCustom -> {}
        row.build == null -> {}
        else -> OutlinedButton(onClick = { onInstall(row.spec) }, enabled = !busy) {
            Text(stringResource(if (row.partial) R.string.repair else R.string.download))
        }
    }
}

/** ⭐ ⋮ — Delete (refused on the model in use) and Repair. */
@Composable
private fun ModelMenu(row: ModelRow, busy: Boolean, onInstall: (ModelSpec) -> Unit, onDelete: (ModelSpec) -> Unit) {
    val canDelete = (row.installed || row.spec.isCustom || row.partial) && row.progress == null
    val canRepair = row.partial && row.build != null && row.progress == null
    if (!canDelete && !canRepair) {
        Spacer(Modifier.size(40.dp))
        return
    }
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.mb_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (canRepair) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.repair)) },
                    enabled = !busy,
                    onClick = { open = false; onInstall(row.spec) },
                )
            }
            if (canDelete) {
                DropdownMenuItem(
                    text = {
                        Text(
                            if (row.selected) stringResource(R.string.delete) + " — " + stringResource(R.string.in_use).lowercase()
                            else stringResource(R.string.delete),
                        )
                    },
                    enabled = !busy && !row.selected,
                    onClick = { open = false; onDelete(row.spec) },
                )
            }
        }
    }
}

/** ⭐ A family page's header: back, the family's name, its count, its note. */
@Composable
internal fun FamilyHeader(family: Family, count: Int, note: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        BackRow(
            family.label,
            stringResource(if (family.isModern()) R.string.mb_group_dit else R.string.mb_group_classic) +
                " · " + stringResource(R.string.mb_models_count, count),
            onBack,
        ) {
            Box(Modifier.padding(end = 8.dp).size(12.dp).clip(CircleShape).background(familyColor(family)))
        }
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text(
                note,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * ⭐ The top of a page one level into a browser — a family's models, one LoRA:
 * arrow, title, a quiet line under it. Shared so the two cannot drift.
 * ⭐ The WHOLE row is Back, not just the 48dp arrow: the user missed it on the
 * first tap (2026-10-06) and asked for a bigger target.
 */
@Composable
internal fun BackRow(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClickLabel = stringResource(R.string.mb_back), onClick = onBack),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.mb_back))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
    }
}

/**
 * ⭐ A pill that opens a list — the [Pill] look, outlined, with ▾. For a choice
 * that sits in a row of pills but has too many words to be pills itself
 * (the LoRA browser's sort order).
 */
@Composable
internal fun <T> PillMenu(
    items: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(
            onClick = { open = true },
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Row(
                Modifier.padding(start = 16.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label(selected),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (k in items) {
                DropdownMenuItem(
                    text = {
                        Text(
                            label(k),
                            color = if (k == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = { open = false; onSelect(k) },
                )
            }
        }
    }
}

@Composable
internal fun subLabel(s: SubKind): String = stringResource(
    when (s) {
        SubKind.ALL -> R.string.mb_kind_all
        SubKind.BASE -> R.string.mb_sub_base
        SubKind.INPAINT -> R.string.mb_kind_inpaint
        SubKind.IMPORTED -> R.string.mb_sub_imported
    },
)
