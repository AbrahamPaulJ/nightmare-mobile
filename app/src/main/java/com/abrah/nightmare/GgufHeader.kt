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
    data class Table(val json: String, val types: Set<String>, val tensors: Int)

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
            val json = StringBuilder("{")
            repeat(tensors.toInt()) { i ->
                val name = readString(b)
                val nDims = b.int
                if (nDims !in 0..8) throw IOException("this GGUF's tensor table is not readable")
                val dims = LongArray(nDims) { b.long }
                val type = typeName(b.int)
                b.long // offset
                types += type
                if (i > 0) json.append(',')
                json.append('"').append(name.replace("\"", "")).append("\":{\"dtype\":\"").append(type)
                    .append("\",\"shape\":[").append(dims.reversed().joinToString(",")).append("]}")
            }
            return Table(json.append('}').toString(), types, tensors.toInt())
        } catch (e: RuntimeException) {
            // ⚠ BufferUnderflow from a read, IllegalArgument from a skip past
            // the end — both mean the table is longer than what was read.
            throw IOException("this GGUF's header is larger than expected for a diffusion model")
        }
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
