package com.abrah.nightmare

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.abrah.nightmare.npu.AppAssets
import com.abrah.nightmare.npu.ClipTokenizer
import com.abrah.nightmare.npu.NpuFiles
import com.abrah.nightmare.npu.T5Tokenizer
import java.io.File

/**
 * ⭐⭐ How many tokens a prompt spends — the `23/77` beside a prompt box, as
 * `local-dream` draws it (`PromptCountLabel`).
 *
 * ⚠⚠ **Counted on the phone, not by `/tokenize`.** Upstream asks the backend,
 * and our fork has that endpoint too — but it answers only for the checkpoint a
 * RUNNING backend was launched with, and on this canvas a prompt is usually
 * being written while no backend is up, or while one is up for a different
 * family. A count that appears only after the first Run is not a count.
 *
 * ⚠ So this has to agree with `TextEncoder::tokenizeInfo` by construction:
 * - the SAME parse — [PromptSyntax] is `PromptProcessor.hpp` line for line, so
 *   `(word:1.2)` spends the tokens of `word`, not of the brackets;
 * - the SAME vocabulary — every CLIP `tokenizer.json` a checkpoint ships and the
 *   video assets' vocab/merges were checked equal entry for entry, 2026-09-16;
 * - the SAME arithmetic — each segment encoded ALONE (BPE never merges across a
 *   comma), plus BOS and EOS.
 *
 * ⚠ What it does not count, stated rather than hidden: textual-inversion
 * embeddings (this app installs none), and the video path's hidden
 * `prompt_modifier` suffix.
 */
object PromptTokens {

    /**
     * What a prompt is measured against — decided by what it is WIRED INTO.
     *
     * ⚠⚠ SD 1.5 and SDXL are the SAME budget: both CLIP encoders are 77 long and
     * share one vocabulary, and upstream counts SDXL with its first tokenizer.
     * What differs is Anima, which counts T5 against 512 and is [ANIMA] below.
     */
    enum class Budget(val max: Int, val weighted: Boolean) {
        /** SD 1.5 / SDXL: `PromptProcessor` weighting, BOS + EOS. */
        CLIP(77, weighted = true),
        /** Video: the raw text goes to CLIP, no weighting syntax. */
        CLIP_RAW(77, weighted = false),
        /**
         * ⚠ npuforge SDXL: up to THREE 77-token chunks, each with its own BOS
         * and EOS — `processChunkedPrompt` in the backend. Counted the same way:
         * content tokens plus two per chunk used.
         */
        CLIP_LONG(231, weighted = true),
        /** npuforge's SDXL Swap v2 template: SIX chunks (`462_masked_v1`), counted as [CLIP_LONG]. */
        CLIP_LONG_6(462, weighted = true),
        /**
         * ⚠ Anima: T5 against `anima_text_seq_len` (512), plus ONE trailing
         * EOS and no BOS — `TextEncoder::tokenizeInfo`'s Anima branch. ⚠⚠ Never
         * a CLIP number: the budget is T5's, so it needs the T5 vocabulary.
         */
        ANIMA(512, weighted = true),
    }

    data class Count(val used: Int, val max: Int) {
        val over get() = used > max
        val label get() = "$used/$max"
    }

    /**
     * ⚠ Compose state, so the canvas's draw pass and the inspector both redraw
     * the moment the vocabulary finishes loading — it is read on an IO thread
     * after the first frame has already been drawn.
     */
    private var clip by mutableStateOf<ClipTokenizer?>(null)

    /** ⚠ Anima's counter — a separate vocabulary, loaded alongside ([ensureLoaded]). */
    private var t5 by mutableStateOf<T5Tokenizer?>(null)

    @Volatile private var loading = false

    private val cache = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Int>?) = size > 256
    }

    /**
     * ⚠ Idempotent and cheap when done; call from anywhere a count may be shown.
     * The first call parses a ~3.6 MB JSON, so it must be off the main thread.
     */
    fun ensureLoaded(context: Context) {
        if ((clip != null && t5 != null) || loading) return
        loading = true
        try {
            if (clip == null) clip = runCatching { findSource(context) }.getOrNull()
            if (t5 == null) t5 = runCatching { findT5(context) }.getOrNull()
            if (t5 == null && ModelCatalog.all.any { it.family == Family.ANIMA_DIT && it.installed(context) }) {
                t5 = runCatching { fetchT5(context) }.getOrNull()
            }
        } finally {
            loading = false
        }
    }

    /**
     * ⚠ An installed Anima checkpoint's `tokenizer_t5.json`, or the video path's
     * `t5_unigram.tsv` — the same 32100 pieces (checked 2026-09-16).
     */
    private fun findT5(context: Context): T5Tokenizer? {
        t5File(context).takeIf { it.isFile && it.length() == T5_BYTES }
            ?.let { T5Tokenizer.fromTokenizerJson(it) }?.let { return it }
        ModelCatalog.root(context).listFiles()
            ?.map { File(it, "tokenizer_t5.json") }
            ?.filter { it.isFile }
            ?.forEach { f -> T5Tokenizer.fromTokenizerJson(f)?.let { return it } }
        if (NpuFiles.hasAsset(context, "t5_unigram.tsv")) return T5Tokenizer(context)
        return null
    }

    /**
     * ⭐ Anima DiT's counter vocabulary. Its package carries Qwen's tokenizer only (the
     * engine has T5's built in), so a phone with no NPU Anima and no video models has no
     * T5 pieces at all. Fetched ONCE, 1.4 MB, by the shared downloader (mirror, size
     * check). ⚠ Not a catalogue file: adding one would turn every installed Anima DiT
     * into "Repair".
     */
    private const val T5_URL = "https://huggingface.co/google-t5/t5-base/resolve/main/tokenizer.json"
    private const val T5_BYTES = 1_389_353L

    private fun t5File(context: Context) = File(context.filesDir, "tokenizer_t5.json")

    private fun fetchT5(context: Context): T5Tokenizer? {
        val f = t5File(context)
        ModelInstaller.fetch(T5_URL, f, T5_BYTES, "downloading", {})
        return T5Tokenizer.fromTokenizerJson(f)
    }

    private fun findSource(context: Context): ClipTokenizer? {
        // ⚠ Any installed checkpoint's file will do — the vocabularies are one.
        ModelCatalog.root(context).listFiles()
            ?.filter { File(it, "tokenizer.json").isFile }
            // ⚠⚠ An Anima directory's `tokenizer.json` is Qwen's, not CLIP's.
            ?.filterNot { File(it, "tokenizer_t5.json").isFile }
            ?.forEach { dir ->
                ClipTokenizer.fromTokenizerJson(File(dir, "tokenizer.json"))?.let { return it }
            }
        if (NpuFiles.hasAsset(context, "clip_vocab.json") &&
            NpuFiles.hasAsset(context, "clip_merges.txt")) {
            AppAssets.load(context)
            return ClipTokenizer(context)
        }
        return null
    }

    /** Null when the text is blank, there is no budget, or that budget's vocabulary is not loaded. */
    fun count(raw: String, budget: Budget?): Count? {
        // ⭐ `<lora:…>` tags never reach the encoder ([PromptLoras]), so they cost no tokens.
        val text = PromptLoras.extract(raw).text
        if (budget == null || text.isBlank()) return null
        val key = "${budget.name}:$text"
        val used = synchronized(cache) {
            cache[key] ?: run {
                val segments = if (budget.weighted) PromptSyntax.segments(text) else listOf(text)
                // ⚠ Parenthesised: a bare `if {} else {}.also` caches only the else.
                (if (budget == Budget.ANIMA) {
                    val tok = t5 ?: return null
                    segments.sumOf { tok.encodeRaw(it).size } + 1
                } else if (budget == Budget.CLIP_LONG || budget == Budget.CLIP_LONG_6) {
                    val tok = clip ?: return null
                    val content = segments.sumOf { tok.encodeRaw(it).size }
                    content + 2 * ((content + 74) / 75).coerceIn(1, budget.max / 77)
                } else {
                    val tok = clip ?: return null
                    segments.sumOf { tok.encodeRaw(it).size } + 2
                }).also { cache[key] = it }
            }
        }
        return Count(used, budget.max)
    }

    /**
     * ⭐ The budget for prompt node [nodeId]: the TIGHTEST of every sampler it
     * feeds, or the selected model's family when it feeds nothing yet.
     *
     * ⚠ Tightest, because a prompt shared between an SD 1.5 sampler and an Anima
     * one is cut at 77 on the first — the number that matters is the one where
     * the text stops being read.
     */
    fun budgetFor(graph: Graph, nodeId: String): Budget? {
        val consumers = graph.nodes.filter { n -> n.inputs.values.any { it.node == nodeId } }
        // ⚠ Wan reads umT5, whose tokenizer this counter does not have: no number beats a wrong one.
        if (consumers.any { it.type in com.abrah.nightmare.WAN_TYPES }) return null
        val budgets = consumers.mapNotNull { budgetOf(it) }
        if (budgets.isNotEmpty()) return budgets.minBy { it.max }
        // ⚠⚠ The selected model is the fallback for a prompt that feeds NO sampler yet, never for
        // one feeding a sampler that has no count. GitHub #25: a prompt into FLUX.2 Klein 9B showed
        // `67/77` because the Klein node's null budget was dropped above and the top bar's SDXL
        // model answered instead.
        if (consumers.any { it.type in SAMPLER_TYPES }) return null
        return specBudget(SelectedModel.spec)
    }

    private fun budgetOf(consumer: Node): Budget? = when {
        consumer.type == "nd.sample" -> Budget.CLIP_RAW
        consumer.type in IMAGE_SAMPLER_TYPES ->
            ModelCatalog.byId(consumer.params["model"].orEmpty())?.let(::specBudget)
                ?: when {
                    consumer.type.startsWith("anima.") || consumer.type.startsWith("animadit.") -> Budget.ANIMA
                    consumer.type.startsWith("sdxl.") || consumer.type.startsWith("sd15.") -> Budget.CLIP
                    else -> null
                }
        else -> null
    }

    private fun specBudget(spec: ModelSpec): Budget? =
        when {
            spec.promptTokens >= Budget.CLIP_LONG_6.max -> Budget.CLIP_LONG_6
            spec.promptTokens > 77 -> Budget.CLIP_LONG
            else -> familyBudget(spec.family)
        }

    /**
     * ⚠ Null for the DiT families: they read the prompt WHOLE through a Qwen3
     * text encoder (upstream counts it unchunked), and this counter has no Qwen
     * vocabulary. No counter beats a CLIP number that means nothing there.
     */
    private fun familyBudget(f: Family): Budget? = when (f) {
        Family.SD15, Family.SD15_SWAP, Family.SDXL, Family.SDXL_SWAP -> Budget.CLIP
        // ⚠ Anima DiT: the engine reads past 512 (sd.cpp pads, never cuts), but 512 is
        // what Anima was trained on, so the counter warns there (the user's call, 2026-10-13).
        Family.ANIMA, Family.ANIMA_DIT -> Budget.ANIMA
        // ⚠ Wan: umT5, whose tokenizer this counter does not have.
        Family.FLUX2, Family.ZIMAGE, Family.QWEN21, Family.KREA2, Family.IRIS, Family.WAN, Family.MOSS -> null
    }
}

/**
 * ⚠⚠ `PromptProcessor.hpp`'s parse, ported LINE FOR LINE — the text segments
 * the backend hands its tokenizer one at a time.
 *
 * ⚠ Only the segment TEXT is kept: a weight changes an embedding's scale, never
 * how many tokens it spends. Pure, so it is JVM-tested against the C++ by hand.
 */
object PromptSyntax {

    private class Group(val children: MutableList<Any> = mutableListOf())

    fun segments(prompt: String): List<String> {
        val root = Group()
        val stack = ArrayDeque<Group>().apply { addLast(root) }
        val cur = StringBuilder()

        fun emit(text: String) {
            val t = text.trim(' ', '\t', '\r', '\n')
            if (t.isNotEmpty()) stack.last().children.add(t)
        }
        fun flush() {
            if (cur.isNotEmpty()) emit(cur.toString())
            cur.setLength(0)
        }
        fun isWs(c: Char) = c == ' ' || c == '\t' || c == '\r' || c == '\n'

        var i = 0
        val n = prompt.length
        while (i < n) {
            val c = prompt[i]
            if (c == '\\' && i + 1 < n && prompt[i + 1] in "()[]\\,:") {
                cur.append(prompt[i + 1]); i += 2; continue
            }
            if (isWs(c)) {
                // ⚠ A space survives only before an ordinary character — the
                // C++ drops it before a bracket, a comma, a space or a tab.
                if (cur.isNotEmpty() && i + 1 < n && prompt[i + 1] !in "()[], \t") cur.append(' ')
                i++; continue
            }
            when (c) {
                '(', '[' -> {
                    flush()
                    val g = Group()
                    stack.last().children.add(g)
                    stack.addLast(g)
                }
                ')' -> {
                    if (cur.isNotEmpty()) {
                        val colon = cur.lastIndexOf(":")
                        val weighted = colon >= 0 && stack.size > 1 &&
                            leadingFloat(cur.substring(colon + 1).trim(' ', '\t', '\r', '\n'))
                        if (weighted) {
                            emit(cur.substring(0, colon)); cur.setLength(0)
                        } else flush()
                    }
                    if (stack.size > 1) stack.removeLast()
                }
                ']' -> {
                    flush()
                    if (stack.size > 1) stack.removeLast()
                }
                ',' -> {
                    flush()
                    stack.last().children.add(",")
                }
                else -> cur.append(c)
            }
            i++
        }
        flush()

        val out = ArrayList<String>()
        fun walk(g: Group) {
            for (ch in g.children) if (ch is Group) walk(ch) else out.add(ch as String)
        }
        walk(root)
        return out
    }

    /** ⚠ `std::stof` succeeds on a leading number and ignores what follows it. */
    private fun leadingFloat(s: String) =
        Regex("""^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?""").containsMatchIn(s) ||
            Regex("""^[+-]?(inf|nan)""", RegexOption.IGNORE_CASE).containsMatchIn(s)
}
