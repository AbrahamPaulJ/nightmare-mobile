package com.abrah.nightmare.npu

import android.content.Context

/**
 * CLIP byte-pair tokenizer, matching HuggingFace `CLIPTokenizer`.
 *
 * SDXL uses two encoders and therefore two tokenizer directories, but their `vocab.json`
 * and `merges.txt` are byte-identical (verified by md5), so one copy serves both. What
 * differs is the PAD id: CLIP L pads with `<|endoftext|>` (49407) and CLIP G's
 * tokenizer_2 pads with `!` (id 0). Padding CLIP G with the end token instead is the kind
 * of mistake that yields a plausible but subtly wrong conditioning vector, so the pad id
 * is passed in explicitly rather than defaulted.
 */
class ClipTokenizer(ctx: Context) {

    private val vocab: Map<String, Int>
    private val ranks: Map<Pair<String, String>, Int>
    private val byteEncoder: Map<Int, Char>
    private val cache = HashMap<String, List<String>>()

    private val bos = AppAssets.config.getInt("bos_id")
    private val eos = AppAssets.config.getInt("eos_id")

    /** CLIP's pre-tokenizer: contractions, letter runs, single digits, symbol runs. */
    private val pattern = Regex(
        """<\|startoftext\|>|<\|endoftext\|>|'s|'t|'re|'ve|'m|'ll|'d|""" +
            """[\p{L}]+|[\p{N}]|[^\s\p{L}\p{N}]+""",
        RegexOption.IGNORE_CASE
    )

    init {
        val vjson = NpuFiles.asset(ctx, "clip_vocab.json").bufferedReader().readText()
        val obj = org.json.JSONObject(vjson)
        val v = HashMap<String, Int>(obj.length())
        val keys = obj.keys()
        while (keys.hasNext()) { val k = keys.next(); v[k] = obj.getInt(k) }
        vocab = v

        val r = HashMap<Pair<String, String>, Int>()
        NpuFiles.asset(ctx, "clip_merges.txt").bufferedReader().useLines { lines ->
            // The first line is a version banner; CLIP uses merges 1..49152.
            lines.drop(1).forEachIndexed { i, line ->
                val p = line.trim().split(' ')
                if (p.size == 2) r[p[0] to p[1]] = i
            }
        }
        ranks = r
        byteEncoder = bytesToUnicode()
    }

    /**
     * GPT-2 / CLIP byte<->unicode table: every byte maps to a printable codepoint so BPE
     * can run over text without ever meeting a control character.
     */
    private fun bytesToUnicode(): Map<Int, Char> {
        val bs = ArrayList<Int>()
        (33..126).forEach { bs.add(it) }
        (161..172).forEach { bs.add(it) }
        (174..255).forEach { bs.add(it) }
        val cs = ArrayList<Int>(bs)
        var n = 0
        for (b in 0..255) if (b !in bs) { bs.add(b); cs.add(256 + n); n++ }
        return bs.indices.associate { bs[it] to cs[it].toChar() }
    }

    private fun bpe(token: String): List<String> {
        cache[token]?.let { return it }

        // CLIP marks the end of a word rather than the start (GPT-2 uses a leading space).
        var word = ArrayList<String>(token.length + 1)
        for (i in token.indices) {
            word.add(if (i == token.length - 1) token[i] + "</w>" else token[i].toString())
        }
        if (word.isEmpty()) return emptyList()

        while (word.size > 1) {
            var bestRank = Int.MAX_VALUE
            var bestIdx = -1
            for (i in 0 until word.size - 1) {
                val rk = ranks[word[i] to word[i + 1]] ?: continue
                if (rk < bestRank) { bestRank = rk; bestIdx = i }
            }
            if (bestIdx < 0) break
            val merged = ArrayList<String>(word.size - 1)
            var i = 0
            while (i < word.size) {
                if (i == bestIdx) { merged.add(word[i] + word[i + 1]); i += 2 }
                else { merged.add(word[i]); i++ }
            }
            word = merged
        }
        cache[token] = word
        return word
    }

    /** Raw ids without BOS/EOS or padding. */
    fun encodeRaw(text: String): List<Int> {
        val clean = text.trim().replace(Regex("""\s+"""), " ").lowercase()
        val out = ArrayList<Int>()
        for (m in pattern.findAll(clean)) {
            val piece = m.value.toByteArray(Charsets.UTF_8)
                .joinToString("") { byteEncoder[it.toInt() and 0xFF]!!.toString() }
            for (t in bpe(piece)) {
                val id = vocab[t]
                if (id != null) out.add(id)
            }
        }
        return out
    }

    /**
     * Tokenise to exactly [len] ids: BOS, content (truncated to leave room for EOS), EOS,
     * then [padId]. This is `padding="max_length", truncation=True` in HF terms.
     */
    fun encode(text: String, padId: Int, len: Int = 77): IntArray {
        val body = encodeRaw(text)
        val keep = body.take(len - 2)
        val ids = IntArray(len) { padId }
        ids[0] = bos
        keep.forEachIndexed { i, v -> ids[i + 1] = v }
        ids[keep.size + 1] = eos
        return ids
    }
}
