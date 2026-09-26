package com.abrah.nightmare.segment

import com.abrah.nightmare.MaskNode
import com.abrah.nightmare.MaskOp
import com.abrah.nightmare.MaskState
import com.abrah.nightmare.SdSampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ⭐⭐⭐ Enable auto mask is remembered FOR THAT FLOW — on the node — and
 * applied to every new photo through [MaskNode.opsOf]. The user's call,
 * 2026-09-23: *"remembered for that flow and auto applied after i crop"*.
 */
class AutoMaskTest {

    private val inpaint = SdSampler.ALL.first { it.inpaint }.name
    private val plain = SdSampler.ALL.first { !it.inpaint }.name
    private val on = mapOf(SdSampler.PICK_SELECT to "true")

    private fun picks(ops: String?) = MaskState.decode(ops).ops

    @Test
    fun ticked_withNoMaskYet_startsFromClothes() {
        assertEquals(listOf(MaskOp.Pick("clothes")), picks(MaskNode.opsOf(inpaint, on)))
    }

    @Test
    fun theNodesOwnChipsAreUsed() {
        val ops = MaskNode.opsOf(inpaint, on + (SdSampler.PICK_TARGETS to "face,hair"))
        assertEquals(listOf(MaskOp.Pick("face"), MaskOp.Pick("hair")), picks(ops))
    }

    /**
     * ⚠⚠⚠ The reported bug, 1.6.019: an EMPTY mask string (a Clear, an Undo,
     * the last chip toggled off and on) switched the auto mask off while its
     * box stayed ticked, and Run said "nothing masked". The tick wins now.
     */
    @Test
    fun anEmptyMaskStringDoesNotSwitchTheAutoMaskOff() {
        assertEquals(
            listOf(MaskOp.Pick("clothes")),
            picks(MaskNode.opsOf(inpaint, on + (MaskNode.OPS to ""))),
        )
    }

    @Test
    fun paintingAddsOnTopOfTheChips() {
        val stroke = "s0.3:0.5,0.5~0,0.02"
        val ops = picks(MaskNode.opsOf(inpaint, on + (MaskNode.OPS to stroke)))
        assertEquals(MaskOp.Pick("clothes"), ops.first())
        assertEquals(2, ops.size)
    }

    @Test
    fun deselectingEveryChipLeavesOnlyThePainting() {
        assertNull(MaskNode.opsOf(inpaint, on + (SdSampler.PICK_TARGETS to "")))
    }

    @Test
    fun untickedDropsPicksStoredByEarlierBuilds() {
        val ops = picks(MaskNode.opsOf(inpaint, mapOf(MaskNode.OPS to "pclothes;s0.3:0.5,0.5~0,0.02")))
        assertEquals(1, ops.size)
        assertEquals(false, ops.first() is MaskOp.Pick)
    }

    @Test
    fun offInANewFlowAndOnImageToImage() {
        assertNull("off by default", MaskNode.opsOf(inpaint, emptyMap()))
        assertNull("image-to-image has no mask", MaskNode.opsOf(plain, on))
    }
}
