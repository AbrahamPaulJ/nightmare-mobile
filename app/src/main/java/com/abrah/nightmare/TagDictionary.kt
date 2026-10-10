package com.abrah.nightmare

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * ⭐⭐ **Tag autocomplete** — Local Dream's feature (`TagAutocompleteRepository`, alpha.5), asked
 * for 2026-10-10: suggestions under a prompt box while a tag is typed, from a tag dictionary,
 * plus an optional translation dictionary so a tag can be found by typing in your own language.
 *
 * Both are CSVs, Local Dream's formats:
 *  - the **tag dictionary** — a1111-tagcomplete's `tag,category,count,"alias,alias"` (first
 *    column required, the rest optional). Imported, or downloaded in one tap: [DOWNLOAD_URL] is
 *    tagcomplete's MIT `danbooru.csv` hosted on our Hugging Face so the mirror setting reaches it;
 *  - the **translation dictionary** — `tag,translation` or `tag,…,translation`: the LAST
 *    non-empty column is the translation. Import only (no clean-licence one to host).
 *
 * ⚠ Held in memory once loaded (~140k entries); loaded off the main thread on the first prompt
 * box focus ([ensureLoaded]) and dropped on any import or delete.
 */
object TagDictionary {

    const val DOWNLOAD_URL = "https://huggingface.co/AbrahamPJ/nightmare-tags/resolve/main/danbooru.csv"
    const val DOWNLOAD_BYTES = 3_518_020L
    const val DOWNLOAD_NAME = "danbooru.csv"

    private const val PREFS = "tag_dictionary"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MAIN_NAME = "main_name"
    private const val KEY_TR_NAME = "translation_name"

    data class State(
        val enabled: Boolean,
        val mainName: String?,
        val mainCount: Int,
        val translationName: String?,
        val translationCount: Int,
    )

    data class Suggestion(
        /** The tag as the dictionary spells it (`long_hair`). */
        val tag: String,
        val count: Int,
        val category: Int,
        val translation: String?,
        /** The alias that matched, when the match was not on the tag itself. */
        val alias: String?,
    )

    internal class Entry(
        val tag: String,
        val norm: String,
        val aliases: List<String>,
        val normAliases: List<String>,
        val category: Int,
        val count: Int,
        var translation: String? = null,
    )

    @Volatile private var entries: List<Entry>? = null
    @Volatile private var counts: Pair<Int, Int>? = null

    fun dir(ctx: Context): File = File(ctx.filesDir, "tags").apply { mkdirs() }
    private fun mainFile(ctx: Context) = File(dir(ctx), "main.csv")
    private fun translationFile(ctx: Context) = File(dir(ctx), "translation.csv")

    fun state(ctx: Context): State {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val main = mainFile(ctx).takeIf { it.length() > 0 }
        val tr = translationFile(ctx).takeIf { it.length() > 0 }
        val (mc, tc) = counts ?: (lineCount(main) to lineCount(tr)).also { counts = it }
        return State(
            enabled = p.getBoolean(KEY_ENABLED, true),
            mainName = main?.let { p.getString(KEY_MAIN_NAME, DOWNLOAD_NAME) },
            mainCount = if (main != null) mc else 0,
            translationName = tr?.let { p.getString(KEY_TR_NAME, "translation.csv") },
            translationCount = if (tr != null) tc else 0,
        )
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /** ⭐ True when suggestions can be offered: switched on and a tag dictionary is present. */
    fun active(ctx: Context): Boolean =
        // ⚠ Cheap — a pref and a file length, no line count: called from composition.
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true) &&
            mainFile(ctx).length() > 0

    /** ⚠ Blocking — off the main thread. Copies [uri] in after checking it parses; the entry count. */
    fun import(ctx: Context, uri: Uri, name: String, translation: Boolean): Int {
        val tmp = File(dir(ctx), "import.part")
        ctx.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            ?: throw IllegalStateException("could not read $name")
        val n = tmp.bufferedReader().useLines { lines ->
            if (translation) parseTranslation(lines).size else parseMain(lines).size
        }
        if (n == 0) {
            tmp.delete()
            throw IllegalStateException("$name has no ${if (translation) "translations" else "tags"} in a form this reads")
        }
        val target = if (translation) translationFile(ctx) else mainFile(ctx)
        target.delete()
        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(if (translation) KEY_TR_NAME else KEY_MAIN_NAME, name).apply()
        invalidate()
        return n
    }

    /** ⚠ Blocking — off the main thread. The one-tap tag dictionary ([DOWNLOAD_URL]). */
    fun download(ctx: Context, onProgress: (ModelInstaller.Progress) -> Unit, isCancelled: () -> Boolean) {
        val part = File(dir(ctx), "$DOWNLOAD_NAME.part")
        UpscalerCatalog.download(DOWNLOAD_URL, part, DOWNLOAD_BYTES, onProgress, isCancelled)
        if (part.length() != DOWNLOAD_BYTES) {
            part.delete()
            throw IllegalStateException("tag dictionary: downloaded ${part.length()} bytes, expected $DOWNLOAD_BYTES")
        }
        mainFile(ctx).delete()
        if (!part.renameTo(mainFile(ctx))) { part.copyTo(mainFile(ctx), overwrite = true); part.delete() }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MAIN_NAME, DOWNLOAD_NAME).apply()
        invalidate()
    }

    fun clear(ctx: Context, translation: Boolean) {
        (if (translation) translationFile(ctx) else mainFile(ctx)).delete()
        invalidate()
    }

    private fun invalidate() {
        entries = null
        counts = null
    }

    /** ⚠ Blocking — call off the main thread. Idempotent. */
    fun ensureLoaded(ctx: Context) {
        if (entries != null) return
        val main = mainFile(ctx).takeIf { it.length() > 0 } ?: return
        val list = main.bufferedReader().useLines { parseMain(it) }
        translationFile(ctx).takeIf { it.length() > 0 }?.bufferedReader()?.useLines { lines ->
            val tr = parseTranslation(lines)
            for (e in list) e.translation = tr[e.tag]
        }
        entries = list
    }

    /** ⭐ Up to [limit] suggestions for the word being typed; empty until [ensureLoaded] ran. */
    fun suggest(query: String, limit: Int = 12): List<Suggestion> = suggestFrom(entries.orEmpty(), query, limit)

    // ---- pure parts, tested on the JVM -------------------------------------------------------

    internal fun parseMain(lines: Sequence<String>): List<Entry> {
        val seen = HashSet<String>()
        return lines.mapNotNull { raw ->
            val cells = csvCells(raw.trim())
            val tag = cells.getOrNull(0)?.trim()?.removePrefix("﻿").orEmpty()
            if (tag.isEmpty() || !seen.add(tag)) return@mapNotNull null
            val aliases = cells.getOrNull(3)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            Entry(
                tag = tag,
                norm = normalise(tag),
                aliases = aliases,
                normAliases = aliases.map(::normalise),
                category = cells.getOrNull(1)?.trim()?.toIntOrNull() ?: 0,
                count = cells.getOrNull(2)?.trim()?.toIntOrNull() ?: 0,
            )
        }.toList()
    }

    internal fun parseTranslation(lines: Sequence<String>): Map<String, String> {
        val map = HashMap<String, String>()
        for (raw in lines) {
            val cells = csvCells(raw.trim())
            if (cells.size < 2) continue
            val tag = cells[0].trim().removePrefix("﻿")
            val tr = cells.drop(1).map { it.trim() }.lastOrNull { it.isNotEmpty() && it.toIntOrNull() == null }
            if (tag.isNotEmpty() && tr != null) map[tag] = tr
        }
        return map
    }

    internal fun suggestFrom(list: List<Entry>, query: String, limit: Int): List<Suggestion> {
        val q = query.trim()
        if (q.isEmpty() || list.isEmpty()) return emptyList()
        // ⭐ Typed in another script: search the translations (CJK needs no minimum length).
        if (q.any { it.code > 0x7F }) {
            return list.asSequence()
                .filter { it.translation?.contains(q, ignoreCase = true) == true }
                .sortedByDescending { it.count }
                .take(limit)
                .map { Suggestion(it.tag, it.count, it.category, it.translation, null) }
                .toList()
        }
        val n = normalise(q)
        if (n.length < 2) return emptyList()
        val prefix = ArrayList<Suggestion>()
        val inner = ArrayList<Suggestion>()
        for (e in list) {
            when {
                e.norm.startsWith(n) -> prefix += Suggestion(e.tag, e.count, e.category, e.translation, null)
                else -> {
                    val alias = e.normAliases.indexOfFirst { it.startsWith(n) }
                    if (alias >= 0) prefix += Suggestion(e.tag, e.count, e.category, e.translation, e.aliases[alias])
                    else if (e.norm.contains(n)) inner += Suggestion(e.tag, e.count, e.category, e.translation, null)
                }
            }
        }
        return (prefix.sortedByDescending { it.count } + inner.sortedByDescending { it.count }).take(limit)
    }

    /** `Long Hair` → `long_hair`: the dictionary's spelling, for matching. */
    internal fun normalise(s: String): String = s.trim().lowercase().replace(' ', '_')

    /**
     * ⭐ The tag as it goes into a prompt: spaces for underscores (how A1111 writes them) and
     * `\(` `\)` so `ganyu_(genshin_impact)` stays a name rather than a weight
     * ([PromptSyntax] reads the escapes).
     */
    fun promptText(tag: String): String =
        tag.replace('_', ' ').replace("(", "\\(").replace(")", "\\)")

    /**
     * ⭐ The word being typed at [caret]: from the last separator (`,` a newline or a bracket)
     * to the caret, or null when there is nothing there to complete.
     */
    fun wordAt(text: String, caret: Int): IntRange? {
        if (caret <= 0 || caret > text.length) return null
        var start = caret
        while (start > 0 && text[start - 1] !in ",\n()[]{}<>|") start--
        while (start < caret && text[start].isWhitespace()) start++
        return if (start < caret) start until caret else null
    }

    /** [text] with the word at [range] replaced by [tag] and a separator; the new text and caret. */
    fun complete(text: String, range: IntRange, tag: String): Pair<String, Int> {
        val insert = promptText(tag)
        val after = text.substring(range.last + 1)
        val sep = if (after.trimStart().startsWith(",")) "" else ", "
        val out = text.substring(0, range.first) + insert + sep + after.trimStart()
        return out to (range.first + insert.length + sep.length)
    }

    private fun lineCount(f: File?): Int = f?.bufferedReader()?.useLines { s -> s.count { it.isNotBlank() } } ?: 0

    /** One CSV line's cells: commas outside double quotes, `""` an escaped quote. */
    private fun csvCells(line: String): List<String> {
        if (line.isEmpty()) return emptyList()
        val out = ArrayList<String>(4)
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += cur.toString(); cur.setLength(0) }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString()
        return out
    }
}
