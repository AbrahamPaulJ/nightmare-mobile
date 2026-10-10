package com.abrah.nightmare.ui

import com.abrah.nightmare.agent.ChatItem
import org.junit.Assert.assertEquals
import org.junit.Test

/** ⭐ The agent chat's pure halves: Markdown blocks, and tool calls folded per turn. */
class AgentChatTest {

    @Test
    fun markdownBecomesBlocks() {
        val md = """
            # Your flow is ready
            I set up **txt2img** with:
            - Illustrious XL
            - 20 steps
            1. open Nodes
            ```
            steps: 20
            cfg: 7.5
            ```
            Done.
        """.trimIndent()
        assertEquals(
            listOf(
                MdBlock.Heading(1, "Your flow is ready"),
                MdBlock.Para("I set up **txt2img** with:"),
                MdBlock.Item("•", "Illustrious XL", 0),
                MdBlock.Item("•", "20 steps", 0),
                MdBlock.Item("1.", "open Nodes", 0),
                MdBlock.Code("steps: 20\ncfg: 7.5"),
                MdBlock.Para("Done."),
            ),
            markdownBlocks(md),
        )
    }

    @Test
    fun anUnclosedFenceKeepsItsText() {
        assertEquals(listOf(MdBlock.Code("a = 1")), markdownBlocks("```\na = 1"))
    }

    @Test
    fun consecutiveToolsFoldIntoOneGroup() {
        val items = listOf(
            ChatItem.User("go"),
            ChatItem.Tool("list_models", "", true),
            ChatItem.Tool("new_flow", "txt2img", true),
            ChatItem.Assistant("ok"),
            ChatItem.Tool("run", "", false),
        )
        val b = chatBlocks(items)
        assertEquals(4, b.size)
        assertEquals(2, (b[1] as ChatBlock.Tools).tools.size)
        assertEquals("t1", b[1].key)
        assertEquals(1, (b[3] as ChatBlock.Tools).tools.size)
    }
}
