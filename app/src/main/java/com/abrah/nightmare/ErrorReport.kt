package com.abrah.nightmare

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ⭐⭐⭐ **What a person sends us when something failed** — the text behind the
 * share icon on an [com.abrah.nightmare.ui.ErrorNotice].
 *
 * ⚠⚠⚠ Reported 2026-10-01: four failures from users' phones (FLUX.2 9B and Z-Image
 * imports, a Krea `dit.gguf` swap, an SDXL checkpoint) arrived as screenshots of
 * one red line, `generate failed http -1 — EOFException: no message`. The
 * backend had written why in its own log, which never leaves the phone. ⇒ One
 * report, shown in full BEFORE it is shared: the message, the last recorded
 * exception's stack, the backend's log and how it exited, and the phone.
 *
 * ⚠⚠ **No prompt text** — the user's call, 2026-10-01. Reports go to strangers
 * and group chats, and a prompt can be explicit. The backend prints the prompt
 * on every request (`Req Rcvd: P:… NP:…`), so [redact] removes that AND every
 * line of every prompt on the canvas wherever else it turns up.
 *
 * ⚠ Everything but [build] is pure, so `ErrorReportTest` reaches it on the JVM.
 */
object ErrorReport {

    /** The newest exception anything [record]ed, with when. */
    data class Failure(val atMs: Long, val trace: String)

    @Volatile
    var last: Failure? = null
        private set

    /**
     * ⭐ Call where a failure is CAUGHT and turned into a message — the message
     * survives to the screen, the stack does not, unless it is kept here.
     */
    fun record(t: Throwable) {
        last = Failure(System.currentTimeMillis(), t.stackTraceToString().take(TRACE_MAX))
    }

    private const val TRACE_MAX = 16_000
    private const val PROMPT = "[prompt removed]"

    /** Everything the text is made of — gathered by [build], rendered by [render]. */
    data class Facts(
        val message: String,
        val nowMs: Long,
        val version: String,
        val device: String,
        val android: String,
        val chip: String,
        val ramTotal: Long,
        val ramAvailable: Long,
        val storageFree: Long,
        val model: String?,
        val modelFiles: List<Pair<String, Long>>,
        val backendRunning: Boolean,
        val backendExit: BackendProcess.Exit?,
        val backendTail: List<String>,
        val appLog: List<String>,
        val failure: Failure?,
    )

    /**
     * ⭐⭐ Strip the prompt out of one log line.
     *
     * ⚠ Two passes, because neither alone is enough: the backend's request line
     * is recognisable but a prompt with a newline in it spills onto lines that
     * are not, and the canvas's prompts are known but the backend may have
     * received an older one. Fragments under 4 characters are left alone —
     * redacting "a" would shred every line.
     */
    fun redact(line: String, prompts: Collection<String>): String {
        var out = line
        val req = out.indexOf("Req Rcvd: P:")
        if (req >= 0) {
            val head = out.substring(0, req) + "Req Rcvd: P:$PROMPT NP:$PROMPT"
            val rest = Regex(""" S:-?\d""").find(out, req)
            out = if (rest != null) head + out.substring(rest.range.first) else head
        }
        val pieces = prompts.flatMap { p -> listOf(p.trim()) + p.lines().map { it.trim() } }
            .filter { it.length >= 4 }
            .distinct()
            .sortedByDescending { it.length }
        for (piece in pieces) {
            out = if (piece.length < CLIP_SEED) out.replace(piece, PROMPT) else redactClipped(out, piece)
        }
        return out
    }

    /** A prompt's first this-many characters identify it in a clipped preview. */
    private const val CLIP_SEED = 12

    /**
     * ⚠⚠ The run log shows a prompt CLIPPED (`一张充满生活电影感的…`), which an
     * exact match never finds. ⇒ Anchor on the first [CLIP_SEED] characters,
     * then take as much of the prompt as matches, and the ellipsis after it.
     */
    private fun redactClipped(line: String, piece: String): String {
        val seed = piece.take(CLIP_SEED)
        val sb = StringBuilder()
        var from = 0
        while (true) {
            val at = line.indexOf(seed, from)
            if (at < 0) break
            var end = at
            while (end < line.length && end - at < piece.length && line[end] == piece[end - at]) end++
            if (end < line.length && line[end] == '…') end++
            sb.append(line, from, at).append(PROMPT)
            from = end
        }
        return sb.append(line, from, line.length).toString()
    }

    /**
     * ⚠ The QNN runtime narrates every context it opens — `QnnDsp <I>` lines,
     * power-config chatter and blank lines — ~85% of a 400-line log, the same
     * on every launch (a user's report, 2026-10-01). Its `<W>`/`<E>` lines and
     * everything the engine says stay.
     */
    internal fun routine(line: String): Boolean {
        val t = line.trim()
        return t.isEmpty() || "QnnDsp <I>" in t || "htpPerfInfrastructure" in t ||
            "m_CFBCallbackInfoObj" in t || "setInferenceBufferForHtpExtensionSkel" in t
    }

    private fun gb(bytes: Long) = if (bytes <= 0) "?" else String.format(Locale.US, "%.2f GB", bytes / 1e9)

    private fun mb(bytes: Long) = String.format(Locale.US, "%.1f MB", bytes / 1e6)

    /** ⭐ The report's text — what the dialog shows and what Share sends, byte for byte. */
    fun render(f: Facts, prompts: Collection<String>): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return buildString {
            appendLine("Nightmare error report")
            appendLine("time: ${time.format(Date(f.nowMs))}")
            appendLine()
            appendLine("error: ${redact(f.message, prompts)}")
            appendLine()
            appendLine("app: ${f.version}")
            appendLine("phone: ${f.device} · Android ${f.android}")
            appendLine("chip: ${f.chip}")
            appendLine("RAM: ${gb(f.ramTotal)} total, ${gb(f.ramAvailable)} available now")
            appendLine("storage free: ${gb(f.storageFree)}")
            appendLine("model: ${f.model ?: "none"}")
            f.modelFiles.forEach { (name, bytes) -> appendLine("  $name  ${mb(bytes)}") }
            appendLine()
            append("backend: ").appendLine(
                when {
                    f.backendRunning -> "running"
                    f.backendExit == null -> "not started"
                    else -> {
                        val e = f.backendExit
                        val ago = (f.nowMs - e.atMs) / 1000
                        "exited ${e.code} ${ago}s ago" +
                            (if (e.byApp) " (stopped by the app)" else "") +
                            (BackendProcess.exitMeaning(e)?.let { " — $it" } ?: "")
                    }
                },
            )
            f.failure?.let {
                appendLine()
                appendLine("last recorded exception (${time.format(Date(it.atMs))}):")
                appendLine(redact(it.trace, prompts).trimEnd())
            }
            if (f.backendTail.isNotEmpty()) {
                val kept = f.backendTail.filterNot(::routine)
                appendLine()
                appendLine(
                    "backend log (oldest first, ${kept.size} lines" +
                        (f.backendTail.size - kept.size).let { if (it > 0) "; $it routine QNN lines left out" else "" } +
                        "):",
                )
                kept.forEach { appendLine(redact(it, prompts)) }
            }
            if (f.appLog.isNotEmpty()) {
                appendLine()
                appendLine("app log (oldest first):")
                f.appLog.forEach { appendLine(redact(it, prompts)) }
            }
        }
    }

    /** `MemAvailable` from `/proc/meminfo`; 0 when unreadable. */
    private fun availableRamBytes(): Long = runCatching {
        File("/proc/meminfo").useLines { lines ->
            lines.first { it.startsWith("MemAvailable:") }.filter { it.isDigit() }.toLong() * 1024L
        }
    }.getOrDefault(0L)

    /**
     * ⭐ Gather and render. [appLog] is the run bar's own log, oldest first;
     * [prompts] every prompt on the canvas, which is what [redact] removes.
     */
    fun build(
        context: Context,
        message: String,
        appLog: List<String>,
        prompts: Collection<String>,
    ): String {
        val caps = DeviceProbe.caps()
        val modelId = runCatching { SelectedModel.id }.getOrNull()
        val spec = modelId?.let { ModelCatalog.byId(it) }
        val files = spec?.let { s ->
            runCatching {
                s.dir(context).listFiles().orEmpty()
                    .filter { it.isFile }
                    .sortedByDescending { it.length() }
                    .take(16)
                    .map { it.name to it.length() }
            }.getOrNull()
        }.orEmpty()
        val facts = Facts(
            message = message,
            nowMs = System.currentTimeMillis(),
            version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            chip = "${caps.soc.ifBlank { Build.HARDWARE }} · HTP v${caps.arch}" +
                when {
                    caps.measured -> " (measured)"
                    caps.known -> " (from the chip table)"
                    else -> " (unknown chip — the floor)"
                } + " · VTCM ${caps.vtcmMb} MB",
            ramTotal = DeviceProbe.totalRamBytes(),
            ramAvailable = availableRamBytes(),
            storageFree = runCatching { ModelStorage.root(context).usableSpace }.getOrDefault(0L),
            model = spec?.let { "${it.label} [${it.id}] · ${it.family} · ${it.backendType}" } ?: modelId,
            modelFiles = files,
            backendRunning = BackendProcess.isRunning,
            backendExit = BackendProcess.lastExit,
            backendTail = BackendProcess.tail(),
            appLog = appLog,
            failure = last,
        )
        return render(facts, prompts)
    }
}
