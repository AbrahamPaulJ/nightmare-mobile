package com.abrah.nightmare

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * ⭐⭐ **MOSS-Image's layout prompt**: boxes on the picture, each with what goes there (GitHub #77's reporter, who ported
 * it first; the source is LinXce/Comfyui-Moss-LayoutPrompt, Apache-2.0, `NOTICE`). MOSS-Image was trained on a JSON
 * prompt that names each element and its box, so nothing in the engine changes: the prompt TEXT does.
 *
 * Two ways in, one compiler ([compile]):
 * - the sampler's **Layout** tab ([PARAM] on the node: boxes as FRACTIONS of the picture, so a size change keeps them
 *   in place), with the wired prompt as the summary caption;
 * - a draft pasted into the prompt box, as the ComfyUI node writes it (`abs` / `xyxy` with its own width and height),
 *   an already compiled prompt (`abs_centralized` / `yxyx`), or the older scene schema (`elements`) ([parse]).
 *
 * ⚠⚠ The output is the plugin's, byte for byte: `json.dumps(ensure_ascii=False, separators=(",", ":"))` key order and
 * escaping ([dumps]), and its rounding (`floor(round(y1, 9)) - h // 2`…, [centered]). `org.json` writes `/` as `\/`,
 * and the model reads the prompt as text, so it is not used for the output.
 */
object MossLayout {

    /** ⭐ The sampler param holding the layout ([write]); absent or empty = no layout. */
    const val PARAM = "moss_layout"

    val LABELS = listOf("object", "character", "text")

    /** ⭐ One element: [x1]..[y2] are fractions of the picture (0 at the left and top). */
    data class Box(
        val label: String = "object",
        val caption: String = "",
        val text: String = "",
        val x1: Double, val y1: Double, val x2: Double, val y2: Double,
    ) {
        val needsText: Boolean get() = label == "text" && text.isBlank()
    }

    data class Layout(val boxes: List<Box> = emptyList(), val meta: List<String> = emptyList())

    /** ⭐ The node's [PARAM]; an empty layout when blank or unreadable (a broken param must not break the node). */
    fun read(json: String?): Layout = runCatching {
        if (json.isNullOrBlank()) return Layout()
        val o = JSONObject(json)
        val items = o.optJSONArray("items") ?: JSONArray()
        Layout(
            boxes = (0 until items.length()).mapNotNull { i ->
                val it = items.optJSONObject(i) ?: return@mapNotNull null
                val b = it.optJSONArray("box") ?: return@mapNotNull null
                if (b.length() != 4) return@mapNotNull null
                Box(
                    label = it.optString("label", "object").takeIf { l -> l in LABELS } ?: "object",
                    caption = it.optString("caption", ""),
                    text = it.optString("text", ""),
                    x1 = b.getDouble(0), y1 = b.getDouble(1), x2 = b.getDouble(2), y2 = b.getDouble(3),
                )
            },
            meta = strings(o.optJSONArray("meta")),
        )
    }.getOrDefault(Layout())

    fun write(l: Layout): String = if (l.boxes.isEmpty() && l.meta.isEmpty()) "" else JSONObject().apply {
        put("items", JSONArray(l.boxes.map { b ->
            JSONObject().put("label", b.label).put("caption", b.caption).apply { if (b.label == "text") put("text", b.text) }
                .put("box", JSONArray(listOf(b.x1, b.y1, b.x2, b.y2)))
        }))
        if (l.meta.isNotEmpty()) put("meta", JSONArray(l.meta))
    }.toString()

    /**
     * ⭐ The prompt MOSS reads for [l] on a [w]x[h] picture, the summary caption being [caption] (the wired prompt), or
     * null with no boxes. ⚠ Throws [IllegalArgumentException] naming the box when a text box has no text (the plugin's
     * rule: the model needs the exact words) or a box is too small to keep a pixel.
     */
    fun compile(l: Layout, caption: String, w: Int, h: Int): String? {
        if (l.boxes.isEmpty()) return null
        val summary = linkedMapOf<String, Any>("caption" to caption.trim())
        if (l.meta.isNotEmpty()) summary["meta"] = l.meta
        val items = l.boxes.mapIndexed { i, b ->
            require(!b.needsText) { "layout box ${i + 1} is a text box with no text" }
            val box = centered(b.x1 * w, b.y1 * h, b.x2 * w, b.y2 * h, w, h)
                ?: throw IllegalArgumentException("layout box ${i + 1} is too small")
            linkedMapOf<String, Any>("label" to b.label, "caption" to b.caption).apply {
                if (b.label == "text") put("text", b.text)
                put("bbox", box)
            }
        }
        return dumps(
            linkedMapOf(
                "summary" to summary,
                "items" to items,
                "bbox_coord_space" to "abs_centralized",
                "bbox_format" to "yxyx",
            ),
        )
    }

    /**
     * ⭐ What the sampler sends for a MOSS render ([com.abrah.nightmare.SdSampler]): the node's layout with [prompt] as
     * its caption; else a layout pasted into the prompt; else null (an ordinary prompt, unchanged).
     */
    fun forRender(param: String?, prompt: String, w: Int, h: Int): String? {
        read(param).takeIf { it.boxes.isNotEmpty() }?.let { return compile(it, prompt, w, h) }
        val pasted = parse(prompt) ?: return null
        return compile(pasted.first, pasted.second, w, h)
    }

    /**
     * ⭐ A layout written as text (the ComfyUI node's draft, a compiled prompt, or the scene schema), wrapped in prose or
     * not, as boxes and its summary caption; null for an ordinary prompt. Its own width and height place the boxes
     * (the plugin's canvas); a compiled prompt has none, so [w] and [h] do.
     */
    fun parse(text: String, w: Int = 1024, h: Int = 1024): Pair<Layout, String>? = runCatching {
        if ('{' !in text) return null
        val d = jsonObjectIn(text) ?: return null
        if (d.has("elements")) return fromScene(d, w, h)
        val items = d.optJSONArray("items") ?: return null
        val cw = d.optInt("width", w).takeIf { it > 0 } ?: w
        val ch = d.optInt("height", h).takeIf { it > 0 } ?: h
        val compiled = d.optString("bbox_coord_space") == "abs_centralized"
        val summary = d.optJSONObject("summary")
        val boxes = (0 until items.length()).mapNotNull { i ->
            val it = items.optJSONObject(i) ?: return@mapNotNull null
            val b = it.optJSONArray("bbox")?.takeIf { a -> a.length() == 4 } ?: return@mapNotNull null
            // ⚠ Compiled: yxyx from the centre, so y comes first and the origin is half the canvas.
            val (x1, y1, x2, y2) = if (compiled) {
                listOf(b.getDouble(1) + cw / 2, b.getDouble(0) + ch / 2, b.getDouble(3) + cw / 2, b.getDouble(2) + ch / 2)
            } else listOf(b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3))
            Box(
                label = it.optString("label", "object").takeIf { l -> l in LABELS } ?: "object",
                caption = it.optString("caption", ""),
                text = it.optString("text", ""),
                x1 = (x1 / cw).coerceIn(0.0, 1.0), y1 = (y1 / ch).coerceIn(0.0, 1.0),
                x2 = (x2 / cw).coerceIn(0.0, 1.0), y2 = (y2 / ch).coerceIn(0.0, 1.0),
            )
        }
        if (boxes.isEmpty()) return null
        Layout(boxes, strings(summary?.optJSONArray("meta"))) to summary?.optString("caption", "").orEmpty()
    }.getOrNull()

    /**
     * ⚠ Pure: the plugin's `_centered`, an absolute xyxy box to the centred yxyx MOSS reads:
     * `[floor(round(y1, 9)) - h // 2, floor(round(x1, 9)) - w // 2, ceil(round(y2, 9)) - h // 2, ceil(round(x2, 9)) - w // 2]`.
     * Null outside the canvas or with no area left.
     */
    internal fun centered(x1: Double, y1: Double, x2: Double, y2: Double, w: Int, h: Int): List<Long>? {
        if (!(x1 >= 0 && x1 < x2 && x2 <= w + 1e-9 && y1 >= 0 && y1 < y2 && y2 <= h + 1e-9)) return null
        val r = listOf(
            floor(r9(y1)).toLong() - h / 2,
            floor(r9(x1)).toLong() - w / 2,
            ceil(r9(y2)).toLong() - h / 2,
            ceil(r9(x2)).toLong() - w / 2,
        )
        return r.takeIf { it[0] < it[2] && it[1] < it[3] }
    }

    private fun r9(v: Double): Double = (v * 1e9).roundToLong() / 1e9

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }

    /** ⚠ The first `{…}` that parses and looks like a layout (the plugin's `_json_objects`), else the first that parses. */
    private fun jsonObjectIn(text: String): JSONObject? {
        runCatching { return JSONObject(text.trim()) }
        var first: JSONObject? = null
        var s = text.indexOf('{')
        while (s >= 0) {
            var depth = 0
            var inStr = false
            var esc = false
            var i = s
            while (i < text.length) {
                val c = text[i]
                if (inStr) {
                    if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
                } else if (c == '"') inStr = true
                else if (c == '{') depth++
                else if (c == '}') {
                    depth--
                    if (depth == 0) {
                        val o = runCatching { JSONObject(text.substring(s, i + 1)) }.getOrNull()
                        if (o != null && (o.has("summary") || o.has("items") || o.has("elements"))) return o
                        if (o != null && first == null) first = o
                        break
                    }
                }
                i++
            }
            s = text.indexOf('{', s + 1)
        }
        return first
    }

    /** ⚠ The older MOSS-Image scene schema (`scene_type`, `gen_brief`, `elements` boxed in centred yxyx unless `abs`). */
    private fun fromScene(v: JSONObject, w: Int, h: Int): Pair<Layout, String>? {
        val els = v.optJSONArray("elements") ?: return null
        val cw = v.optInt("width", w).takeIf { it > 0 } ?: w
        val ch = v.optInt("height", h).takeIf { it > 0 } ?: h
        val centredSpace = v.optString("bbox_coord_space", "") != "abs"
        val raw = (0 until els.length()).mapNotNull { i ->
            val e = els.optJSONObject(i) ?: return@mapNotNull null
            val b = e.optJSONArray("bbox")?.takeIf { it.length() == 4 } ?: return@mapNotNull null
            val type = e.optString("type", "").trim().lowercase()
            var label = when (type) { "person", "character" -> "character"; "text" -> "text"; else -> "object" }
            val caption = listOf("identity", "shape", "note").map { e.optString(it, "").trim() }.filter { it.isNotEmpty() }.joinToString(" ")
            var text = ""
            if (label == "text") {
                text = e.optString("text", "").trim().ifEmpty { e.optString("identity", "").trim() }
                if (text.isEmpty()) label = "object"
            }
            val box = if (centredSpace) {
                doubleArrayOf(b.getDouble(1) + cw / 2.0, b.getDouble(0) + ch / 2.0, b.getDouble(3) + cw / 2.0, b.getDouble(2) + ch / 2.0)
            } else doubleArrayOf(b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3))
            Triple(label, caption to text, box)
        }
        if (raw.isEmpty()) return null
        // ⭐ The plugin's `_fit_items`: scaled about the centre until every box is inside the canvas.
        var hw = 0.0
        var hh = 0.0
        for ((_, _, b) in raw) {
            hw = maxOf(hw, kotlin.math.abs(b[0] - cw / 2.0), kotlin.math.abs(b[2] - cw / 2.0))
            hh = maxOf(hh, kotlin.math.abs(b[1] - ch / 2.0), kotlin.math.abs(b[3] - ch / 2.0))
        }
        val sc = if (hw > 0 && hh > 0 && (hw > cw / 2.0 || hh > ch / 2.0)) minOf((cw / 2.0) / hw, (ch / 2.0) / hh) else 1.0
        fun fx(x: Double) = (((x - cw / 2.0) * sc + cw / 2.0) / cw).coerceIn(0.0, 1.0)
        fun fy(y: Double) = (((y - ch / 2.0) * sc + ch / 2.0) / ch).coerceIn(0.0, 1.0)
        val boxes = raw.map { (label, ct, b) -> Box(label, ct.first, ct.second, fx(b[0]), fy(b[1]), fx(b[2]), fy(b[3])) }
        val caption = listOf("scene_type", "gen_brief").map { v.optString(it, "").trim() }.filter { it.isNotEmpty() }.joinToString(". ")
        return Layout(boxes) to caption
    }

    /** ⚠ Pure: Python's `json.dumps(v, ensure_ascii=False, separators=(",", ":"))` for maps, lists, strings and numbers. */
    internal fun dumps(v: Any?): String = StringBuilder().also { emit(it, v) }.toString()

    private fun emit(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v)
            is Number -> sb.append(v.toDouble().let { d -> if (d == floor(d) && kotlin.math.abs(d) < 1e15) "${d.toLong()}.0" else d.toString() })
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString())
                    sb.append(':')
                    emit(sb, x)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                v.forEachIndexed { i, x -> if (i > 0) sb.append(','); emit(sb, x) }
                sb.append(']')
            }
            else -> quote(sb, v.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (c < ' ') sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", c.code)) else sb.append(c)
        }
        sb.append('"')
    }
}
