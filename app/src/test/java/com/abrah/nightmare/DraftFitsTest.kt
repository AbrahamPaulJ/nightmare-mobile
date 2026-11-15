package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ⭐ Saved #8046: Use on MiniMax H3, Text to video, and the canvas stayed on Text to image, because the Text to video
 * draft held that flow. A draft that is not of its flow is neither kept nor restored ([draftFits]).
 */
@RunWith(RobolectricTestRunner::class)
class DraftFitsTest {

    private fun build(id: String) = com.abrah.nightmare.canvas.RECIPES.first { it.id == id }.build().graph

    @Test
    fun aTextToImageCanvasIsNoTextToVideoDraft() {
        val t2v = build(com.abrah.nightmare.canvas.T2V)
        val t2i = build("txt2img")
        assertFalse("the bug: Text to image kept as Text to video's draft", draftFits(t2v, t2i))
        assertFalse(draftFits(t2i, t2v))
        assertTrue("a flow is its own draft", draftFits(t2v, t2v))
        assertTrue(draftFits(t2i, t2i))
        // ⭐ Edits stay a draft: a moved node, a changed prompt, another video model on the same node.
        val edited = Graph(t2v.nodes.map { if (it.type in VIDEO_MODEL_TYPES) it.copy(type = com.abrah.nightmare.npu.H3Node.name) else it })
        assertTrue(draftFits(t2v, edited))
    }

    /** ⭐ Saved #8080: FLUX.3's Text to image draft held Fibo 2.0 Edit's canvas, same node id, neither a video. */
    @Test
    fun aPictureDraftOfAnotherModelDoesNotFit() {
        val t2i = build("txt2img")
        val main = t2i.nodes.first { it.type == "core.output" }.inputs.getValue("media").node
        fun withMain(type: String, model: String) =
            Graph(t2i.nodes.map { if (it.id == main) it.copy(type = type, params = it.params + ("model" to model)) else it })
        val flux3 = withMain("flux3.sample", "flux3_klein")
        assertFalse("another generator", draftFits(flux3, withMain("fibo.sample", "fibo2_edit_turbo")))
        assertFalse("the same generator, another model", draftFits(flux3, withMain("flux3.sample", "flux3_other")))
        assertTrue("its own model, edited", draftFits(flux3, withMain("flux3.sample", "flux3_klein")))
    }

    @Test
    fun theRecipeIdComesOutOfItsKey() {
        assertEquals("t2v", draftRecipeId("recipe:t2v"))
        assertEquals("txt2img", draftRecipeId("recipe:txt2img:sd15:abc"))
        assertNull(draftRecipeId("saved:fibo edit"))
        assertNull(draftRecipeId(null))
    }
}
