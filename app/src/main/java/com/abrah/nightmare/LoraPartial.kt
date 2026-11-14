package com.abrah.nightmare

/**
 * ⭐ The engine's word on a LoRA it applied only in part: `lora.hpp - Only (840 / 1092) LoRA tensors have been applied,
 * lora_file_path = …/odette_v1.safetensors` (Saved #8071). Read from the newest request's lines only (after its
 * `Req Rcvd`), so an earlier render's line is never reported again.
 */
object LoraPartial {
    data class Partial(val name: String, val applied: Int, val total: Int)

    private val ONLY = Regex("""Only \((\d+) / (\d+)\) LoRA tensors have been applied, lora_file_path = (\S+)""")
    private const val REQUEST = "Req Rcvd"

    /** [lines] oldest first ([BackendProcess.newestLaunch]); one entry per file, in the order the engine named them. */
    fun of(lines: List<String>): List<Partial> {
        val from = lines.indexOfLast { it.startsWith(REQUEST) }.coerceAtLeast(0)
        return lines.subList(from, lines.size).mapNotNull { line ->
            ONLY.find(line)?.let { m ->
                val (applied, total, path) = m.destructured
                Partial(path.substringAfterLast('/').removeSuffix(".safetensors"), applied.toInt(), total.toInt())
                    .takeIf { it.applied < it.total }
            }
        }.distinctBy { it.name }
    }
}
