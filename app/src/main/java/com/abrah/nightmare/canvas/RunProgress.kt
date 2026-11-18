package com.abrah.nightmare.canvas

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * ⭐⭐ ONE bar for the whole run (the user, 2026-11-19, after a tester's screen recording, Saved #8241-8244): "Starting"
 * drew Material's indeterminate sweep, which reads as a jump to 80 %, then `generate 0/8 · 0%` sat still for ~13 s while
 * the model loaded and the prompt was read, then leapt to 8/8. Now every surface (the run bar, the output page's ring,
 * the shade) draws [fraction]:
 *
 * - before the node's first step, the PREPARING share fills by the time it took last time ([Learned.prepMs]), stopping
 *   short of the share so it never claims a step that has not come;
 * - from the first step, the steps fill the rest.
 *
 * The share is this model's own (prep / whole node), learned per model from its runs as a running average, because
 * MOSS spends most of a short run preparing and SD 1.5 almost none: one fixed split would lie for both.
 */
object RunProgress {

    /** A model's last runs: from the clock's start to its first step, and to its node's end. */
    data class Learned(val prepMs: Long, val totalMs: Long)

    private const val PREFS = "run_progress"
    private val learned = ConcurrentHashMap<String, Learned>()

    @Volatile
    private var prefs: SharedPreferences? = null

    /** The share preparing gets when the model has never run here. */
    const val DEFAULT_SHARE = 0.15f
    const val DEFAULT_PREP_MS = 10_000L

    /** ⚠ The preparing share stops here until a step lands: a bar that fills while nothing is counted would be a guess. */
    private const val PREP_CAP = 0.95f

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        for ((k, v) in p.all) parse(v as? String)?.let { learned[k] = it }
    }

    internal fun parse(v: String?): Learned? {
        val parts = v?.split(',') ?: return null
        val prep = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val total = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return Learned(prep, total).takeIf { total > 0 && prep in 0..total }
    }

    fun of(key: String?): Learned? = key?.let { learned[it] }

    /** ⭐ A node with steps ended: fold its times into the model's average (half old, half new). */
    fun record(key: String?, prepMs: Long, totalMs: Long) {
        if (key == null || totalMs <= 0 || prepMs !in 0..totalMs) return
        val n = averaged(learned[key], Learned(prepMs, totalMs))
        learned[key] = n
        prefs?.edit()?.putString(key, "${n.prepMs},${n.totalMs}")?.apply()
    }

    internal fun averaged(old: Learned?, new: Learned): Learned =
        if (old == null) new else Learned((old.prepMs + new.prepMs) / 2, (old.totalMs + new.totalMs) / 2)

    @androidx.annotation.VisibleForTesting
    internal fun clearForTest() { learned.clear(); prefs = null }

    /**
     * ⚠ Pure: the whole run's fraction for a node at [step] (done-of-total, the backend's own count), [sinceMs] after its
     * clock started, given what [learnedRun] says about this model. Never moves backwards within a node: the preparing part
     * stops below the share, and steps start at it.
     */
    fun fraction(step: Pair<Int, Int>?, sinceMs: Long, learnedRun: Learned?): Float {
        val share = learnedRun?.let { (it.prepMs.toFloat() / it.totalMs).coerceIn(0.05f, 0.9f) } ?: DEFAULT_SHARE
        val s = step?.takeIf { it.second > 0 && it.first > 0 }
        if (s != null) return share + (1f - share) * (s.first.toFloat() / s.second).coerceIn(0f, 1f)
        val prep = (learnedRun?.prepMs ?: DEFAULT_PREP_MS).coerceAtLeast(1_000L)
        return share * (sinceMs.coerceAtLeast(0L).toFloat() / prep).coerceIn(0f, PREP_CAP)
    }
}
