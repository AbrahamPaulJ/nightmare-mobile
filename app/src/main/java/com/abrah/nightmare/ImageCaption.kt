package com.abrah.nightmare

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.abrah.nightmare.segment.IdleRelease
import java.io.File
import java.io.IOException
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * ⭐ One of the prompt box's describe models (`docs/FLORENCE.md`) — what Models →
 * Tools, the missing-model popup and [PictureModels] need of each, so the view
 * model keeps ONE map of rows over [Describe.Mode.models] (`translateRows`' shape).
 *
 * ⚠ Several files each, installed the way [PromptTranslate] installs: every file
 * at its exact size, `.part` then rename.
 */
interface DescribeModel {
    val id: String
    val label: String
    @get:androidx.annotation.StringRes
    val labelRes: Int
    val bytes: Long
    val installed: Boolean
    val installId: String get() = "describe:$id"
    fun refresh(context: Context)
    fun isInstalled(context: Context): Boolean
    fun bytesOnDisk(context: Context): Long
    fun install(context: Context, onProgress: (ModelInstaller.Progress) -> Unit, isCancelled: () -> Boolean = { false })
    fun delete(context: Context)
    fun trim()
}

/** ⭐ The files of one [DescribeModel]: a pinned Hugging Face base, repo path → exact size. */
internal class ModelFiles(val dirName: String, val base: String, val files: List<Pair<String, Long>>) {
    val bytes: Long = files.sumOf { it.second }

    fun dir(context: Context): File = File(BackendProcess.modelsDir(context), dirName)

    fun file(context: Context, path: String) = File(dir(context), path.substringAfterLast('/'))

    fun isInstalled(context: Context): Boolean = files.all { (p, size) -> file(context, p).length() == size }

    fun bytesOnDisk(context: Context): Long = dir(context).listFiles()?.sumOf { it.length() } ?: 0L

    fun install(context: Context, label: String, onProgress: (ModelInstaller.Progress) -> Unit, isCancelled: () -> Boolean) {
        dir(context).mkdirs()
        var before = 0L
        for ((path, size) in files) {
            val target = file(context, path)
            if (target.length() != size) {
                val part = File(target.parentFile, "${target.name}.part")
                val base0 = before
                UpscalerCatalog.download(base + path, part, size, { p ->
                    onProgress(ModelInstaller.Progress(p.phase, base0 + p.done, bytes))
                }, isCancelled)
                if (part.length() != size) {
                    val got = part.length()
                    part.delete()
                    throw IOException("$label: ${target.name} downloaded $got bytes, expected $size")
                }
                if (!part.renameTo(target)) { part.copyTo(target, overwrite = true); part.delete() }
            }
            before += size
        }
    }
}

/**
 * ⭐⭐ A picture into SENTENCES — Florence-2-base fine-tuned for SD prompts
 * (MiaoshouAI's PromptGen v2.0), on the CPU (`docs/FLORENCE.md`).
 *
 * ⚠⚠ **The vision encoder is fp32 and must stay fp32.** Measured 2026-10-08: int8
 * on the vision half invents things ("four photographs of a cat" for two kittens,
 * "a ghostly character"); int8 on the language half alone tracks fp32. ⇒ 548 MB,
 * not 275 — and plain Florence-2-base (1.6.106, int8 throughout) is what read as
 * "sooo basic".
 *
 * ⚠⚠ Peak ~1.5–2.3 GB (the 768² vision activations) ⇒ in [PictureModels], idles
 * out after [IdleRelease.IDLE_MS], and `HarnessOps.ensureBackendFor` trims it.
 */
object ImageCaption : DescribeModel {

    private const val TAG = "ImageCaption"

    override val id = "promptgen"
    override val label = "Describe (Florence-2 PromptGen)"
    override val labelRes = R.string.describe_promptgen_label

    /**
     * ⚠ Our own build, at a PINNED commit: PromptGen's weights transplanted into
     * onnx-community's Florence-2-base-ft graphs (`docs/FLORENCE.md` §6).
     */
    internal val FILES = ModelFiles(
        "promptgen",
        "https://huggingface.co/AbrahamPJ/florence2-promptgen-onnx/resolve/$PROMPTGEN_REV/",
        listOf(
            "onnx/vision_encoder.onnx" to 366_549_825L,
            "onnx/embed_tokens_quantized.onnx" to 39_390_433L,
            "onnx/encoder_model_quantized.onnx" to 43_651_492L,
            "onnx/decoder_model_merged_quantized.onnx" to 98_177_642L,
            "vocab.json" to 798_293L,
        ),
    )

    override val bytes: Long get() = FILES.bytes

    /**
     * The task prompts as token ids, made on the PC with the BART tokenizer (the
     * app has only [ByteLevel], the way back). PromptGen's processor turns
     * `<CAPTION>` / `<MORE_DETAILED_CAPTION>` into these sentences.
     */
    enum class Length(val promptIds: LongArray, val maxTokens: Int) {
        /** `<CAPTION>` — "What does the image describe?" */
        SHORT(longArrayOf(0, 2264, 473, 5, 2274, 6190, 116, 2), 96),
        /** `<MORE_DETAILED_CAPTION>` — "Describe with a paragraph what is shown in the image." */
        DETAILED(longArrayOf(0, 47066, 21700, 19, 10, 17818, 99, 16, 2343, 11, 5, 2274, 4, 2), 320),
    }

    override fun isInstalled(context: Context): Boolean = FILES.isInstalled(context)

    override fun bytesOnDisk(context: Context): Long = FILES.bytesOnDisk(context)

    @Volatile
    override var installed: Boolean = false
        private set

    override fun refresh(context: Context) {
        installed = isInstalled(context)
        // ⚠ 1.6.106 shipped plain Florence-2-base int8 into `florence2/`; PromptGen
        // replaced it before anyone but us installed it. Its 276 MB has no reader now.
        File(BackendProcess.modelsDir(context), "florence2").takeIf { it.isDirectory }?.deleteRecursively()
    }

    override fun install(context: Context, onProgress: (ModelInstaller.Progress) -> Unit, isCancelled: () -> Boolean) {
        FILES.install(context, context.getString(labelRes), onProgress, isCancelled)
        refresh(context)
        Log.i(TAG, "installed $label (${bytesOnDisk(context)} bytes)")
    }

    override fun delete(context: Context) {
        close()
        FILES.dir(context).deleteRecursively()
        refresh(context)
    }

    // ---- the open model ---------------------------------------------------

    private class Model(
        val env: OrtEnvironment,
        val vision: OrtSession,
        val embed: OrtSession,
        val encoder: OrtSession,
        val decoder: OrtSession,
        val vocab: Array<String?>,
    ) : AutoCloseable {
        override fun close() {
            vision.close(); embed.close(); encoder.close(); decoder.close()
        }
    }

    private var model: Model? = null

    @Synchronized
    private fun model(context: Context): Model? {
        model?.let { return it }
        if (!isInstalled(context)) return null
        val t = System.nanoTime()
        val env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR, "nightmare-caption")
        // ⚠ 4, as every CPU model here: measured, 6 was no faster (`docs/FLORENCE.md` §3).
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
        fun open(i: Int) = env.createSession(FILES.file(context, FILES.files[i].first).absolutePath, opts)
        val vocab = ByteLevel.vocab(FILES.file(context, FILES.files[4].first).readText())
        val m = Model(env, open(0), open(1), open(2), open(3), vocab)
        Log.i(TAG, "opened in ${(System.nanoTime() - t) / 1_000_000} ms")
        return m.also { model = it }
    }

    @Synchronized
    private fun releaseModel() {
        if (model == null) return
        model?.close()
        model = null
        Log.i(TAG, "model released (idle or low memory)")
    }

    private val idle = IdleRelease(IdleRelease.IDLE_MS) { releaseModel() }

    override fun trim() = idle.releaseNow()

    @Synchronized
    fun close() {
        model?.close()
        model = null
    }

    /**
     * ⭐⭐ [photo] in words. Null when the model is not installed.
     *
     * ⚠⚠ Blocking, ~3–5 s. Never from the main thread.
     */
    fun caption(context: Context, photo: Bitmap, length: Length): String? {
        idle.begin()
        try {
            val m = model(context) ?: return null
            val t = System.nanoTime()
            val ids = generate(m, photo, length)
            val text = ByteLevel.decode(ids, m.vocab)
            Log.i(TAG, "${length.name.lowercase()} ${ids.size} tokens in ${(System.nanoTime() - t) / 1_000_000} ms")
            return text
        } finally {
            idle.end()
        }
    }

    /**
     * Florence's own generation config, greedy: the first token forced to `<s>`
     * (`forced_bos_token_id`) and no 3-gram twice (`no_repeat_ngram_size`).
     * ⚠ Without the n-gram block the int8 exports can loop ("There are pictures
     * on the wall." ×12 — `docs/FLORENCE.md` §4). Beam search (3) is not used: 3×
     * the decode cost.
     */
    private fun generate(m: Model, photo: Bitmap, length: Length): LongArray {
        val env = m.env
        val prompt = length.promptIds
        val image = OnnxTensor.createTensor(env, pixels(photo), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { p ->
            m.vision.run(mapOf("pixel_values" to p)).use { r -> copy(env, r[0] as OnnxTensor) }
        }
        val text = embed(m, prompt)
        val seq = image.info.shape[1].toInt() + prompt.size
        val dim = image.info.shape[2]
        val joined = FloatBuffer.allocate(seq * dim.toInt()).put(image.floatBuffer).put(text.floatBuffer).apply { rewind() }
        image.close(); text.close()
        val mask = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(seq) { 1 }), longArrayOf(1, seq.toLong()))
        val hidden = OnnxTensor.createTensor(env, joined, longArrayOf(1, seq.toLong(), dim)).use { x ->
            m.encoder.run(mapOf("inputs_embeds" to x, "attention_mask" to mask)).use { r -> copy(env, r[0] as OnnxTensor) }
        }

        val kvNames = m.decoder.inputNames.filter { it.startsWith("past_key_values") }
        val kvShape = (m.decoder.inputInfo[kvNames[0]]!!.info as TensorInfo).shape
        val outNames = m.decoder.outputNames.toList()
        val past = HashMap<String, OnnxTensor>()
        kvNames.forEach { past[it] = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, kvShape[1], 0, kvShape[3])) }
        val out = mutableListOf(DECODER_START)
        try {
            for (step in 0 until length.maxTokens) {
                val e = embed(m, longArrayOf(out.last()))
                val branch = OnnxTensor.createTensor(env, booleanArrayOf(step > 0))
                val feed = HashMap<String, OnnxTensor>(past).apply {
                    put("inputs_embeds", e); put("encoder_hidden_states", hidden)
                    put("encoder_attention_mask", mask); put("use_cache_branch", branch)
                }
                val next = m.decoder.run(feed).use { r ->
                    val lg = (r[0] as OnnxTensor).floatBuffer
                    val logits = FloatArray(lg.capacity()).also { lg.get(it) }
                    for (j in 1 until outNames.size) {
                        val key = outNames[j].replace("present", "past_key_values")
                        // ⚠ The encoder's K/V is made once, at step 0; after that
                        // the cached branch returns it unchanged.
                        if (step == 0 || ".decoder." in key) past.put(key, copy(env, r[j] as OnnxTensor))?.close()
                    }
                    pickNext(logits, out, first = step == 0)
                }
                e.close(); branch.close()
                out += next
                if (next == EOS) break
            }
        } finally {
            past.values.forEach { it.close() }
            hidden.close(); mask.close()
        }
        return out.toLongArray()
    }

    /** The next token: forced `<s>` first, then argmax with no 3-gram repeated. Pure, for the tests. */
    internal fun pickNext(logits: FloatArray, out: List<Long>, first: Boolean): Long {
        if (first) return BOS
        if (out.size >= 3) {
            val a = out[out.size - 2]; val b = out[out.size - 1]
            for (i in 0..out.size - 3) {
                if (out[i] == a && out[i + 1] == b) logits[out[i + 2].toInt()] = Float.NEGATIVE_INFINITY
            }
        }
        var best = 0
        for (i in 1 until logits.size) if (logits[i] > logits[best]) best = i
        return best.toLong()
    }

    private fun embed(m: Model, ids: LongArray): OnnxTensor =
        OnnxTensor.createTensor(m.env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { t ->
            m.embed.run(mapOf("input_ids" to t)).use { r -> copy(m.env, r[0] as OnnxTensor) }
        }

    /** ⚠ A result is closed with its `Result`; anything kept is copied out first. */
    private fun copy(env: OrtEnvironment, t: OnnxTensor): OnnxTensor =
        OnnxTensor.createTensor(env, t.floatBuffer, t.info.shape)

    private const val SIZE = 768
    private const val BOS = 0L
    private const val EOS = 2L
    private const val DECODER_START = 2L

    /**
     * 768², SQUASHED (the processor resizes without a crop), ImageNet mean/std,
     * NCHW — `preprocessor_config.json`'s numbers.
     */
    private fun pixels(src: Bitmap): FloatBuffer {
        val sq = Bitmap.createScaledBitmap(src, SIZE, SIZE, true)
        val px = IntArray(SIZE * SIZE).also { sq.getPixels(it, 0, SIZE, 0, 0, SIZE, SIZE) }
        if (sq !== src) sq.recycle()
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        val buf = FloatBuffer.allocate(3 * SIZE * SIZE)
        for (c in 0 until 3) {
            val shift = 16 - 8 * c
            for (p in px) buf.put(((p shr shift and 0xFF) / 255f - mean[c]) / std[c])
        }
        return buf.apply { rewind() }
    }

    /**
     * ⭐ Where a caption goes in a prompt that already has text: after it, as one
     * more comma-separated piece, so a checkpoint's quality tags and a LoRA's
     * trigger words stay in front.
     */
    fun appended(prompt: String, caption: String): String {
        val head = prompt.trimEnd()
        if (head.isEmpty()) return caption
        return if (head.endsWith(",")) "$head $caption" else "$head, $caption"
    }
}

/** ⚠ Set by the upload (`docs/FLORENCE.md` §6) — a new upload is a new revision here. */
private const val PROMPTGEN_REV = "62041a2bf351576d1f0185cff78cfc7956fc38f8"

/**
 * ⭐⭐ A picture into Danbooru TAGS — SmilingWolf's WD ViT tagger v3, on the CPU
 * (`docs/FLORENCE.md`). One pass, ~0.5 s on the PC; for the anime checkpoints
 * (Illustrious, Anima, Pony) that read tags rather than sentences.
 *
 * ⚠⚠ **fp32, from SmilingWolf's own repo.** Measured 2026-10-08: dynamic int8
 * (per-tensor or per-channel) moved tag probabilities by up to 0.68 and
 * invented tags ("guitar", "polka dot background") — the activations, not the
 * head, which was never quantized. 378 MB, nothing re-hosted.
 *
 * ⚠ It knows Danbooru's whole vocabulary, explicit tags and the four ratings
 * included — the user's call (2026-10-08) is that the rating goes into the prompt.
 */
object ImageTagger : DescribeModel {

    private const val TAG = "ImageTagger"

    override val id = "wdtagger"
    override val label = "Tags (WD ViT v3)"
    override val labelRes = R.string.describe_wd_label

    internal val FILES = ModelFiles(
        "wdtagger",
        "https://huggingface.co/SmilingWolf/wd-vit-tagger-v3/resolve/7f6b584d0bd3f55c4531f14ba3d4761b2bccdc0f/",
        listOf("model.onnx" to 378_536_310L, "selected_tags.csv" to 308_468L),
    )

    override val bytes: Long get() = FILES.bytes

    /** SmilingWolf's own defaults (the wd-tagger Space). */
    const val GENERAL_THRESHOLD = 0.35f
    const val CHARACTER_THRESHOLD = 0.85f

    override fun isInstalled(context: Context): Boolean = FILES.isInstalled(context)

    override fun bytesOnDisk(context: Context): Long = FILES.bytesOnDisk(context)

    @Volatile
    override var installed: Boolean = false
        private set

    override fun refresh(context: Context) {
        installed = isInstalled(context)
    }

    override fun install(context: Context, onProgress: (ModelInstaller.Progress) -> Unit, isCancelled: () -> Boolean) {
        FILES.install(context, context.getString(labelRes), onProgress, isCancelled)
        refresh(context)
        Log.i(TAG, "installed $label (${bytesOnDisk(context)} bytes)")
    }

    override fun delete(context: Context) {
        close()
        FILES.dir(context).deleteRecursively()
        refresh(context)
    }

    /** A tag's name and Danbooru category: 0 general, 4 character, 9 rating. */
    internal data class Tag(val name: String, val category: Int)

    private class Model(val env: OrtEnvironment, val session: OrtSession, val tags: List<Tag>) : AutoCloseable {
        override fun close() = session.close()
    }

    private var model: Model? = null

    @Synchronized
    private fun model(context: Context): Model? {
        model?.let { return it }
        if (!isInstalled(context)) return null
        val env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR, "nightmare-tagger")
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
        val session = env.createSession(FILES.file(context, FILES.files[0].first).absolutePath, opts)
        val tags = readTags(FILES.file(context, FILES.files[1].first).readText())
        return Model(env, session, tags).also { model = it }
    }

    @Synchronized
    private fun releaseModel() {
        if (model == null) return
        model?.close()
        model = null
        Log.i(TAG, "model released (idle or low memory)")
    }

    private val idle = IdleRelease(IdleRelease.IDLE_MS) { releaseModel() }

    override fun trim() = idle.releaseNow()

    @Synchronized
    fun close() {
        model?.close()
        model = null
    }

    /** ⭐ [photo] as a prompt of tags. Null when not installed. ⚠ Blocking, off the main thread. */
    fun tags(context: Context, photo: Bitmap): String? {
        idle.begin()
        try {
            val m = model(context) ?: return null
            val t = System.nanoTime()
            val size = (m.session.inputInfo.values.first().info as TensorInfo).shape[1].toInt()
            val probs = OnnxTensor.createTensor(m.env, pixels(photo, size), longArrayOf(1, size.toLong(), size.toLong(), 3)).use { x ->
                m.session.run(mapOf(m.session.inputNames.first() to x)).use { r ->
                    val b = (r[0] as OnnxTensor).floatBuffer
                    FloatArray(b.capacity()).also { b.get(it) }
                }
            }
            val text = prompt(m.tags, probs)
            Log.i(TAG, "tagged in ${(System.nanoTime() - t) / 1_000_000} ms")
            return text
        } finally {
            idle.end()
        }
    }

    /**
     * ⭐ The tags as a prompt: characters, then general tags by confidence, then the
     * rating. Pure, for the tests. Underscores become spaces except in kaomoji
     * (`^_^`), and brackets are escaped — `power \(chainsaw man\)` — because a bare
     * `(…)` is a WEIGHT in an SD prompt.
     */
    internal fun prompt(tags: List<Tag>, probs: FloatArray): String {
        val chars = tags.indices.filter { tags[it].category == 4 && probs[it] > CHARACTER_THRESHOLD }.sortedByDescending { probs[it] }
        val general = tags.indices.filter { tags[it].category == 0 && probs[it] > GENERAL_THRESHOLD }.sortedByDescending { probs[it] }
        val rating = tags.indices.filter { tags[it].category == 9 }.maxByOrNull { probs[it] }
        return (chars + general + listOfNotNull(rating)).joinToString(", ") { tidy(tags[it].name) }
    }

    private val KAOMOJI = setOf(
        "0_0", "(o)_(o)", "+_+", "+_-", "._.", "<o>_<o>", "<|>_<|>", "=_=", ">_<", "3_3", "6_9", ">_o",
        "@_@", "^_^", "o_o", "u_u", "x_x", "|_|", "||_||",
    )

    internal fun tidy(name: String): String {
        val spaced = if (name in KAOMOJI) name else name.replace('_', ' ')
        return spaced.replace("(", "\\(").replace(")", "\\)")
    }

    /** `selected_tags.csv`: `tag_id,name,category,count`. ⚠ A name may be quoted and hold a comma. */
    internal fun readTags(csv: String): List<Tag> =
        csv.lineSequence().drop(1).filter { it.isNotBlank() }.map { line ->
            val f = csvFields(line)
            Tag(f[1], f[2].trim().toInt())
        }.toList()

    private fun csvFields(line: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString()
        return out
    }

    /**
     * The wd-tagger Space's preprocessing: composite on WHITE, pad to a white
     * square (centred), resize to [size]², BGR, 0–255, NHWC.
     */
    private fun pixels(src: Bitmap, size: Int): FloatBuffer {
        val m = maxOf(src.width, src.height)
        val sq = Bitmap.createBitmap(m, m, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(sq).apply {
            drawColor(android.graphics.Color.WHITE)
            drawBitmap(src, ((m - src.width) / 2).toFloat(), ((m - src.height) / 2).toFloat(), null)
        }
        val scaled = Bitmap.createScaledBitmap(sq, size, size, true)
        if (scaled !== sq) sq.recycle()
        val px = IntArray(size * size).also { scaled.getPixels(it, 0, size, 0, 0, size, size) }
        scaled.recycle()
        val buf = FloatBuffer.allocate(size * size * 3)
        for (p in px) {
            buf.put((p and 0xFF).toFloat())          // B
            buf.put((p shr 8 and 0xFF).toFloat())    // G
            buf.put((p shr 16 and 0xFF).toFloat())   // R
        }
        return buf.apply { rewind() }
    }
}

/**
 * ⭐⭐ What the describe button can write, and which model writes it — the dialog's
 * chips (`docs/FLORENCE.md` §1).
 */
enum class DescribeMode(val model: DescribeModel) {
    SHORT(ImageCaption), DETAILED(ImageCaption), TAGS(ImageTagger);

    companion object {
        /** Every model, once — the Tools rows and [PictureModels]. */
        val models: List<DescribeModel> = listOf(ImageCaption, ImageTagger)

        /**
         * ⭐⭐ The chip the dialog opens on: TAGS when the prompt feeds a checkpoint
         * that reads Danbooru tags, DETAILED otherwise — the user's call, 2026-10-08.
         * Which checkpoint: the samplers the prompt is wired into, else the selected
         * one ([PromptTokens.budgetFor]'s rule).
         */
        fun defaultFor(graph: Graph, promptNode: String): DescribeMode {
            val specs = graph.nodes
                .filter { n -> n.inputs.values.any { it.node == promptNode } }
                .mapNotNull { ModelCatalog.byId(it.params["model"].orEmpty()) }
                .ifEmpty { listOf(SelectedModel.spec) }
            return if (specs.any { readsTags(it) }) TAGS else DETAILED
        }

        /**
         * ⭐ A checkpoint that reads tags: the Anima family, every catalogue model on
         * the shared anime negative ([ModelCatalog.styleWords] — Illustrious and
         * upstream's anime SD 1.5s), Pony, and an import NAMED like one of those.
         */
        fun readsTags(spec: ModelSpec): Boolean =
            spec.family == Family.ANIMA ||
                ModelCatalog.styleWords(spec) == "anime" ||
                Regex("illustrious|noob|pony|anime|anima", RegexOption.IGNORE_CASE).containsMatchIn(spec.id + " " + spec.label)
    }
}

/**
 * ⭐ GPT-2 / BART byte-level BPE, the decode half only: token ids back to text.
 *
 * Each vocab entry is a run of BYTES spelled in printable stand-ins (`Ġ` is a
 * space); decoding maps the stand-ins back to bytes and reads UTF-8.
 */
internal object ByteLevel {

    /** The stand-in character for each byte, GPT-2's `bytes_to_unicode`. */
    private val byteOf: Map<Char, Int> by lazy {
        val bs = ArrayList<Int>()
        bs += '!'.code..'~'.code
        bs += '¡'.code..'¬'.code
        bs += '®'.code..'ÿ'.code
        val cs = ArrayList(bs)
        var n = 0
        for (b in 0..255) if (b !in bs) { bs += b; cs += 256 + n; n++ }
        cs.indices.associate { cs[it].toChar() to bs[it] }
    }

    /** `vocab.json` (token → id) as an id-indexed table. */
    fun vocab(json: String): Array<String?> {
        val o = org.json.JSONObject(json)
        val out = arrayOfNulls<String>(o.length())
        for (k in o.keys()) {
            val id = o.getInt(k)
            if (id in out.indices) out[id] = k
        }
        return out
    }

    /**
     * Text for [ids]. ⚠ Skips `<s>` `<pad>` `</s>` `<unk>` (0–3), `<mask>` and every
     * id past the vocab — Florence's `<loc_*>` and task tokens, which a caption
     * never wants.
     */
    fun decode(ids: LongArray, vocab: Array<String?>): String {
        val bytes = java.io.ByteArrayOutputStream()
        for (id in ids) {
            if (id < 4 || id >= vocab.size) continue
            val tok = vocab[id.toInt()] ?: continue
            if (tok == "<mask>") continue
            for (ch in tok) bytes.write(byteOf[ch] ?: continue)
        }
        return bytes.toString(Charsets.UTF_8.name()).trim()
    }
}
