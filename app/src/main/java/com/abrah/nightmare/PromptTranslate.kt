package com.abrah.nightmare

import android.content.Context
import io.github.yinvoker.foxlet.ExternalModel
import io.github.yinvoker.foxlet.Foxlet
import io.github.yinvoker.foxlet.LanguagePair
import io.github.yinvoker.foxlet.ModelFiles
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * ⭐⭐⭐ **Russian / Chinese prompts → English, on the phone** (the user's ask,
 * 2026-09-26). SD 1.5 and SDXL read prompts through CLIP, which is English-only.
 *
 * ⭐ Firefox Translations' own models (Mozilla, MPL-2.0) on the Bergamot/Marian
 * engine, through Foxlet (`yinvoke/foxlet-translate`, MIT, built from source —
 * `docs/TRANSLATE.md`). Offline once the model is here, no Google services, and
 * the model files are OURS to download ([dir]) so they can come through the
 * app's own mirror rather than Mozilla's CDN.
 */
object PromptTranslate {

    /**
     * A source language: its Bergamot model directory, its language code, and
     * the three files it downloads — Mozilla's v2.1, unmodified, re-hosted on
     * Hugging Face so the app's mirror setting covers them ([BASE]). Sizes are
     * the integrity check, read off the repo 2026-09-26.
     */
    enum class Source(val code: String, val dirName: String, val files: List<Pair<String, Long>>) {
        RU(
            "ru", "ruen",
            listOf(
                "model.ruen.intgemm.alphas.bin" to 31_561_787L,
                "lex.50.50.ruen.s2t.bin" to 4_397_908L,
                "vocab.ruen.spm" to 952_371L,
            ),
        ),
        ZH(
            "zh", "zhen",
            listOf(
                "model.zhen.intgemm.alphas.bin" to 43_977_787L,
                "lex.50.50.zhen.s2t.bin" to 9_219_192L,
                "vocab.zhen.spm" to 1_359_697L,
            ),
        );

        val bytes: Long get() = files.sumOf { it.second }

        /** ⭐ The one-download latch's id, as a checkpoint's is its own id. */
        val installId: String get() = "translate:$code"
    }

    /** ⚠ A Hugging Face URL, so [Prefs.apply] swaps in the mirror like every model. */
    const val BASE = "https://huggingface.co/AbrahamPJ/nightmare-translate/resolve/main/"

    /**
     * ⭐ Which language a prompt is in, read off its CHARACTERS — Cyrillic is
     * Russian, Han is Chinese. No model, so the button can appear the moment the
     * text qualifies. Null when there is nothing to translate.
     */
    fun detect(text: String): Source? {
        var cyrillic = 0
        var han = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.CYRILLIC -> cyrillic++
                Character.UnicodeScript.HAN -> han++
                else -> {}
            }
            i += Character.charCount(cp)
        }
        return when {
            cyrillic == 0 && han == 0 -> null
            han >= cyrillic -> Source.ZH
            else -> Source.RU
        }
    }

    fun dir(context: Context, source: Source): File =
        File(ModelCatalog.root(context), "translate/${source.dirName}")

    /** ⚠ Every file at its exact size — a half-fetched model is not installed. */
    fun installed(context: Context, source: Source): Boolean =
        source.files.all { (name, size) -> File(dir(context, source), name).length() == size }

    fun bytesOnDisk(context: Context, source: Source): Long =
        dir(context, source).listFiles()?.sumOf { it.length() } ?: 0L

    /**
     * ⭐ Fetch [source]'s three files through the shared downloader (resume,
     * mirror, size check). ⚠ Blocking — off the main thread. Each lands as
     * `.part` and is renamed only when whole.
     */
    fun install(
        context: Context,
        source: Source,
        onProgress: (ModelInstaller.Progress) -> Unit,
        isCancelled: () -> Boolean = { false },
    ) {
        val dir = dir(context, source).apply { mkdirs() }
        var before = 0L
        for ((name, size) in source.files) {
            val target = File(dir, name)
            if (target.length() != size) {
                val part = File(dir, "$name.part")
                val base = before
                UpscalerCatalog.download(BASE + "${source.dirName}/$name", part, size, { p ->
                    onProgress(ModelInstaller.Progress(p.phase, base + p.done, source.bytes))
                }, isCancelled)
                if (part.length() != size) {
                    val got = part.length()
                    part.delete()
                    throw java.io.IOException("$name: downloaded $got bytes, expected $size")
                }
                if (!part.renameTo(target)) { part.copyTo(target, overwrite = true); part.delete() }
            }
            before += size
        }
    }

    /** ⚠ Releases the engine first — it may hold these files open. */
    suspend fun delete(context: Context, source: Source) = lock.withLock {
        client?.shutdown()
        client = null
        dir(context, source).deleteRecursively()
    }

    private val lock = Mutex()
    private var client: Foxlet? = null

    /**
     * ⭐⭐ [prompt] with every Russian or Chinese PIECE in English, and nothing
     * else touched.
     *
     * ⚠⚠ A prompt is a comma list with weights — `(красная шляпа:1.3), 8k` — and
     * a translator handed the whole string rewrites it as a sentence, dropping
     * the commas and the weights. So it is split on commas and newlines; only a
     * piece with Cyrillic or Han text is translated, and inside a
     * `(text:weight)` only the text. English tags, embedding names and numbers
     * pass through as typed.
     */
    suspend fun translate(context: Context, prompt: String, source: Source): String = lock.withLock {
        val foxlet = client ?: Foxlet.create(context.applicationContext).also { client = it }
        val model = ExternalModel(LanguagePair(source.code, "en"), ModelFiles.fromDirectory(dir(context, source)))
        val pieces = splitPrompt(prompt)
        val todo = pieces.withIndex().filter { (_, p) -> p is Piece.Text && detect(p.core) != null }
        if (todo.isEmpty()) return@withLock prompt
        val out = foxlet.translator.translate(todo.map { (it.value as Piece.Text).core }, model)
        val done = pieces.toMutableList()
        todo.forEachIndexed { k, (i, p) -> done[i] = (p as Piece.Text).copy(core = out[k].trim().trimEnd('.')) }
        // ⚠ Chinese separators become ", " — CLIP does not read `，` or `、` as a
        // comma, and they survived translation untouched (measured 2026-09-26).
        done.replaceAll { if (it is Piece.Sep && it.text.any { c -> c == '，' || c == '、' }) Piece.Sep(", ") else it }
        done.joinToString("") { it.render() }
    }

    // ---- prompt pieces ------------------------------------------------------

    /** A separator kept verbatim, or a piece of text with what surrounds its words. */
    sealed interface Piece {
        fun render(): String
        data class Sep(val text: String) : Piece { override fun render() = text }
        data class Text(val lead: String, val core: String, val tail: String) : Piece {
            override fun render() = lead + core + tail
        }
    }

    private val SEPARATOR = Regex("[,，、\\n]+\\s*")

    /** ⚠ `(text:1.2)` keeps its brackets and weight; spaces around a piece are kept too. */
    private val WEIGHTED = Regex("""^(\s*[(\[]*)(.*?)((?::\s*[0-9.]+)?[)\]]*\s*)$""", RegexOption.DOT_MATCHES_ALL)

    fun splitPrompt(prompt: String): List<Piece> {
        val out = ArrayList<Piece>()
        var at = 0
        for (m in SEPARATOR.findAll(prompt)) {
            if (m.range.first > at) out += textPiece(prompt.substring(at, m.range.first))
            out += Piece.Sep(m.value)
            at = m.range.last + 1
        }
        if (at < prompt.length) out += textPiece(prompt.substring(at))
        return out
    }

    private fun textPiece(s: String): Piece.Text {
        val m = WEIGHTED.matchEntire(s) ?: return Piece.Text("", s, "")
        return Piece.Text(m.groupValues[1], m.groupValues[2], m.groupValues[3])
    }
}
