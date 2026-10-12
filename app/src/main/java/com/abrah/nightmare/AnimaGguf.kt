package com.abrah.nightmare

import android.util.Half
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ⭐⭐ Anima's official BF16 `.safetensors` DiT → the Q8_0 GGUF the DiT engine runs
 * fastest (`docs/ANIMA-GGML.md` §4): 56 s against 67 s at 1024², and 2.2 GB of
 * NPU memory against 4.0. The layout is Bedovyy's (`Bedovyy/Anima-GGUF`), which
 * the engine already loads: names lose the ComfyUI `model.diffusion_model.` (or
 * `net.`) prefix, dims are ggml-order, kv `general.architecture = cosmos`.
 *
 * ⚠ The type rule reproduces his file tensor for tensor (checked on all 685):
 * F32 for 1-D tensors, `final_layer.*`, `t_embedder.*` and any row not a multiple
 * of 32; Q8_0 for the rest. The official turbo 1.1 converts to exactly
 * [TURBO_11_BYTES] — the size the catalogue checks.
 */
object AnimaGguf {
    /** `anima-turbo-v1.1.safetensors` converted, and Bedovyy's Q8_0 — the same size. */
    const val TURBO_11_BYTES = 2_276_712_576L

    private const val ALIGN = 32
    private const val CHUNK = 1L shl 20
    private const val GGML_F32 = 0
    private const val GGML_Q8_0 = 8
    private val PREFIXES = listOf("model.diffusion_model.", "net.")

    private class Tensor(val name: String, val shape: LongArray, val dtype: String, val begin: Long, val end: Long) {
        val elements: Long = shape.fold(1L) { a, b -> a * b }
        val type: Int = if (shape.size == 1 || name.startsWith("final_layer.") ||
            name.startsWith("t_embedder.") || shape.last() % 32 != 0L) GGML_F32 else GGML_Q8_0
        val outBytes: Long = if (type == GGML_F32) elements * 4 else elements / 32 * 34
    }

    val converter = Converter { src, dst, onProgress, isCancelled -> convert(src, dst, onProgress, isCancelled) }

    fun convert(src: File, dst: File, onProgress: (Long, Long) -> Unit, isCancelled: () -> Boolean) {
        RandomAccessFile(src, "r").use { raf ->
            val lenBuf = ByteArray(8).also { raf.readFully(it) }
            val headerLen = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).long
            if (headerLen <= 0 || headerLen > 64L shl 20) throw IOException("not a safetensors file")
            val header = JSONObject(String(ByteArray(headerLen.toInt()).also { raf.readFully(it) }, Charsets.UTF_8))
            val base = 8 + headerLen
            // ⚠ Sorted: org.json does not keep the file's order, and a fixed
            // order makes the output byte-for-byte reproducible.
            val tensors = header.keys().asSequence().filter { it != "__metadata__" }.sorted().map { key ->
                val t = header.getJSONObject(key)
                val shape = t.getJSONArray("shape").let { a -> LongArray(a.length()) { a.getLong(it) } }
                val off = t.getJSONArray("data_offsets")
                val name = PREFIXES.firstOrNull { key.startsWith(it) }?.let { key.removePrefix(it) } ?: key
                Tensor(name, shape, t.getString("dtype"), off.getLong(0), off.getLong(1))
            }.toList()
            if (tensors.none { it.name.startsWith("llm_adapter.") }) throw IOException("not an Anima DiT (no llm_adapter)")
            val total = tensors.sumOf { it.outBytes }

            BufferedOutputStream(FileOutputStream(dst), 1 shl 20).use { out ->
                val head = ByteArrayOutputStreamLE()
                head.bytes("GGUF".toByteArray()); head.u32(3); head.u64(tensors.size.toLong()); head.u64(3)
                head.str("general.architecture"); head.u32(8); head.str("cosmos")
                head.str("general.quantization_version"); head.u32(4); head.u32(2)
                head.str("general.file_type"); head.u32(4); head.u32(7)
                var offset = 0L
                for (t in tensors) {
                    head.str(t.name); head.u32(t.shape.size)
                    for (d in t.shape.reversedArray()) head.u64(d)
                    head.u32(t.type); head.u64(offset)
                    offset += pad(t.outBytes)
                }
                head.zeros(((ALIGN - head.size() % ALIGN) % ALIGN))
                out.write(head.toByteArray())

                var done = 0L
                for (t in tensors) {
                    val width = when (t.dtype) { "F32" -> 4; "BF16", "F16" -> 2; else -> throw IOException("unsupported dtype ${t.dtype}") }
                    if (t.end - t.begin != t.elements * width) throw IOException("${t.name}: size does not match its shape")
                    raf.seek(base + t.begin)
                    // ⚠ In chunks, never a whole tensor: the largest is 16.7 M values, ~120 MB
                    // of heap at once. A chunk is a multiple of 32, so no Q8_0 block straddles two.
                    var left = t.elements
                    var written = 0L
                    while (left > 0) {
                        if (isCancelled()) throw IOException("cancelled")
                        val n = minOf(left, CHUNK).toInt()
                        val raw = ByteArray(n * width).also { raf.readFully(it) }
                        val x = toFloats(raw, t.dtype, n)
                        val data = if (t.type == GGML_F32) f32Bytes(x) else q8_0(x)
                        out.write(data)
                        written += data.size
                        left -= n
                    }
                    out.write(ByteArray((pad(written) - written).toInt()))
                    done += t.outBytes
                    onProgress(done, total)
                }
            }
        }
    }

    private fun pad(n: Long) = (n + ALIGN - 1) / ALIGN * ALIGN

    private fun toFloats(raw: ByteArray, dtype: String, n: Int): FloatArray {
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        return when (dtype) {
            "BF16" -> FloatArray(n) { java.lang.Float.intBitsToFloat((bb.getShort(it * 2).toInt() and 0xFFFF) shl 16) }
            "F16" -> FloatArray(n) { Half.toFloat(bb.getShort(it * 2)) }
            "F32" -> FloatArray(n) { bb.getFloat(it * 4) }
            else -> throw IOException("unsupported dtype $dtype")
        }
    }

    private fun f32Bytes(x: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(x.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.asFloatBuffer().put(x)
        return bb.array()
    }

    /** ggml's block_q8_0: an fp16 scale, then 32 int8s — `d = max|x| / 127`, round half to even. */
    internal fun q8_0(x: FloatArray): ByteArray {
        val blocks = x.size / 32
        val out = ByteBuffer.allocate(blocks * 34).order(ByteOrder.LITTLE_ENDIAN)
        for (b in 0 until blocks) {
            val o = b * 32
            var amax = 0f
            for (i in 0 until 32) amax = maxOf(amax, kotlin.math.abs(x[o + i]))
            val d = amax / 127.0f
            val inv = if (d > 0f) 1.0f / d else 0f
            out.putShort(Half.toHalf(d))
            for (i in 0 until 32) {
                out.put(Math.rint((x[o + i] * inv).toDouble()).coerceIn(-127.0, 127.0).toInt().toByte())
            }
        }
        return out.array()
    }

    /** A little-endian writer for the GGUF header. */
    private class ByteArrayOutputStreamLE : java.io.ByteArrayOutputStream() {
        fun bytes(b: ByteArray) = write(b)
        fun u32(v: Int) = write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun u64(v: Long) = write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
        fun str(s: String) { val b = s.toByteArray(Charsets.UTF_8); u64(b.size.toLong()); write(b) }
        fun zeros(n: Int) = write(ByteArray(n))
    }
}
