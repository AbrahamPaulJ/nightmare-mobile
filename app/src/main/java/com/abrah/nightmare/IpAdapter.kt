package com.abrah.nightmare

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.abrah.nightmare.segment.IdleRelease
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.security.MessageDigest

/**
 * ⭐⭐ **IP-Adapter** for SD 1.5 Swap v2: a REFERENCE PICTURE steers the render
 * (its subject, style, face) the way the prompt does. The user's design,
 * 2026-09-30 (npuforge `notes/2026-09-30-ip-adapter-template.md`).
 *
 * ⭐⭐⭐ **The template is adapter-agnostic.** A Swap v2 UNet takes, per
 * cross-attention layer, the image prompt's K and V as INPUTS (`ipk_i`
 * [1, inner, 16], `ipv_i` [1, 16, inner]) — so everything adapter-specific
 * happens HERE, on the CPU, once per picture:
 *
 *     picture → CLIP ViT-H/14 (penultimate hidden state) → the adapter's head
 *     (Resampler → 16 tokens → each layer's to_k_ip / to_v_ip) → K, V
 *
 * The head is a small per-adapter download ([HEADS]); the encoder is shared.
 * The IP scale multiplies V (softmax·(sV) = s·softmax·V), so the graph has no
 * scale input. A 4-token adapter would fit by repeating each token 4× (exact).
 *
 * ⚠⚠ The CFG uncond pass gets the ZEROS-IMAGE K/V ([negDir]), not zeros and not
 * the reference's: diffusers' IP-Adapter does the same, and sharing the
 * reference's would cancel most of its effect in the guidance difference.
 * The backend binds `neg/` before the uncond pass and `pos/` before the cond
 * pass (`backend-patches/016`).
 */
object IpAdapter {

    private const val TAG = "IpAdapter"

    const val ID = "ipadapter"

    /** In every Swap v2 model folder (npuforge writes it): the layers, in the UNet's input order. */
    const val TARGETS_FILE = "ip_targets.json"

    const val PLUS = "plus"
    const val FACE = "face"

    /** ⭐ What the node's chooser offers; Plus first — the general one. */
    val ADAPTERS = listOf(PLUS, FACE)

    /** The token slots the template was built with. */
    const val TOKENS = 16

    /** CLIP's input edge and normalisation (CLIPImageProcessor's defaults). */
    private const val EDGE = 224
    private val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
    private val STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
    private const val THREADS = 4

    /** ⚠ Bumped when a hosted file changes, so an old download is refetched. */
    const val REVISION = 1

    private const val BASE = "https://huggingface.co/AbrahamPJ/nightmare-ip-adapter/resolve/main/"

    class Part(val file: String, val bytes: Long) {
        val url get() = BASE + file
    }

    /**
     * ViT-H/14 truncated to its penultimate layer, **int16 weights** behind
     * `DequantizeLinear`, fp32 compute. ⚠⚠ Measured 2026-09-30 against fp32, Plus
     * K/V worst cosine over 12 pictures + the zeros image: dynamic int8 0.838, int8
     * without fc2 0.931, 4-bit weight-only 0.922, int8 weight-only per channel
     * 0.991, per 64-block 0.9925 — ViT-H's activation outliers; the Plus Resampler
     * amplifies them. int16: 0.99999995, 1.17 GB, 1.39 GB peak RSS on the PC.
     * fp16 weights behind `Cast` were as exact but peaked at 3.3 GB: ORT runs every
     * Cast up front, while `DequantizeLinear` weights are expanded layer by layer.
     */
    val ENCODER = Part("clip_vit_h_w16qdq.onnx", 1_227_799_347L)

    /** The heads are int8 weight-only: worst K/V cosine 0.99964 (Plus), 0.99991 (Face). */
    val HEADS = mapOf(
        PLUS to Part("ip_plus_head_w8qdq.onnx", 49_514_510L),
        FACE to Part("ip_face_head_w8qdq.onnx", 49_514_510L),
    )

    fun dir(context: Context): File = File(BackendProcess.modelsDir(context), ID)

    private fun file(context: Context, part: Part) = File(dir(context), part.file)

    private fun stamp(context: Context) = File(dir(context), ".rev")

    /** ⭐ Does this Swap model take a reference picture? Only a v2 conversion does. */
    fun supports(modelDir: File): Boolean = File(modelDir, TARGETS_FILE).isFile

    fun isInstalled(context: Context, adapter: String): Boolean {
        val head = HEADS[adapter] ?: return false
        return file(context, ENCODER).length() == ENCODER.bytes &&
            file(context, head).length() == head.bytes &&
            runCatching { stamp(context).readText().trim().toInt() }.getOrDefault(0) >= REVISION
    }

    /** The download still missing for [adapter], in bytes (the encoder is shared). */
    fun bytesToFetch(context: Context, adapter: String): Long {
        val head = HEADS[adapter] ?: return 0L
        return listOf(ENCODER, head).filter { file(context, it).length() != it.bytes }.sumOf { it.bytes }
    }

    fun bytesOnDisk(context: Context): Long = dir(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Fetch the encoder (when missing) and [adapter]'s head. ⚠ Blocking — off the main thread. */
    fun install(
        context: Context,
        adapter: String,
        onProgress: (ModelInstaller.Progress) -> Unit,
        isCancelled: () -> Boolean = { false },
    ) {
        val head = HEADS[adapter] ?: throw IllegalArgumentException("unknown IP-Adapter \"$adapter\"")
        dir(context).mkdirs()
        for (part in listOf(ENCODER, head)) {
            val target = file(context, part)
            if (target.length() == part.bytes) continue
            val tmp = File(target.path + ".part")
            UpscalerCatalog.download(part.url, tmp, part.bytes, onProgress, isCancelled)
            if (tmp.length() != part.bytes) {
                val got = tmp.length()
                tmp.delete()
                throw IOException("IP-Adapter: downloaded $got bytes, expected ${part.bytes} from ${part.url}")
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
        stamp(context).writeText(REVISION.toString())
        Log.i(TAG, "installed IP-Adapter $adapter (${bytesOnDisk(context)} bytes)")
    }

    fun delete(context: Context) {
        close()
        dir(context).deleteRecursively()
    }

    /** ⭐ One adapter's head; the shared encoder goes with the last one. */
    fun delete(context: Context, adapter: String) {
        close()
        HEADS[adapter]?.let { file(context, it).delete() }
        File(dir(context), "neg_$adapter").deleteRecursively()
        if (HEADS.values.none { file(context, it).isFile }) dir(context).deleteRecursively()
    }

    fun label(adapter: String) = when (adapter) {
        FACE -> "IP-Adapter Plus Face"
        else -> "IP-Adapter Plus"
    }

    /**
     * ⭐ Its key among SD 1.5 Swap's downloads (`HarnessViewModel.cnRows`),
     * beside the ControlNet types — one list, one install latch, one Tools section.
     */
    const val ROW_PREFIX = "ip_"

    fun rowKey(adapter: String) = ROW_PREFIX + adapter

    // ---- the open models --------------------------------------------------

    private var env: OrtEnvironment? = null
    private var encoder: OrtSession? = null
    private val heads = HashMap<String, OrtSession>()

    @Synchronized
    private fun sessions(context: Context, adapter: String): Pair<OrtSession, OrtSession> {
        val e = env ?: OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR, "nightmare-ipadapter")
            .also { env = it }
        fun open(part: Part) = e.createSession(
            file(context, part).absolutePath,
            OrtSession.SessionOptions().apply { setIntraOpNumThreads(THREADS) },
        )
        val enc = encoder ?: open(ENCODER).also { encoder = it }
        val head = heads[adapter] ?: open(HEADS.getValue(adapter)).also { heads[adapter] = it }
        return enc to head
    }

    @Synchronized
    private fun releaseModels() {
        if (encoder == null && heads.isEmpty()) return
        runCatching { encoder?.close() }
        heads.values.forEach { runCatching { it.close() } }
        encoder = null
        heads.clear()
        Log.i(TAG, "models released (idle or low memory)")
    }

    private val idle = IdleRelease(IdleRelease.IDLE_MS) { releaseModels() }

    fun trim() = idle.releaseNow()

    @Synchronized
    fun close() = releaseModels()

    /**
     * ⭐ CLIPImageProcessor, the part that matters: shortest edge to 224, centre
     * crop 224², normalised, CHW. ⚠ Halved in steps first — one bilinear jump
     * from a 3000 px photo to 224 samples 4 pixels of every 180 and aliases.
     */
    internal fun pixels(src: Bitmap): FloatArray {
        val px = IntArray(EDGE * EDGE)
        square(src).getPixels(px, 0, EDGE, 0, 0, EDGE, EDGE)
        val n = EDGE * EDGE
        val chw = FloatArray(3 * n)
        for (i in 0 until n) {
            val c = px[i]
            chw[i] = (((c shr 16) and 0xFF) / 255f - MEAN[0]) / STD[0]
            chw[n + i] = (((c shr 8) and 0xFF) / 255f - MEAN[1]) / STD[1]
            chw[2 * n + i] = ((c and 0xFF) / 255f - MEAN[2]) / STD[2]
        }
        return chw
    }

    /** ⭐ The 224² square the encoder reads — what the node's tile shows. */
    fun square(src: Bitmap): Bitmap {
        var b = src
        while (minOf(b.width, b.height) >= EDGE * 2) {
            b = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
        }
        val s = EDGE.toFloat() / minOf(b.width, b.height)
        val w = maxOf(EDGE, Math.round(b.width * s))
        val h = maxOf(EDGE, Math.round(b.height * s))
        val scaled = Bitmap.createScaledBitmap(b, w, h, true)
        return Bitmap.createBitmap(scaled, (w - EDGE) / 2, (h - EDGE) / 2, EDGE, EDGE)
    }

    /** Every `ipk_i` / `ipv_i` for [chw] (CLIP pixels), UNSCALED, keyed by input name. */
    private fun kv(context: Context, adapter: String, chw: FloatArray): Map<String, FloatArray> {
        val (enc, head) = sessions(context, adapter)
        val e = env!!
        val hidden = OnnxTensor.createTensor(e, FloatBuffer.wrap(chw), longArrayOf(1, 3, EDGE.toLong(), EDGE.toLong())).use { px ->
            enc.run(mapOf("pixels" to px)).use { r ->
                val t = r.get(0) as OnnxTensor
                FloatArray(t.info.shape.fold(1L) { a, d -> a * d }.toInt()).also { t.floatBuffer.get(it) } to t.info.shape
            }
        }
        return OnnxTensor.createTensor(e, FloatBuffer.wrap(hidden.first), hidden.second).use { h ->
            head.run(mapOf("hidden" to h)).use { r ->
                r.associate { (name, v) ->
                    val t = v as OnnxTensor
                    name to FloatArray(t.info.shape.fold(1L) { a, d -> a * d }.toInt()).also { t.floatBuffer.get(it) }
                }
            }
        }
    }

    private fun write(dir: File, values: Map<String, FloatArray>, scale: Float) {
        dir.mkdirs()
        for ((name, v) in values) {
            val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            val fb = bb.asFloatBuffer()
            if (name.startsWith("ipv_")) for (x in v) fb.put(x * scale) else fb.put(v)
            File(dir, "$name.raw").writeBytes(bb.array())
        }
    }

    /** ⚠ How many reference directories one model keeps; each is ~3 MB. */
    private const val KEEP = 6

    /** The last few pictures' unscaled K/V (~1.6 MB each), newest last. */
    private val recent = object : LinkedHashMap<String, Map<String, FloatArray>>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, FloatArray>>?) = size > 3
    }

    /**
     * ⭐⭐ The `ip_dir` for a render: `<model>/swap_ip/<key>/{pos,neg}/`, made on
     * first use. ⚠⚠ Named after its CONTENTS — the CLIP pixels, the adapter,
     * the scale and [REVISION] — because the backend keys a cached latent by
     * `ip_dir`'s PATH (patch 016), like `lora_dir`.
     */
    fun ipDir(
        context: Context,
        modelDir: File,
        picture: Bitmap,
        adapter: String,
        scale: Double,
        say: (String) -> Unit = {},
    ): File {
        require(adapter in HEADS) { "unknown IP-Adapter \"$adapter\"" }
        val chw = pixels(picture)
        val bb = ByteBuffer.allocate(chw.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.asFloatBuffer().put(chw)
        fun hash(tail: String): String = MessageDigest.getInstance("SHA-256").run {
            update(bb.array())
            update(tail.toByteArray())
            digest().take(8).joinToString("") { "%02x".format(it) }
        }
        val key = hash("|$adapter|$scale|$REVISION")
        val pictureKey = hash("|$adapter|$REVISION")
        val root = File(modelDir, "swap_ip")
        val out = File(root, key)
        val done = File(out, ".done")
        if (!done.isFile) {
            idle.begin()
            try {
                val t0 = System.nanoTime()
                val s = scale.toFloat()
                // ⭐ The UNSCALED K/V of this picture are kept, so moving the strength
                // slider rewrites 3 MB instead of re-running the encoder (~6 s on an S25).
                val pos = synchronized(recent) { recent[pictureKey] } ?: run {
                    say("reading the reference picture")
                    kv(context, adapter, chw).also { synchronized(recent) { recent[pictureKey] = it } }
                }
                write(File(out, "pos"), pos, s)
                write(File(out, "neg"), negFor(context, adapter), s)
                done.writeText(adapter)
                Log.i(TAG, "ip $adapter @$scale -> ${out.name} in ${(System.nanoTime() - t0) / 1_000_000} ms")
            } finally {
                idle.end()
            }
        }
        out.setLastModified(System.currentTimeMillis())
        root.listFiles { f -> f.isDirectory }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .drop(KEEP)
            .forEach { it.deleteRecursively() }
        return out
    }

    /**
     * The zeros-image K/V (the uncond side), UNSCALED — computed once per
     * adapter and kept beside the models, since it depends on nothing else.
     */
    private fun negFor(context: Context, adapter: String): Map<String, FloatArray> {
        val d = File(dir(context), "neg_$adapter")
        val done = File(d, ".done")
        if (!done.isFile) {
            write(d, kv(context, adapter, FloatArray(3 * EDGE * EDGE)), 1f)
            done.writeText(REVISION.toString())
        }
        return d.listFiles { f -> f.name.endsWith(".raw") }.orEmpty().associate { f ->
            val bb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            f.name.removeSuffix(".raw") to FloatArray(bb.remaining()).also { bb.get(it) }
        }
    }
}
