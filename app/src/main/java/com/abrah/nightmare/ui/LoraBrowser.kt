package com.abrah.nightmare.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abrah.nightmare.Family
import com.abrah.nightmare.LoraSources
import com.abrah.nightmare.LoraSources.Hit
import com.abrah.nightmare.LoraSources.Page
import com.abrah.nightmare.LoraSources.Sort
import com.abrah.nightmare.LoraSources.Source
import com.abrah.nightmare.LoraSources.Target
import com.abrah.nightmare.LoraSources.Version
import com.abrah.nightmare.ModelInstaller
import com.abrah.nightmare.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ⭐⭐⭐ **Get LoRAs — search CivitAI or Hugging Face and download into `_loras`**
 * (`docs/LORA-BROWSER.md`). Opened from a node's LoRA list, on that node's family.
 *
 * ⭐ **Search as you type**, 350 ms after the last key — measured: a search on a
 * kept-alive connection answers in 0.27–0.53 s, so results land ~0.6–0.9 s after
 * the typing stops. A newer query CANCELS the one in flight ([LaunchedEffect]
 * restarts); Enter searches at once. The sheet opens on "most downloaded for
 * this family", which also opens the connection before the first keystroke.
 *
 * ⚠⚠ Built from the shared pieces (`docs/UI.md` §8): [ScreenHeader], [SearchBox],
 * [Pill], [PillMenu], [BackRow], [ErrorNotice] (§8.5),
 * [Badge] and [familyColor] from the Models browser, and each downloadable
 * version is a [DownloadCard] (§8.1) — the size, the live byte count, the bar
 * and Cancel come with it.
 *
 * ⚠ The content is split from [com.abrah.nightmare.ui.PullDownSheet] so a golden
 * (`LoraBrowserScreenshotTest`) can draw it, and [search] / [details] are parameters so it draws without a
 * network.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LoraBrowserContent(
    initialTarget: Target,
    /** File names in `_loras` — what "Added" is decided by. */
    installed: Set<String>,
    /** The browser's download in flight: version id → progress. */
    fetch: Pair<String, ModelInstaller.Progress>?,
    /** Why the last download failed, by version id. */
    fetchError: Pair<String, Throwable>?,
    /** Another download is running — every Download is disabled (one at a time, §8.1). */
    busy: Boolean,
    civitaiKey: String,
    mature: Boolean,
    onSaveKey: (String) -> Unit,
    onDownload: (label: String, version: Version) -> Unit,
    onCancel: () -> Unit,
    search: suspend (Source, Target, String, Sort, String?) -> Page = { src, t, q, s, c ->
        withContext(Dispatchers.IO) { LoraSources.search(src, t, q, s, c, mature, civitaiKey.ifBlank { null }) }
    },
    details: suspend (Hit, Target) -> Hit = { h, t -> withContext(Dispatchers.IO) { LoraSources.details(h, t) } },
    /** ⚠ A pasted link → the one model it names. */
    resolveLink: suspend (LoraSources.Link, Target) -> Hit? = { link, t ->
        withContext(Dispatchers.IO) {
            when (link) {
                is LoraSources.Link.Civitai -> LoraSources.civitaiModel(link.modelId, t, mature, civitaiKey.ifBlank { null })
                is LoraSources.Link.HuggingFace -> LoraSources.hfRepo(link.repo, t)
            }
        }
    },
    modifier: Modifier = Modifier,
) {
    var target by remember { mutableStateOf(initialTarget) }
    var source by remember { mutableStateOf(Source.CIVITAI) }
    var sort by remember { mutableStateOf(Sort.DOWNLOADS) }
    var query by remember { mutableStateOf("") }
    // ⚠ Bumped by Enter and by a pill: the next search skips the debounce.
    var now by remember { mutableIntStateOf(0) }
    var immediate by remember { mutableStateOf(true) }
    var hits by remember { mutableStateOf<List<Hit>>(emptyList()) }
    var next by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var selected by remember { mutableStateOf<Hit?>(null) }
    val cache = remember { HashMap<String, Page>() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(source, target, sort, query.trim(), now) {
        val q = query.trim()
        if (!immediate && q.isNotEmpty()) delay(DEBOUNCE_MS)
        immediate = false
        loading = true
        error = null
        val key = "$source|$target|$sort|$q|$mature"
        val result = runCatching {
            cache[key] ?: run {
                val link = LoraSources.parseLink(q)
                if (link != null) Page(listOfNotNull(resolveLink(link, target)), null)
                else search(source, target, q, sort, null)
            }.also { cache[key] = it }
        }
        // ⚠⚠ A newer keystroke CANCELS this search, and the cancellation arrives
        // as an exception that runCatching catches — rethrown, or every cancelled
        // search would paint itself as an error.
        result.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
        result.onSuccess { hits = it.hits; next = it.next }
            .onFailure {
                hits = emptyList(); next = null; error = it
                // ⚠ So the notice's share icon has the stack (§8.5).
                if (it !is LoraSources.Failure) com.abrah.nightmare.ErrorReport.record(it)
            }
        loading = false
    }

    // ⚠⚠ Laid out like [LibraryScreen] and Settings — the other two bodies of a
    // [PullDownSheet]: `navigationBarsPadding().padding(16.dp)`, then
    // [ScreenHeader]. Before 1.6.098 it had no padding of its own and ran into
    // both edges of the sheet (phone, 2026-10-07).
    Column(modifier.fillMaxSize().navigationBarsPadding().padding(16.dp)) {
    val shown = selected
    if (shown != null) {
        // ⚠ Back leaves the LoRA before it leaves the sheet, as a Models family page does.
        BackHandler { selected = null }
        LoraDetail(
            shown, target, installed, fetch, fetchError, busy, civitaiKey, onSaveKey, onDownload, onCancel,
            details = details, onBack = { selected = null },
        )
        return@Column
    }

    // ⭐ The family is part of the title — "Get LoRAs for SD 1.5 / SDXL" — so
    // the controls below are one row, not three rows of pills.
    ScreenHeader(stringResource(R.string.lb_title), onClose = null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (t in Target.entries) Pill(t.label, t == target) { immediate = true; target = t }
        }
    }
    SearchBox(
        query, { query = it },
        modifier = Modifier.padding(top = 8.dp),
        hint = stringResource(R.string.lb_search_hint),
        onSubmit = { immediate = true; now++ },
    )
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (s in Source.entries) Pill(sourceLabel(s), s == source) { immediate = true; source = s }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            PillMenu(Sort.entries, sort, { sortLabel(it) }) { immediate = true; sort = it }
        }
    }
    if (source == Source.CIVITAI && civitaiKey.isBlank()) {
        // ⭐ The [FamilyHeader] note's card: grey prose on `surfaceContainer`.
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.lb_key_banner),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CivitaiKeyField(civitaiKey, onSaveKey)
            }
        }
    }

    LazyColumn(
        Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (loading && hits.isEmpty()) {
            item { Status(stringResource(R.string.lb_searching), spinner = true) }
        }
        // ⭐ The app's one failure look (`docs/UI.md` §8.5); reportable only when
        // it is a fault, not a site saying no (region, key, early access).
        error?.let { e ->
            item { ErrorNotice(failureText(e), Modifier.fillMaxWidth(), reportable = e !is LoraSources.Failure) }
        }
        if (!loading && error == null && hits.isEmpty()) {
            item { Status(stringResource(R.string.lb_no_results)) }
        }
        items(hits, key = { "${it.source}|${it.id}" }) { hit ->
            LoraHitCard(hit, target, installed) { selected = hit }
        }
        val cursor = next
        if (cursor != null && hits.isNotEmpty()) {
            item {
                TextButton(
                    enabled = !loading,
                    onClick = {
                        loading = true
                        scope.launch {
                            runCatching { search(source, target, query.trim(), sort, cursor) }
                                .also { r -> r.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it } }
                                .onSuccess { p -> hits = (hits + p.hits).distinctBy { it.id }; next = p.next }
                                .onFailure { error = it }
                            loading = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.lb_load_more)) }
            }
        }
    }
    }
}

private const val DEBOUNCE_MS = 350L

@Composable
private fun sourceLabel(s: Source): String = when (s) {
    Source.CIVITAI -> "CivitAI"
    Source.HUGGINGFACE -> "Hugging Face"
}

@Composable
private fun sortLabel(s: Sort): String = stringResource(
    when (s) {
        Sort.DOWNLOADS -> R.string.lb_sort_downloads
        Sort.RATED -> R.string.lb_sort_rated
        Sort.NEWEST -> R.string.lb_sort_newest
    },
)

/** ⭐ The sentence a person can act on, for every failure [LoraSources] names. */
@Composable
private fun failureText(e: Throwable): String = when (e) {
    is LoraSources.Failure.RegionBlocked -> stringResource(R.string.lb_err_region)
    is LoraSources.Failure.NeedsKey -> stringResource(R.string.lb_err_key)
    is LoraSources.Failure.BadKey -> stringResource(R.string.lb_err_bad_key)
    is LoraSources.Failure.EarlyAccess ->
        e.until?.let { stringResource(R.string.lb_early_access_until, it.substringBefore('T')) }
            ?: stringResource(R.string.lb_early_access)
    else -> stringResource(R.string.lb_err_network, e.message ?: e.javaClass.simpleName)
}

/** Searching, or nothing found — grey prose (`docs/UI.md` §8.5). A failure is an [ErrorNotice]. */
@Composable
private fun Status(text: String, spinner: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (spinner) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun Target.family(): Family = when (this) {
    Target.SD15 -> Family.SD15_SWAP
    Target.SDXL -> Family.SDXL_SWAP
}

/** "12.3k", "1.2M" — a download count at a glance. */
internal fun compactCount(n: Long): String = when {
    n >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(java.util.Locale.ROOT, "%.1fk", n / 1_000.0)
    else -> n.toString()
}

/** ⭐ A result: the [ModelCardV2] look — badges, then a quiet status line — with a preview. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LoraHitCard(hit: Hit, target: Target, installed: Set<String>, onOpen: () -> Unit) {
    val added = hit.versions.any { v -> v.file?.name?.let { it in installed } == true }
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            RemoteThumb(hit.thumb, Modifier.size(76.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    hit.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(
                    Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val bases = hit.versions.map { it.baseModel }.distinct().ifEmpty { listOf(target.label) }
                    for (b in bases.take(3)) Badge(b, familyColor(target.family()))
                    if (added) Badge(stringResource(R.string.lb_added), MaterialTheme.colorScheme.primary)
                    if (hit.nsfw) Badge("NSFW", MaterialTheme.colorScheme.error)
                }
                Text(
                    listOfNotNull(
                        hit.creator?.let { stringResource(R.string.lb_by, it) },
                        stringResource(R.string.lb_downloads, compactCount(hit.downloads)),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** ⭐ One LoRA: its preview, its page, and a [DownloadCard] per version (or per file, on Hugging Face). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.LoraDetail(
    hit: Hit,
    target: Target,
    installed: Set<String>,
    fetch: Pair<String, ModelInstaller.Progress>?,
    fetchError: Pair<String, Throwable>?,
    busy: Boolean,
    civitaiKey: String,
    onSaveKey: (String) -> Unit,
    onDownload: (String, Version) -> Unit,
    onCancel: () -> Unit,
    details: suspend (Hit, Target) -> Hit,
    onBack: () -> Unit,
) {
    val uri = LocalUriHandler.current
    val full by produceState<Result<Hit>?>(initialValue = if (hit.versions.isNotEmpty()) Result.success(hit) else null, hit) {
        if (hit.versions.isEmpty()) value = runCatching { details(hit, target) }
    }
    // ⭐ The Models family page's header ([BackRow]): the whole row is Back.
    BackRow(
        hit.name,
        listOfNotNull(
            hit.creator?.let { stringResource(R.string.lb_by, it) },
            stringResource(R.string.lb_downloads, compactCount(hit.downloads)),
        ).joinToString(" · "),
        onBack,
    )
    LazyColumn(
        Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RemoteThumb(hit.thumb, Modifier.size(140.dp))
                Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Badge(sourceLabel(hit.source))
                        if (hit.nsfw) Badge("NSFW", MaterialTheme.colorScheme.error)
                    }
                    OutlinedButton(onClick = { runCatching { uri.openUri(hit.pageUrl) } }) {
                        Text(stringResource(R.string.lb_open_page))
                    }
                }
            }
        }
        val result = full
        when {
            result == null -> item { Status(stringResource(R.string.lb_searching), spinner = true) }
            result.isFailure -> result.exceptionOrNull()!!.let { e ->
                item { ErrorNotice(failureText(e), Modifier.fillMaxWidth(), reportable = e !is LoraSources.Failure) }
            }
            else -> items(result.getOrThrow().versions, key = { it.id }) { v ->
                VersionCard(hit, v, installed, fetch, fetchError, busy, civitaiKey, onSaveKey, onDownload, onCancel)
            }
        }
    }
}

/** ⭐ One version on the [DownloadCard] every downloadable row uses (`docs/UI.md` §8.1). */
@Composable
private fun VersionCard(
    hit: Hit,
    v: Version,
    installed: Set<String>,
    fetch: Pair<String, ModelInstaller.Progress>?,
    fetchError: Pair<String, Throwable>?,
    busy: Boolean,
    civitaiKey: String,
    onSaveKey: (String) -> Unit,
    onDownload: (String, Version) -> Unit,
    onCancel: () -> Unit,
) {
    val file = v.file
    val here = file?.name?.let { it in installed } == true
    val progress = fetch?.takeIf { it.first == v.id }?.second
    val tooLarge = file != null && file.bytes > LoraSources.MAX_LORA_BYTES
    val size = file?.let { "${it.bytes shr 20} MB" }
    val status = when {
        here -> listOfNotNull(stringResource(R.string.mb_status_installed), size)
        file == null -> listOf(stringResource(R.string.lb_no_file))
        v.earlyAccess -> listOf(stringResource(R.string.lb_early_access))
        tooLarge -> listOfNotNull(stringResource(R.string.lb_too_large), size)
        else -> listOfNotNull(stringResource(R.string.mb_status_not_installed), size)
    }.joinToString(" · ")
    val detail = listOfNotNull(
        v.baseModel.takeIf { it.isNotBlank() },
        v.trigger.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.lb_trigger, it.joinToString(", ")) },
    ).joinToString(" · ")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DownloadCard(
            title = v.name,
            emphasised = here,
            status = status,
            detail = detail,
            progress = progress,
        ) {
            when {
                progress != null -> OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                here -> OutlinedButton(onClick = {}, enabled = false) { Text(stringResource(R.string.lb_added)) }
                else -> Button(
                    onClick = { onDownload(hit.name, v) },
                    enabled = !busy && file != null && !tooLarge && !v.earlyAccess,
                ) { Text(stringResource(R.string.download)) }
            }
        }
        val err = fetchError?.takeIf { it.first == v.id }?.second
        if (err != null) {
            // ⚠ The view model already `ErrorReport.record`s a non-site failure.
            ErrorNotice(failureText(err), Modifier.fillMaxWidth(), reportable = err !is LoraSources.Failure)
            if (err is LoraSources.Failure.NeedsKey || err is LoraSources.Failure.BadKey) {
                CivitaiKeyField(civitaiKey, onSaveKey)
            }
        }
    }
}

/**
 * ⭐ The CivitAI API key — ONE field, used by the browser's banner and by
 * Settings → Downloads, so the two cannot disagree. Hidden like a password;
 * committed on Save or the keyboard's Done, never per keystroke.
 */
@Composable
internal fun CivitaiKeyField(key: String, onSave: (String) -> Unit) {
    var text by remember(key) { mutableStateOf(key) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.trim() },
            singleLine = true,
            label = { Text(stringResource(R.string.lb_key_label)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave(text) }),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = { onSave(text) }, enabled = text != key) { Text(stringResource(R.string.save)) }
    }
}

// ---- previews --------------------------------------------------------------

/**
 * ⭐ A preview fetched off the main thread, decoded at most 256 px
 * ([com.abrah.nightmare.ImageStore]'s decode — `inSampleSize`, so the full image is
 * never allocated), and kept in a small cache so scrolling back costs nothing.
 * ⚠ Any failure is a blank square: a missing preview must never fail the list.
 */
@Composable
internal fun RemoteThumb(url: String?, modifier: Modifier) {
    val bmp by produceState<ImageBitmap?>(initialValue = url?.let { ThumbCache.get(it) }, url) {
        if (url == null || value != null) return@produceState
        value = withContext(Dispatchers.IO) { ThumbCache.load(url) }
    }
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        bmp?.let {
            androidx.compose.foundation.Image(
                it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private object ThumbCache {
    private val cache = android.util.LruCache<String, ImageBitmap>(60)

    // ⚠ Only for its [com.abrah.nightmare.ImageStore.decode] (sampled, EXIF-upright); it stores nothing here.
    private val decoder = com.abrah.nightmare.ImageStore(limit = 1)

    fun get(url: String): ImageBitmap? = cache.get(url)

    fun load(url: String): ImageBitmap? = runCatching {
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "NightmareMobile")
        }
        try {
            if (conn.responseCode !in 200..299) return@runCatching null
            val bytes = conn.inputStream.use { it.readBytes() }
            decoder.decode(bytes, 256)?.asImageBitmap()
        } finally {
            conn.disconnect()
        }
    }.getOrNull()?.also { cache.put(url, it) }
}
