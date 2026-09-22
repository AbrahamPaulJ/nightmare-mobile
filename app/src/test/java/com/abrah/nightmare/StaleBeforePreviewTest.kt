package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ⭐⭐⭐ **The Received frame goes away when it stops being a Received frame.**
 *
 * ⚠⚠ Reported from the phone 2026-09-22, and it had shipped twice: untick
 * `auto upscale`, press Run, and the node still drew a "Received" picture from
 * an earlier run above a freshly made one. The rule swept only the nodes that
 * were STILL before/after nodes, so the one that had just stopped being one was
 * never considered.
 */
class StaleBeforePreviewTest {

    /** ⭐ The bug, in one assertion. */
    @Test
    fun aNodeThatStoppedBeingABeforeAfterNodeLosesItsEntry() {
        assertEquals(
            setOf("output"),
            staleBeforePreviews(
                held = setOf("output"),
                // ⚠ `auto upscale` was just unticked, so nothing is one.
                beforeAfter = emptySet(),
                empty = emptySet(),
            ),
        )
    }

    /** ⚠ The half that already worked: still one, but nothing feeds it. */
    @Test
    fun aBeforeAfterNodeWithNothingWiredInLosesItsEntryToo() {
        assertEquals(
            setOf("output"),
            staleBeforePreviews(
                held = setOf("output"),
                beforeAfter = setOf("output"),
                empty = setOf("output"),
            ),
        )
    }

    /**
     * ⚠⚠ …and a node that is still one, with a wire, KEEPS its entry even
     * though this pass resolved no bitmap for it. Dropping it here is what
     * would make the frame blink out and back on every preview pass.
     */
    @Test
    fun aLiveBeforeAfterNodeKeepsItsEntryWhileItsBitmapResolves() {
        assertEquals(
            emptySet<String>(),
            staleBeforePreviews(
                held = setOf("output"),
                beforeAfter = setOf("output"),
                empty = emptySet(),
            ),
        )
    }

    /** ⚠ One node going stale does not take another node's entry with it. */
    @Test
    fun onlyTheStaleOneIsDropped() {
        assertEquals(
            setOf("old"),
            staleBeforePreviews(
                held = setOf("old", "live"),
                beforeAfter = setOf("live"),
                empty = emptySet(),
            ),
        )
    }
}
