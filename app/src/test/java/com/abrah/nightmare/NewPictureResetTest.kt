package com.abrah.nightmare

import com.abrah.nightmare.canvas.CanvasState
import com.abrah.nightmare.canvas.Workflow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * ⭐ A framing and a mask are normalised to the picture they were made on, so a
 * NEW picture resets both — on every node downstream, not only the next one.
 */
class NewPictureResetTest {

    private val framed = mapOf("x" to "0.2", "y" to "0.1", "w" to "0.5", "h" to "0.5", CropNode.LOCKED to "true")

    private fun state() = CanvasState(
        Workflow(
            Graph(
                listOf(
                    Node("photo", "core.image", mapOf("uri" to "content://old")),
                    Node("i2i", "sd15.sample", framed + ("pad" to "blur"), sources("image" to "photo")),
                    Node(
                        "inpaint", "sd15.inpaint",
                        framed + (MaskNode.OPS to "s0.3:0.5,0.5~0,0.02") + ("grow" to "0.05"),
                        sources("image" to "i2i"),
                    ),
                    Node("other", "sd15.sample", framed),
                ),
            ),
            positions = emptyMap(),
        ),
    )

    @Test fun aNewPictureResetsEveryFramingAndMaskDownstream() {
        val g = state().setParam("photo", "uri", "content://new").workflow.graph
        assertEquals("content://new", g.byId.getValue("photo").params["uri"])
        for (id in listOf("i2i", "inpaint")) {
            val p = g.byId.getValue(id).params
            for (k in framed.keys) assertFalse("$id kept $k", k in p)
        }
        assertFalse(MaskNode.OPS in g.byId.getValue("inpaint").params)
        // ⚠ Settings are not content.
        assertEquals("blur", g.byId.getValue("i2i").params["pad"])
        assertEquals("0.05", g.byId.getValue("inpaint").params["grow"])
        // ⚠ A sampler fed by nothing of this photo is untouched.
        assertEquals(framed, g.byId.getValue("other").params)
    }

    @Test fun theSamePictureAgainResetsNothing() {
        val g = state().setParam("photo", "uri", "content://old").workflow.graph
        assertEquals("0.2", g.byId.getValue("i2i").params["x"])
    }
}
