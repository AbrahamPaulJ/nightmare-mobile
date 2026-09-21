package com.abrah.nightmare

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.util.Log
import java.io.File

/**
 * ⭐⭐⭐ **What killed the app last time, shown once, the next time it opens.**
 *
 * ⚠⚠⚠ This project's most expensive lesson is *read the tombstone before
 * calling anything a memory problem* — five builds and a whole day were spent on
 * 2026-09-12 diagnosing a SIGSEGV from **when** it fired rather than from the
 * `F DEBUG` block, which had the answer on day one (`CLAUDE.md`,
 * `docs/NEODRAGON.md` §5b). That block is only reachable over adb. A person
 * holding the phone sees the app vanish and reopen with nothing to say, and the
 * one artefact that explains it is the one they cannot get to.
 *
 * ⇒ `ActivityManager.getHistoricalProcessExitReasons` is the supported way to
 * read it from inside the app: reason, description, and for a native crash the
 * **tombstone itself** through `getTraceInputStream()`. Both are API 30 and
 * `minSdk` is 31, so neither needs a version guard.
 *
 * ## ⚠⚠⚠ The hard part is NOT reporting, it is NOT reporting
 *
 * Asked for explicitly, 2026-09-21: *"make sure its only for app opens after
 * crashes, false positives would be annoying"*. Android kills apps constantly
 * and almost none of it is a crash — a background trim, a swipe away, a
 * configuration change. A card that cried "crashed!" after every routine
 * reclaim would be worse than nothing, because it would be ignored by the time
 * a real one arrived.
 *
 * So a report needs BOTH halves to line up:
 *
 * 1. **The reason has to be one that means something.** [REAL_CRASH] always
 *    counts — a Java exception, a native SIGSEGV or an ANR is never routine.
 *    [KILLED] counts only conditionally (below). Everything else — user
 *    requested, user stopped, exit self, permission change, dependency died,
 *    `REASON_OTHER` — is dropped outright. `REASON_OTHER` in particular is what
 *    an ordinary swipe-away reports, and treating it as a crash is the single
 *    easiest way to get this wrong.
 * 2. **For a KILL, the app has to have been DOING something** — [breadcrumb].
 *    Being reclaimed while idle in the background is Android working as
 *    designed and is not worth a word. Being reclaimed while loading a 6.7 GB
 *    checkpoint is the most important thing this app could tell anyone, and it
 *    is exactly what happened on this phone at 1024² (`docs/MODELS.md` §9).
 *
 * ⚠ And it is shown ONCE: [LAST_SHOWN] holds the timestamp of the newest exit
 * already reported, so reopening the app twice does not report the same death
 * twice.
 */
object CrashReport {

    private const val TAG = "CrashReport"
    private const val PREFS = "crash-report"

    /** ⚠ The timestamp of the newest exit already shown, so nothing repeats. */
    private const val LAST_SHOWN = "last_shown_ms"

    /**
     * ⭐ Exits that are ALWAYS worth reporting. None of these happens to a
     * healthy app, so none of them needs corroborating.
     */
    private val REAL_CRASH = setOf(
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
    )

    /**
     * ⚠⚠ Exits that are worth reporting ONLY if the app was mid-job. These are
     * the shapes an lmkd reclaim takes, and a backgrounded app being reclaimed
     * is routine — see the class note.
     */
    private val KILLED = setOf(
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_SIGNALED,
    )

    /**
     * ⭐⭐ What the app was in the middle of, or absent when it was idle.
     *
     * ⚠ A FILE, not a field: the point is to survive the process dying, which
     * is the one moment a field cannot. Deleted on a clean finish, so its mere
     * existence at startup means "the last run never ended".
     */
    private fun breadcrumb(context: Context) = File(context.filesDir, "in-flight.txt")

    /** ⚠ In memory so [mark] can be called freely; the file is written once per job. */
    @Volatile
    private var marked: String? = null

    /**
     * ⭐ "The app is now doing [what]" — call before anything that loads a
     * model or downloads gigabytes.
     *
     * ⚠ Idempotent and cheap to call repeatedly: a download ticks every 150 ms
     * and this must not be 150 ms of file writes.
     */
    fun mark(context: Context, what: String) {
        if (marked == what) return
        marked = what
        runCatching { breadcrumb(context).writeText(what) }
    }

    /** ⭐ "…and it finished." ⚠ Must be reached on EVERY exit path, including failure. */
    fun clear(context: Context) {
        if (marked == null && !breadcrumb(context).exists()) return
        marked = null
        runCatching { breadcrumb(context).delete() }
    }

    /** ⭐ One report, ready to show. [pending] returns null far more often. */
    data class Report(
        /** When the process died. */
        val whenMs: Long,
        /** One line: what Android called it. */
        val headline: String,
        /** What the app was doing, if it was doing anything. */
        val doing: String?,
        /** ⭐ The tombstone or ANR trace, when Android kept one. Often null. */
        val trace: String?,
    )

    /**
     * ⭐⭐⭐ The whole decision, in one function so there is one place to argue
     * with — see the class note for why each clause is there.
     *
     * ⚠ Reads the breadcrumb and CLEARS it: the question "was it mid-job" is
     * only meaningful about the exit being reported, and leaving the file would
     * make the next routine reclaim look like a crash.
     */
    fun pending(context: Context): Report? {
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        val doing = runCatching {
            breadcrumb(context).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
        clear(context)

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastShown = prefs.getLong(LAST_SHOWN, 0L)

        val exits = runCatching {
            am.getHistoricalProcessExitReasons(context.packageName, 0, 8)
        }.getOrNull().orEmpty()
        // ⚠ Newest first, which is the order Android documents.
        val exit = exits.firstOrNull { it.timestamp > lastShown && worthReporting(it, doing) }
            ?: return null

        // ⚠⚠ Recorded BEFORE the caller shows anything. A report that fails to
        // render must still not come back on every launch forever.
        prefs.edit().putLong(LAST_SHOWN, exit.timestamp).apply()

        val trace = runCatching {
            exit.traceInputStream?.bufferedReader()?.use { it.readText() }
        }.getOrNull()?.takeIf { it.isNotBlank() }
        Log.i(TAG, "reporting exit ${exit.reason} at ${exit.timestamp} (doing=$doing)")
        return Report(exit.timestamp, headlineFor(exit), doing, trace)
    }

    private fun worthReporting(exit: ApplicationExitInfo, doing: String?): Boolean = when (exit.reason) {
        in REAL_CRASH -> true
        // ⚠⚠ The clause the whole design turns on. No breadcrumb means the app
        // was idle when the system took it, which is Android working normally.
        in KILLED -> doing != null
        else -> false
    }

    /** ⚠ Android's own words where it has them; its reason code where it does not. */
    private fun headlineFor(exit: ApplicationExitInfo): String {
        val what = when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH -> "the app crashed"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "the app crashed in native code"
            ApplicationExitInfo.REASON_ANR -> "the app stopped responding"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "Android closed the app to free memory"
            ApplicationExitInfo.REASON_SIGNALED -> "Android stopped the app"
            else -> "the app closed unexpectedly"
        }
        val detail = exit.description?.trim()?.takeIf { it.isNotEmpty() }
        return if (detail != null) "$what — $detail" else what
    }
}
