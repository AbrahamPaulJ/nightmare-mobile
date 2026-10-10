package com.abrah.nightmare.agent

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.abrah.nightmare.ImageCaption
import com.abrah.nightmare.ImageTagger
import com.abrah.nightmare.api.ApiCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * ⭐⭐ The agent's chat as its view sees it — the items, whether it is working, the chosen start
 * ([mode]), the attached picture, the setups — held by the view model so a conversation
 * survives switching views (`ui/AgentScreen.kt`).
 * ⚠ Compose state is written on the main thread only: the loop runs on IO and posts back.
 */
class AgentSession(
    private val ctx: Context,
    private val core: ApiCore,
    private val host: CanvasHost,
    private val scope: CoroutineScope,
) {
    val items = mutableStateListOf<ChatItem>()
    var busy by mutableStateOf(false)
        private set
    var config by mutableStateOf(AgentConfig.load(ctx))
        private set
    var profiles by mutableStateOf(AgentConfig.profiles(ctx))
        private set
    /** ⭐ The chat's start (Create · Create & Run · Edit current); null = just talk. */
    var mode by mutableStateOf<AgentMode?>(null)
    /** ⭐ The picture waiting to go with the next message — a copy on disk. */
    var attached by mutableStateOf<String?>(null)
        private set

    /** ⭐ Each provider's model ids, fetched once per session for the search box. */
    val models = mutableStateMapOf<Provider, List<String>>()
    var modelsLoading by mutableStateOf(false)
        private set
    var modelsError by mutableStateOf<String?>(null)
        private set

    private var agent: Agent? = null
    private var job: Job? = null
    @Volatile private var stopping = false
    private val asks = ConcurrentHashMap<Int, CompletableFuture<String>>()
    private val nextAsk = AtomicInteger()
    /** `attachment:N` → its file, for the agent's `input_images`. */
    private val attachments = ConcurrentHashMap<String, String>()

    fun send(text: String) {
        val t = text.trim()
        val picture = attached
        if ((t.isEmpty() && picture == null) || busy || !config.ready) return
        // ⭐ The start choice travels with the chat's FIRST message, where the model reads it once.
        val first = items.none { it is ChatItem.User }
        items += ChatItem.User(t)
        picture?.let { items += ChatItem.Picture(it) }
        attached = null
        busy = true
        stopping = false
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val note = picture?.let { describe(it) }
                    val sent = listOfNotNull(
                        AgentTools.modeNote(mode).takeIf { first && mode != null },
                        note,
                        t.ifEmpty { null },
                    ).joinToString("\n")
                    val a = agent ?: Agent(
                        client(),
                        AgentTools(ctx, core, host, ::ask) { attachments.toMap() },
                        AgentTools.systemPrompt(language(), config.instructions),
                    ).also { agent = it }
                    a.send(sent, emit = { item -> scope.launch(Dispatchers.Main) { items += item } }, cancelled = { stopping })
                }
            } catch (e: Exception) {
                items += ChatItem.Error(e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
            }
        }
    }

    /**
     * ⭐⭐ An attached picture as the model will hear of it (the user's call, 2026-10-08: the model
     * need not SEE it) — described and tagged ON THE PHONE (Florence-2 PromptGen, WD tagger, when
     * installed), and named `attachment:N` for `set_params` `input_images`. The picture itself
     * never leaves the phone. ⚠ Blocking, IO.
     */
    private fun describe(path: String): String {
        val id = "attachment:" + (attachments.size + 1)
        attachments[id] = path
        val bmp = runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, o)
            var s = 1
            while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 1024) s *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
        }.getOrNull()
        val caption = bmp?.let { runCatching { ImageCaption.caption(ctx, it, ImageCaption.Length.DETAILED) }.getOrNull() }
        val tags = bmp?.let { runCatching { ImageTagger.tags(ctx, it) }.getOrNull() }
        scope.launch(Dispatchers.Main) {
            items += ChatItem.Tool("describe", listOfNotNull(caption?.let { "caption" }, tags?.let { "tags" }).joinToString(" · ").ifEmpty { "no describe models installed" }, true)
        }
        return buildString {
            append("[The person attached a picture: $id")
            bmp?.let { append(" (${it.width}x${it.height})") }
            append(". ")
            caption?.let { append("Description: $it ") }
            tags?.let { append("Tags: $it ") }
            if (caption == null && tags == null) append("No description models are installed on the phone; ask what it shows if you need to. ")
            append("Use it with set_params input_images {imageNodeId: \"$id\"} — the photo of img2img / flux_edit, or a Swap flow's control or IP-Adapter picture node.]")
        }
    }

    /** ⭐ A picked picture, copied into the app (a content URI can lose its grant). */
    fun attach(uri: String) {
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = if (uri.startsWith("content://")) {
                        ctx.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { it.readBytes() }
                    } else File(uri).readBytes()
                    bytes?.let { b ->
                        File(File(ctx.filesDir, "agent").apply { mkdirs() }, "att_${System.currentTimeMillis()}").apply { writeBytes(b) }.absolutePath
                    }
                }.getOrNull()
            }
            if (path != null) attached = path else items += ChatItem.Error("that picture could not be read")
        }
    }

    /** ⭐ A canvas Run pressed in the Agent view: its pictures join the chat. Main thread. */
    fun showPictures(bitmaps: List<android.graphics.Bitmap>) {
        if (bitmaps.isEmpty()) return
        scope.launch {
            val paths = withContext(Dispatchers.IO) {
                bitmaps.mapIndexed { i, bmp ->
                    File(File(ctx.filesDir, "agent").apply { mkdirs() }, "run_${System.currentTimeMillis()}_$i.png")
                        .apply { writeBytes(com.abrah.nightmare.ImageStore.encodePng(bmp)) }.absolutePath
                }
            }
            paths.forEach { items += ChatItem.Picture(it) }
        }
    }

    fun detach() {
        attached = null
    }

    /**
     * ⭐ A question for the person, BLOCKING the agent's thread until a button is tapped
     * ([answer]) or the chat is stopped ([AgentTools.CANCEL]). Never call it on main.
     */
    fun ask(question: String, options: List<String>): String {
        val id = nextAsk.incrementAndGet()
        val f = CompletableFuture<String>()
        asks[id] = f
        scope.launch(Dispatchers.Main) { items += ChatItem.Ask(id, question, options) }
        if (stopping) f.complete(AgentTools.CANCEL)
        return try { f.get() } finally { asks.remove(id) }
    }

    fun answer(id: Int, option: String) {
        val i = items.indexOfFirst { it is ChatItem.Ask && it.id == id }
        if (i >= 0) items[i] = (items[i] as ChatItem.Ask).copy(answer = option)
        asks[id]?.complete(option)
    }

    /** ⭐ Stops after the step in flight; a question waiting is answered Cancel, a render cancelled. */
    fun stop() {
        stopping = true
        asks.values.forEach { it.complete(AgentTools.CANCEL) }
        runCatching { host.cancelRun() }
    }

    /** ⭐ Clear chat — the conversation and its attachments go; the setup stays. */
    fun newChat() {
        if (busy) return
        items.clear()
        agent = null
        mode = null
        attached = null
        attachments.values.forEach { runCatching { File(it).delete() } }
        attachments.clear()
    }

    fun saveConfig(input: SetupInput) {
        AgentConfig.save(ctx, input.provider, input.baseUrl, input.model, input.key, input.instructions)
        input.profileName?.trim()?.takeIf { it.isNotEmpty() }?.let { AgentConfig.saveProfile(ctx, it) }
        reload()
    }

    fun useProfile(name: String) {
        AgentConfig.useProfile(ctx, name)
        reload()
    }

    fun deleteProfile(name: String) {
        AgentConfig.deleteProfile(ctx, name)
        profiles = AgentConfig.profiles(ctx)
    }

    private fun reload() {
        config = AgentConfig.load(ctx)
        profiles = AgentConfig.profiles(ctx)
        // ⚠ A new provider, model or instructions starts a new conversation: the old one's tool
        // calls were made in another model's format, under other instructions.
        agent = null
    }

    /**
     * ⭐ The provider's model list for the search box — fetched ONCE per provider per session,
     * when the box first takes focus, then filtered locally as the person types (no request
     * per keystroke). [typedKey] is a key typed but not yet saved.
     */
    fun loadModels(provider: Provider, baseUrl: String, typedKey: String?) {
        if (models.containsKey(provider) || modelsLoading) return
        modelsError = null
        modelsLoading = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val key = typedKey?.takeIf { it.isNotBlank() } ?: AgentConfig.key(ctx)
                    LlmClient(if (provider == Provider.CUSTOM) baseUrl else provider.baseUrl, key, "").models()
                }
            }
            modelsLoading = false
            r.onSuccess { models[provider] = it }.onFailure { modelsError = it.message }
        }
    }

    private fun client() = LlmClient(config.baseUrl, AgentConfig.key(ctx), config.model)

    private fun language() = ctx.resources.configuration.locales[0].displayLanguage
}
