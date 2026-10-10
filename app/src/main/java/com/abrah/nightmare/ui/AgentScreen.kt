package com.abrah.nightmare.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abrah.nightmare.R
import com.abrah.nightmare.agent.AgentConfig
import com.abrah.nightmare.agent.AgentMode
import com.abrah.nightmare.agent.ChatItem
import com.abrah.nightmare.agent.Provider
import com.abrah.nightmare.agent.SetupInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.material.icons.filled.Delete
import com.abrah.nightmare.canvas.RunLogState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** ⭐ Everything the agent's panel shows — hoisted, the session owns it. */
class AgentUi(
    val items: List<ChatItem>,
    val busy: Boolean,
    val config: AgentConfig,
    val mode: AgentMode?,
    /** The provider's model ids once loaded (`AgentSession.loadModels`), per provider. */
    val models: Map<Provider, List<String>>,
    val modelsLoading: Boolean,
    val modelsError: String?,
    /** ⭐ The canvas's run log while a Run is going — drawn live at the foot of the chat. */
    val runLog: RunLogState?,
    /** ⭐ The named setups (`AgentConfig.profiles`). */
    val profiles: List<String> = emptyList(),
    /** ⭐ The picture waiting to go with the next message (a file), or null. */
    val attached: String? = null,
    /** ⭐ A LoRA the agent is downloading (`download_lora`): its name and live progress, or null. */
    val download: Pair<String, com.abrah.nightmare.ModelInstaller.Progress>? = null,
    /** ⭐ The aspect of what the canvas's run makes — the live run's frame ([com.abrah.nightmare.canvas.runAspect]). */
    val runAspect: Float = 1f,
)

/** ⭐ What the panel can ask the session to do. */
class AgentActions(
    val onSend: (String) -> Unit = {},
    val onStop: () -> Unit = {},
    val onNewChat: () -> Unit = {},
    val onMode: (AgentMode?) -> Unit = {},
    val onAnswer: (Int, String) -> Unit = { _, _ -> },
    val onSaveConfig: (SetupInput) -> Unit = {},
    val onLoadModels: (Provider, String, String?) -> Unit = { _, _, _ -> },
    val onUseProfile: (String) -> Unit = {},
    val onDeleteProfile: (String) -> Unit = {},
    val onAttach: (String) -> Unit = {},
    val onDetach: () -> Unit = {},
    /** ⭐ Cancel on the download card — the installers' one cancel. */
    val onCancelDownload: () -> Unit = {},
)

/**
 * ⭐⭐ The Agent VIEW (the user's three views, 2026-10-08 — it was a panel over the canvas, which
 * fought the keyboard and covered Run): the header, then the setup (until a provider, a model and
 * a key are saved, and again from the ⚙) or the chat. Hoisted; also what the goldens draw.
 */
@Composable
fun AgentScreen(
    ui: AgentUi,
    act: AgentActions,
    modifier: Modifier = Modifier,
) {
    var setup by rememberSaveable { mutableStateOf(!ui.config.ready) }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.agent_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            if (!setup) {
                // ⭐ Clear chat — the conversation and its attachments; the setup stays.
                IconButton(onClick = act.onNewChat, enabled = !ui.busy && ui.items.isNotEmpty()) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.agent_clear_chat))
                }
            }
            IconButton(onClick = { setup = !setup || !ui.config.ready }) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.agent_setup))
            }
        }
        if (setup) {
            AgentSetup(ui, act) { input ->
                act.onSaveConfig(input)
                setup = false
            }
        } else {
            AgentChat(ui, act, Modifier.weight(1f))
        }
    }
}

@Composable
private fun AgentChat(ui: AgentUi, act: AgentActions, modifier: Modifier) {
    val list = rememberLazyListState()
    val live = ui.runLog?.takeIf { it.running }
    // ⭐ Consecutive tool calls fold into ONE row per turn (Violet Studio's accordion).
    val blocks = chatBlocks(ui.items)
    // ⭐⭐ Follow the chat only while the person is at its end (the user, 2026-10-09: it force-
    // scrolled away from what they were reading). A drag up stops following; reaching the end again,
    // or sending, resumes it. What arrives meanwhile is counted on a pill instead.
    var follow by remember { mutableStateOf(true) }
    var shown by remember { mutableIntStateOf(blocks.size) }
    var firstUnseen by remember { mutableStateOf<Int?>(null) }
    val dragging by list.interactionSource.collectIsDraggedAsState()
    val blockCount by rememberUpdatedState(blocks.size)
    LaunchedEffect(list) {
        snapshotFlow { dragging to list.canScrollForward }.collect { (drag, more) ->
            if (!more) {
                follow = true
                firstUnseen = null
                shown = blockCount
            } else if (drag) follow = false
        }
    }
    LaunchedEffect(ui.items.size, live?.lines?.size, ui.busy) {
        if (follow || ui.items.lastOrNull() is ChatItem.User) {
            follow = true
            firstUnseen = null
            shown = blocks.size
            val extra = (if (live != null) 1 else 0) + (if (ui.busy) 1 else 0)
            val last = blocks.size - 1 + extra + (if (ui.items.isEmpty()) 1 else 0)
            if (last >= 0) list.animateScrollToItem(last)
        } else if (blocks.size > shown && firstUnseen == null) {
            firstUnseen = shown
        }
    }
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxWidth().padding(top = 4.dp)) {
      Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxSize(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // ⚠ No start cards (2026-10-09) and no example prompts (2026-10-10): an empty chat is
            // empty — the agent reads whether to run from the wording (`AgentTools.systemPrompt`).
            itemsIndexed(blocks, key = { _, b -> b.key }) { _, b ->
                when (b) {
                    is ChatBlock.One -> ChatRow(b.item, act)
                    is ChatBlock.Tools -> ToolGroup(b.tools)
                }
            }
            // ⭐ The run as its picture's frame, log inside ([com.abrah.nightmare.canvas.RunFrame]).
            if (live != null) item { com.abrah.nightmare.canvas.RunFrame(live, ui.runAspect, Modifier.fillMaxWidth()) }
            // ⭐⭐ A LoRA download the agent started shows the Models card's progress — bytes, bar,
            // phase, Cancel (the user, 2026-10-10: only an endless bar said anything was happening).
            ui.download?.let { (name, p) ->
                item(key = "download") {
                    DownloadCard(
                        title = name, emphasised = false, status = p.phase, detail = stringResource(R.string.agent_downloading_lora),
                        progress = p,
                    ) { OutlinedButton(onClick = act.onCancelDownload) { Text(stringResource(R.string.cancel)) } }
                }
            }
            if (ui.busy && live == null && ui.download == null) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp)) }
        }
        // ⭐ "N new messages" — a tap goes to the FIRST of them, not the end.
        firstUnseen?.let { from ->
            val n = blocks.size - from
            if (n > 0) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)) {
                Pill(pluralStringResource(R.plurals.agent_new_messages, n, n), selected = true) {
                    firstUnseen = null
                    shown = blocks.size
                    scope.launch { list.animateScrollToItem(from) }
                }
            }
        }
      }
        var text by rememberSaveable { mutableStateOf("") }
        // ⭐ A picture to go with the message — the harness describes and tags it on the phone.
        ui.attached?.let { path ->
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))) { ChatPicture(path) }
                IconButton(onClick = act.onDetach) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.agent_detach))
                }
            }
        }
        val pick = com.abrah.nightmare.canvas.rememberImagePick(act.onAttach)
        // ⭐ The composer (Violet Studio): one rounded field — attach, the text, a round violet Send
        // (Stop while it works).
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = pick, enabled = !ui.busy) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.agent_attach), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.agent_input_hint)) },
                maxLines = 5,
                colors = androidx.compose.material3.TextFieldDefaults.colors(
                    focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                modifier = Modifier.weight(1f),
            )
            val canSend = text.isNotBlank() || ui.attached != null
            androidx.compose.material3.FilledIconButton(
                onClick = { if (ui.busy) act.onStop() else { act.onSend(text); text = "" } },
                enabled = ui.busy || canSend,
                modifier = Modifier.size(44.dp),
            ) {
                if (ui.busy) Icon(StopIcon, contentDescription = stringResource(R.string.agent_stop), modifier = Modifier.size(18.dp))
                else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.agent_send), modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** ⭐ What the chat draws: a single item, or a turn's consecutive tool calls as one group. */
internal sealed interface ChatBlock {
    val key: String
    data class One(val item: ChatItem, override val key: String) : ChatBlock
    data class Tools(val tools: List<ChatItem.Tool>, override val key: String) : ChatBlock
}

/** ⭐ Pure: consecutive [ChatItem.Tool]s become one [ChatBlock.Tools]. Keys stay stable as the chat grows. */
internal fun chatBlocks(items: List<ChatItem>): List<ChatBlock> {
    val out = mutableListOf<ChatBlock>()
    var run = mutableListOf<ChatItem.Tool>()
    var runStart = 0
    items.forEachIndexed { i, it ->
        if (it is ChatItem.Tool) {
            if (run.isEmpty()) runStart = i
            run += it
        } else {
            if (run.isNotEmpty()) { out += ChatBlock.Tools(run, "t$runStart"); run = mutableListOf() }
            out += ChatBlock.One(it, "i$i")
        }
    }
    if (run.isNotEmpty()) out += ChatBlock.Tools(run, "t$runStart")
    return out
}

/**
 * ⭐⭐ A turn's tools as one collapsible row — "3 tools completed", or how many failed — expanded
 * to each call and its summary. ⚠ Collapsed by default; a failed group opens itself so the
 * failure is seen.
 */
@Composable
private fun ToolGroup(tools: List<ChatItem.Tool>) {
    val failed = tools.count { !it.ok }
    var open by rememberSaveable(tools.size, failed) { mutableStateOf(failed > 0) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { open = !open }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (failed == 0) Icons.Filled.Check else Icons.Filled.Close, contentDescription = null,
                tint = if (failed == 0) Success else MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (failed == 0) pluralStringResource(R.plurals.agent_tools_done, tools.size, tools.size)
                else stringResource(R.string.agent_tools_failed, tools.size, failed),
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f),
            )
            Icon(if (open) ChevronUpIcon else ChevronDownIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        if (open) {
            for (t in tools) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        if (t.ok) Icons.Filled.Check else Icons.Filled.Close, contentDescription = null,
                        tint = if (t.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 2.dp).size(14.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        t.name + if (t.summary.isNotBlank()) " · " + t.summary else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatRow(item: ChatItem, act: AgentActions) {
    when (item) {
        is ChatItem.User -> if (item.text.isNotBlank()) UserMessage(item.text)
        is ChatItem.Assistant -> AgentMessage(item.text)
        // ⚠ Tools are drawn by [ToolGroup]; a lone one never reaches here.
        is ChatItem.Tool -> Unit
        is ChatItem.Picture -> ChatPicture(item.path)
        is ChatItem.Error -> ErrorNotice(item.text)
        is ChatItem.Log -> Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
        ) {
            for (l in item.lines.takeLast(8)) {
                Text(l, style = MeasureTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        is ChatItem.Ask -> Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(com.abrah.nightmare.ui.Warning.copy(alpha = 0.10f)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(item.question, style = MaterialTheme.typography.bodyMedium)
            if (item.answer == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // ⚠ The safe option first and the action last, where the thumb lands — §8.1's order.
                    item.options.asReversed().forEachIndexed { i, o ->
                        if (i == item.options.size - 1) {
                            Button(onClick = { act.onAnswer(item.id, o) }, shape = RoundedCornerShape(12.dp)) { Text(o) }
                        } else {
                            OutlinedButton(onClick = { act.onAnswer(item.id, o) }, shape = RoundedCornerShape(12.dp)) { Text(o) }
                        }
                    }
                }
            } else {
                Text("→ " + item.answer, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** ⭐ The person's message: a violet bubble on the right, with Copy under it. */
@Composable
private fun UserMessage(text: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
        CopyAction(text)
    }
}

/** ⭐ The agent's message: Markdown on a plum card, with Copy under it. */
@Composable
private fun AgentMessage(text: String) {
    Column(Modifier.fillMaxWidth()) {
        MarkdownText(
            text,
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp),
        )
        CopyAction(text)
    }
}

/** ⚠ Decoded off the main thread at most 1024 px — a result can be 2048². */
@Composable
private fun ChatPicture(path: String) {
    val bmp by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, o)
                var s = 1
                while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 1024) s *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
            }.getOrNull()
        }
    }
    bmp?.let {
        Image(it, contentDescription = null, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)))
    }
}

/**
 * ⭐ Provider ([com.abrah.nightmare.canvas.Chooser]), address (Other only), key, and the model —
 * a SEARCH BOX (the user's call, 2026-10-08): the provider's list is fetched once, when the box
 * first takes focus, and filtered locally as the person types; suggestions show only while it
 * has focus. ⚠ The key field is never filled back: a saved key shows as "saved".
 */
@Composable
private fun AgentSetup(ui: AgentUi, act: AgentActions, onSave: (SetupInput) -> Unit) {
    val config = ui.config
    var provider by remember { mutableStateOf(config.provider) }
    var base by remember { mutableStateOf(config.baseUrl) }
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(config.model) }
    var instructions by remember(config) { mutableStateOf(config.instructions) }
    var profileName by remember { mutableStateOf("") }
    var modelFocus by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val uri = LocalUriHandler.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.agent_note), style = NoteTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // ⭐ Saved setups — tap one to use it, ✕ to forget it (the user's call, 2026-10-08).
        if (ui.profiles.isNotEmpty()) {
            Text(stringResource(R.string.agent_profiles), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                for (name in ui.profiles) {
                    Pill(name, false) { act.onUseProfile(name) }
                    IconButton(onClick = { act.onDeleteProfile(name) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.agent_profile_delete, name), modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        com.abrah.nightmare.canvas.Chooser(
            label = stringResource(R.string.agent_provider),
            hint = null,
            options = Provider.entries.map { it.label },
            current = provider.label,
            onPick = { label ->
                Provider.entries.firstOrNull { it.label == label }?.let {
                    if (it != provider) model = ""
                    provider = it
                    base = it.baseUrl.ifBlank { base }
                }
            },
        )
        if (provider == Provider.CUSTOM) {
            OutlinedTextField(
                value = base, onValueChange = { base = it.trim() }, singleLine = true,
                label = { Text(stringResource(R.string.agent_base_url)) },
                placeholder = { Text("http://192.168.0.10:8080/v1") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = key, onValueChange = { key = it.trim() }, singleLine = true,
            label = { Text(stringResource(R.string.agent_key)) },
            placeholder = { if (config.hasKey && provider == config.provider) Text(stringResource(R.string.agent_key_saved)) },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        provider.keyPage?.let { page ->
            TextButton(onClick = { runCatching { uri.openUri(page) } }) { Text(stringResource(R.string.agent_get_key, provider.label)) }
        }
        OutlinedTextField(
            value = model, onValueChange = { model = it.trim() }, singleLine = true,
            label = { Text(stringResource(R.string.agent_model)) },
            placeholder = { Text(stringResource(R.string.agent_model_search)) },
            modifier = Modifier.fillMaxWidth().onFocusChanged { f ->
                modelFocus = f.isFocused
                if (f.isFocused) act.onLoadModels(provider, base, key.ifBlank { null })
            },
        )
        if (modelFocus) {
            val all = ui.models[provider]
            when {
                ui.modelsLoading && all == null -> Text(stringResource(R.string.agent_models_loading), style = NoteTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                all == null -> ui.modelsError?.let { ErrorNotice(it) }
                else -> {
                    val shown = all.filter { model.isBlank() || it.contains(model, ignoreCase = true) }.take(MAX_SUGGESTIONS)
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant).verticalScroll(rememberScrollState()),
                    ) {
                        if (shown.isEmpty()) {
                            Text(stringResource(R.string.agent_models_none), style = NoteTextStyle, modifier = Modifier.padding(12.dp))
                        }
                        for (id in shown) {
                            Text(
                                id,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth().clickable { model = id; focus.clearFocus() }.padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        }
        // ⭐ The person's own instructions, added to the agent's — style, language, what to allow.
        OutlinedTextField(
            value = instructions, onValueChange = { instructions = it },
            label = { Text(stringResource(R.string.agent_instructions)) },
            placeholder = { Text(stringResource(R.string.agent_instructions_hint)) },
            minLines = 3, maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = profileName, onValueChange = { profileName = it }, singleLine = true,
            label = { Text(stringResource(R.string.agent_profile_name)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onSave(SetupInput(provider, base, model, key.ifBlank { null }, instructions, profileName.ifBlank { null })) },
            enabled = model.isNotBlank() && (provider != Provider.CUSTOM || base.isNotBlank()),
        ) { Text(stringResource(R.string.save)) }
    }
}

private const val MAX_SUGGESTIONS = 40
