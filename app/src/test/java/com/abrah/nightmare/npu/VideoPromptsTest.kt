package com.abrah.nightmare.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ⭐ Each video model's opening prompt ([VideoPrompts]): one per model, and a switch never replaces the person's own. */
class VideoPromptsTest {

    @Test
    fun everyVideoModelHasItsOwnPrompt() {
        val t2v = listOf(WanVideoNode.name, WanNsfw13Node.name, H3Node.name, VideoGate.VIDEO_TYPE).map { VideoPrompts.forType(it, i2v = false) }
        assertEquals(t2v.size, t2v.toSet().size)
        assertNotEquals(VideoPrompts.forType(WanVideoNode.name, true), VideoPrompts.forType(WanVideoNode.name, false))
        assertEquals(VideoPrompts.WAN14_I2V, VideoPrompts.forType(WanA14Node.name, true))
        assertEquals(VideoPrompts.MOTION, VideoPrompts.forType(WanFunNode.name, true))
        // ⚠ No fox: the user's call, 2026-10-30.
        assertTrue(listOf(VideoPrompts.WAN_T2V, VideoPrompts.WAN_I2V, VideoPrompts.WAN14_I2V, VideoPrompts.WAN14_T2V, VideoPrompts.NSFW13,
            VideoPrompts.NEODRAGON, VideoPrompts.MOTION, VideoPrompts.H3).none { "fox" in it.lowercase() })
    }

    @Test
    fun onlyAnOpeningPromptIsReplaced() {
        assertTrue(VideoPrompts.isDefault(""))
        assertTrue(VideoPrompts.isDefault(VideoPrompts.WAN_T2V))
        assertTrue(VideoPrompts.isDefault("  " + VideoPrompts.H3 + "\n"))
        // A flow saved with 1.6.260's fox still swaps.
        assertTrue(VideoPrompts.isDefault("a red fox trotting through a snowy pine forest, soft morning light, cinematic"))
        assertFalse(VideoPrompts.isDefault("my own cat on the sofa"))
        assertFalse(VideoPrompts.isDefault(VideoPrompts.WAN_T2V + " at night"))
    }

    @Test
    fun theRecipesOpenOnTheTable() {
        fun prompt(w: com.abrah.nightmare.canvas.Workflow) = w.graph.nodes.first { it.type == "core.prompt" }.params["prompt"]
        assertEquals(VideoPrompts.WAN_T2V, prompt(com.abrah.nightmare.canvas.wanVideoWorkflow(false)))
        assertEquals(VideoPrompts.WAN_I2V, prompt(com.abrah.nightmare.canvas.wanVideoWorkflow(true)))
        assertEquals(VideoPrompts.MOTION, prompt(com.abrah.nightmare.canvas.motionTransferWorkflow()))
        assertEquals(VideoPrompts.NEODRAGON, prompt(com.abrah.nightmare.canvas.textToVideoWorkflow()))
    }
}
