package com.abrah.nightmare.npu

import android.content.Context
import org.json.JSONObject
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The host-side weights and constants, loaded from APK assets.
 *
 * Only what genuinely cannot live in a converted graph is here. The sinusoidal
 * projections were hoisted out of the graphs deliberately -- quantising a `Sin` at its
 * argument (encoded over [0,1000] at 16 bits) caps accuracy around 47 dB and cost the
 * MMDiT 8.41 dB -- so they are recomputed in Kotlin instead. See export_app_assets.py.
 */
class Tensor(val dims: IntArray, val data: FloatArray) {
    val rows get() = dims[0]
    val cols get() = if (dims.size > 1) dims[1] else 1
}

object AppAssets {

    lateinit var config: JSONObject; private set
    private lateinit var weights: Map<String, Tensor>

    @Volatile private var loaded = false

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        config = JSONObject(NpuFiles.asset(ctx, "pipeline_config.json").bufferedReader().readText())
        // Both weight blobs share one namespace: SSD1B's add_embedding for the first
        // frame, and the MMDiT's time_text_embed for the video path. Their tensor names
        // do not collide.
        weights = readNdw(ctx, "ssd1b_addembed.ndw") + readNdw(ctx, "mmdit_temb.ndw")
        loaded = true
    }

    operator fun get(name: String): Tensor =
        weights[name] ?: error("missing weight $name")

    private fun readNdw(ctx: Context, name: String): Map<String, Tensor> {
        NpuFiles.asset(ctx, name).use { raw ->
            val ins = DataInputStream(raw.buffered())
            val magic = ByteArray(4).also { ins.readFully(it) }
            check(String(magic) == "NDW1") { "$name: bad magic" }
            val count = ins.readLeInt()
            val out = LinkedHashMap<String, Tensor>(count)
            repeat(count) {
                val nb = ByteArray(ins.readLeInt()).also { ins.readFully(it) }
                val tName = String(nb, Charsets.UTF_8)
                val ndim = ins.readLeInt()
                val dims = IntArray(ndim) { ins.readLeInt() }
                var n = 1
                dims.forEach { n *= it }
                val bytes = ByteArray(n * 4).also { ins.readFully(it) }
                val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val data = FloatArray(n).also { fb.get(it) }
                out[tName] = Tensor(dims, data)
            }
            return out
        }
    }

    private fun DataInputStream.readLeInt(): Int {
        val b = ByteArray(4).also { readFully(it) }
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
    }
}

/** y = x @ W^T + b, with W stored [out, in] as PyTorch does. */
fun linear(x: FloatArray, w: Tensor, b: Tensor): FloatArray {
    val outN = w.dims[0]
    val inN = w.dims[1]
    require(x.size == inN) { "linear: expected $inN inputs, got ${x.size}" }
    val y = FloatArray(outN)
    for (o in 0 until outN) {
        var acc = 0.0
        val base = o * inN
        for (i in 0 until inN) acc += w.data[base + i].toDouble() * x[i]
        y[o] = (acc + b.data[o]).toFloat()
    }
    return y
}

fun silu(x: FloatArray): FloatArray =
    FloatArray(x.size) { (x[it] / (1.0 + Math.exp(-x[it].toDouble()))).toFloat() }
