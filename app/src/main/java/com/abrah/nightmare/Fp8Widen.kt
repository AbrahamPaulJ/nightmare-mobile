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
 * ⭐ **`F8_E5M2` too** (a tester's Klein 9B "pruned_fp8", 1.6.214, 2026-10-22: `unsupported dtype 'F8_E5M2'` at launch): our
 * engine's safetensors reader maps E5M2 to no ggml type at all. Its non-matmul tensors become `F32` (exact: E5M2 is the
 * top byte of an fp16); its matmul weights become `F8_E4M3` by nearest rounding, the same size. Exact from 2^-6 to 448
 * (E4M3 has one more mantissa bit); below 2^-6 they land on E4M3's subnormal grid, as any unscaled `fp8_e4m3fn` cast
 * of the same weights would. ⚠ Not scaled: a per-tensor scale needs the engine to apply it on every path (fused ones too).
 *
 * ⚠ During the copy the import makes anyway, from a stream: no second pass over 12 GB and no second copy on disk.
 */
object Fp8Widen {

    private const val F8 = "F8_E4M3"
    private const val E5M2 = "F8_E5M2"

    /** What the copy does to one tensor. */
    internal enum class Act { F32, E4M3 }

    /** ⚠ ComfyUI's scaled-fp8 marker: the engine never reads it, so it is left exactly as it was. */
    private const val MARKER = "scaled_fp8"

    /** ⚠ Larger than any real checkpoint header (tens of KB); anything bigger is not one. */
    private const val MAX_HEADER = 64L shl 20

    /** ⭐ Whether an fp8 tensor of this [shape] becomes F32: everything but a matmul weight the HTP's fp8 path takes. */
    internal fun widens(name: String, dtype: String, shape: List<Long>): Boolean = actOf(name, dtype, shape) == Act.F32

    /** ⭐ [Act] for one tensor, or null to copy it as it is. */
    internal fun actOf(name: String, dtype: String, shape: List<Long>): Act? {
        if (name == MARKER || (dtype != F8 && dtype != E5M2)) return null
        val matmul = shape.size == 2 && shape[1] % 32 == 0L && shape[1] > 0 && shape[0] >= 32
        return when {
            !matmul -> Act.F32
            dtype == E5M2 -> Act.E4M3
            else -> null
        }
    }

    /**
     * ⭐ Copies a `.safetensors` from [input] to [out], widening what [widens] says. Returns how many tensors it
     * widened; 0 means the bytes went across unchanged. [onBytes] gets the bytes READ so far.
     */
    fun copy(input: InputStream, out: OutputStream, onBytes: (Long) -> Unit = {}, isCancelled: () -> Boolean = { false }): Int {
        val lenBytes = ByteArray(8)
        if (input.readUpTo(lenBytes) != 8) throw EOFException("this file is too small to be a checkpoint")
        val headerLen = ByteBuffer.wrap(lenBytes).order(ByteOrder.LITTLE_ENDIAN).long
        if (headerLen !in 2..MAX_HEADER) throw java.io.IOException("this is not a .safetensors file (its header claims $headerLen bytes)")
        val headerBytes = ByteArray(headerLen.toInt())
        if (input.readUpTo(headerBytes).toLong() != headerLen) throw EOFException("the file ends inside its header")
        var read = 8L + headerLen
        val header = JSONObject(String(headerBytes, Charsets.UTF_8))
        val entries = header.keys().asSequence().filter { it != "__metadata__" }
            .mapNotNull { k -> header.optJSONObject(k)?.let { k to it } }.toList()
        // ⚠ Decided BEFORE the offsets are read: a file with nothing to widen is copied as it is, whatever it holds.
        // ⚠ A tensor that states no shape is left alone: the engine decides (`docs/DIT.md` §2e, never refuse on a guess).
        val acts = entries.filter { (_, t) -> t.has("shape") }
            .mapNotNull { (n, t) -> actOf(n, t.optString("dtype"), shapeOf(t))?.let { n to it } }.toMap()
        val wide = acts.keys

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
            // ⚠ Checked before anything is written: `e < b` made a negative size, and the copy below an array of it.
            if (b < 0 || e < b) throw java.io.IOException("tensor '$name' has a broken place in the file ($b..$e)")
            val size = if (acts[name] == Act.F32) (e - b) * 4 else e - b
            newHeader.put(name, JSONObject().apply {
                put("dtype", when (acts[name]) { Act.F32 -> "F32"; Act.E4M3 -> F8; null -> t.getString("dtype") })
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
                input.skipExactly(b - pos)
                read += b - pos
            }
            val act = acts[name]
            if (act != null) {
                val e5m2 = t.optString("dtype") == E5M2
                // ⚠ In slabs (one byte per element), never the whole tensor: a big one plus its 4x F32 copy was an
                // OutOfMemoryError, an Error the import's `catch (e: Exception)` does not see.
                var left = e - b
                while (left > 0) {
                    if (isCancelled()) throw ModelInstaller.Cancelled()
                    val n = minOf(left, buf.size.toLong()).toInt()
                    if (input.readUpTo(buf, 0, n) != n) throw EOFException("the file ends inside '$name'")
                    if (act == Act.F32) out.write(toF32(buf, n, e5m2))
                    else { for (i in 0 until n) buf[i] = E5M2_TO_E4M3[buf[i].toInt() and 0xFF]; out.write(buf, 0, n) }
                    left -= n
                    read += n
                    onBytes(read)
                }
            } else pump(e - b)
            pos = e
        }
        return wide.size
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

    /** E5M2, all 256 codes: the top byte of an IEEE fp16 (infinities and NaNs included). */
    private val LUT_E5M2 = FloatArray(256) { code ->
        val sign = if (code and 0x80 != 0) -1f else 1f
        val exp = (code shr 2) and 0x1F
        val man = code and 0x3
        when {
            exp == 0x1F -> if (man == 0) sign * Float.POSITIVE_INFINITY else Float.NaN
            exp == 0 -> sign * (man / 4f) * Math.scalb(1f, -14)
            else -> sign * (1f + man / 4f) * Math.scalb(1f, exp - 15)
        }
    }

    /**
     * ⭐ Each E5M2 code's nearest E4M3 code: ties to the even code, past ±448 saturated to ±448 (E4M3 fn has no
     * infinity), NaN to NaN. Searched over all 256 E4M3 codes once.
     */
    internal val E5M2_TO_E4M3 = ByteArray(256) { code ->
        val v = LUT_E5M2[code]
        when {
            v.isNaN() -> 0x7F.toByte()
            v.isInfinite() || kotlin.math.abs(v) >= 448f -> (if (v < 0) 0xFE else 0x7E).toByte()
            else -> {
                var best = 0
                var bestErr = Float.MAX_VALUE
                for (c in 0 until 256) {
                    val w = LUT[c]
                    if (w.isNaN()) continue
                    // ⚠ The sign kept for zero: -0 stays -0.
                    if ((c and 0x80 != 0) != (code and 0x80 != 0)) continue
                    val err = kotlin.math.abs(w - v)
                    if (err < bestErr || (err == bestErr && c % 2 == 0)) { best = c; bestErr = err }
                }
                best.toByte()
            }
        }
    }

    /** ⭐ fp8 bytes (E4M3, or E5M2 when [e5m2]) as little-endian `F32`. */
    internal fun toF32(raw: ByteArray, count: Int = raw.size, e5m2: Boolean = false): ByteArray {
        val dst = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
        val lut = if (e5m2) LUT_E5M2 else LUT
        for (i in 0 until count) dst.putFloat(lut[raw[i].toInt() and 0xFF])
        return dst.array()
    }
}
