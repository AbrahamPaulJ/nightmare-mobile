package com.abrah.nightmare

/**
 * ⭐⭐ `<lora:name:0.9>` in a prompt — A1111's and CivitAI's way of naming a LoRA (a user's
 * request, decided with the user 2026-10-10: parse, apply, strip).
 *
 * ⚠⚠ Nothing read these before: stable-diffusion.cpp parses them only in its CLI example, not
 * in the library our DiT engine links, and the QNN path never did. So a prompt pasted from
 * CivitAI fed `< lora : name : 0 . 9 >` to the text encoder as words — ~10 tokens of 77, a
 * nudge to the picture, and no LoRA.
 *
 * Now: every tag is REMOVED from the text before it is encoded (positive and negative). A tag
 * whose name matches a file in the LoRA folder is added to the node's [LoraSpec] list at its
 * weight — the tag wins when both name the same file — on a model that takes LoRAs; any other
 * tag is skipped and the run says so ([SdSampler]). `<lyco:…>` is read the same way.
 */
object PromptLoras {

    data class Tag(val name: String, val strength: Double)

    data class Split(val text: String, val tags: List<Tag>)

    /** `<lora:NAME>`, `<lora:NAME:W>`, `<lora:NAME:W:anything>` — the extra fields A1111 allows are ignored. */
    private val TAG = Regex(
        """<\s*(?:lora|lyco)\s*:\s*([^:>]+?)\s*(?::\s*([-+]?(?:\d+\.?\d*|\.\d+))\s*)?(?::[^>]*)?>""",
        RegexOption.IGNORE_CASE,
    )

    /** ⚠ Pure. The text with every tag gone (and the commas it leaves behind tidied), and the tags. */
    fun extract(text: String): Split {
        val tags = TAG.findAll(text).map { m ->
            Tag(m.groupValues[1].trim(), m.groupValues[2].toDoubleOrNull() ?: LoraSpec.FULL)
        }.toList()
        if (tags.isEmpty()) return Split(text, emptyList())
        return Split(tidy(TAG.replace(text, "")), tags)
    }

    /** `a, , b,` → `a, b`: the separators a removed tag leaves behind. */
    private fun tidy(text: String): String =
        text.replace(Regex("""[ \t]*,(?:[ \t]*,)+"""), ",")
            .replace(Regex("""[ \t]{2,}"""), " ")
            .lines().joinToString("\n") { it.trim().trim(',').trim() }
            .trim()

    /**
     * ⚠ Pure. The installed file a tag names, or null: an exact file name first, then the name
     * without its extension — `<lora:foo>` finds `foo.safetensors`. Case-insensitive.
     * ⚠ Reduced to a bare file name, so a tag cannot reach outside the folder.
     */
    fun resolve(name: String, installed: List<String>): String? {
        val n = java.io.File(name).name
        if (n.isEmpty()) return null
        return installed.firstOrNull { it.equals(n, ignoreCase = true) }
            ?: installed.firstOrNull { it.substringBeforeLast('.').equals(n, ignoreCase = true) }
    }

    /** ⚠ Pure. [spec] with [add] merged in; an entry for the same file is replaced by the tag's. */
    fun merge(spec: String?, add: List<LoraSpec.Entry>): String {
        val kept = LoraSpec.parse(spec).filter { e -> add.none { it.name.equals(e.name, ignoreCase = true) } }
        return LoraSpec.format(kept + add)
    }
    // ---- autocomplete (GitHub #19: "type <lora: and pick from your LoRAs") ----------------

    /** ⭐ What a prompt's LoRA chips prefer: the LoRAs its samplers already use, and their bases. */
    data class Hint(val active: Set<String>, val bases: Set<LoraLibrary.Base>)

    /** ⚠ Pure. The [Hint] for the prompt node [nodeId]: every sampler it is wired into. */
    fun hintFor(graph: Graph, nodeId: String): Hint {
        val consumers = graph.nodes.filter { n -> n.inputs.values.any { it.node == nodeId } }
        val active = consumers.flatMap { LoraSpec.parse(it.params[SdSampler.LORAS]).map { e -> e.name } }.toSet()
        val bases = consumers.mapNotNull { c ->
            LoraLibrary.Base.of(ModelCatalog.byId(c.params["model"].orEmpty())?.family)
        }.toSet()
        return Hint(active, bases)
    }

    /** An open tag at the caret: where its `<` is, and the name typed so far. */
    data class Open(val start: Int, val partial: String)

    private val OPEN = Regex("""<\s*(?:lora|lyco)\s*:([^:<>
]*)$""", RegexOption.IGNORE_CASE)

    /** ⚠ Pure. The `<lora:…` the caret is in the middle of typing, or null. */
    fun openTagAt(text: String, caret: Int): Open? {
        if (caret < 0 || caret > text.length) return null
        val lt = text.lastIndexOf('<', (caret - 1).coerceAtLeast(0))
        if (lt < 0) return null
        val m = OPEN.find(text.substring(lt, caret)) ?: return null
        return Open(lt, m.groupValues[1].trim())
    }

    /**
     * ⚠ Pure. [files] that match [partial] (anywhere in the name, any case), the ones in
     * [hint]`.active` first, then the ones of a base the samplers take, then by name; at most [limit].
     */
    fun rank(files: List<Pair<String, LoraLibrary.Base>>, partial: String, hint: Hint?, limit: Int = 12): List<String> =
        files.filter { (name, _) -> partial.isEmpty() || name.contains(partial, ignoreCase = true) }
            .sortedWith(
                compareBy<Pair<String, LoraLibrary.Base>>(
                    { (name, _) -> if (hint?.active?.any { it.equals(name, ignoreCase = true) } == true) 0 else 1 },
                    { (_, base) -> if (hint != null && base in hint.bases) 0 else 1 },
                    { (name, _) -> name.lowercase() },
                )
            )
            .take(limit).map { it.first }

    /**
     * ⚠ Pure. [text] with the open tag at [open] replaced by `<lora:NAME:1.0>` (NAME without its
     * extension), and the strength's range so the caller can SELECT it — the next keystroke types
     * over `1.0`. A tag already closed after the caret (`<lora:fo|o:0.5>`) is replaced whole.
     */
    fun complete(text: String, open: Open, caret: Int, file: String): Triple<String, Int, Int> {
        val rest = text.substring(caret)
        val close = Regex("""^[^<>
]*>""").find(rest)?.value?.length ?: 0
        val name = file.substringBeforeLast('.')
        val strength = "1.0"
        val tag = "<lora:$name:$strength>"
        val out = text.substring(0, open.start) + tag + text.substring(caret + close)
        val from = open.start + tag.length - 1 - strength.length
        return Triple(out, from, from + strength.length)
    }
}
