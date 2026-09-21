package com.abrah.nightmare

import java.util.Locale

/**
 * ⭐⭐ The `loras` param's text, in one place.
 *
 * The param is a string because a workflow is JSON and a node's params are
 * flat text — `style.safetensors@0.8, film.safetensors`. Three surfaces read
 * or write it: the picker sheet ([com.abrah.nightmare.canvas.LoraSheet]), the
 * summary line on the inspector, and [SdSampler.parseLoras] at Run.
 *
 * ⚠⚠⚠ **They call the SAME tokeniser.** Three hand-rolled splits agree right
 * up until one of them learns something the others did not — which is the
 * `clipNodes` failure (`docs/ARCHITECTURE.md` §5.6) with a different noun. So
 * the splitting and the joining live here, and resolving a name to a FILE — the
 * one part that can fail, and the one part the UI must not do — stays in
 * [SdSampler.parseLoras].
 */
object LoraSpec {

    /** A LoRA the graph names, and how hard to apply it. */
    data class Entry(val name: String, val strength: Double)

    /** ⚠ Full strength, which is every trainer's default and the engine's. */
    const val FULL = 1.0

    /**
     * ⭐ The strength range the slider offers.
     *
     * ⚠ It reaches **2.0**, not 1.0: over-driving an adapter is a real
     * technique rather than a mistake, kohya and ComfyUI both assume it, and
     * the engine takes any float. A slider that cannot reach a value the
     * format documents reads as a bug in the slider.
     */
    const val MIN = 0.0
    const val MAX = 2.0
    const val STEP = 0.05

    /**
     * Split the param. Never throws: a name that is not installed is still an
     * entry, because the sheet has to SHOW it in order for anyone to remove it
     * — a workflow that arrived from another phone is exactly that case.
     *
     * ⚠ The LAST `@`, because a filename may contain one and a strength never
     * does: `v1@2.safetensors` is a name.
     * ⚠⚠ Sanitised to a bare filename here, so nothing downstream handles a
     * `../` — a saved workflow is untrusted text.
     */
    fun parse(spec: String?): List<Entry> {
        val text = spec?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        return text.split(',').mapNotNull { piece ->
            val entry = piece.trim()
            if (entry.isEmpty()) return@mapNotNull null
            val at = entry.lastIndexOf('@')
            val strength = if (at > 0) entry.substring(at + 1).trim().toDoubleOrNull() else null
            val raw = if (strength != null) entry.substring(0, at).trim() else entry
            val name = java.io.File(raw).name
            if (name.isEmpty()) null else Entry(name, strength ?: FULL)
        }
    }

    /** The inverse of [parse]. */
    fun format(entries: List<Entry>): String =
        entries.joinToString(", ") { e ->
            if (e.strength == FULL) e.name else "${e.name}@${number(e.strength)}"
        }

    /**
     * ⚠⚠⚠ **[Locale.ROOT], and this is not a nicety.** On a phone set to a
     * comma-decimal locale — German, French, Spanish, most of Europe —
     * `"%.2f"` writes `0,80`, and the comma is this format's SEPARATOR. One
     * slider drag would turn one LoRA into two names, neither of which exists.
     *
     * ⚠ Trailing zeros go: `0.80` is `0.8` and `1.00` never appears at all
     * (full strength is written bare).
     */
    fun number(v: Double): String =
        String.format(Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')

    /** The inspector's one-line summary of what will be applied. */
    fun summary(entries: List<Entry>): String =
        entries.joinToString(", ") { e -> "${e.name.removeSuffix(".safetensors")} ${number(e.strength)}" }
}
