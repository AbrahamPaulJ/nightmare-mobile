package com.abrah.nightmare

import org.json.JSONArray
import org.json.JSONObject
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ⭐⭐ An fp8 DiT's NON-matmul fp8 tensors, made `F32` while the import copies the file.
 *
 * ⚠⚠ Why (a tester, 2026-10-19, `krea2Turbo4Step_v10` on a realme SM8750): the engine keeps `F8_E4M3` native, which
 * only the HTP's matmul runs; ggml-cpu has no fp8 at all, so an fp8 tensor any other op reads lands on the CPU and
 * aborts in `dup` (`ops.cpp:571`). The fp8 files that work (the built-in Klein 4B, Z-Image KJ) keep only 2-D matmul
 * weights in fp8 and every 1-D tensor in BF16/F32; that merge had its norm scales, `mod.lin`, `last.modulation.lin`
 * [2, 6144] and `txtfusion.projector.weight` [1, 12] in fp8 too. ⇒ Those become `F32` with the same values; the
 * matmul weights stay fp8, byte for byte (the user's call, 2026-10-21, over a whole-model Q4 conversion).
 *
 * ⚠ During the copy the import makes anyway, from a stream: no second pass over 12 GB and no second copy on disk.
 */
object Fp8Widen {

    private const val F8 = "F8_E4M3"

    /** ⚠ ComfyUI's scaled-fp8 marker: the engine never reads it, so it is left exactly as it was. */
    private const val MARKER = "scaled_fp8"

    /** ⚠ Larger than any real checkpoint header (tens of KB); anything bigger is not one. */
    private const val MAX_HEADER = 64L shl 20

    /** ⭐ Whether an fp8 tensor of this [shape] becomes F32: everything but a matmul weight the HTP's fp8 path takes. */
    internal fun widens(name: String, dtype: String, shape: List<Long>): Boolean {
        if (dtype != F8 || name == MARKER) return false
        val matmul = shape.size == 2 && shape[1] % 32 == 0L && shape[1] > 0 && shape[0] >= 32
        return !matmul
    }

    /**
     * ⭐ Copies a `.safetensors` from [input] to [out], widening what [widens] says. Returns how many tensors it
     * widened; 0 means the bytes went across unchanged. [onBytes] gets the bytes READ so far.
     */
    fun copy(input: InputStream, out: OutputStream, onBytes: (Long) -> Unit = {}, isCancelled: () -> Boolean = { false }): Int {
        val lenBytes = ByteArray(8)
        if (readUpTo(input, lenBytes) != 8) throw EOFException("this file is too small to be a checkpoint")
        val headerLen = ByteBuffer.wrap(lenBytes).order(ByteOrder.LITTLE_ENDIAN).long
        if (headerLen !in 2..MAX_HEADER) throw java.io.IOException("this is not a .safetensors file (its header claims $headerLen bytes)")
        val headerBytes = ByteArray(headerLen.toInt())
        if (readUpTo(input, headerBytes).toLong() != headerLen) throw EOFException("the file ends inside its header")
        var read = 8L + headerLen
        val header = JSONObject(String(headerBytes, Charsets.UTF_8))
        val entries = header.keys().asSequence().filter { it != "__metadata__" }
            .mapNotNull { k -> header.optJSONObject(k)?.let { k to it } }.toList()
        // ⚠ Decided BEFORE the offsets are read: a file with nothing to widen is copied as it is, whatever it holds.
        // ⚠ A tensor that states no shape is left alone: the engine decides (`docs/DIT.md` §2e, never refuse on a guess).
        val wide = entries.filter { (n, t) -> t.has("shape") && widens(n, t.optString("dtype"), shapeOf(t)) }.map { it.first }.toSet()

        val buf = ByteArray(1 shl 20)
        fun pump(count: Long) {
            var left = count
            while (left > 0) {
                if (isCancelled()) throw ModelInstaller.Cancelled()
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw EOFException("the file ends inside its data")
                out.write(buf, 0, n)
                left -= n
                read += n
                onBytes(read)
            }
        }
        if (wide.isEmpty()) {
            out.write(lenBytes)
            out.write(headerBytes)
            while (true) {
                if (isCancelled()) throw ModelInstaller.Cancelled()
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                read += n
                onBytes(read)
            }
            return 0
        }

        if (entries.any { (_, t) -> t.optJSONArray("data_offsets")?.length() != 2 }) {
            throw java.io.IOException("this checkpoint's tensor table has an entry without its place in the file")
        }
        val tensors = entries.sortedBy { (_, t) -> t.getJSONArray("data_offsets").getLong(0) }
        val newHeader = JSONObject()
        header.optJSONObject("__metadata__")?.let { newHeader.put("__metadata__", it) }
        var at = 0L
        for ((name, t) in tensors) {
            val (b, e) = t.getJSONArray("data_offsets").let { it.getLong(0) to it.getLong(1) }
            val size = if (name in wide) (e - b) * 4 else e - b
            newHeader.put(name, JSONObject().apply {
                put("dtype", if (name in wide) "F32" else t.getString("dtype"))
                put("shape", t.getJSONArray("shape"))
                put("data_offsets", JSONArray().put(at).put(at + size))
            })
            at += size
        }
        var json = newHeader.toString().toByteArray()
        // ⚠ Padded to 8 bytes with spaces, as the format recommends ([LoraClean] does the same).
        val pad = (8 - json.size % 8) % 8
        if (pad > 0) json += ByteArray(pad) { ' '.code.toByte() }
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(json.size.toLong()).array())
        out.write(json)

        // ⚠ In FILE order, front to back: a stream cannot seek. A gap between tensors is skipped, never copied.
        var pos = 0L
        for ((name, t) in tensors) {
            val (b, e) = t.getJSONArray("data_offsets").let { it.getLong(0) to it.getLong(1) }
            if (b < pos) throw java.io.IOException("tensor '$name' overlaps the one before it")
            if (b > pos) {
                skipExactly(input, b - pos)
                read += b - pos
            }
            if (name in wide) {
                if (isCancelled()) throw ModelInstaller.Cancelled()
                val raw = ByteArray((e - b).toInt())
                if (readUpTo(input, raw).toLong() != e - b) throw EOFException("the file ends inside '$name'")
                out.write(toF32(raw))
                read += raw.size
                onBytes(read)
            } else pump(e - b)
            pos = e
        }
        return wide.size
    }

    /** ⚠ Not `readNBytes`/`skipNBytes`: API 33, and minSdk is 31. */
    private fun readUpTo(input: InputStream, into: ByteArray): Int {
        var got = 0
        while (got < into.size) {
            val n = input.read(into, got, into.size - got)
            if (n < 0) break
            got += n
        }
        return got
    }

    private fun skipExactly(input: InputStream, count: Long) {
        var left = count
        val sink = ByteArray(minOf(count, 1L shl 16).toInt())
        while (left > 0) {
            val n = input.read(sink, 0, minOf(sink.size.toLong(), left).toInt())
            if (n < 0) throw EOFException("the file ends between its tensors")
            left -= n
        }
    }

    private fun shapeOf(t: JSONObject): List<Long> =
        t.optJSONArray("shape")?.let { a -> List(a.length()) { a.getLong(it) } }.orEmpty()

    /** E4M3 (fn: no infinities, S.1111.111 is NaN), all 256 codes. */
    private val LUT = FloatArray(256) { code ->
        val sign = if (code and 0x80 != 0) -1f else 1f
        val exp = (code shr 3) and 0xF
        val man = code and 0x7
        when {
            exp == 0xF && man == 0x7 -> Float.NaN
            exp == 0 -> sign * (man / 8f) * Math.scalb(1f, -6)
            else -> sign * (1f + man / 8f) * Math.scalb(1f, exp - 7)
        }
    }

    /** ⭐ fp8 (E4M3) bytes as little-endian `F32`. */
    internal fun toF32(raw: ByteArray): ByteArray {
        val dst = ByteBuffer.allocate(raw.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (b in raw) dst.putFloat(LUT[b.toInt() and 0xFF])
        return dst.array()
    }
}
