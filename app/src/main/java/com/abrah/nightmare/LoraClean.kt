package com.abrah.nightmare

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ⭐⭐ A LoRA's INTEGER tensors, made float before the DiT engine sees the file.
 *
 * ⚠⚠ Why (a tester, 2026-10-13, FLUX.2 Klein 4B on an 8 Gen 3): a CivitAI LoRA carrying `I32`
 * tensors aborted the engine on every run — *"type i32 unsupported for integer quantization: no
 * dequantization available"*, SIGABRT, a "the engine aborted" message and no picture. Trainers
 * store small bookkeeping tensors that way (an `alpha` as an int is the common one). The engine
 * only takes float types, so the app rewrites the file ONCE, in place: every integer tensor becomes
 * `F32` with the same values and shape, everything else is copied byte for byte.
 *
 * ⚠ In place, in the app's own `_loras` folder: the next run reads a header, sees nothing to do,
 * and costs a few kilobytes. ⚠ Written to a `.part` and renamed, so a kill mid-rewrite leaves the
 * original.
 */
object LoraClean {

    private val INT_BYTES = mapOf(
        "I8" to 1, "U8" to 1, "BOOL" to 1, "I16" to 2, "U16" to 2,
        "I32" to 4, "U32" to 4, "I64" to 8, "U64" to 8,
    )

    /** ⚠ Larger than any real LoRA header (a few hundred KB); anything bigger is not one. */
    private const val MAX_HEADER = 64L shl 20

    /** ⭐ Rewrites [file] when it has integer tensors. True when it did. ⚠ Blocking: off the main thread. */
    fun ensure(file: File): Boolean {
        if (!file.isFile || !file.name.endsWith(".safetensors", ignoreCase = true)) return false
        val (headerLen, header) = readHeader(file) ?: return false
        val tensors = header.keys().asSequence().filter { it != "__metadata__" }
            .map { it to header.getJSONObject(it) }.toList()
        if (tensors.none { (_, t) -> t.optString("dtype") in INT_BYTES }) return false

        val dataStart = 8L + headerLen
        // ⚠ In FILE order, so the data is read front to back once.
        val ordered = tensors.sortedBy { (_, t) -> t.getJSONArray("data_offsets").getLong(0) }
        val out = JSONObject()
        header.optJSONObject("__metadata__")?.let { out.put("__metadata__", it) }
        var at = 0L
        for ((name, t) in ordered) {
            val (b, e) = t.getJSONArray("data_offsets").let { it.getLong(0) to it.getLong(1) }
            val width = INT_BYTES[t.optString("dtype")]
            val size = if (width != null) (e - b) / width * 4 else e - b
            out.put(name, JSONObject().apply {
                put("dtype", if (width != null) "F32" else t.getString("dtype"))
                put("shape", t.getJSONArray("shape"))
                put("data_offsets", JSONArray().put(at).put(at + size))
            })
            at += size
        }
        var json = out.toString().toByteArray()
        // ⚠ Padded to 8 bytes with spaces, as the format recommends.
        val pad = (8 - json.size % 8) % 8
        if (pad > 0) json += ByteArray(pad) { ' '.code.toByte() }

        val part = File(file.parentFile, file.name + ".part")
        RandomAccessFile(file, "r").use { src ->
            part.outputStream().buffered(1 shl 20).use { dst ->
                dst.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(json.size.toLong()).array())
                dst.write(json)
                val buf = ByteArray(1 shl 20)
                for ((_, t) in ordered) {
                    val (b, e) = t.getJSONArray("data_offsets").let { it.getLong(0) to it.getLong(1) }
                    val dtype = t.optString("dtype")
                    src.seek(dataStart + b)
                    var left = e - b
                    if (dtype in INT_BYTES) {
                        val raw = ByteArray(left.toInt()).also { src.readFully(it) }
                        dst.write(toF32(raw, dtype))
                    } else while (left > 0) {
                        val n = src.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) throw java.io.EOFException("${file.name} ends inside its data")
                        dst.write(buf, 0, n)
                        left -= n
                    }
                }
            }
        }
        if (!part.renameTo(file)) {
            part.copyTo(file, overwrite = true)
            part.delete()
        }
        return true
    }

    private fun readHeader(file: File): Pair<Long, JSONObject>? = runCatching {
        RandomAccessFile(file, "r").use { r ->
            val lenBytes = ByteArray(8).also { r.readFully(it) }
            val len = ByteBuffer.wrap(lenBytes).order(ByteOrder.LITTLE_ENDIAN).long
            if (len <= 0 || len > MAX_HEADER || len > r.length() - 8) return null
            val h = ByteArray(len.toInt()).also { r.readFully(it) }
            len to JSONObject(String(h, Charsets.UTF_8))
        }
    }.getOrNull()

    /** The integers in [raw], little-endian, as `F32`. */
    internal fun toF32(raw: ByteArray, dtype: String): ByteArray {
        val src = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val width = INT_BYTES.getValue(dtype)
        val n = raw.size / width
        val dst = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(n) {
            val v: Double = when (dtype) {
                "I8" -> src.get().toDouble()
                "U8", "BOOL" -> (src.get().toInt() and 0xFF).toDouble()
                "I16" -> src.short.toDouble()
                "U16" -> (src.short.toInt() and 0xFFFF).toDouble()
                "I32" -> src.int.toDouble()
                "U32" -> (src.int.toLong() and 0xFFFFFFFFL).toDouble()
                "I64" -> src.long.toDouble()
                else -> src.long.toULong().toDouble()
            }
            dst.putFloat(v.toFloat())
        }
        return dst.array()
    }
}
