package com.abrah.nightmare

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ⭐ GitHub #147: a CPU without i8mm gets the armv8.2 engine; one core without it counts. */
class CpuFeaturesTest {

    private val v9 = "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp sha512 asimdfhm dit uscat ilrcpc flagm ssbs sb paca pacg dcpodp sve2 flagm2 frint i8mm bf16 bti"
    private val v82 = "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp"

    private fun info(vararg cores: String) =
        cores.mapIndexed { i, f -> "processor\t: $i\nBogoMIPS\t: 49.15\nFeatures\t: $f\nCPU implementer\t: 0x41\n" }.joinToString("\n")

    @Test
    fun anArmv9PhoneHasI8mm() = assertTrue(CpuFeatures.allCoresHave(info(v9, v9, v9), "i8mm"))

    @Test
    fun aTensorG1DoesNot() = assertFalse(CpuFeatures.allCoresHave(info(v82, v82, v82), "i8mm"))

    @Test
    fun oneCoreWithoutItCounts() = assertFalse(CpuFeatures.allCoresHave(info(v9, v82), "i8mm"))

    @Test
    fun aFeatureIsAWholeWordNotASubstring() = assertFalse(CpuFeatures.allCoresHave(info("fp asimd i8mmx"), "i8mm"))
}
