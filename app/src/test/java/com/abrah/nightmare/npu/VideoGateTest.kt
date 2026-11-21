package com.abrah.nightmare.npu

import com.abrah.nightmare.DeviceProbe
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ⭐ GitHub #122: Neodragon's contexts are v79 binaries, so a chip KNOWN to be below v79 never sees it. */
class VideoGateTest {

    private fun caps(arch: Int, soc: String, qualcomm: Boolean = true) =
        DeviceProbe.Caps(arch = arch, vtcmMb = 8, measured = false, soc = soc, qualcomm = qualcomm)

    @Test
    fun anEightSGen4IsBelowTheFloor() = assertTrue(VideoGate.belowFloor(caps(73, "SM8735")))

    @Test
    fun anEightEliteIsNot() = assertFalse(VideoGate.belowFloor(caps(79, "SM8750")))

    @Test
    fun anUnknownChipStillAsksTheCanary() =
        assertFalse(VideoGate.belowFloor(caps(DeviceProbe.FLOOR_ARCH, "XYZ1234")))

    @Test
    fun aChipKnownNotToBeASnapdragonIsBelowIt() = assertTrue(VideoGate.belowFloor(caps(DeviceProbe.FLOOR_ARCH, "MT6989", qualcomm = false)))
}
