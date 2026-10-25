package com.abrah.nightmare.canvas

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.abrah.nightmare.Node
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ⚠⚠ The Wan video node's sheet draws its size ONCE (reported from the phone, 2026-10-15: the Default
 * view showed Width, Height and Width again, and the size hint three times). Counted by text, so it
 * fails whichever surface draws the second copy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class WanSheetTest {

    @get:Rule
    val fastIdle = com.abrah.nightmare.FastIdle()

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theWanSizeIsDrawnOnce() {
        val wan = com.abrah.nightmare.npu.WanVideoNode
        rule.setContent {
            MaterialTheme {
                NodeInspectorBody(
                    nodeId = "video",
                    node = Node("video", wan.name, params = mapOf("seed" to "0")),
                    type = wan,
                    onSetParam = { _, _, _ -> },
                    onDelete = {},
                    onReset = {},
                )
            }
        }
        rule.onAllNodesWithText("Width", substring = true).assertCountEquals(1)
        rule.onAllNodesWithText("Height", substring = true).assertCountEquals(1)
        rule.onAllNodesWithText("the size Wan 2.2 is made for", substring = true).assertCountEquals(1)
        // ⭐ The LoRA row, as on the image nodes (1.6.162; 1.6.160 shipped Wan LoRAs as prompt tags only).
        org.junit.Assert.assertTrue(
            "no LoRA row on the Wan sheet",
            rule.onAllNodesWithText("LoRA", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    /** ⭐ The 14B's own size advice under the picker (its timings), never the 5B's "three times faster". */
    @Test
    fun theWan14ShowsItsOwnSizeAdvice() {
        val a14 = com.abrah.nightmare.npu.WanA14Node
        rule.setContent {
            MaterialTheme {
                NodeInspectorBody(
                    nodeId = "video",
                    node = Node("video", a14.name, params = mapOf("seed" to "0")),
                    type = a14,
                    onSetParam = { _, _, _ -> },
                    onDelete = {},
                    onReset = {},
                )
            }
        }
        rule.onAllNodesWithText("for faces and fine detail", substring = true).assertCountEquals(1)
        rule.onAllNodesWithText("the size Wan 2.2 is made for", substring = true).assertCountEquals(0)
    }
}
