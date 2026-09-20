package com.abrah.nightmare.canvas

import com.abrah.nightmare.NODE_TYPES
import com.abrah.nightmare.Node
import com.abrah.nightmare.SdSampler
import com.abrah.nightmare.sources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐ The Add node sheet as three tabs of cards (2026-09-17): Common, Generate,
 * Inpaint; one card per JOB with a chip per family, and no "sample" anywhere a
 * person reads.
 */
class PaletteTest {

    private val sections = paletteTabs(NODE_TYPES).toMap()

    @Test fun threeTabsInOrder() =
        assertEquals(listOf("common", "generate", "inpaint"), paletteTabs(NODE_TYPES).map { it.first })

    /** ⚠ The light, family-agnostic nodes, in the order a flow is built. */
    @Test fun commonHoldsTheAgnosticNodes() = assertEquals(
        listOf("core.image", "core.prompt", "image.upscale", "core.output"),
        sections.getValue("common").map { it.single().name },
    )

    /** ⚠ It was registered all along, but its card read like the SD one. */
    @Test fun videoIsInGenerate() {
        val generate = sections.getValue("generate")
        assertEquals(listOf("Image", "Video"), generate.map { it.first().paletteName })
        assertTrue(generate.any { card -> card.any { it.name == "nd.sample" } })
    }

    @Test fun inpaintIsItsOwnTab() {
        val inpaint = sections.getValue("inpaint")
        // ⭐ The samplers, and the segment model (docs/SEGMENTER.md), which only feeds them.
        assertEquals(listOf("Inpaint", "Segment model"), inpaint.map { it.first().paletteName })
        // ⚠ No FLUX.2: `flux2.inpaint` is written but NOT registered, because
        // the engine honours the mask and then regenerates nothing inside it
        // (measured 2026-09-20, `SdSampler.ALL`). This list is what guards
        // that decision — registering it silently would show up here first.
        assertEquals(listOf("SD 1.5", "SDXL", "Anima"), inpaint.first().map { it.paletteVariant })
        assertFalse(sections.getValue("generate").flatten().any { it.name.endsWith(".inpaint") })
    }

    @Test fun threeFamiliesAreOneCard() {
        val generate = sections.getValue("generate")
        val sd = generate.single { card -> card.any { it.name == SdSampler.SDXL.name } }
        assertEquals(
            listOf("sd15.sample", "sdxl.sample", "anima.sample", "flux2.sample", "zimage.sample"),
            sd.map { it.name },
        )
    }

    /** ⚠ The port that makes the Tap tool appear exists only on the inpaint types. */
    @Test fun onlyInpaintTakesASegmenter() {
        for (t in com.abrah.nightmare.SdSampler.ALL) {
            assertEquals(t.name, t.inpaint, t.inputs.any { it.type == com.abrah.nightmare.SelectObjectNode.PORT_TYPE })
        }
    }

    @Test fun hiddenTypesAreNotOffered() =
        assertFalse(sections.values.flatten().flatten().any { it.hidden })

    @Test fun nothingAPersonReadsSaysSample() {
        for (t in sections.values.flatten().flatten()) {
            assertFalse(t.name, t.paletteName.contains("sample", ignoreCase = true))
        }
        val n = Node("s", SdSampler.SDXL.name)
        assertEquals("SDXL Text to image", SdSampler.SDXL.titleFor(n))
        assertEquals(
            "SDXL Image to image",
            SdSampler.SDXL.titleFor(n.copy(inputs = sources("image" to "photo"))),
        )
        assertEquals("Anima Inpaint", SdSampler.ANIMA_INPAINT.titleFor(n))
        assertEquals("sdxl_inpaint", SdSampler.SDXL_INPAINT.defaultId)
        assertTrue(inpaintWorkflow().graph.nodes.none { it.id.contains("sample") })
    }
}
