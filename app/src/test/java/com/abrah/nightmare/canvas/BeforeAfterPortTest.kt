package com.abrah.nightmare.canvas

import com.abrah.nightmare.Graph
import com.abrah.nightmare.MediaOutputNode
import com.abrah.nightmare.NODE_TYPES
import com.abrah.nightmare.Node
import com.abrah.nightmare.UpscaleNode
import com.abrah.nightmare.sources
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ⭐⭐⭐ **A node's input port is asked of its TYPE, never written out.**
 *
 * ⚠⚠⚠ The same two port names have now broken two features. `core.output`
 * reads its picture on **`media`** — the union type that takes a picture or a
 * clip — and `image.upscale` reads its on **`image`**:
 *
 * | what | wrote | should have asked | cost |
 * |---|---|---|---|
 * | `insertUpscale` | `inputs["image"]` | the output's own wire | the upscale button could never find a wire, one whole release |
 * | `applyBeforeAfterPreviews` | `pictureInto(id, types)`, whose port defaults to `image` | the type's first input | an auto-upscaling output recorded NO before picture, and I reported it as working |
 *
 * ⇒ This test exists to make the NEXT one of those fail here instead of on the
 * phone. It does not test a feature; it tests that a rule is being followed.
 */
class BeforeAfterPortTest {

    private val types = NODE_TYPES

    /** ⚠ The rule, stated once: the port a node reads is the type's first input. */
    private fun portOf(n: Node) = types[n.type]?.inputs?.firstOrNull()?.name ?: "image"

    @Test
    fun theOutputNodeReadsItsPictureOnMedia() {
        assertEquals("media", portOf(Node("out", MediaOutputNode.name)))
    }

    @Test
    fun theUpscaleNodeReadsItsPictureOnImage() {
        assertEquals("image", portOf(Node("up", UpscaleNode.name)))
    }

    /**
     * ⭐⭐ The whole point: `pictureInto`'s DEFAULT port finds nothing on an
     * output node, and the resolved one finds the wire.
     *
     * ⚠ If this ever stops failing on the default, the default has changed and
     * the comment above needs rewriting rather than the test deleting.
     */
    @Test
    fun theDefaultPortMissesAnOutputNodesWire() {
        val g = Graph(
            listOf(
                Node("gen", "sd15.sample"),
                Node("out", MediaOutputNode.name, inputs = sources("media" to "gen")),
            ),
        )
        val st = CanvasState(Workflow(g, emptyMap()))
            .copy(rendered = mapOf("gen" to "img_before"))

        assertEquals(
            "the default port is `image`, which an output node does not have",
            null,
            st.pictureInto("out", types),
        )
        assertEquals(
            "asking the type for the port finds what the output received",
            "img_before",
            st.pictureInto("out", types, portOf(g.byId.getValue("out"))),
        )
    }

    /** ⚠ And the same call on an upscale node, which is where it always worked. */
    @Test
    fun anUpscaleNodeResolvesEitherWay() {
        val g = Graph(
            listOf(
                Node("gen", "sd15.sample"),
                Node("up", UpscaleNode.name, inputs = sources("image" to "gen")),
            ),
        )
        val st = CanvasState(Workflow(g, emptyMap()))
            .copy(rendered = mapOf("gen" to "img_before"))
        assertEquals("img_before", st.pictureInto("up", types))
        assertEquals("img_before", st.pictureInto("up", types, portOf(g.byId.getValue("up"))))
    }

    /** ⭐ Auto-upscale is read through ONE function, so three surfaces agree. */
    @Test
    fun autoUpscaleIsReadInOnePlace() {
        val off = Node("out", MediaOutputNode.name)
        val on = Node("out", MediaOutputNode.name, mapOf(MediaOutputNode.UPSCALE to "true"))
        assertEquals(false, MediaOutputNode.autoUpscales(off))
        assertEquals(true, MediaOutputNode.autoUpscales(on))
        // ⚠ A different node type never auto-upscales, whatever its params say.
        assertEquals(
            false,
            MediaOutputNode.autoUpscales(
                Node("x", "core.prompt", mapOf(MediaOutputNode.UPSCALE to "true")),
            ),
        )
    }
}
