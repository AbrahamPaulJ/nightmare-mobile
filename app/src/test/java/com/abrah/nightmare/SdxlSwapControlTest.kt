package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐ SDXL Swap's ControlNet and IP-Adapter on the app side (backends 023, 024): which
 * file a node means, which build a phone gets, which head reads the reference.
 */
class SdxlSwapControlTest {

    private fun caps(arch: Int, soc: String = "SM8750") =
        DeviceProbe.Caps(arch = arch, vtcmMb = 8, measured = true, soc = soc)

    @Test
    fun sdxlControlNetsAreTheirOwnIdsAndSd15KeepsItsBareTypes() {
        assertEquals("canny", ControlNetCatalog.idFor(Family.SD15_SWAP, SwapInputs.CANNY))
        assertEquals("sdxl_canny", ControlNetCatalog.idFor(Family.SDXL_SWAP, SwapInputs.CANNY))
        assertEquals("canny", ControlNetCatalog.idFor(null, SwapInputs.CANNY))
        assertEquals(Family.SDXL_SWAP, ControlNetCatalog.entry("sdxl_depth")!!.family)
    }

    @Test
    fun aPhoneGetsTheHighestSdxlBuildAtOrBelowItsArch() {
        assertEquals("canny/controlnet_v79.bin", ControlNetCatalog.buildFor("sdxl_canny", caps(79))!!.path)
        assertEquals("canny/controlnet_v81.bin", ControlNetCatalog.buildFor("sdxl_canny", caps(81, "SM8850"))!!.path)
        assertEquals("depth/controlnet_v75.bin", ControlNetCatalog.buildFor("sdxl_depth", caps(75, "SM8650"))!!.path)
        // ⚠ Below v75 there is no SDXL ControlNet — said, never a download that fails at load.
        assertNull(ControlNetCatalog.buildFor("sdxl_canny", caps(73, "SM8550")))
        assertEquals("openpose/controlnet_v79.bin", ControlNetCatalog.buildFor("sdxl_openpose", caps(79))!!.path)
        // SD 1.5's tier rule is unchanged.
        assertEquals("canny/controlnet_8gen2.bin", ControlNetCatalog.buildFor("canny", caps(79))!!.path)
    }

    /**
     * ⭐ SD 1.5's three tiers (2026-10-09): an 8 Gen 1 (v69, 8 MB) gets the `8gen1` build —
     * its `_min` canny failed to load on a user's phone — and openpose now reaches it; a v68
     * with 2 MB keeps `min`; a chip newer than every Skel here gets nothing.
     */
    @Test
    fun sd15TiersByChip() {
        assertEquals("canny/controlnet_8gen1.bin", ControlNetCatalog.buildFor("canny", caps(69, "SM8450"))!!.path)
        assertEquals("openpose/controlnet_8gen1.bin", ControlNetCatalog.buildFor("openpose", caps(69, "SM8450"))!!.path)
        val v68 = DeviceProbe.Caps(arch = 68, vtcmMb = 2, measured = true, soc = "SM8350")
        assertEquals("depth/controlnet_min.bin", ControlNetCatalog.buildFor("depth", v68)!!.path)
        assertNull(ControlNetCatalog.buildFor("openpose", v68))
        assertNull(ControlNetCatalog.buildFor("canny", caps(DeviceProbe.NEWER_THAN_STAGED, "SM8950")))
    }

    @Test
    fun theHintIsTheFamilysSize() {
        assertEquals(512, SwapInputs.sizeFor(Family.SD15_SWAP))
        assertEquals(1024, SwapInputs.sizeFor(Family.SDXL_SWAP))
    }

    @Test
    fun theNodesIpChoiceMeansTheFamilysHead() {
        assertEquals(IpAdapter.PLUS, IpAdapter.adapterFor(Family.SD15_SWAP, IpAdapter.PLUS))
        assertEquals(IpAdapter.SDXL_PLUS, IpAdapter.adapterFor(Family.SDXL_SWAP, IpAdapter.PLUS))
        assertEquals(IpAdapter.SDXL_FACE, IpAdapter.adapterFor(Family.SDXL_SWAP, IpAdapter.FACE))
        assertEquals(IpAdapter.NONE, IpAdapter.adapterFor(Family.SDXL_SWAP, IpAdapter.NONE))
        // ⚠ The chooser offers the CHOICE, never a family's head.
        assertFalse(IpAdapter.SDXL_PLUS in IpAdapter.CHOICES)
        assertTrue(IpAdapter.SDXL_PLUS in IpAdapter.ADAPTERS)
    }

    @Test
    fun bothSwapFamiliesTakeControlAndReferencePictures() {
        for (t in listOf(SdSampler.SD15_SWAP, SdSampler.SDXL_SWAP, SdSampler.SDXL_SWAP_INPAINT)) {
            assertTrue(t.name, SdSampler.isSwapType(t.name))
            val ports = t.inputs.map { it.name }
            assertTrue(t.name, SdSampler.CONTROL in ports && "reference" in ports)
            val knobs = t.widgets.map { it.name }
            assertTrue(t.name, SdSampler.CONTROLNET in knobs && SdSampler.IP_ADAPTER in knobs)
        }
        assertFalse(SdSampler.isSwapType(SdSampler.SDXL.name))
    }
}
