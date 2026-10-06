package com.abrah.nightmare

import org.json.JSONObject
import java.io.File

/**
 * ⭐⭐ A note per LoRA — its trigger words, how to use it, the settings it wants
 * — opened from the ⋮ on its row in the picker (asked for 2026-10-05). The point
 * is to copy what a LoRA needs instead of remembering it.
 *
 * ⚠⚠ Kept BESIDE the LoRAs (`_loras/lora_notes.json`), not in app storage:
 * the files live in the models folder, which survives an uninstall and moves
 * with "Store models in Downloads"; notes kept elsewhere would outlive or lose
 * the adapters they describe. Keyed by FILE NAME — the same key the `loras`
 * param ([LoraSpec]) and the picker use.
 *
 * ⭐ The FIRST non-blank line is the trigger: "Add to prompt" appends exactly
 * that, so a note reads "trigger words, then everything else".
 */
object LoraNotes {
    const val FILE = "lora_notes.json"

    fun read(dir: File): Map<String, String> = runCatching {
        val f = File(dir, FILE)
        if (!f.isFile) return emptyMap()
        val j = JSONObject(f.readText())
        j.keys().asSequence().associateWith { j.optString(it) }.filterValues { it.isNotBlank() }
    }.getOrDefault(emptyMap())

    /**
     * Write [note] for [name]; a blank note removes the entry. Returns the new map.
     *
     * ⚠ Through a temp file and a rename, so a kill mid-write cannot leave half a
     * JSON that [read] would then treat as no notes at all.
     */
    fun write(dir: File, name: String, note: String): Map<String, String> {
        val next = read(dir).toMutableMap()
        if (note.isBlank()) next.remove(name) else next[name] = note.trimEnd()
        dir.mkdirs()
        val tmp = File(dir, "$FILE.tmp")
        tmp.writeText(JSONObject(next as Map<*, *>).toString(2))
        val dest = File(dir, FILE)
        if (!tmp.renameTo(dest)) {
            dest.delete()
            tmp.renameTo(dest)
        }
        return next
    }

    /** The trigger words: the note's first non-blank line, trimmed. */
    fun trigger(note: String): String? =
        note.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }

    /**
     * [prompt] with [add] appended, comma-separated — unchanged when the prompt
     * already contains it (a second tap must not stack the trigger twice).
     */
    fun appendToPrompt(prompt: String, add: String): String {
        val a = add.trim().trim(',').trim()
        if (a.isEmpty() || prompt.contains(a, ignoreCase = true)) return prompt
        val p = prompt.trimEnd().trimEnd(',').trimEnd()
        return if (p.isEmpty()) a else "$p, $a"
    }

    /**
     * ⭐ The `core.prompt` node wired into [samplerId]'s `prompt` port, or null.
     *
     * ⚠ Only a DIRECT prompt node: anything else upstream (a translate node,
     * a plugin) produces its text at Run, so there is no field to append to and
     * the button says so rather than guessing.
     */
    fun promptNodeOf(graph: Graph, samplerId: String): String? {
        val src = graph.byId[samplerId]?.inputs?.get("prompt")?.node ?: return null
        return src.takeIf { graph.byId[it]?.type == PROMPT_TYPE }
    }

    const val PROMPT_TYPE = "core.prompt"
}
