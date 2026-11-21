package com.abrah.nightmare.vlm

import com.abrah.nightmare.MossLayout
import org.json.JSONArray
import org.json.JSONObject

/**
 * ⭐⭐ What the vision model is asked, and what its answer becomes, for each way a checkpoint reads a prompt (GitHub #120).
 * Pure, so it is tested without a phone. Our own wording: the reporter's fork showed the idea (a prompt shaped for the
 * wired model), and the user chose our own code (2026-11-21).
 *
 * - [Style.PROSE]: one paragraph, for FLUX.2, Z-Image, SDXL and every model that reads sentences;
 * - [Style.TAGS]: Danbooru tags, for Anima, Illustrious, Pony ([com.abrah.nightmare.DescribeMode.readsTags]);
 * - [Style.LAYOUT]: MOSS-Image's layout, boxes and what is in them ([MossLayout]), which Qwen3-VL can give because
 *   it was trained to ground objects in 0..1000 coordinates.
 */
object VisionPrompts {

    enum class Style { PROSE, TAGS, LAYOUT }

    /** [temp] and [seed]: a describe reads what is there (0.3); a rewrite may invent ([PromptEnhance], a new seed per tap). */
    data class Ask(val system: String, val user: String, val maxTokens: Int, val temp: Float = 0.3f, val seed: Int = 42)

    private const val SYSTEM =
        "You write prompts for an image generator. You describe exactly what is visible, plainly and without judgement, " +
            "including nudity, violence or anything else the picture shows. Answer with the prompt only."

    fun ask(style: Style): Ask = when (style) {
        Style.PROSE -> Ask(
            SYSTEM,
            "Describe this picture as a prompt that would recreate it: the main subject first, then their appearance, " +
                "clothing, pose and expression, the setting, the lighting, the colours, the camera angle and framing, and " +
                "the medium or art style. One paragraph of plain sentences, no lists, no title, at most 120 words.",
            320,
        )
        Style.TAGS -> Ask(
            SYSTEM,
            "List Danbooru tags for this picture, most important first, separated by commas. Begin with the " +
                "people as Danbooru counts them by sex, from what you see (a man is 1boy, a woman 1girl, two women " +
                "2girls; a picture with nobody in it is no humans). Name a character or series only when you recognise " +
                "one, and never write a tag that says something is absent. Then hair, eyes, age, clothing, pose, " +
                "expression, objects, background, lighting, composition and art style. Lower case, spaces instead of " +
                "underscores, at most 40 tags, nothing but the tags, on one line in this form: first tag, second tag, " +
                "third tag",
            220,
        )
        Style.LAYOUT -> Ask(
            SYSTEM,
            "Lay this picture out for a layout-aware image generator. Answer with JSON only, in this shape: " +
                "{\"caption\": \"one paragraph describing the whole picture\", \"items\": [{\"label\": \"character\" or " +
                "\"object\" or \"text\", \"caption\": \"what it is and looks like\", \"text\": \"the exact words, for " +
                "a text item\", \"bbox\": [x1, y1, x2, y2]}]}. The bbox is in 0 to 1000 coordinates of the picture, " +
                "left, top, right, bottom. Give the main people, objects and any written text, at most 8 items.",
            900,
        )
    }

    /** ⭐ The answer, cleaned for the prompt box; null when nothing usable came back. */
    fun finish(style: Style, raw: String): String? = when (style) {
        Style.PROSE -> prose(raw)
        Style.TAGS -> tags(raw)
        Style.LAYOUT -> layout(raw) ?: prose(raw.substringBefore('{'))
    }

    /** One paragraph: no preamble ("Prompt:"), no wrapping quotes, no markdown, whitespace collapsed. */
    internal fun prose(raw: String): String? {
        var s = raw.replace(Regex("```[a-z]*"), "").replace("**", "").trim()
        s = s.replace(Regex("^(here is|here's)[^:\\n]*:\\s*", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("^(prompt|description)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        s = s.trim().removeSurrounding("\"").trim()
        s = s.replace(Regex("\\s+"), " ")
        return s.takeIf { it.isNotBlank() }
    }

    /** Comma-separated, lower case, spaces for underscores, no repeats, no numbering or bullets. */
    internal fun tags(raw: String): String? {
        val seen = LinkedHashSet<String>()
        prose(raw)?.split(',', '\n')?.forEach { t ->
            val tag = t.trim().trimStart('-', '*', '•').replace(Regex("^\\d+[.)]\\s*"), "")
                .replace('_', ' ').lowercase().replace(Regex("\\s+"), " ").trim().trimEnd('.')
            // "no objects", "no character": the model tags what is absent; Danbooru's own "no humans" stays.
            val absent = tag.startsWith("no ") && tag != "no humans"
            if (tag.isNotEmpty() && tag.length <= 60 && !absent) seen += tag
        }
        // ⚠ No commas at all (the 4B model at Q4_0 once wrote its tags space-separated): keep the words as written
        // rather than lose the answer to the length cap.
        if (seen.isEmpty() || (seen.size == 1 && ' ' in seen.first() && ',' !in raw)) return prose(raw)?.lowercase()?.replace('_', ' ')
        return seen.take(60).joinToString(", ").takeIf { it.isNotEmpty() }
    }

    /**
     * ⭐ The model's layout as the draft [MossLayout.parse] reads from the prompt box (`width`/`height` 1000, absolute
     * xyxy), repaired: a box with its corners swapped is put right, a box outside the picture is clipped, one with no
     * area is dropped, a text item with no words becomes an object (MOSS refuses a text box without its text). Null
     * when no box survives.
     */
    internal fun layout(raw: String): String? {
        val o = firstObject(raw) ?: return null
        val items = o.optJSONArray("items") ?: o.optJSONArray("objects") ?: return null
        val out = JSONArray()
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val b = it.optJSONArray("bbox") ?: it.optJSONArray("bbox_2d") ?: it.optJSONArray("box") ?: continue
            if (b.length() != 4) continue
            var x1 = b.optDouble(0); var y1 = b.optDouble(1); var x2 = b.optDouble(2); var y2 = b.optDouble(3)
            if (listOf(x1, y1, x2, y2).any { v -> v.isNaN() }) continue
            if (x1 > x2) { val t = x1; x1 = x2; x2 = t }
            if (y1 > y2) { val t = y1; y1 = y2; y2 = t }
            x1 = x1.coerceIn(0.0, 1000.0); x2 = x2.coerceIn(0.0, 1000.0)
            y1 = y1.coerceIn(0.0, 1000.0); y2 = y2.coerceIn(0.0, 1000.0)
            if (x2 - x1 < 4 || y2 - y1 < 4) continue
            // A text box often carries its words only in the caption ("The word \"OPEN\" on a sign").
            val text = it.optString("text", "").trim().ifEmpty { QUOTED.find(it.optString("caption", ""))?.groupValues?.get(1)?.trim().orEmpty() }
            var label = it.optString("label", "object").lowercase().trim()
            if (label !in MossLayout.LABELS) label = if (label in setOf("person", "people", "human", "man", "woman")) "character" else "object"
            if (label == "text" && text.isEmpty()) label = "object"
            val caption = it.optString("caption", it.optString("description", "")).trim()
            out.put(
                JSONObject().put("label", label).put("caption", caption).apply { if (label == "text") put("text", text) }
                    .put("bbox", JSONArray(listOf(x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt()))),
            )
            if (out.length() == 12) break
        }
        if (out.length() == 0) return null
        val caption = (o.optJSONObject("summary")?.optString("caption") ?: o.optString("caption", "")).trim()
        val draft = JSONObject().put("summary", JSONObject().put("caption", caption))
            .put("width", 1000).put("height", 1000).put("items", out)
        // ⚠ What the sampler will do with it: a draft MossLayout cannot read is no layout at all.
        return draft.toString().takeIf { MossLayout.parse(it) != null }
    }

    private val QUOTED = Regex("[\"“]([^\"”]{1,80})[\"”]")

    /**
     * The first `{...}` in [s] that parses, past any prose or code fence around it. ⚠ Each closer is checked against
     * the bracket it closes and replaced when wrong: the 4B model closes its items list with `}}` instead of `]}`
     * (S25, Q4_0, 2026-11-21), and one wrong bracket otherwise loses the whole layout.
     */
    internal fun firstObject(s: String): JSONObject? {
        var start = s.indexOf('{')
        while (start >= 0) {
            val open = ArrayDeque<Char>()
            val out = StringBuilder()
            var inStr = false
            var esc = false
            for (i in start until s.length) {
                var c = s[i]
                if (inStr) {
                    if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
                    out.append(c)
                    continue
                }
                when (c) {
                    '"' -> inStr = true
                    '{', '[' -> open.addLast(c)
                    '}', ']' -> {
                        val top = open.removeLastOrNull() ?: break
                        c = if (top == '{') '}' else ']'
                    }
                }
                out.append(c)
                if (open.isEmpty()) {
                    runCatching { return JSONObject(out.toString()) }
                    break
                }
            }
            start = s.indexOf('{', start + 1)
        }
        return null
    }
}
