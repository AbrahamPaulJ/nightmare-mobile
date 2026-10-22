package com.abrah.nightmare

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ⭐⭐ A GGUF DiT's quantized tensors that are not block matmul weights, made `BF16` while the import copies the file.
 *
 * ⚠⚠ Why (a tester on an 8 Gen 3, 1.6.203, a self-converted Klein 9B "Q4_0", TG #6347): the engine aborted in its
 * first segment, `flux.prelude`, in ggml-cpu's `dup` (`ops.cpp:571`), which copies a quantized tensor only to F32. The
 * Klein 9B Q4_0 we ship quantizes only the 2-D weights inside `double_blocks.` / `single_blocks.`: its 9 tensors
 * outside them (`img_in`, `txt_in`, `time_in`, the three modulations, `final_layer`) and every 1-D norm scale are
 * BF16, as stable-diffusion.cpp's own converter leaves them (`tensor_should_be_converted`). The tester's file was
 * 204 MB smaller, and its prelude 65.5 MB where BF16 `txt_in` alone is 100: those were quantized. ⇒ They become BF16
 * with the values they hold; everything else goes across byte for byte. [Fp8Widen] is the same fix for a
 * safetensors.
 *
 * ⚠ During the copy the import makes anyway, from a stream. ⚠ Only the legacy block types are dequantized
 * ([DEQUANT]); a K-quant in those places is left as it is and named in [Result.left], the engine's to report.
 */
object GgufWiden {

    /** What a copy did: tensors made BF16, and ones that should have been but are of a type not dequantized here. */
    data class Result(val widened: Int, val left: List<String>)

    private val FLOATS = setOf("F32", "F16", "BF16", "F64", "I8", "I16", "I32", "I64")

    /** ⭐ The quantized types this dequantizes (ggml's reference `dequantize_row_*`). */
    val DEQUANT = setOf("Q4_0", "Q4_1", "Q5_0", "Q5_1", "Q8_0")

    /** ⚠ A DiT's header is tens of KB; [GgufHeader] reads 16 MB for a text encoder's vocabulary. */
    private const val HEAD = 16 shl 20

    private const val BF16 = 30

    /**
     * ⭐ Whether a [type] tensor becomes BF16: a quantized 1-D one in any family (a norm scale is never a matmul), and,
     * in FLUX.2, a quantized one outside the transformer blocks. [ne] is ggml's order, innermost first.
     */
    internal fun widens(family: Family?, name: String, type: String, ne: LongArray): Boolean {
        if (type in FLOATS) return false
        if (ne.size < 2) return true
        return family == Family.FLUX2 && !IN_BLOCKS.containsMatchIn(name)
    }

    /**
     * ⚠⚠ A block weight by PATH, not by the name's start: ComfyUI-style files prefix every name
     * (`model.diffusion_model.double_blocks.0.…`, Mr-J's Klein-Pmaster 9B Q4_0, TG 2026-10-22), and `startsWith`
     * called all 115 block matmuls "outside the blocks": a 5.25 GB Q4_0 imported as 18 GB of BF16. The engine strips
     * those prefixes itself.
     */
    private val IN_BLOCKS = Regex("""(^|\.)(double|single)_blocks\.""")

    private class Info(val name: String, val dims: LongArray, val type: Int, val offset: Long) {
        val elements: Long get() = dims.fold(1L) { a, d -> a * d }
    }

    /**
     * ⭐ Copies a `.gguf` from [input] to [out], widening what [widens] says. [onBytes] gets the bytes READ so far.
     */
    fun copy(
        input: InputStream,
        out: OutputStream,
        family: Family?,
        onBytes: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Result {
        val head = ByteArray(HEAD)
        val got = input.readUpTo(head)
        val b = ByteBuffer.wrap(head, 0, got).order(ByteOrder.LITTLE_ENDIAN)
        var alignment = 32
        val kvEnd: Int
        val infos: List<Info>
        try {
            for (i in 0 until 4) if (b.get() != GgufHeader.MAGIC[i]) throw IOException("not a GGUF file")
            val version = b.int
            if (version < 2) throw IOException("GGUF version $version is too old")
            val tensors = b.long
            val kvs = b.long
            if (tensors !in 1..100_000 || kvs !in 0..100_000) throw IOException("this GGUF's header is not readable")
            repeat(kvs.toInt()) {
                val key = GgufHeader.readString(b)
                val type = b.int
                if (key == "general.alignment" && type == 4) alignment = b.int else GgufHeader.skipValue(b, type)
            }
            kvEnd = b.position()
            infos = List(tensors.toInt()) {
                val name = GgufHeader.readString(b)
                val nDims = b.int
                if (nDims !in 0..8) throw IOException("this GGUF's tensor table is not readable")
                val dims = LongArray(nDims) { b.long }
                Info(name, dims, b.int, b.long)
            }
        } catch (e: RuntimeException) {
            throw IOException("this GGUF's header is larger than expected for a diffusion model")
        }
        if (alignment <= 0) throw IOException("this GGUF's alignment ($alignment) is not usable")
        val headerEnd = b.position()
        fun align(x: Long) = (x + alignment - 1) / alignment * alignment
        fun size(type: String, elements: Long): Long {
            val (per, bytes) = GgufHeader.BLOCKS[type] ?: throw IOException("this GGUF has a tensor of unknown type $type")
            return (elements + per - 1) / per * bytes
        }
        val should = infos.filter { widens(family, it.name, GgufHeader.typeName(it.type), it.dims) }
        val wide = should.filter { GgufHeader.typeName(it.type) in DEQUANT }.map { it.name }.toSet()
        val left = should.filter { it.name !in wide }.map { "${it.name} (${GgufHeader.typeName(it.type)})" }

        val rest = SequenceInputStream(ByteArrayInputStream(head, headerEnd, got - headerEnd), input)
        var read = headerEnd.toLong()
        val buf = ByteArray(1 shl 20)
        fun pump(count: Long) {
            var n = count
            while (n > 0) {
                if (isCancelled()) throw ModelInstaller.Cancelled()
                val r = rest.read(buf, 0, minOf(buf.size.toLong(), n).toInt())
                if (r < 0) throw EOFException("the file ends inside its data")
                out.write(buf, 0, r)
                n -= r
                read += r
                onBytes(read)
            }
        }
        if (wide.isEmpty()) {
            out.write(head, 0, headerEnd)
            while (true) {
                if (isCancelled()) throw ModelInstaller.Cancelled()
                val r = rest.read(buf)
                if (r < 0) break
                out.write(buf, 0, r)
                read += r
                onBytes(read)
            }
            return Result(0, left)
        }

        // ⚠ In FILE order, front to back: a stream cannot seek. New offsets follow the same order.
        val order = infos.sortedBy { it.offset }
        val newOffset = HashMap<String, Long>()
        var at = 0L
        for (t in order) {
            at = align(at)
            newOffset[t.name] = at
            at += if (t.name in wide) t.elements * 2 else size(GgufHeader.typeName(t.type), t.elements)
        }
        val le = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        fun u32(v: Int) { le.clear(); le.putInt(v); out.write(le.array(), 0, 4) }
        fun u64(v: Long) { le.clear(); le.putLong(v); out.write(le.array(), 0, 8) }
        fun zeros(n: Long) { var k = n; while (k > 0) { val c = minOf(k, buf.size.toLong()).toInt(); java.util.Arrays.fill(buf, 0, c, 0); out.write(buf, 0, c); k -= c } }

        out.write(head, 0, kvEnd)
        var written = kvEnd.toLong()
        for (t in infos) {
            val name = t.name.toByteArray(Charsets.UTF_8)
            u64(name.size.toLong()); out.write(name)
            u32(t.dims.size)
            for (d in t.dims) u64(d)
            u32(if (t.name in wide) BF16 else t.type)
            u64(newOffset.getValue(t.name))
            written += 8 + name.size + 4 + 8L * t.dims.size + 4 + 8
        }
        zeros(align(written) - written)

        rest.skipExactly(align(headerEnd.toLong()) - headerEnd)
        read = align(headerEnd.toLong())
        var pos = 0L
        var outPos = 0L
        for (t in order) {
            val type = GgufHeader.typeName(t.type)
            val bytes = size(type, t.elements)
            if (t.offset < pos) throw IOException("tensor '${t.name}' overlaps the one before it")
            rest.skipExactly(t.offset - pos)
            read += t.offset - pos
            val dst = newOffset.getValue(t.name)
            zeros(dst - outPos)
            if (t.name in wide) {
                widen(rest, out, type, t.elements, isCancelled)
                read += bytes
                onBytes(read)
                outPos = dst + t.elements * 2
            } else {
                pump(bytes)
                outPos = dst + bytes
            }
            pos = t.offset + bytes
        }
        zeros(align(outPos) - outPos)
        return Result(wide.size, left)
    }

    /** ⭐ [elements] of a [type] tensor from [input] to [out] as little-endian BF16, a slab of blocks at a time. */
    private fun widen(input: InputStream, out: OutputStream, type: String, elements: Long, isCancelled: () -> Boolean) {
        val (per, bytes) = GgufHeader.BLOCKS.getValue(type)
        var blocks = (elements + per - 1) / per
        var left = elements
        val slab = 4096
        val raw = ByteArray(slab * bytes)
        val f = FloatArray(slab * per)
        val o = ByteArray(slab * per * 2)
        while (blocks > 0) {
            if (isCancelled()) throw ModelInstaller.Cancelled()
            val n = minOf(blocks, slab.toLong()).toInt()
            if (input.readUpTo(raw, 0, n * bytes) != n * bytes) throw EOFException("the file ends inside a tensor")
            dequantize(type, raw, n, f)
            val count = minOf(left, (n * per).toLong()).toInt()
            for (i in 0 until count) {
                val h = toBf16(f[i])
                o[2 * i] = h.toByte()
                o[2 * i + 1] = (h ushr 8).toByte()
            }
            out.write(o, 0, count * 2)
            blocks -= n
            left -= count
        }
    }

    /** IEEE float to BF16 bits, nearest-even (NaN stays NaN). */
    internal fun toBf16(x: Float): Int {
        val bits = x.toRawBits()
        if (x.isNaN()) return 0x7FC0
        return ((bits + 0x7FFF + ((bits ushr 16) and 1)) ushr 16) and 0xFFFF
    }

    /** ⭐ ggml's `dequantize_row_*` for [DEQUANT], [n] blocks of [raw] into [dst]. */
    internal fun dequantize(type: String, raw: ByteArray, n: Int, dst: FloatArray) {
        fun half(at: Int) = TemplateLora.halfToFloat(((raw[at].toInt() and 0xFF) or ((raw[at + 1].toInt() and 0xFF) shl 8)).toShort())
        fun u8(at: Int) = raw[at].toInt() and 0xFF
        fun u32(at: Int) = u8(at) or (u8(at + 1) shl 8) or (u8(at + 2) shl 16) or (u8(at + 3) shl 24)
        for (k in 0 until n) {
            val y = k * 32
            when (type) {
                "Q4_0" -> {
                    val p = k * 18; val d = half(p)
                    for (j in 0 until 16) {
                        val q = u8(p + 2 + j)
                        dst[y + j] = ((q and 0x0F) - 8) * d
                        dst[y + j + 16] = ((q ushr 4) - 8) * d
                    }
                }
                "Q4_1" -> {
                    val p = k * 20; val d = half(p); val m = half(p + 2)
                    for (j in 0 until 16) {
                        val q = u8(p + 4 + j)
                        dst[y + j] = (q and 0x0F) * d + m
                        dst[y + j + 16] = (q ushr 4) * d + m
                    }
                }
                "Q5_0" -> {
                    val p = k * 22; val d = half(p); val qh = u32(p + 2)
                    for (j in 0 until 16) {
                        val q = u8(p + 6 + j)
                        val h0 = ((qh ushr j) shl 4) and 0x10
                        val h1 = (qh ushr (j + 12)) and 0x10
                        dst[y + j] = (((q and 0x0F) or h0) - 16) * d
                        dst[y + j + 16] = (((q ushr 4) or h1) - 16) * d
                    }
                }
                "Q5_1" -> {
                    val p = k * 24; val d = half(p); val m = half(p + 2); val qh = u32(p + 4)
                    for (j in 0 until 16) {
                        val q = u8(p + 8 + j)
                        val h0 = ((qh ushr j) shl 4) and 0x10
                        val h1 = (qh ushr (j + 12)) and 0x10
                        dst[y + j] = ((q and 0x0F) or h0) * d + m
                        dst[y + j + 16] = ((q ushr 4) or h1) * d + m
                    }
                }
                "Q8_0" -> {
                    val p = k * 34; val d = half(p)
                    for (j in 0 until 32) dst[y + j] = raw[p + 2 + j] * d
                }
                else -> throw IOException("cannot dequantize $type")
            }
        }
    }
}
