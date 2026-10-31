package com.abrah.nightmare

import android.content.Context

/**
 * ⭐ The app's own settings, as opposed to the graph's.
 *
 * ⚠⚠ A separate store from `SelectedModel`, which is also a preference but is
 * a property of the WORK — a graph names its checkpoint, and opening someone
 * else's flow moves it. Theme is a property of the person, and nothing a
 * workflow does should ever change it.
 *
 * ⚠ Read once into a mutable state at start-up and written through, like
 * `SelectedModel`: a `SharedPreferences` read on every recomposition is a
 * disk touch in the draw path.
 */
object Prefs {

    private const val FILE = "nightmare.prefs"
    private const val KEY_THEME = "theme"
    private const val KEY_ACCENT = "accent"
    private const val KEY_MIRROR = "download_base"

    /**
     * ⚠⚠ THREE values, not a boolean. "Follow the system" is the default and
     * it is not expressible as on/off — a switch alone would silently pin the
     * app to whatever the phone happened to be when it was first opened, and
     * the user would have no way back to following.
     */
    /** ⭐ BLACK: dark with true-black backgrounds for OLED screens (a tester's wish, 2026-10-24). Stored by name. */
    enum class Theme { SYSTEM, DARK, BLACK, LIGHT }

    /**
     * ⭐ The accent colour, apart from light or dark (a tester's wish, 2026-10-24: "too monotonous"). VIOLET is the
     * default look; WALLPAPER is Android 12+'s Material You, offered only there. Stored by name. `ui/Accents.kt`.
     */
    enum class Accent { VIOLET, WALLPAPER, BLUE, TEAL, ORANGE, PINK }

    @Volatile var accent: Accent = Accent.VIOLET
        private set

    /**
     * ⚠ Backing state so Compose recomposes; loaded by [load] before first draw.
     * ⚠ Every in-memory value is @Volatile: the API's, the agent's and OpService's threads read them too.
     */
    @Volatile var theme: Theme = Theme.SYSTEM
        private set

    fun load(context: Context) {
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_THEME, null)
        // ⚠ An unknown value falls back rather than throwing. A preferences
        // file survives a downgrade, and a theme nobody recognises must not
        // stop the app opening.
        theme = Theme.entries.firstOrNull { it.name == raw } ?: Theme.SYSTEM
        accent = Accent.entries.firstOrNull {
            it.name == context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_ACCENT, null)
        } ?: Accent.VIOLET
        downloadBase = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_MIRROR, null)?.takeIf { it.isNotBlank() } ?: HF_ORIGIN
        civitaiKey = loadCivitaiKey(context)
        matureContent = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_MATURE, false)
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        defaultView = MainView.entries.firstOrNull { it.name == p.getString(KEY_VIEW, null) }
        // ⭐ Top to bottom for NEW users (the user, 2026-10-13); someone who has already been asked
        // which view to open on keeps the side-to-side pages they had. Written once either way, so
        // the answer never changes under them.
        if (!p.contains(KEY_NODES_VERTICAL)) {
            p.edit().putBoolean(KEY_NODES_VERTICAL, !p.contains(KEY_VIEW)).apply()
        }
        nodesVertical = p.getBoolean(KEY_NODES_VERTICAL, false)
        hideChat = p.getBoolean(KEY_HIDE_CHAT, false)
        moreOptionsOpen = p.getBoolean(KEY_MORE_OPTIONS, false)
        samplingOpen = p.getBoolean(KEY_SAMPLING_OPEN, false)
        postOpen = p.getBoolean(KEY_POST_OPEN, false)
        wanStream = p.getBoolean(KEY_WAN_STREAM, false)
        heatPause = p.getBoolean(KEY_HEAT_PAUSE, false)
        ditGpu = p.getBoolean(KEY_DIT_GPU, false)
        ditTaesd = p.getBoolean(KEY_DIT_TAESD, true)
    }

    /** ⭐ A sampler's "More options" fold open (the user, 2026-10-17: closed by default, remembered). */
    @Volatile var moreOptionsOpen: Boolean = false
        private set

    fun setMoreOptionsOpen(context: Context, value: Boolean) {
        moreOptionsOpen = value
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_MORE_OPTIONS, value).apply()
    }

    /** ⭐ Whether the person accepted [modelId]'s licence ([ModelSpec.licence]); asked before its first download. */
    fun licenceAccepted(context: Context, modelId: String): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_LICENCE + modelId, false)

    fun acceptLicence(context: Context, modelId: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_LICENCE + modelId, true).apply()
    }

    private const val KEY_LICENCE = "licence_accepted_"

    /** ⭐ The image samplers' Scheduler, seed and CFG fold: closed by default, open or closed remembered. */
    @Volatile var samplingOpen: Boolean = false
        private set

    fun setSamplingOpen(context: Context, value: Boolean) {
        samplingOpen = value
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_SAMPLING_OPEN, value).apply()
    }

    /**
     * ⭐ The Output node's Post-processing fold: closed by default, remembered (the user, 2026-10-27). ⚠ Its own key:
     * it shared [moreOptionsOpen], so opening a sampler's More options opened Post-processing too.
     */
    @Volatile var postOpen: Boolean = false
        private set

    fun setPostOpen(context: Context, value: Boolean) {
        postOpen = value
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_POST_OPEN, value).apply()
    }

    // ---- the three views (the user's design, 2026-10-08) ----------------------

    private const val KEY_VIEW = "default_view"
    private const val KEY_NODES_VERTICAL = "nodes_vertical"
    private const val KEY_HIDE_CHAT = "hide_chat"
    private const val KEY_MORE_OPTIONS = "more_options_open"
    private const val KEY_SAMPLING_OPEN = "sampling_open"
    private const val KEY_POST_OPEN = "post_processing_open"

    /**
     * ⭐ What the app opens on. NULL until the person has been asked (first launch: Nodes for
     * beginners, Graph for advanced). ⚠ Agent is never a default — it needs a key first.
     */
    @Volatile var defaultView: MainView? = null
        private set

    fun setDefaultView(context: Context, value: MainView) {
        defaultView = value
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_VIEW, value.name).apply()
    }

    /** ⭐ The Nodes view top to bottom instead of swiped left to right. */
    @Volatile var nodesVertical: Boolean = false
        private set

    fun setNodesVertical(context: Context, value: Boolean) {
        nodesVertical = value
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_NODES_VERTICAL, value).apply()
    }

    /** ⭐ Chat taken off the view button (the user, 2026-10-13): Default and Advanced only. */
    @Volatile var hideChat: Boolean = false
        private set

    fun setHideChat(context: Context, value: Boolean) {
        hideChat = value
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_HIDE_CHAT, value).apply()
    }

    // ---- the LoRA browser (`docs/LORA-BROWSER.md`) ---------------------------

    private const val KEY_CIVITAI_KEY = "civitai_api_key"
    private const val KEY_CIVITAI_SEALED = "civitai_api_key_sealed"
    private const val KEY_MATURE = "civitai_mature"

    /**
     * ⭐ The person's own CivitAI API key — 32 of the 40 most-downloaded LoRAs
     * refuse a download without one (measured 2026-10-07). Blank = none.
     * ⚠ App-private preferences, never logged, never put in a URL: it travels
     * only as an `Authorization` header to CivitAI ([LoraSources]).
     */
    @Volatile var civitaiKey: String = ""
        private set

    fun setCivitaiKey(context: Context, value: String) {
        civitaiKey = value.trim()
        val e = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
        val sealed = civitaiKey.takeIf { it.isNotEmpty() }?.let { runCatching { com.abrah.nightmare.agent.KeyVault.seal(it) }.getOrNull() }
        when {
            civitaiKey.isEmpty() -> e.remove(KEY_CIVITAI_KEY).remove(KEY_CIVITAI_SEALED)
            sealed != null -> e.putString(KEY_CIVITAI_SEALED, sealed).remove(KEY_CIVITAI_KEY)
            // ⚠ No Keystore (a JVM test): kept plain as before rather than lost.
            else -> e.putString(KEY_CIVITAI_KEY, civitaiKey).remove(KEY_CIVITAI_SEALED)
        }
        e.apply()
    }

    /**
     * ⭐ Sealed with the agent's [agent.KeyVault] (AES-GCM, key in the Android Keystore), as the LLM key is: it sat in
     * plain prefs. ⚠ A key saved plain by an older build is read, then sealed in place.
     */
    private fun loadCivitaiKey(context: Context): String {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        p.getString(KEY_CIVITAI_SEALED, null)?.let { s -> runCatching { com.abrah.nightmare.agent.KeyVault.open(s) }.getOrNull()?.let { return it } }
        val plain = p.getString(KEY_CIVITAI_KEY, null).orEmpty()
        if (plain.isNotEmpty()) {
            runCatching { com.abrah.nightmare.agent.KeyVault.seal(plain) }.getOrNull()?.let { sealed ->
                p.edit().putString(KEY_CIVITAI_SEALED, sealed).remove(KEY_CIVITAI_KEY).apply()
            }
        }
        return plain
    }

    /**
     * ⭐ Show mature content — search civitai.red instead of civitai.com. OFF by
     * default (the user's call, 2026-10-07): since April 2026 civitai.com
     * answers with SFW results only, civitai.red with everything.
     */
    @Volatile var matureContent: Boolean = false
        private set

    fun setMatureContent(context: Context, value: Boolean) {
        matureContent = value
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_MATURE, value).apply()
    }

    fun setAccent(context: Context, value: Accent) {
        accent = value
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_ACCENT, value.name).apply()
    }

    fun setTheme(context: Context, value: Theme) {
        theme = value
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_THEME, value.name).apply()
    }

    // ---- download mirror -------------------------------------------------

    /**
     * ⭐⭐ **Where model files are fetched from.** Asked for 2026-09-20:
     * huggingface.co is unreachable from mainland China, and upstream
     * `local-dream` has offered a mirror since long before this fork
     * (`ui/screens/ModelListScreen.kt`, "Download source").
     *
     * ⚠⚠ **Ours is a REWRITE, not a base.** Upstream stores a base URL and
     * builds every model URL from it. This catalogue stores whole URLs across
     * eight constants in five files ([ModelCatalog.SD15_BASE_URL] and friends,
     * [Upscalers], [segment.Segmenter], [npu.VideoInstaller]), so rebasing them
     * all would mean touching every one and keeping them in step forever. ⇒
     * one substitution where a connection is opened ([apply] — in
     * [ModelInstaller.fetch] and in [LoraSources]'s Hugging Face calls), which
     * cannot go out of step with a catalogue entry because it never reads one.
     *
     * ⚠ Only `huggingface.co` is rewritten: a mirror of Hugging Face says nothing
     * about GitHub. The DiT engine has a copy on Hugging Face as well
     * ([DitEngine.HF_URL], since 1.6.159), tried first when a mirror is set and
     * second otherwise ([DitEngine.sources]); its GitHub URL is never rewritten.
     */
    const val HF_ORIGIN = "https://huggingface.co/"

    /** ⚠ hf-mirror.com is the one upstream offers, so it is the one we offer. */
    const val HF_MIRROR = "https://hf-mirror.com/"

    /**
     * The chosen origin, always ending in `/`. [HF_ORIGIN] unless changed.
     * ⚠ A custom value is whatever the user typed, so it is normalised on the
     * way in rather than trusted on the way out.
     */
    @Volatile var downloadBase: String = HF_ORIGIN
        private set

    fun setDownloadBase(context: Context, value: String) {
        downloadBase = normalise(value)
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_MIRROR, downloadBase).apply()
    }

    /**
     * ⭐⭐⭐ The substitution itself — every download goes through this.
     *
     * ⚠⚠ Pure and total: an unknown host, an empty setting or the default
     * all return [url] unchanged, because a download that silently goes
     * nowhere is worse than one that ignores a preference.
     */
    fun apply(url: String): String =
        if (downloadBase == HF_ORIGIN || !url.startsWith(HF_ORIGIN)) url
        else downloadBase + url.removePrefix(HF_ORIGIN)

    /** ⚠ Trailing slash guaranteed; blank falls back to the default. */
    private fun normalise(value: String): String {
        val t = value.trim()
        if (t.isEmpty()) return HF_ORIGIN
        return if (t.endsWith("/")) t else "$t/"
    }

    // ---- low RAM: upstream local-dream's three Settings switches -----------

    /**
     * ⭐⭐ **SDXL low RAM, Anima low RAM, Anima sequential DiT** — upstream's own
     * Settings (`sdxl_lowram`, `anima_lowram`, `anima_seq_dit`, same keys). They
     * were forced per catalogue entry here until 2026-09-28, which put a 16 GB
     * phone in the slow mode with no live preview for no reason (`docs/DEVICES.md` §6c).
     *
     * ⚠ An app SETTING, never a node param: a node param is saved into shared
     * flows and would carry one phone's choice to another ([Residency]'s argument,
     * which is about nodes and does not reach this).
     *
     * ⭐ Unset means the DEFAULT, computed from this phone's RAM rather than stored:
     * the two low-RAM switches are on below [LOWRAM_BELOW_BYTES] — upstream's hint
     * says they are "required on devices with less than 16GB RAM", and upstream
     * defaults them on everywhere. ⚠ Sequential DiT stays OFF by default, as
     * upstream has it: its own hint says to enable it only when low RAM mode alone
     * still cannot run, and it is slower per step.
     *
     * ⚠ Read from storage at every LAUNCH ([lowRamFor]) rather than from a loaded
     * copy, because a headless `OpService` op launches backends without ever
     * running [load].
     */
    const val KEY_SDXL_LOWRAM = "sdxl_lowram"
    const val KEY_ANIMA_LOWRAM = "anima_lowram"
    const val KEY_ANIMA_SEQ_DIT = "anima_seq_dit"

    /**
     * ⭐ Video: stream the model (1.6.193, GitHub #48; OFF by default, ours, not upstream's). Every Wan clip runs
     * under a budget with `all=disk`, as v75 always does ([wanStream], `WanVideoNode.memoryBudget`): a OnePlus
     * 8 Elite (16 GB, so never budgeted) rendered every clip as a colour grid with the DiT resident, and on v75 the
     * resident run was the noisy one and the streamed run clean (`docs/VIDEO-GGML.md` §4). ~30% slower, so never
     * the default: the S25 is clean resident.
     */
    const val KEY_WAN_STREAM = "wan_stream"

    /**
     * ⚠ [KEY_WAN_STREAM] held in memory: a Wan node's launch key reads it with no Context
     * (`WanVideoNode.keyFor`). Loaded by [load], kept in step by [setLowRam].
     */
    @Volatile var wanStream: Boolean = false
        private set

    /** ⭐ Pause a video render while the battery is hot ([BatteryHeat], Saved #7354, the user 2026-10-30): opt-in. */
    const val KEY_HEAT_PAUSE = "heat_pause"

    /** ⚠ [KEY_HEAT_PAUSE] held in memory: a node's run reads it with no Context. Loaded by [load], set by [setLowRam]. */
    @Volatile var heatPause: Boolean = false
        private set

    /** ⭐ The DiT models on the GPU on any phone ([DitGpu], the user 2026-10-31): opt-in; automatic below v73. */
    const val KEY_DIT_GPU = "dit_gpu"

    /** ⚠ [KEY_DIT_GPU] held in memory: [DitGpu.wanted] is read with no Context. Loaded by [load], set by [setLowRam]. */
    @Volatile var ditGpu: Boolean = false
        private set

    /** ⭐ On the GPU route, the tiny decoder instead of the VAE ([DitGpu.Tae], the user 2026-10-31): on by default. */
    const val KEY_DIT_TAESD = "dit_fast_decoder"

    /** ⚠ [KEY_DIT_TAESD] held in memory, as [ditGpu]. */
    @Volatile var ditTaesd: Boolean = true
        private set

    /**
     * ⚠ 13 GiB, between the classes rather than at "16": a 12 GB phone reports
     * ~10.9–11.4 GiB of `totalMem` and a 16 GB one ~14.5–15 GiB, the rest being
     * reserved before Android sees it.
     */
    const val LOWRAM_BELOW_BYTES = 13L shl 30

    /**
     * ⚠ 10 GiB: the 12 GB class's floor, between it (~10.9–11.4 GiB of `totalMem`) and the 8 GB
     * class (~7.3 GiB). What a model needing "a 12 GB phone" is gated on (Krea 2, 2026-10-14).
     */
    const val RAM_12GB_CLASS_BYTES = 10L shl 30

    /** ⚠ The phone class a [minRamBytes] gate names to a person: 16 at [LOWRAM_BELOW_BYTES], else 12. */
    fun ramClassGb(minRamBytes: Long): Int = if (minRamBytes >= LOWRAM_BELOW_BYTES) 16 else 12

    data class LowRam(
        val sdxl: Boolean,
        val anima: Boolean,
        val animaSeqDit: Boolean,
        val wanStream: Boolean = false,
        val heatPause: Boolean = false,
        val ditGpu: Boolean = false,
        val ditTaesd: Boolean = true,
    )

    /** ⚠ Unknown RAM (0) answers ON — the safe side is the slow mode, never the kill. */
    fun lowRamDefault(totalRamBytes: Long): Boolean =
        totalRamBytes <= 0L || totalRamBytes < LOWRAM_BELOW_BYTES

    private fun totalRam(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            ?: return 0L
        return android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
    }

    fun lowRam(context: Context): LowRam {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val def = lowRamDefault(totalRam(context))
        fun read(key: String, fallback: Boolean) =
            if (p.contains(key)) p.getBoolean(key, fallback) else fallback
        return LowRam(
            sdxl = read(KEY_SDXL_LOWRAM, def),
            anima = read(KEY_ANIMA_LOWRAM, def),
            animaSeqDit = read(KEY_ANIMA_SEQ_DIT, false),
            wanStream = read(KEY_WAN_STREAM, false),
            heatPause = read(KEY_HEAT_PAUSE, false),
            ditGpu = read(KEY_DIT_GPU, false),
            ditTaesd = read(KEY_DIT_TAESD, true),
        )
    }

    fun setLowRam(context: Context, key: String, on: Boolean) {
        if (key == KEY_WAN_STREAM) wanStream = on
        if (key == KEY_HEAT_PAUSE) heatPause = on
        if (key == KEY_DIT_GPU) ditGpu = on
        if (key == KEY_DIT_TAESD) ditTaesd = on
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(key, on).apply()
    }

    /**
     * ⭐ Whether a launch of [spec] passes `--lowram`. SDXL and Anima follow the
     * switches; every other family keeps its catalogue entry's own answer
     * ([ModelSpec.lowram] — Z-Image's `te=disk`; Qwen is `all=disk` in the backend
     * whatever this says).
     */
    fun lowRamFor(context: Context, spec: ModelSpec): Boolean = when (spec.family) {
        Family.SDXL, Family.SDXL_SWAP -> lowRam(context).sdxl
        Family.ANIMA -> lowRam(context).anima
        else -> spec.lowram
    }
}

/**
 * ⭐⭐ The six destinations of the shell (the user's design, 2026-10-08 — Violet Studio): the
 * library's three, then the three views of one flow. ⚠ The ORDER is the 3 × 2 tile grid's.
 */
enum class MainView {
    MODELS, FLOWS, RESULTS, AGENT, NODES, GRAPH;

    /** ⭐ A view of the flow (Agent · Nodes · Graph), not a library destination. */
    val isCanvas get() = this == AGENT || this == NODES || this == GRAPH
}
