package com.abrah.nightmare.npu

import android.content.Context
import org.json.JSONObject
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The precomputed, prompt-independent structure of the AR video loop.
 *
 * Everything here depends only on (unit_index, stage): the padding envelope, which
 * history slices form the past conditions, the temporal RoPE tables, and the token
 * layout. It is computed once on the desktop against the reference implementation
 * (export_video_structure.py) rather than reimplemented in Kotlin, because a silent
 * off-by-one in any of those four would produce a plausible-looking video with no
 * indication of which one was wrong.
 *
 * The one genuinely prompt-dependent piece -- which of the 128 text positions are real --
 * is combined with this structure on device by [attnMask]. Shipping the 18 masks instead
 * would be 214 MB.
 */
object VideoStructure {

    lateinit var cfg: JSONObject; private set
    private lateinit var t: Map<String, Tensor>
    @Volatile private var loaded = false

    val numStages get() = cfg.getInt("num_stages")
    val startUnit get() = cfg.getInt("start_unit")
    val numUnits get() = cfg.getInt("num_latent_units")
    val textTokens get() = cfg.getInt("text_tokens")
    val latC get() = cfg.getInt("lat_c")
    val patch get() = cfg.getInt("patch")
    val maskFill get() = cfg.getDouble("mask_fill").toFloat()
    val gamma get() = cfg.getDouble("gamma").toFloat()

    @Synchronized
    /**
     * The frame size the video path animates, for the crop UI.
     *
     * Loads on demand: the crop dialog can open before any generation has run, so it
     * cannot assume `load()` has already happened.
     */
    fun cropWidth(ctx: Context): Int { load(ctx); return cfg.getInt("video_width") }
    fun cropHeight(ctx: Context): Int { load(ctx); return cfg.getInt("video_height") }

    /**
     * ⭐⭐ The frame size as a value a node can declare WITHOUT a Context.
     *
     * ⚠⚠ `NodeType.requiredInputSize` is asked by the CANVAS — while a
     * finger is still down on a wire — and it is handed a [Node], not a
     * platform context. So the crop's demand cannot call [cropWidth], which
     * has to read an asset off disk.
     *
     * ⚠ The literal is the value in `video_structure.json`, and it stops
     * being the answer the moment [load] has run: one fact, one home, and the
     * default is only in play before the video assets have ever been read.
     * ⚠⚠ A structure that disagreed would silently re-derive every crop in
     * the graph, so [Video] checks it rather than trusting it.
     */
    @Volatile
    var frameSize: Pair<Int, Int> = 512 to 320
        private set

    fun load(ctx: Context) {
        if (loaded) return
        cfg = JSONObject(NpuFiles.asset(ctx, "video_structure.json").bufferedReader().readText())
        t = readNdw(ctx, "video_structure.ndw")
        frameSize = cfg.getInt("video_width") to cfg.getInt("video_height")
        loaded = true
    }

    class Call(val meta: JSONObject, private val key: String) {
        val stage get() = meta.getInt("stage")
        val seqLen get() = meta.getInt("seq_len")
        val nImage get() = meta.getInt("n_image_tokens")
        val envShapes: List<IntArray> get() = meta.getJSONArray("env_shapes").let { a ->
            (0 until a.length()).map { i ->
                a.getJSONArray(i).let { s -> IntArray(3) { s.getInt(it) } }
            }
        }
        /** per envelope slot: (index into the real latent list, or -1; frames of front pad) */
        val padPlan: List<IntArray> get() = meta.getJSONArray("pad_plan").let { a ->
            (0 until a.length()).map { i ->
                a.getJSONArray(i).let { s -> IntArray(2) { s.getInt(it) } }
            }
        }
        /** history slices as (pyramid level, tBegin, tEnd) */
        val history: List<IntArray> get() = meta.getJSONArray("history").let { a ->
            (0 until a.length()).map { i ->
                a.getJSONArray(i).let { s -> IntArray(3) { s.getInt(it) } }
            }
        }
        val ropeCos get() = VideoStructure.t.getValue("$key.rope_cos")
        val ropeSin get() = VideoStructure.t.getValue("$key.rope_sin")
        val order get() = VideoStructure.t.getValue("$key.order").data
        val imageValid get() = VideoStructure.t.getValue("$key.image_valid").data
    }

    fun call(unit: Int, stage: Int): Call {
        val key = "u${unit}s$stage"
        return Call(cfg.getJSONObject("calls").getJSONObject(key), key)
    }

    /**
     * Rebuild `merge_input`'s additive attention mask for one call.
     *
     * mask[q,k] is 0 where attention is allowed and [maskFill] where it is not. Three
     * conditions, exactly as the reference composes them:
     *   1. token-id equality -- valid text and all image tokens share id 1, invalid text
     *      positions get id 0, so padded text attends only to padded text
     *   2. temporal causality -- order[q] >= order[k]
     *   3. padded image KEYS are hidden from every query
     *
     * The fill is finite (-100) rather than -inf on purpose: a fully-masked query row
     * would otherwise be NaN, and such rows do exist here (they belong to padding, and
     * the output slice discards them) -- trap #2.
     */
    fun attnMask(c: Call, textMask: FloatArray): FloatArray {
        val nText = textTokens
        val s = c.seqLen
        val order = c.order
        val imgValid = c.imageValid
        val fill = maskFill

        // token id per position: 1 = real, 0 = padded text
        val id = FloatArray(s)
        for (i in 0 until nText) id[i] = if (textMask[i] > 0f) 1f else 0f
        for (i in nText until s) id[i] = 1f

        // key validity: text keys are always addressable, image keys only where real
        val keyValid = BooleanArray(s)
        for (i in 0 until nText) keyValid[i] = true
        for (i in nText until s) keyValid[i] = imgValid[i - nText] > 0f

        val m = FloatArray(s * s)
        for (q in 0 until s) {
            val base = q * s
            val idq = id[q]
            val oq = order[q]
            for (k in 0 until s) {
                val ok = idq == id[k] && oq >= order[k] && keyValid[k]
                if (!ok) m[base + k] = fill
            }
        }
        return m
    }

    private fun readNdw(ctx: Context, name: String): Map<String, Tensor> {
        NpuFiles.asset(ctx, name).use { raw ->
            val ins = DataInputStream(raw.buffered())
            val magic = ByteArray(4).also { ins.readFully(it) }
            check(String(magic) == "NDW1") { "$name: bad magic" }
            val count = ins.readLeInt()
            val out = HashMap<String, Tensor>(count * 2)
            repeat(count) {
                val nb = ByteArray(ins.readLeInt()).also { ins.readFully(it) }
                val tName = String(nb, Charsets.UTF_8)
                val ndim = ins.readLeInt()
                val dims = IntArray(ndim) { ins.readLeInt() }
                var n = 1
                dims.forEach { n *= it }
                val bytes = ByteArray(n * 4).also { ins.readFully(it) }
                val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                out[tName] = Tensor(dims, FloatArray(n).also { fb.get(it) })
            }
            return out
        }
    }

    private fun DataInputStream.readLeInt(): Int {
        val b = ByteArray(4).also { readFully(it) }
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
    }
}
