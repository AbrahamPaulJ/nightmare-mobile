package com.abrah.nightmare

/**
 * ⭐ What this phone's CPU cores can execute, read off `/proc/cpuinfo` (GitHub #147).
 *
 * ⚠ The DiT engine is built `-march=armv8.7a+i8mm`: its quantised matmuls use the int8 matrix instructions, which a
 * Pixel 6's Tensor G1 (Cortex-X1, A76, A55: ARMv8.2 + dotprod) does not have, so the first CPU matmul died of SIGILL
 * (`exited 132`, signal 4). Such a phone gets the armv8.2 build of the engine instead ([DitEngine]).
 */
object CpuFeatures {

    /**
     * ⚠ EVERY core must have it: a thread moves between big and little cores, and one core without the instruction is a
     * SIGILL whenever a matmul lands there. True when the file cannot be read: the shipped engine is the default.
     */
    val hasI8mm: Boolean by lazy {
        runCatching { java.io.File("/proc/cpuinfo").readText() }.getOrNull()?.let { allCoresHave(it, "i8mm") } ?: true
    }

    /** ⚠ Pure, for the test: every `Features` line lists [feature]; no Features line at all reads as yes. */
    fun allCoresHave(cpuinfo: String, feature: String): Boolean {
        val lines = cpuinfo.lines().filter { it.trimStart().startsWith("Features") }
        return lines.all { l -> l.substringAfter(':').split(' ', '\t').any { it == feature } }
    }
}
