package com.abrah.nightmare

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32

/**
 * ⭐⭐ **What made a picture, inside the picture** (GitHub #106, the user's call 2026-11-14: A1111's text plus our own,
 * ON by default, a switch to turn it off: [Prefs.pngInfo]).
 *
 * Two text chunks go in right after the PNG's header:
 * - `parameters`: A1111's block (prompt, `Negative prompt:`, then `Steps: 20, Sampler: ...`), which CivitAI, A1111,
 *   Forge and most picture viewers read. It also marks the picture as AI-made, the reporter's other point.
 * - `nightmare`: JSON with the whole flow ([Workflow.toJson]) and the result's own fields, what Nightmare reads back
 *   on import so a picture moved to a new phone comes back as a result with its flow ([read], [Embedded]).
 *
 * ⚠ Streamed: [wrap] sits between the encoder and the MediaStore stream, so a 4096² save never holds the PNG in the
 * heap ([ImageSaver.saveBitmap]'s reason). ⚠ A stream that is not a PNG passes through untouched.
 * ⚠ `tEXt` is Latin-1 by the spec; text outside it (a Chinese prompt) goes as `iTXt` (UTF-8), PIL's rule, which
 * every reader above handles.
 */
object PngInfo {
    const val PARAMETERS = "parameters"
    const val NIGHTMARE = "nightmare"

    data class Info(val parameters: String, val json: String)

    private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    /** Signature (8) + the IHDR chunk (4 length + 4 type + 13 data + 4 crc). */
    private const val HEAD = 33

    /** ⭐ [out] with [info]'s chunks after IHDR; [out] itself when [info] is null. */
    fun wrap(out: OutputStream, info: Info?): OutputStream = if (info == null) out else Inserting(out, chunks(info))

    /** ⚠ Pure: the encoded chunks, `parameters` first. */
    internal fun chunks(info: Info): ByteArray = ByteArrayOutputStream().also { b ->
        b.write(textChunk(PARAMETERS, info.parameters))
        b.write(textChunk(NIGHTMARE, info.json, forceUtf8 = true))
    }.toByteArray()

    internal fun textChunk(key: String, text: String, forceUtf8: Boolean = false): ByteArray {
        val latin1 = Charsets.ISO_8859_1.newEncoder()
        val data = ByteArrayOutputStream()
        val type: String
        if (!forceUtf8 && latin1.canEncode(text)) {
            type = "tEXt"
            data.write(key.toByteArray(Charsets.ISO_8859_1)); data.write(0)
            data.write(text.toByteArray(Charsets.ISO_8859_1))
        } else {
            type = "iTXt"
            data.write(key.toByteArray(Charsets.ISO_8859_1)); data.write(0)
            data.write(0); data.write(0) // not compressed, method 0
            data.write(0) // no language tag
            data.write(0) // no translated keyword
            data.write(text.toByteArray(Charsets.UTF_8))
        }
        return chunk(type, data.toByteArray())
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val t = type.toByteArray(Charsets.ISO_8859_1)
        val crc = CRC32().apply { update(t); update(data) }.value
        return ByteArrayOutputStream(data.size + 12).apply {
            write(int32(data.size)); write(t); write(data); write(int32(crc.toInt()))
        }.toByteArray()
    }

    private fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    /** ⚠ Holds the first [HEAD] bytes, checks them, writes them and the chunks, then passes everything through. */
    private class Inserting(out: OutputStream, private val extra: ByteArray) : FilterOutputStream(out) {
        private val head = ByteArray(HEAD)
        private var n = 0
        private var done = false

        override fun write(b: Int) {
            if (done) { out.write(b); return }
            head[n++] = b.toByte()
            if (n == HEAD) release()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var i = off
            val end = off + len
            while (!done && i < end) write(b[i++].toInt())
            if (i < end) out.write(b, i, end - i)
        }

        private fun release() {
            done = true
            out.write(head, 0, n)
            val isPng = n == HEAD && head.copyOfRange(0, 8).contentEquals(SIGNATURE) &&
                String(head, 12, 4, Charsets.ISO_8859_1) == "IHDR"
            if (isPng) out.write(extra)
        }

        override fun flush() { out.flush() }

        override fun close() {
            if (!done) { done = true; out.write(head, 0, n) }
            out.close()
        }
    }

    /**
     * ⭐ The text chunks of a PNG (`tEXt` and uncompressed `iTXt`), read up to the first IDAT, or empty when [input]
     * is not a PNG. Ours sit right after the header, and A1111's too.
     */
    fun read(input: InputStream): Map<String, String> {
        val d = DataInputStream(input)
        val sig = ByteArray(8)
        if (runCatching { d.readFully(sig) }.isFailure || !sig.contentEquals(SIGNATURE)) return emptyMap()
        val out = linkedMapOf<String, String>()
        while (true) {
            val len = runCatching { d.readInt() }.getOrNull() ?: break
            val typeB = ByteArray(4)
            if (runCatching { d.readFully(typeB) }.isFailure) break
            val type = String(typeB, Charsets.ISO_8859_1)
            if (type == "IDAT" || type == "IEND" || len < 0 || len > MAX_TEXT) break
            val data = ByteArray(len)
            if (runCatching { d.readFully(data); d.readInt() }.isFailure) break
            when (type) {
                "tEXt" -> {
                    val z = data.indexOf(0)
                    if (z > 0) out[String(data, 0, z, Charsets.ISO_8859_1)] = String(data, z + 1, len - z - 1, Charsets.ISO_8859_1)
                }
                "iTXt" -> {
                    val z = data.indexOf(0)
                    if (z > 0 && z + 2 < len && data[z + 1].toInt() == 0) {
                        // ⚠ Skip the language tag and the translated keyword, each NUL-terminated.
                        var i = z + 3
                        repeat(2) { while (i < len && data[i].toInt() != 0) i++; i++ }
                        if (i <= len) out[String(data, 0, z, Charsets.ISO_8859_1)] = String(data, i, len - i, Charsets.UTF_8)
                    }
                }
            }
        }
        return out
    }

    /** ⚠ A text chunk past this is not ours to read (a flow is a few KB, a ComfyUI workflow under 1 MB). */
    private const val MAX_TEXT = 8 shl 20

    private fun ByteArray.indexOf(b: Int): Int { for (i in indices) if (this[i].toInt() == b) return i; return -1 }

    /**
     * ⭐ A1111's `parameters` block. [fields] in order; a value holding a comma or a colon is quoted, A1111's own rule,
     * so its parser keeps it whole.
     */
    fun a1111(prompt: String, negative: String, fields: List<Pair<String, String>>): String = buildString {
        append(prompt.trim())
        if (negative.isNotBlank()) append("\nNegative prompt: ").append(negative.trim())
        val line = fields.filter { it.second.isNotBlank() }.joinToString(", ") { (k, v) ->
            val one = v.replace('\n', ' ').trim()
            k + ": " + if (one.contains(',') || one.contains(':') || one.contains('"')) "\"" + one.replace("\"", "'") + "\"" else one
        }
        if (line.isNotEmpty()) append('\n').append(line)
    }
}
