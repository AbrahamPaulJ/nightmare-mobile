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
}
