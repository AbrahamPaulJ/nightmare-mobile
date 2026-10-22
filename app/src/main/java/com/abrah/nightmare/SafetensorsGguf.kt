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
 * ⭐⭐ An official `.safetensors` DiT → the GGUF the DiT engine runs, on the phone. ONE writer for
 * every model that needs it ([AnimaGguf], [IrisGguf]); a model brings only its [Rule].
 *
 * ⚠ Output is reproducible byte for byte: tensors are written in sorted name order (org.json does
 * not keep the file's), offsets aligned to 32. Dims are ggml-order (the safetensors shape reversed).
 */
object SafetensorsGguf {
    const val F32 = 0
    const val Q8_0 = 8
    const val BF16 = 30

    private const val ALIGN = 32
    private const val CHUNK = 1 shl 20

    /** What a model decides per tensor. */
    interface Rule {
        /** The prefixes stripped from a key (ComfyUI's `model.diffusion_model.`). */
        val prefixes: List<String> get() = emptyList()
        /** GGUF key/values written after the header, as (key, string value). */
        val kv: List<Pair<String, String>> get() = emptyList()
        /**
         * ⚠ …then these, as (key, uint32 value). They are part of the file's SIZE, which the catalogue
         * checks: Anima's two went missing in the 1.6.167 refactor, its file came out 64 bytes short, and
         * every Anima Turbo 1.1 download failed at the end of the conversion (a tester, 2026-10-16).
         */
        val kvU32: List<Pair<String, Int>> get() = emptyList()
        /** ⭐ False drops a tensor (after the prefix is stripped): Anima 2.9B's RoPE buffers, which the engine builds itself. */
        fun keep(name: String): Boolean = true
        /** ⚠ Throws when [names] are not this model's: a wrong file must fail here, not render noise. */
        fun check(names: List<String>)
        /** The output shape (torch order) — [shape] unless the model pads it. */
        fun outShape(name: String, shape: LongArray): LongArray = shape
        /** [F32], [BF16] or [Q8_0]; [Q8_0] only for a row (last dim) that is a multiple of 32. */
        fun type(name: String, outShape: LongArray, dtype: String): Int
    }

    internal class Tensor(val name: String, val shape: LongArray, val outShape: LongArray, val dtype: String, val begin: Long, val end: Long, val type: Int) {
        val elements: Long = shape.fold(1L) { a, b -> a * b }
        val outElements: Long = outShape.fold(1L) { a, b -> a * b }
        val outBytes: Long = when (type) {
            F32 -> outElements * 4
            BF16 -> outElements * 2
            else -> outElements / 32 * 34
        }
    }

    /** ⭐ The bytes [convert] will write — what a catalogue entry's size is checked against. */
    internal fun plan(src: File, rule: Rule): Pair<List<Tensor>, Long> = RandomAccessFile(src, "r").use { raf -> read(raf, rule) }

    private fun read(raf: RandomAccessFile, rule: Rule): Pair<List<Tensor>, Long> {
        val lenBuf = ByteArray(8).also { raf.readFully(it) }
        val headerLen = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).long
        if (headerLen <= 0 || headerLen > 64L shl 20) throw IOException("not a safetensors file")
        val header = JSONObject(String(ByteArray(headerLen.toInt()).also { raf.readFully(it) }, Charsets.UTF_8))
        val tensors = header.keys().asSequence().filter { it != "__metadata__" }.sorted().filter { key ->
            rule.keep(rule.prefixes.firstOrNull { key.startsWith(it) }?.let { key.removePrefix(it) } ?: key)
        }.map { key ->
            val t = header.getJSONObject(key)
            val shape = t.getJSONArray("shape").let { a -> LongArray(a.length()) { a.getLong(it) } }
            val off = t.getJSONArray("data_offsets")
            val name = rule.prefixes.firstOrNull { key.startsWith(it) }?.let { key.removePrefix(it) } ?: key
            val out = rule.outShape(name, shape)
            val dtype = t.getString("dtype")
            val type = rule.type(name, out, dtype)
            if (type == Q8_0 && (out.size < 2 || out.last() % 32 != 0L)) throw IOException("$name: Q8_0 needs rows of 32")
            if (type == BF16 && dtype != "BF16") throw IOException("$name: BF16 out needs a BF16 source")
            Tensor(name, shape, out, dtype, off.getLong(0), off.getLong(1), type)
        }.toList()
        rule.check(tensors.map { it.name })
        return tensors to (8 + headerLen)
    }

    /** ⭐ Returns the bytes written, from the open file's position after a sync ([Converter.run]). */
    fun convert(src: File, dst: File, rule: Rule, onProgress: (Long, Long) -> Unit, isCancelled: () -> Boolean): Long {
        RandomAccessFile(src, "r").use { raf ->
            val (tensors, base) = read(raf, rule)
            val total = tensors.sumOf { it.outBytes }
            val fos = FileOutputStream(dst)
            BufferedOutputStream(fos, 1 shl 20).use { out ->
                val head = Le()
                head.bytes("GGUF".toByteArray()); head.u32(3); head.u64(tensors.size.toLong()); head.u64((rule.kv.size + rule.kvU32.size).toLong())
                for ((k, v) in rule.kv) { head.str(k); head.u32(8); head.str(v) }
                for ((k, v) in rule.kvU32) { head.str(k); head.u32(4); head.u32(v) }
                var offset = 0L
                for (t in tensors) {
                    head.str(t.name); head.u32(t.outShape.size)
                    for (d in t.outShape.reversedArray()) head.u64(d)
                    head.u32(t.type); head.u64(offset)
                    offset += pad(t.outBytes)
                }
                head.zeros((ALIGN - head.size() % ALIGN) % ALIGN)
                out.write(head.toByteArray())

                var done = 0L
                for (t in tensors) {
                    val width = when (t.dtype) { "F32" -> 4; "BF16", "F16" -> 2; else -> throw IOException("unsupported dtype ${t.dtype}") }
                    if (t.end - t.begin != t.elements * width) throw IOException("${t.name}: size does not match its shape")
                    raf.seek(base + t.begin)
                    // ⚠ Row by row, in groups of whole rows: a row is where padding goes and where a Q8_0
                    // block must not straddle. A 1-D tensor is one row.
                    val rowIn = (if (t.shape.size > 1) t.shape.last() else t.elements).toInt()
                    val rowOut = (if (t.outShape.size > 1) t.outShape.last() else t.outElements).toInt()
                    val rowsIn = t.elements / rowIn
                    val rowsOut = t.outElements / rowOut
                    val group = maxOf(1, CHUNK / maxOf(rowOut, 1))
                    var written = 0L
                    var r = 0L
                    while (r < rowsOut) {
                        if (isCancelled()) throw ModelInstaller.Cancelled() // ⚠ Its own type: a plain IOException read as a FAILED download
                        val n = minOf(group.toLong(), rowsOut - r).toInt()
                        val real = minOf(n.toLong(), maxOf(0L, rowsIn - r)).toInt()
                        val raw = ByteArray(real * rowIn * width).also { if (real > 0) raf.readFully(it) }
                        val data = encode(t, raw, real, n, rowIn, rowOut, width)
                        out.write(data)
                        written += data.size
                        r += n
                    }
                    out.write(ByteArray((pad(written) - written).toInt()))
                    done += t.outBytes
                    onProgress(done, total)
                }
                out.flush()
                fos.fd.sync()
                return fos.channel.position()
            }
        }
    }

    /** [n] output rows of [rowOut] values: the first [real] from [raw] (rows of [rowIn]), the rest and every row's tail zero. */
    private fun encode(t: Tensor, raw: ByteArray, real: Int, n: Int, rowIn: Int, rowOut: Int, width: Int): ByteArray {
        if (t.type == BF16) {
            val outB = ByteArray(n * rowOut * 2)
            for (i in 0 until real) System.arraycopy(raw, i * rowIn * 2, outB, i * rowOut * 2, rowIn * 2)
            return outB
        }
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val x = FloatArray(n * rowOut)
        for (i in 0 until real) for (j in 0 until rowIn) {
            val k = (i * rowIn + j)
            x[i * rowOut + j] = when (t.dtype) {
                "BF16" -> java.lang.Float.intBitsToFloat((bb.getShort(k * 2).toInt() and 0xFFFF) shl 16)
                "F16" -> Half.toFloat(bb.getShort(k * 2))
                else -> bb.getFloat(k * width)
            }
        }
        return if (t.type == F32) f32Bytes(x) else q8_0(x)
    }

    private fun pad(n: Long) = (n + ALIGN - 1) / ALIGN * ALIGN

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
    private class Le : java.io.ByteArrayOutputStream() {
        fun bytes(b: ByteArray) = write(b)
        fun u32(v: Int) = write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun u64(v: Long) = write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
        fun str(s: String) { val b = s.toByteArray(Charsets.UTF_8); u64(b.size.toLong()); write(b) }
        fun zeros(n: Int) = write(ByteArray(n))
    }
}
