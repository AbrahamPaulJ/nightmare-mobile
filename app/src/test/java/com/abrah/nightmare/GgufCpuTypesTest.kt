package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Test

/** ⭐ GitHub #112: a K-quant DiT GGUF is named in the run log ([GgufHeader.cpuTypes]); a stray K-quant tensor is not. */
class GgufCpuTypesTest {

    private fun table(vararg types: String) =
        GgufHeader.Table("{}", types.toSet(), types.size, types.withIndex().associate { (i, t) -> "t$i" to t })

    @Test
    fun aKQuantModelIsNamedAndAStrayHeadIsNot() {
        // A Q4_K_M upload: Q4_K matmuls, Q6_K some, F32 norms.
        assertEquals(setOf("Q4_K", "Q6_K"), GgufHeader.cpuTypes(table(*Array(60) { "Q4_K" }, *Array(20) { "Q6_K" }, *Array(20) { "F32" })))
        // A Q4_0 model with one Q6_K tensor: the NPU runs it.
        assertEquals(emptySet<String>(), GgufHeader.cpuTypes(table(*Array(80) { "Q4_0" }, "Q6_K", *Array(19) { "F32" })))
        assertEquals(emptySet<String>(), GgufHeader.cpuTypes(table(*Array(50) { "Q8_0" }, *Array(50) { "BF16" })))
        assertEquals(emptySet<String>(), GgufHeader.cpuTypes(table()))
    }
}
