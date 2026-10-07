package com.abrah.nightmare

import java.util.Locale

/**
 * ⭐⭐ The prompt toolbar's edits — the tag under the caret made heavier or lighter,
 * deleted, or followed by a new one (`docs/CANVAS.md`, the prompt box). Asked for
 * by a user, 2026-10-08: *"weight/tag editor like in LD"*.
 *
 * ⚠ PORTED from local-dream's `TagAutocompleteRepository` (xororz, CC BY-NC 4.0 —
 * this project's own licence, inherited), so a prompt edited here behaves as it
 * does there: a tag is the comma-separated piece under the caret; a weight is
 * `(tag:1.2)` and steps by 0.1; A1111's `(tag)` = 1.1 and `[tag]` = 0.9 are read;
 * landing on 1.0 drops the wrapper. Pure, so the tests drive it.
 *
 * Each edit returns (new text, new caret), or null when there is no tag to act on.
 */
object PromptTags {

    private data class Segment(val start: Int, val end: Int, val content: String)

    private fun segmentBetween(text: String, rawStart: Int, rawEnd: Int): Segment? {
        var start = rawStart
        while (start < rawEnd && text[start].isWhitespace()) start++
        var end = rawEnd
        while (end > start && text[end - 1].isWhitespace()) end--
        if (start >= end) return null
        return Segment(start, end, text.substring(start, end))
    }

    private fun activeSegment(text: String, caret: Int): Segment? {
        if (caret < 0 || caret > text.length) return null
        val start = if (caret == 0) 0 else text.lastIndexOf(',', startIndex = caret - 1).let { if (it == -1) 0 else it + 1 }
        val end = text.indexOf(',', startIndex = caret).let { if (it == -1) text.length else it }
        return segmentBetween(text, start, end)
    }

    /**
     * The tag under the caret, or — when the caret sits in an empty slot right
     * after a ", " — the tag before it, so a weight lands on the tag just typed.
     */
    private fun resolveSegment(text: String, caret: Int): Segment? {
        activeSegment(text, caret)?.let { return it }
        if (caret <= 0 || caret > text.length) return null
        val prevComma = text.lastIndexOf(',', startIndex = caret - 1)
        if (prevComma < 0) return null
        val start = text.lastIndexOf(',', startIndex = prevComma - 1).let { if (it == -1) 0 else it + 1 }
        return segmentBetween(text, start, prevComma)
    }

    private val explicitWeight = Regex("""^\((.*):(-?\d+(?:\.\d+)?)\)$""")

    /** `s` wholly inside ONE open/close pair, a `\(` escape not counting. */
    private fun isBalancedWrap(s: String, open: Char, close: Char): Boolean {
        if (s.length < 2 || s.first() != open || s.last() != close) return false
        var depth = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' -> i++
                c == open -> depth++
                c == close -> {
                    depth--
                    if (depth == 0 && i != s.length - 1) return false
                }
            }
            i++
        }
        return depth == 0
    }

    private fun tenth(v: Double) = Math.round(v * 10.0) / 10.0

    /** (bare tag, weight): `(t:1.3)` → 1.3, `((t))` → 1.2, `[t]` → 0.9, `t` → 1.0. */
    internal fun parse(content: String): Pair<String, Double> {
        var inner = content.trim()
        var delta = 0.0
        while (true) {
            explicitWeight.matchEntire(inner)?.let { m ->
                val w = m.groupValues[2].toDoubleOrNull()
                if (m.groupValues[1].isNotEmpty() && w != null) return m.groupValues[1] to tenth(w + delta)
            }
            when {
                isBalancedWrap(inner, '(', ')') -> delta += 0.1
                isBalancedWrap(inner, '[', ']') -> delta -= 0.1
                else -> return inner to tenth(1.0 + delta)
            }
            inner = inner.substring(1, inner.length - 1).trim()
        }
    }

    /** ↑ / ↓: the tag's weight by [delta]; one decimal, a '.' whatever the locale. */
    fun adjustWeight(text: String, caret: Int, delta: Double): Pair<String, Int>? {
        val seg = resolveSegment(text, caret) ?: return null
        val (inner, weight) = parse(seg.content)
        val w = tenth(weight + delta)
        val replacement = if (w == 1.0) inner else "($inner:${String.format(Locale.US, "%.1f", w)})"
        val updated = text.substring(0, seg.start) + replacement + text.substring(seg.end)
        val newCaret = if (caret in seg.start..seg.end) seg.start + replacement.length
        else (caret + replacement.length - (seg.end - seg.start)).coerceIn(0, updated.length)
        return updated to newCaret
    }

    /**
     * ✕: the tag under the caret and one comma beside it. ⚠ In an empty slot it
     * closes the SLOT, never reaching back to delete the intact tag before it.
     */
    fun deleteTag(text: String, caret: Int): Pair<String, Int>? {
        val seg = activeSegment(text, caret)
        if (seg != null) {
            val commaBefore = if (seg.start == 0) -1 else text.lastIndexOf(',', startIndex = seg.start - 1)
            val commaAfter = text.indexOf(',', startIndex = seg.end)
            return when {
                commaBefore >= 0 -> {
                    val prefix = text.substring(0, commaBefore)
                    (prefix + if (commaAfter >= 0) text.substring(commaAfter) else "") to prefix.length
                }
                commaAfter >= 0 -> text.substring(commaAfter + 1).trimStart() to 0
                else -> "" to 0
            }
        }
        if (caret < 0 || caret > text.length) return null
        val prevComma = if (caret == 0) -1 else text.lastIndexOf(',', startIndex = caret - 1)
        val nextComma = text.indexOf(',', startIndex = caret)
        return when {
            prevComma >= 0 -> text.removeRange(prevComma, if (nextComma >= 0) nextComma else text.length) to prevComma
            nextComma >= 0 -> text.substring(nextComma + 1).trimStart() to 0
            else -> null
        }
    }

    /** +: ", " after the tag under the caret, the caret in the new empty slot. An empty prompt is just focused. */
    fun addTagAfter(text: String, caret: Int): Pair<String, Int>? {
        val seg = activeSegment(text, caret) ?: return if (text.isBlank()) "" to 0 else null
        val updated = text.substring(0, seg.end) + ", " + text.substring(seg.end)
        return updated to seg.end + 2
    }

    /**
     * ⭐ Undo / redo for one prompt box. Typing within [COALESCE_MS] of the last
     * change is ONE step, so undo takes back a word, not a letter; a toolbar edit
     * is always its own step.
     */
    class History(private val limit: Int = 100) {
        private val back = ArrayDeque<String>()
        private val ahead = ArrayDeque<String>()
        private var lastAt = 0L

        val canUndo get() = back.isNotEmpty()
        val canRedo get() = ahead.isNotEmpty()

        /** [before] is the text being replaced. */
        fun record(before: String, now: Long, coalesce: Boolean) {
            if (!(coalesce && now - lastAt < COALESCE_MS && back.isNotEmpty())) {
                back.addLast(before)
                if (back.size > limit) back.removeFirst()
            }
            lastAt = if (coalesce) now else 0L
            ahead.clear()
        }

        fun undo(current: String): String? = back.removeLastOrNull()?.also { ahead.addLast(current); lastAt = 0L }

        fun redo(current: String): String? = ahead.removeLastOrNull()?.also { back.addLast(current); lastAt = 0L }

        companion object {
            const val COALESCE_MS = 800L
        }
    }
}
