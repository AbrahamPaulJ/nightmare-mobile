package com.abrah.nightmare

import java.io.File

/**
 * ⭐ The chip's own temperatures, CPU, GPU and NPU, each the hottest of its sensors (GitHub #78: a phone on a cooler
 * keeps its battery cool, so the battery's temperature says nothing about the chip). Read from `/sys/class/thermal`,
 * which an app may read on the S25 (68 zones, the `thermal` harness op, 2026-11-04: `cpu-*`, `gpuss-*`, and the NPU's
 * `nsphvx-*` / `nsphmx-*`). ⚠ Another maker may forbid it: [read] is then null and the readout keeps the battery's.
 * ⚠ The battery's temperature still drives the heat warnings and pause ([BatteryHeat]): that is the battery's sake.
 */
object ChipTemps {

    data class Temps(val cpu: Float?, val gpu: Float?, val npu: Float?) {
        val any: Boolean get() = cpu != null || gpu != null || npu != null
    }

    /** ⚠ Pure: which group a zone's `type` belongs to, or null. */
    internal fun groupOf(type: String): String? {
        val t = type.lowercase()
        return when {
            t.startsWith("cpu") -> "cpu"
            t.startsWith("gpu") -> "gpu"
            t.startsWith("nsp") -> "npu"
            else -> null
        }
    }

    /** ⚠ Pure: a zone's `temp` in m°C to °C; null for a sensor that is off (-273) or absurd. */
    internal fun celsius(raw: String): Float? =
        raw.trim().toLongOrNull()?.let { it / 1000f }?.takeIf { it in -40f..150f }

    /** ⭐ The zones by group, found once: the types do not change while the app runs. */
    private val zones: Map<String, List<File>> by lazy {
        File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }.orEmpty()
            .mapNotNull { z -> runCatching { groupOf(File(z, "type").readText())?.let { it to File(z, "temp") } }.getOrNull() }
            .groupBy({ it.first }, { it.second })
    }

    /** ⭐ Now, or null when the phone lets the app read none of them. Cheap: ~40 tiny reads, every 2 s at most. */
    fun read(): Temps? {
        fun max(g: String) = zones[g]?.mapNotNull { f -> runCatching { celsius(f.readText()) }.getOrNull() }?.maxOrNull()
        return Temps(max("cpu"), max("gpu"), max("npu")).takeIf { it.any }
    }

    /** ⭐ Whether Settings offers the switch: a phone that gives none keeps the battery's. */
    val available: Boolean by lazy { read() != null }
}
