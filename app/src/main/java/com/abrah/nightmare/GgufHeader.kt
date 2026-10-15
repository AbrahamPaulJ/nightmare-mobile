package com.abrah.nightmare

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ⭐⭐ **A GGUF file's tensor table**, read from its first bytes — so a `.gguf`
 * DiT import can be checked the way a `.safetensors` one is.
 *
 * ⚠⚠ The output is deliberately the SAME shape as a safetensors header —
 * `{"name": {"dtype": "Q4_0", "shape": [4096, 128]}, …}` — so
 * [CustomModels.ditFamilyOf] and [CustomModels.isKlein9b] read both formats
 * with one set of signatures. ⚠ GGUF stores dims innermost-first (ggml's `ne`),
 * the reverse of torch's order, so they are REVERSED here: Klein 9B's
 * `img_in.weight` is `[128, 4096]` in the file and `[4096, 128]` in the JSON,
 * which is what its safetensors says (read off both, 2026-10-01).
 *
 * The format (v2/v3): `GGUF`, u32 version, u64 tensor count, u64 kv count,
 * the kv pairs, then per tensor: name, u32 n_dims, u64 dims, u32 type, u64 offset.
 */
object GgufHeader {

    val MAGIC = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())

    /** The parsed table: the safetensors-shaped JSON, and each weight type's name. */
    data class Table(
        val json: String,
        val types: Set<String>,
        val tensors: Int,
        /** ⭐ Each tensor's weight type, by name. */
        val typeOf: Map<String, String> = emptyMap(),
    )

    /** ggml's type ids, as the names a person would search for. */
    private val TYPE_NAMES = mapOf(
        0 to "F32", 1 to "F16", 2 to "Q4_0", 3 to "Q4_1", 6 to "Q5_0", 7 to "Q5_1",
        8 to "Q8_0", 9 to "Q8_1", 10 to "Q2_K", 11 to "Q3_K", 12 to "Q4_K", 13 to "Q5_K",
        14 to "Q6_K", 15 to "Q8_K", 16 to "IQ2_XXS", 17 to "IQ2_XS", 18 to "IQ3_XXS",
        19 to "IQ1_S", 20 to "IQ4_NL", 21 to "IQ3_S", 22 to "IQ2_S", 23 to "IQ4_XS",
        24 to "I8", 25 to "I16", 26 to "I32", 27 to "I64", 28 to "F64", 29 to "IQ1_M",
        30 to "BF16", 34 to "TQ1_0", 35 to "TQ2_0", 39 to "MXFP4",
    )

    fun typeName(id: Int): String = TYPE_NAMES[id] ?: "type$id"

    /**
     * ⭐ The weight types the HTP path is known to run — the fork validates
     * Q4_0, Q8_0, MXFP4 and FP8 on Hexagon, and our three GGUF packages carry
     * F32/F16/BF16 for their small tensors (and type 40, Krea 2's, which a
     * 24 GB OnePlus 13 renders — GitHub #5). Anything else is UNMEASURED here:
     * a K-quant may fall back to the CPU, or fail.
     */
    val KNOWN_GOOD = setOf("F32", "F16", "BF16", "Q4_0", "Q8_0", "MXFP4", "type40")

    /**
     * ⭐ The matmul types ggml-hexagon runs (its type switch, read 2026-10-14), plus the float ones.
     */
    val NPU_MATMUL = setOf("F32", "F16", "BF16", "Q4_0", "Q4_1", "Q8_0", "IQ4_NL", "MXFP4", "type40")

    /**
     * ⭐ A text encoder's BLOCK weight types the NPU cannot run, or empty (also when the header is not
     * readable: that is the engine's to report).
     *
     * ⚠⚠ Only `blk.*`: every shipped bartowski "Q4_0" encoder (Klein 9B, Krea 2, Qwen 2.1) has a Q6_K
     * `output.weight` (the LM head, unused for text encoding) and Q4_1 `ffn_down`s, and runs; an
     * "uncensored" Q4_K_M copied over Klein 4B's all-Q4_0 one (Telegram #6290) is Q4_K/Q6_K in its blocks.
     * ⚠ 16 MB: a llama-style GGUF carries its whole vocabulary in the header (4 MB was not enough).
     */
    fun unrunnableBlockTypes(file: java.io.File): Set<String> = runCatching {
        val head = file.inputStream().use { s ->
            val buf = ByteArray(minOf(file.length(), 16L shl 20).toInt())
            var n = 0
            while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r < 0) break; n += r }
            buf.copyOf(n)
        }
        blockTypesOutside(parse(head), NPU_MATMUL)
    }.getOrDefault(emptySet())

    /** ⚠ Pure: the `blk.*` weight types in [t] not in [allowed]. */
    fun blockTypesOutside(t: Table, allowed: Set<String>): Set<String> =
        t.typeOf.filterKeys { it.startsWith("blk.") }.values.toSet() - allowed

    /**
     * Parses [bytes], the first part of a file that starts with [MAGIC].
     * ⚠ Throws when the table runs past what was read — a DiT's is ~15–50 KB,
     * so 8 MB not being enough means this is not one.
     */
    fun parse(bytes: ByteArray): Table {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        try {
            for (i in 0 until 4) if (b.get() != MAGIC[i]) throw IOException("not a GGUF file")
            val version = b.int
            if (version < 2) throw IOException("GGUF version $version is too old")
            val tensors = b.long
            val kvs = b.long
            if (tensors !in 1..100_000 || kvs !in 0..100_000) {
                throw IOException("this GGUF's header is not readable")
            }
            repeat(kvs.toInt()) {
                skipString(b)
                skipValue(b, b.int)
            }
            val types = mutableSetOf<String>()
            val typeOf = LinkedHashMap<String, String>()
            val json = StringBuilder("{")
            repeat(tensors.toInt()) { i ->
                val name = readString(b)
                val nDims = b.int
                if (nDims !in 0..8) throw IOException("this GGUF's tensor table is not readable")
                val dims = LongArray(nDims) { b.long }
                val type = typeName(b.int)
                b.long // offset
                types += type
                typeOf[name] = type
                if (i > 0) json.append(',')
                json.append('"').append(name.replace("\"", "")).append("\":{\"dtype\":\"").append(type)
                    .append("\",\"shape\":[").append(dims.reversed().joinToString(",")).append("]}")
            }
            return Table(json.append('}').toString(), types, tensors.toInt(), typeOf)
        } catch (e: RuntimeException) {
            // ⚠ BufferUnderflow from a read, IllegalArgument from a skip past
            // the end — both mean the table is longer than what was read.
            throw IOException("this GGUF's header is larger than expected for a diffusion model")
        }
    }

    /** ggml's block layout per type: (elements per block, bytes per block). */
    private val BLOCKS = mapOf(
        "F32" to (1 to 4), "F16" to (1 to 2), "BF16" to (1 to 2), "F64" to (1 to 8),
        "I8" to (1 to 1), "I16" to (1 to 2), "I32" to (1 to 4), "I64" to (1 to 8),
        "Q4_0" to (32 to 18), "Q4_1" to (32 to 20), "Q5_0" to (32 to 22), "Q5_1" to (32 to 24),
        "Q8_0" to (32 to 34), "Q8_1" to (32 to 36), "IQ4_NL" to (32 to 18), "MXFP4" to (32 to 17),
        "Q2_K" to (256 to 84), "Q3_K" to (256 to 110), "Q4_K" to (256 to 144), "Q5_K" to (256 to 176),
        "Q6_K" to (256 to 210), "Q8_K" to (256 to 292), "IQ4_XS" to (256 to 136), "IQ2_XXS" to (256 to 66),
        "IQ2_XS" to (256 to 74), "IQ2_S" to (256 to 82), "IQ3_XXS" to (256 to 98), "IQ3_S" to (256 to 110),
        "IQ1_S" to (256 to 50), "IQ1_M" to (256 to 56), "TQ1_0" to (256 to 54), "TQ2_0" to (256 to 66),
    )

    /**
     * ⭐ Where this GGUF's data ends, by its own header: the data section starts at the header's end
     * rounded up to `general.alignment` (32 by default), and the last tensor ends at its offset plus its
     * size. Returns (end, alignment), or null when the table is unreadable or a type's size is unknown.
     * ⚠ Pure: [bytes] is the start of the file.
     */
    fun dataEnd(bytes: ByteArray): Pair<Long, Int>? = runCatching {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 4) if (b.get() != MAGIC[i]) return null
        if (b.int < 2) return null
        val tensors = b.long
        val kvs = b.long
        if (tensors !in 1..100_000 || kvs !in 0..100_000) return null
        var alignment = 32
        repeat(kvs.toInt()) {
            val key = readString(b)
            val type = b.int
            if (key == "general.alignment" && type == 4) alignment = b.int else skipValue(b, type)
        }
        var end = 0L
        repeat(tensors.toInt()) {
            readString(b)
            val nDims = b.int
            if (nDims !in 0..8) return null
            var elements = 1L
            repeat(nDims) { elements *= b.long }
            val (per, size) = BLOCKS[typeName(b.int)] ?: return null
            val offset = b.long
            end = maxOf(end, offset + (elements + per - 1) / per * size)
        }
        val start = (b.position().toLong() + alignment - 1) / alignment * alignment
        (start + end) to alignment
    }.getOrNull()

    private val completeCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Boolean>>()

    /**
     * ⭐⭐ A GGUF holds every byte its header promises: its length is the data's end, or that end padded
     * to the alignment (writers pad after each tensor, the last one too).
     *
     * ⚠⚠ The proof the exact-size check could not give: a file of ANOTHER size that is nonetheless whole,
     * a re-quantised or modified text encoder (Telegram #6290, #6310: "llm.gguf missing, repair 0 MB"),
     * while a half-downloaded one (written in place, right name) still ends short of its own table.
     * ⚠ Cached by path, length and time: the catalogue asks often, and a header can be 16 MB.
     */
    fun isComplete(file: java.io.File): Boolean {
        val len = file.length()
        if (len <= 0L) return false
        val stamp = len * 31 + file.lastModified()
        completeCache[file.path]?.let { (s, ok) -> if (s == stamp) return ok }
        val ok = runCatching {
            val head = file.inputStream().use { s ->
                val buf = ByteArray(minOf(len, 16L shl 20).toInt())
                var n = 0
                while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                buf.copyOf(n)
            }
            dataEnd(head)?.let { (end, align) -> len == end || len == (end + align - 1) / align * align } ?: false
        }.getOrDefault(false)
        completeCache[file.path] = stamp to ok
        return ok
    }

    private fun readString(b: ByteBuffer): String {
        val n = b.long
        if (n !in 0..(1L shl 20)) throw IOException("this GGUF's header is not readable")
        val a = ByteArray(n.toInt())
        b.get(a)
        return String(a, Charsets.UTF_8)
    }

    private fun skipString(b: ByteBuffer) {
        val n = b.long
        if (n < 0 || n > b.remaining()) throw java.nio.BufferUnderflowException()
        b.position(b.position() + n.toInt())
    }

    /** Value sizes by GGUF value type; 8 = string, 9 = array. */
    private fun skipValue(b: ByteBuffer, type: Int) {
        when (type) {
            0, 1, 7 -> b.position(b.position() + 1)
            2, 3 -> b.position(b.position() + 2)
            4, 5, 6 -> b.position(b.position() + 4)
            10, 11, 12 -> b.position(b.position() + 8)
            8 -> skipString(b)
            9 -> {
                val inner = b.int
                val n = b.long
                if (n < 0) throw IOException("this GGUF's header is not readable")
                for (k in 0 until n) skipValue(b, inner)
            }
            else -> throw IOException("this GGUF's header has an unknown value type $type")
        }
    }
}
