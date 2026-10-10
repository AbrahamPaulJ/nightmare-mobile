package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ⭐ The ControlNet rows name a chip's own build only when it is not the common one. */
class ControlNetLabelTest {

    private fun caps(arch: Int, soc: String) = DeviceProbe.Caps(arch = arch, vtcmMb = 8, measured = true, soc = soc)

    @Test
    fun anEightGenOneSeesItsOwnBuildNamed() {
        val c = caps(69, "SM8450")
        assertEquals("canny/controlnet_8gen1.bin", ControlNetCatalog.buildFor(SwapInputs.CANNY, c)?.path)
        assertEquals(R.string.controlnet_build_8gen1, ControlNetCatalog.buildLabelRes(SwapInputs.CANNY, c))
        assertEquals(R.string.controlnet_build_8gen1, ControlNetCatalog.buildLabelRes(SwapInputs.OPENPOSE, c))
    }

    @Test
    fun theCommonBuildAndSdxlStayUnlabelled() {
        val c = caps(79, "SM8750")
        assertNull(ControlNetCatalog.buildLabelRes(SwapInputs.CANNY, c))
        assertNull(ControlNetCatalog.buildLabelRes(ControlNetCatalog.idFor(Family.SDXL_SWAP, SwapInputs.CANNY), c))
    }
}
