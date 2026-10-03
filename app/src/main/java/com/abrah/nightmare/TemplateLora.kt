package com.abrah.nightmare

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * ⭐⭐ Packs a LoRA `.safetensors` into a TEMPLATE UNet's LoRA inputs.
 *
 * A template UNet (npuforge `notes/2026-09-29-input-lora-cn-probe.md`) carries,
 * for each of its 160 attention/feed-forward Linears, a rank-[R] branch fed from
 * graph INPUTS: `delta = S[t] * ((x @ A_t) @ B_t)`, `A_t` [din, R], `B_t` [R, dout].
 * The backend reads them per request from a directory of `<input>.raw` files
 * (`lora_dir`, backend-patches 015), so swapping a LoRA is a file write, not a
 * conversion.
 *
 * A Linear applies `x @ W^T`, so `A B S = dW^T = (alpha/r) * D^T U^T` for kohya's
 * `lora_down` D [r, din] and `lora_up` U [dout, r]. The graph's A/B inputs have a
 * fixed ±1 window, so each is scaled to max|.| = 1 and `S = (alpha/r) * mA * mB`
 * carries the rest. ⚠ STRENGTH is not packed: the backend multiplies `lora_S` by
 * the request's `lora_strength`, so a strength change rewrites one 640-byte input.
 * r < R zero-pads; r > R keeps the best rank-R approximation ([truncate]).
 *
 * ⚠⚠ The target ORDER is the export's hook order, not a pattern worth
 * re-deriving here: it ships beside the UNet as [TARGETS_FILE] and is read, never
 * hardcoded. A reordered list would put every LoRA on the wrong layer and still
 * render — a picture that looks like a weak LoRA, not like a bug.
 *
 * Mirrors npuforge's `pack_lora.py` byte for byte (TemplateLoraTest).
 */
object TemplateLora {

    const val TARGETS_FILE = "lora_targets.json"

    data class Target(val name: String, val idx: Int, val din: Int, val dout: Int)

    data class Targets(val rank: Int, val list: List<Target>)

    /** What a pack did, for the log and the user. */
    data class Packed(
        val matched: Int,
        val total: Int,
        val loraRank: Int,
        val sMax: Float,
        /** UNet LoRA keys the template has no input for (conv / LoCon layers). */
        val unusedUnetKeys: Int,
        /** Text-encoder keys: the template's CLIP takes no LoRA, so they are dropped. */
        val textEncoderKeys: Int,
    )

    fun readTargets(json: String): Targets {
        val o = JSONObject(json)
        val arr = o.getJSONArray("targets")
        return Targets(o.getInt("rank"), (0 until arr.length()).map { i ->
            val t = arr.getJSONObject(i)
            Target(t.getString("name"), t.getInt("idx"), t.getInt("din"), t.getInt("dout"))
        })
    }

    /** The diffusers module path of a target: `...attn1.q` -> `...attn1.to_q`. */
    fun modulePath(name: String): String {
        val base = name.substringBeforeLast('.')
        val leaf = name.substringAfterLast('.')
        return if (base.endsWith(".ff")) {
            base + if (leaf == "proj") ".net.0.proj" else ".net.2"
        } else {
            base + when (leaf) {
                "q" -> ".to_q"
                "k" -> ".to_k"
                "v" -> ".to_v"
                "out" -> ".to_out.0"
                else -> throw IllegalArgumentException("unknown target leaf: $name")
            }
        }
    }

    /** kohya's key prefix: `lora_unet_down_blocks_0_..._attn1_to_q`. */
    fun kohyaPrefix(name: String): String = "lora_unet_" + modulePath(name).replace('.', '_')

    /**
     * Pack [lora] for [targets] into [outDir] (emptied of `.raw` files first — a
     * stale `la_7.raw` from the previous LoRA would be read as this one's).
     * A target the LoRA does not train gets no file: the backend zero-fills it.
     */
    fun pack(lora: File, targets: Targets, outDir: File): Packed =
        Safetensors(lora).use { st -> packFrom(st, targets, outDir) }

    private fun packFrom(st: Safetensors, targets: Targets, outDir: File): Packed {
        val keys = st.names
        outDir.mkdirs()
        outDir.listFiles { f -> f.name.endsWith(".raw") }?.forEach { it.delete() }

        val r0 = targets.rank
        val s = FloatArray(targets.list.size)
        var matched = 0
        var loraRank = 0
        val used = HashSet<String>()
        for (t in targets.list) {
            val (down, up, alphaKey) = findPair(t.name, keys) ?: continue
            used += down; used += up; alphaKey?.let { used += it }
            val dShape = st.shape(down)
            val uShape = st.shape(up)
            val r = dShape[0].toInt()
            val din = dShape.drop(1).fold(1L) { a, b -> a * b }.toInt()
            val dout = uShape[0].toInt()
            require(din == t.din && dout == t.dout && uShape[1].toInt() == r) {
                "${t.name}: LoRA shape [$r, $din] / [$dout, ${uShape[1]}] does not fit the " +
                    "template's [${t.din} -> ${t.dout}] -- a LoRA for another model family?"
            }
            loraRank = maxOf(loraRank, r)
            var d = st.floats(down)            // [rk, din]
            var u = st.floats(up)              // [dout, rk]
            val alpha = alphaKey?.let { st.floats(it)[0].toDouble() } ?: r.toDouble()
            var rk = r
            if (r > r0) {
                val (dt, ut) = truncate(d, u, r, t.din, t.dout, r0)
                d = dt; u = ut; rk = r0
            }
            val mA = d.maxOf { kotlin.math.abs(it) }
            val mB = u.maxOf { kotlin.math.abs(it) }
            if (mA == 0f || mB == 0f) continue
            val la = FloatArray(t.din * r0)
            for (j in 0 until rk) for (i in 0 until t.din) la[i * r0 + j] = d[j * t.din + i] / mA
            val lb = FloatArray(r0 * t.dout)
            for (j in 0 until rk) for (o in 0 until t.dout) lb[j * t.dout + o] = u[o * rk + j] / mB
            writeRaw(File(outDir, "la_${t.idx}.raw"), la)
            writeRaw(File(outDir, "lb_${t.idx}.raw"), lb)
            s[t.idx] = (alpha / r * mA.toDouble() * mB.toDouble()).toFloat()
            matched++
        }
        writeRaw(File(outDir, "lora_S.raw"), s)
        val unet = keys.count { (it.startsWith("lora_unet_") || it.startsWith("unet.")) && it !in used }
        val te = keys.count { it.startsWith("lora_te") || it.startsWith("text_encoder.") }
        return Packed(matched, targets.list.size, loraRank, s.maxOrNull() ?: 0f, unet, te)
    }

    /**
     * ⭐⭐ Several LoRAs into the ONE rank-R slot, strengths BAKED — so the
     * request's `lora_strength` is 1.0 for a merged pack.
     *
     * Per target the factors are stacked: `D = [s1 d1; s2 d2; …]` (each row
     * scaled by that LoRA's `alpha/r * strength`) and `U = [u1 u2 …]`, whose
     * product is the sum of the deltas exactly. Ranks summing to ≤ R are exact;
     * beyond that [truncate] keeps the best rank-R approximation of the SUM,
     * which is the optimum for the slot — truncating each LoRA first would not be.
     *
     * ⚠ Also the route for ONE LoRA at a negative strength: `lora_S` has a
     * [0, 0.25] window, so a sign can only live in the factors.
     *
     * Targets are packed in parallel; each writes only its own files and `S` slot.
     */
    fun packMerged(loras: List<Pair<File, Double>>, targets: Targets, outDir: File): Packed {
        require(loras.isNotEmpty())
        val sts = loras.map { Safetensors(it.first) }
        try {
            outDir.mkdirs()
            outDir.listFiles { f -> f.name.endsWith(".raw") }?.forEach { it.delete() }
            val r0 = targets.rank
            val s = FloatArray(targets.list.size)
            val matched = java.util.concurrent.atomic.AtomicInteger()
            val maxRank = java.util.concurrent.atomic.AtomicInteger()
            val used = sts.map { java.util.concurrent.ConcurrentHashMap.newKeySet<String>() }
            java.util.stream.IntStream.range(0, targets.list.size).parallel().forEach { ti ->
                val t = targets.list[ti]
                val ds = ArrayList<FloatArray>()
                val us = ArrayList<FloatArray>()
                val rs = ArrayList<Int>()
                for ((li, st) in sts.withIndex()) {
                    val (down, up, alphaKey) = findPair(t.name, st.names) ?: continue
                    used[li] += down; used[li] += up; alphaKey?.let { used[li] += it }
                    val dShape = st.shape(down)
                    val uShape = st.shape(up)
                    val r = dShape[0].toInt()
                    val din = dShape.drop(1).fold(1L) { a, b -> a * b }.toInt()
                    require(din == t.din && uShape[0].toInt() == t.dout && uShape[1].toInt() == r) {
                        "${t.name}: ${loras[li].first.name} does not fit the template's " +
                            "[${t.din} -> ${t.dout}] -- a LoRA for another model family?"
                    }
                    val alpha = alphaKey?.let { st.floats(it)[0].toDouble() } ?: r.toDouble()
                    val scale = (alpha / r * loras[li].second).toFloat()
                    if (scale == 0f) continue
                    ds += st.floats(down).also { d -> for (i in d.indices) d[i] *= scale }
                    us += st.floats(up)
                    rs += r
                }
                if (rs.isEmpty()) return@forEach
                val rTot = rs.sum()
                maxRank.accumulateAndGet(rTot, ::maxOf)
                var d = FloatArray(rTot * t.din)
                var u = FloatArray(t.dout * rTot)
                var off = 0
                for (k in rs.indices) {
                    val r = rs[k]
                    System.arraycopy(ds[k], 0, d, off * t.din, r * t.din)
                    for (o in 0 until t.dout) for (j in 0 until r) u[o * rTot + off + j] = us[k][o * r + j]
                    off += r
                }
                var rk = rTot
                if (rTot > r0) {
                    val (dt, ut) = truncate(d, u, rTot, t.din, t.dout, r0)
                    d = dt; u = ut; rk = r0
                }
                val mA = d.maxOf { kotlin.math.abs(it) }
                val mB = u.maxOf { kotlin.math.abs(it) }
                if (mA == 0f || mB == 0f) return@forEach
                val la = FloatArray(t.din * r0)
                for (j in 0 until rk) for (i in 0 until t.din) la[i * r0 + j] = d[j * t.din + i] / mA
                val lb = FloatArray(r0 * t.dout)
                for (j in 0 until rk) for (o in 0 until t.dout) lb[j * t.dout + o] = u[o * rk + j] / mB
                writeRaw(File(outDir, "la_${t.idx}.raw"), la)
                writeRaw(File(outDir, "lb_${t.idx}.raw"), lb)
                s[t.idx] = mA * mB
                matched.incrementAndGet()
            }
            writeRaw(File(outDir, "lora_S.raw"), s)
            var unet = 0
            var te = 0
            for ((li, st) in sts.withIndex()) {
                unet += st.names.count { (it.startsWith("lora_unet_") || it.startsWith("unet.")) && it !in used[li] }
                te += st.names.count { it.startsWith("lora_te") || it.startsWith("text_encoder.") }
            }
            return Packed(matched.get(), targets.list.size, maxRank.get(), s.maxOrNull() ?: 0f, unet, te)
        } finally {
            sts.forEach { it.close() }
        }
    }

    /**
     * The best rank-[k] approximation of `D^T U^T` (Eckart–Young), as a new
     * down [k, din] / up [dout, k] pair: thin QR of `D^T` and of `U`, an SVD of
     * the small `r x r` core `Rd Ru^T`, then the top [k] singular pairs split as
     * sqrt(sigma) on each side. Doubles throughout. The FACTORS are not unique
     * (signs, rotations inside equal singular values) but their product is, so
     * this matches `pack_lora.py`'s torch path on the product, not the bytes.
     */
    private fun truncate(
        d: FloatArray, u: FloatArray, r: Int, din: Int, dout: Int, k: Int,
    ): Pair<FloatArray, FloatArray> {
        // D^T: [din, r] column-major by rank (column j = row j of D); U: [dout, r].
        val a = Array(r) { j -> DoubleArray(din) { i -> d[j * din + i].toDouble() } }
        val b = Array(r) { j -> DoubleArray(dout) { o -> u[o * r + j].toDouble() } }
        val ra = thinQr(a)                 // a becomes Q_d (columns), ra = R_d [r, r]
        val rb = thinQr(b)                 // b becomes Q_u, rb = R_u
        // core = R_d R_u^T
        val core = Array(r) { i -> DoubleArray(r) { j -> (0 until r).sumOf { l -> ra[i][l] * rb[j][l] } } }
        val (left, sigma, right) = jacobiSvd(core)   // core = left diag(sigma) right^T, sorted
        val newD = FloatArray(k * din)
        val newU = FloatArray(dout * k)
        // Accumulate whole columns (contiguous) rather than dotting across the r arrays.
        val accD = DoubleArray(din)
        val accU = DoubleArray(dout)
        for (c in 0 until k) {
            val s = kotlin.math.sqrt(sigma[c])
            accD.fill(0.0); accU.fill(0.0)
            for (l in 0 until r) {
                val cl = left[l][c]
                val al = a[l]
                for (i in 0 until din) accD[i] += al[i] * cl
                val cr = right[l][c]
                val bl = b[l]
                for (o in 0 until dout) accU[o] += bl[o] * cr
            }
            for (i in 0 until din) newD[c * din + i] = (accD[i] * s).toFloat()
            for (o in 0 until dout) newU[o * k + c] = (accU[o] * s).toFloat()
        }
        return newD to newU
    }

    /** Modified Gram-Schmidt, twice (re-orthogonalised). Columns in place -> Q; returns R. */
    private fun thinQr(cols: Array<DoubleArray>): Array<DoubleArray> {
        val n = cols.size
        val r = Array(n) { DoubleArray(n) }
        for (j in 0 until n) {
            repeat(2) {
                for (i in 0 until j) {
                    var dot = 0.0
                    for (x in cols[j].indices) dot += cols[i][x] * cols[j][x]
                    r[i][j] += dot
                    for (x in cols[j].indices) cols[j][x] -= dot * cols[i][x]
                }
            }
            var nrm = 0.0
            for (v in cols[j]) nrm += v * v
            nrm = kotlin.math.sqrt(nrm)
            r[j][j] = nrm
            if (nrm > 0) for (x in cols[j].indices) cols[j][x] /= nrm
        }
        return r
    }

    /**
     * One-sided Jacobi SVD of a square matrix (rows = m[i]). Returns (U, sigma, V)
     * with sigma descending, U and V as [row][column].
     */
    private fun jacobiSvd(m: Array<DoubleArray>): Triple<Array<DoubleArray>, DoubleArray, Array<DoubleArray>> {
        val n = m.size
        // Stored as COLUMNS (w[col][row]) so every rotation walks contiguous memory.
        val w = Array(n) { c -> DoubleArray(n) { r -> m[r][c] } }
        val v = Array(n) { c -> DoubleArray(n).also { it[c] = 1.0 } }
        for (sweep in 0 until 60) {
            var off = 0.0
            for (p in 0 until n - 1) for (q in p + 1 until n) {
                val wp = w[p]; val wq = w[q]
                var alpha = 0.0; var beta = 0.0; var gamma = 0.0
                for (i in 0 until n) {
                    alpha += wp[i] * wp[i]; beta += wq[i] * wq[i]; gamma += wp[i] * wq[i]
                }
                if (gamma == 0.0) continue
                off = maxOf(off, kotlin.math.abs(gamma) / kotlin.math.sqrt(alpha * beta))
                val zeta = (beta - alpha) / (2 * gamma)
                val t = kotlin.math.sign(zeta).let { if (it == 0.0) 1.0 else it } /
                    (kotlin.math.abs(zeta) + kotlin.math.sqrt(1 + zeta * zeta))
                val c = 1 / kotlin.math.sqrt(1 + t * t)
                val s = c * t
                val vp = v[p]; val vq = v[q]
                for (i in 0 until n) {
                    val a = wp[i]; val b = wq[i]
                    wp[i] = c * a - s * b; wq[i] = s * a + c * b
                    val e = vp[i]; val f = vq[i]
                    vp[i] = c * e - s * f; vq[i] = s * e + c * f
                }
            }
            if (off < 1e-12) break
        }
        val sigma = DoubleArray(n) { j -> kotlin.math.sqrt(w[j].sumOf { it * it }) }
        val order = (0 until n).sortedByDescending { sigma[it] }
        val uOut = Array(n) { i -> DoubleArray(n) { c -> val j = order[c]; if (sigma[j] > 0) w[j][i] / sigma[j] else 0.0 } }
        val vOut = Array(n) { i -> DoubleArray(n) { c -> v[order[c]][i] } }
        return Triple(uOut, DoubleArray(n) { sigma[order[it]] }, vOut)
    }

    /** (down, up, alpha?) keys for a target, kohya first, then diffusers/PEFT. */
    /**
     * ⭐ The LDM / SGM block path of a diffusers target, or null: many SDXL LoRAs (kohya on SGM
     * checkpoints) name `lora_unet_input_blocks_4_1_transformer_blocks_0_attn1_to_q` where the
     * diffusers form is `..._down_blocks_1_attentions_0_...`. One formula holds for SD 1.5 and
     * SDXL: down block b, attention a -> input_blocks.{3b+a+1}.1; up -> output_blocks.{3b+a}.1;
     * mid -> middle_block.1. Mirrors npuforge's SDXL packer (both naming forms, rel 1.9e-6).
     */
    internal fun sgmPath(name: String): String? {
        val m = modulePath(name)
        Regex("""^down_blocks\.(\d+)\.attentions\.(\d+)\.(.+)$""").find(m)?.let { r ->
            val (b, a, rest) = r.destructured
            return "input_blocks.${3 * b.toInt() + a.toInt() + 1}.1.$rest"
        }
        Regex("""^up_blocks\.(\d+)\.attentions\.(\d+)\.(.+)$""").find(m)?.let { r ->
            val (b, a, rest) = r.destructured
            return "output_blocks.${3 * b.toInt() + a.toInt()}.1.$rest"
        }
        return if (m.startsWith("mid_block.attentions.0.")) "middle_block.1." + m.removePrefix("mid_block.attentions.0.")
        else null
    }

    private fun findPair(name: String, keys: Set<String>): Triple<String, String, String?>? {
        val k = kohyaPrefix(name)
        if ("$k.lora_down.weight" in keys && "$k.lora_up.weight" in keys) {
            return Triple("$k.lora_down.weight", "$k.lora_up.weight", "$k.alpha".takeIf { it in keys })
        }
        sgmPath(name)?.let { sgm ->
            val s = "lora_unet_" + sgm.replace('.', '_')
            if ("$s.lora_down.weight" in keys && "$s.lora_up.weight" in keys) {
                return Triple("$s.lora_down.weight", "$s.lora_up.weight", "$s.alpha".takeIf { it in keys })
            }
        }
        val m = modulePath(name)
        for (p in listOf("unet.$m", "base_model.model.$m")) {
            if ("$p.lora_A.weight" in keys && "$p.lora_B.weight" in keys) {
                return Triple("$p.lora_A.weight", "$p.lora_B.weight", "$p.alpha".takeIf { it in keys })
            }
        }
        return null
    }

    private fun writeRaw(f: File, a: FloatArray) {
        val bb = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.asFloatBuffer().put(a)
        f.outputStream().use { it.write(bb.array()) }
    }

    /**
     * A safetensors file: an 8-byte little-endian header length, that JSON
     * (`name -> {dtype, shape, data_offsets}`), then the data. F32, F16 and BF16.
     */
    internal class Safetensors(file: File) : java.io.Closeable {
        private val raf = RandomAccessFile(file, "r")

        override fun close() = raf.close()
        private val header: JSONObject
        private val dataStart: Long

        init {
            val lenBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            raf.channel.read(lenBuf, 0)
            val len = lenBuf.getLong(0)
            require(len in 2..(64L * 1024 * 1024)) { "not a .safetensors file (header claims $len bytes)" }
            val hb = ByteArray(len.toInt())
            raf.seek(8)
            raf.readFully(hb)
            header = JSONObject(String(hb, Charsets.UTF_8))
            dataStart = 8 + len
        }

        val names: Set<String> = header.keys().asSequence().filter { it != "__metadata__" }.toSet()

        fun shape(name: String): List<Long> {
            val a = header.getJSONObject(name).getJSONArray("shape")
            return (0 until a.length()).map { a.getLong(it) }
        }

        fun floats(name: String): FloatArray {
            val t = header.getJSONObject(name)
            val off = t.getJSONArray("data_offsets")
            val begin = off.getLong(0)
            val n = (off.getLong(1) - begin).toInt()
            val bb = raf.channel.map(FileChannel.MapMode.READ_ONLY, dataStart + begin, n.toLong())
                .order(ByteOrder.LITTLE_ENDIAN)
            return when (val dt = t.getString("dtype")) {
                "F32" -> FloatArray(n / 4).also { bb.asFloatBuffer().get(it) }
                "F16" -> FloatArray(n / 2) { halfToFloat(bb.getShort(it * 2)) }
                "BF16" -> FloatArray(n / 2) { Float.fromBits((bb.getShort(it * 2).toInt() and 0xFFFF) shl 16) }
                // ⚠⚠ Reported 2026-10-01 (a character LoRA): `….alpha: dtype I64
                // is not supported`. Some trainers save each module's `alpha` as
                // an INTEGER scalar; it is a number like any other here.
                "F64" -> FloatArray(n / 8) { bb.getDouble(it * 8).toFloat() }
                "I64" -> FloatArray(n / 8) { bb.getLong(it * 8).toFloat() }
                "I32" -> FloatArray(n / 4) { bb.getInt(it * 4).toFloat() }
                "I16" -> FloatArray(n / 2) { bb.getShort(it * 2).toFloat() }
                "I8" -> FloatArray(n) { bb.get(it).toFloat() }
                "U8" -> FloatArray(n) { (bb.get(it).toInt() and 0xFF).toFloat() }
                "F8_E4M3" -> FloatArray(n) { fp8e4m3(bb.get(it)) }
                "F8_E5M2" -> FloatArray(n) { halfToFloat(((bb.get(it).toInt() and 0xFF) shl 8).toShort()) }
                else -> throw IllegalArgumentException("$name: dtype $dt is not supported")
            }
        }

        /** FP8 E4M3FN: bias 7, no infinities, 0x7F/0xFF are NaN. */
        private fun fp8e4m3(b: Byte): Float {
            val v = b.toInt() and 0xFF
            val sign = if (v and 0x80 != 0) -1f else 1f
            val exp = (v ushr 3) and 0xF
            val mant = v and 0x7
            if (exp == 0xF && mant == 0x7) return Float.NaN
            return sign * if (exp == 0) mant / 8f * (1f / 64f)
            else (1f + mant / 8f) * Math.scalb(1f, exp - 7)
        }

        private fun halfToFloat(h: Short): Float {
            val bits = h.toInt() and 0xFFFF
            val sign = (bits ushr 15) shl 31
            val exp = (bits ushr 10) and 0x1F
            val mant = bits and 0x3FF
            return when (exp) {
                0 -> if (mant == 0) Float.fromBits(sign) else {
                    // subnormal: mant * 2^-24
                    val v = mant * (1.0f / (1 shl 24))
                    if (sign != 0) -v else v
                }
                0x1F -> Float.fromBits(sign or 0x7F800000 or (mant shl 13))
                else -> Float.fromBits(sign or ((exp + 112) shl 23) or (mant shl 13))
            }
        }
    }
}
