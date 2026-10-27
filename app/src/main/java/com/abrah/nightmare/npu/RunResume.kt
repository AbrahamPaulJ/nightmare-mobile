package com.abrah.nightmare.npu

import android.content.Context
import com.abrah.nightmare.Node

/**
 * ⭐ The seeds of a Motion transfer run while it is in flight, so a run Android kills can be carried on
 * (the user's call, 2026-10-27).
 *
 * ⚠⚠ A finished part is kept under a key that includes the seed ([WanFunNode]'s parts marker), and a
 * random seed (0) rolls a NEW one every Run, so after a kill the next Run never found its parts. Saved
 * when the run starts, cleared however it ends; only a dead process leaves them behind, and the next Run
 * of the SAME flow takes them back ([pinsFor]). A run that ended normally rolls fresh, as before.
 * ⚠ `commit`, not `apply`: the moment this matters is the process dying before an async write lands.
 */
object RunResume {
    private const val FILE = "run_resume"
    private const val KEY_GRAPH = "graph"
    private const val KEY_SEEDS = "seeds"
    private const val KEY_AT = "at"
    /** ⚠ A day: a record older than that is from a run nobody is coming back to, and must not word a later crash. */
    private const val KEEP_MS = 24 * 60 * 60 * 1000L

    /** ⭐ Only Motion transfer resumes across a kill today ([WanFunNode]'s parts). */
    fun resumable(nodes: List<Node>): Boolean = nodes.any { it.type == WanFunNode.name }

    /** ⚠ What the flow IS, unrolled: ids, types, knobs and wires. A changed knob is a different run. */
    fun keyOf(nodes: List<Node>): String =
        nodes.sortedBy { it.id }.joinToString("|") { n ->
            n.id + ";" + n.type + ";" + n.params.toSortedMap() + ";" + n.inputs.toSortedMap()
        }.hashCode().toString()

    fun save(context: Context, key: String, seeds: Map<String, Int>) {
        if (seeds.isEmpty()) return
        prefs(context).edit()
            .putString(KEY_GRAPH, key)
            .putString(KEY_SEEDS, seeds.entries.joinToString(",") { "${it.key}=${it.value}" })
            .putLong(KEY_AT, System.currentTimeMillis())
            .commit()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().commit()
    }

    /** True when a killed run left its seeds: the crash notice says Run carries on ([pinsFor]). */
    fun pending(context: Context): Boolean = fresh(context) && prefs(context).getString(KEY_GRAPH, null) != null

    private fun fresh(context: Context) = System.currentTimeMillis() - prefs(context).getLong(KEY_AT, 0L) < KEEP_MS

    /** The killed run's seeds, as `runWorkflow`'s `pinned`, when [key] is the same flow; else null. */
    fun pinsFor(context: Context, key: String): Map<String, Map<String, String>>? {
        val p = prefs(context)
        if (!fresh(context) || p.getString(KEY_GRAPH, null) != key) return null
        return parse(p.getString(KEY_SEEDS, null)).takeIf { it.isNotEmpty() }
    }

    /** ⚠ Pure, so it is JVM-tested: `a=1,b=2` to `{a: {seed: 1}, b: {seed: 2}}`, junk dropped. */
    internal fun parse(raw: String?): Map<String, Map<String, String>> =
        raw.orEmpty().split(",").mapNotNull { e ->
            val (id, v) = e.split("=", limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            v.toIntOrNull()?.takeIf { it > 0 }?.let { id to mapOf("seed" to it.toString()) }
        }.toMap()

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
