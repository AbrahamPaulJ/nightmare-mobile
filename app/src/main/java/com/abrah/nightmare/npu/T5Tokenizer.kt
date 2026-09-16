package com.abrah.nightmare.npu

import android.content.Context

/**
 * SentencePiece **Unigram** tokenizer for DistilT5, matching HuggingFace `T5Tokenizer`.
 *
 * T5 does not use BPE like CLIP does; it scores a lattice of every possible piece and
 * takes the highest-scoring segmentation (Viterbi). The vocabulary ships as
 * `t5_unigram.tsv` -- 32100 (piece, log-probability) pairs lifted straight out of
 * `tokenizer.json`, so the scores are the model's own, not a reconstruction.
 *
 * Pipeline, in the order `tokenizer.json` declares it:
 *   normalizer     Precompiled charsmap (NFKC-ish). Implemented as whitespace collapsing,
 *                  which is exactly equivalent for the ASCII prompts this app accepts;
 *                  see [normalize].
 *   pre_tokenizer  Metaspace: spaces become U+2581 and one is prepended ("always").
 *   model          Unigram, unk_id 2.
 *   post_processor TemplateProcessing: append `</s>` (id 1).
 *
 * Padding is to 128 with `<pad>` (id 0), and the attention mask marks the real tokens --
 * the MMDiT needs that mask, because it is what makes the padded text positions
 * invisible in the joint attention.
 */
class T5Tokenizer private constructor() {

    private val pieces = ArrayList<String>(32100)
    private val scores = ArrayList<Float>(32100)
    private val index = HashMap<String, Int>(65536)
    private var unkId = 2

    /** Longest piece in the vocabulary, so the lattice never scans further than needed. */
    private var maxPieceLen = 1

    /** The video path's copy — `t5_unigram.tsv` from its host-side assets. */
    constructor(ctx: Context) : this() {
        NpuFiles.asset(ctx, "t5_unigram.tsv").bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isEmpty()) continue
                val tab = line.lastIndexOf('\t')
                if (tab < 0) continue
                val key = line.substring(0, tab)
                if (key == "unk_id") { unkId = line.substring(tab + 1).trim().toInt(); continue }
                add(if (key == "\\t") "\t" else key, line.substring(tab + 1).toFloat())
            }
        }
        check(pieces.size > 30000) { "t5_unigram.tsv looks truncated: ${pieces.size}" }
    }

    private fun add(piece: String, score: Float) {
        index[piece] = pieces.size
        pieces.add(piece)
        scores.add(score)
        if (piece.length > maxPieceLen) maxPieceLen = piece.length
    }

    companion object {
        const val PAD_ID = 0
        const val EOS_ID = 1
        const val MAX_LEN = 128
        private const val SPACE = '▁'      // Metaspace replacement

        /**
         * ⭐ An Anima checkpoint's `tokenizer_t5.json` (HF Unigram).
         *
         * ⚠ Its 32100 pieces and scores are IDENTICAL to `t5_unigram.tsv`,
         * checked entry for entry 2026-09-16 — so this and the video copy count
         * the same. Null for anything that is not a Unigram model.
         */
        fun fromTokenizerJson(file: java.io.File): T5Tokenizer? {
            val model = org.json.JSONObject(file.readText()).optJSONObject("model") ?: return null
            if (model.optString("type") != "Unigram") return null
            val vocab = model.getJSONArray("vocab")
            return T5Tokenizer().apply {
                unkId = model.optInt("unk_id", 2)
                for (k in 0 until vocab.length()) {
                    val row = vocab.getJSONArray(k)
                    add(row.getString(0), row.getDouble(1).toFloat())
                }
            }.takeIf { it.pieces.size > 30000 }
        }
    }

    /**
     * The declared normalizer is a `Precompiled` charsmap, which for Latin text performs
     * NFKC plus whitespace folding. Reproducing the compiled table would mean shipping and
     * interpreting Google's serialised trie; for the ASCII/Latin-1 prompts this app takes,
     * collapsing whitespace runs and trimming is the same function.
     */
    private fun normalize(text: String): String =
        text.replace(Regex("""\s+"""), " ").trim()

    /**
     * Viterbi over the piece lattice.
     *
     * best[i] is the score of the best segmentation of the first i characters. Walking
     * forward and taking, for each end position, the highest-scoring (start, piece) pair
     * gives the global optimum because the model is a unigram: piece scores are
     * independent, so a prefix's best segmentation is part of the whole string's best.
     */
    private fun viterbi(s: String): List<Int> {
        val n = s.length
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val backPiece = IntArray(n + 1) { -1 }
        val backStart = IntArray(n + 1) { -1 }
        best[0] = 0.0

        for (i in 0 until n) {
            if (best[i] == Double.NEGATIVE_INFINITY) continue
            val limit = minOf(n, i + maxPieceLen)
            for (j in i + 1..limit) {
                val id = index[s.substring(i, j)] ?: continue
                val cand = best[i] + scores[id]
                if (cand > best[j]) { best[j] = cand; backPiece[j] = id; backStart[j] = i }
            }
            // Unknown character: consume one char at the unk penalty so the lattice can
            // never dead-end on text the vocabulary does not cover.
            val j = i + 1
            if (backPiece[j] == -1) {
                val cand = best[i] - 10.0
                if (cand > best[j]) { best[j] = cand; backPiece[j] = unkId; backStart[j] = i }
            }
        }

        val out = ArrayList<Int>()
        var p = n
        while (p > 0) {
            val id = backPiece[p]
            if (id < 0) break
            out.add(id)
            p = backStart[p]
        }
        out.reverse()
        return out
    }

    /** Raw piece ids, without `</s>` or padding. */
    fun encodeRaw(text: String): List<Int> {
        val norm = normalize(text)
        if (norm.isEmpty()) return emptyList()
        // Metaspace with prepend_scheme="always"
        val meta = (SPACE + norm).replace(' ', SPACE)
        return viterbi(meta)
    }

    /**
     * @return ids padded to [MAX_LEN], and the attention mask (1 for real tokens).
     */
    fun encode(text: String): Pair<IntArray, FloatArray> {
        val body = encodeRaw(text).take(MAX_LEN - 1)
        val ids = IntArray(MAX_LEN) { PAD_ID }
        val mask = FloatArray(MAX_LEN)
        body.forEachIndexed { i, v -> ids[i] = v; mask[i] = 1f }
        ids[body.size] = EOS_ID
        mask[body.size] = 1f
        return ids to mask
    }
}
