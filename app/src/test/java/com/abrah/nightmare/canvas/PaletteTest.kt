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
    /**
     * ⚠⚠ **`image.upscale` is NOT here since 2026-09-22** — it is retired
     * (`UpscaleNode.hidden`), because `core.output` now carries an auto-upscale
     * checkbox and a flow already ends at that node. ⚠ Hidden, not deleted:
     * flows saved with one still load and still run, which is why the type is
     * registered and this list is what guards the retirement.
     */
    @Test fun commonHoldsTheAgnosticNodes() = assertEquals(
        // ⭐ 2026-10-15: the Video node (Motion transfer's driving clip) joined them; 2026-10-21 Face edit and Animate portrait (no checkpoint).
        listOf("core.image", "core.prompt", "core.video", "core.output", "face.animate", "face.edit"),
        sections.getValue("common").map { it.single().name },
    )

    /**
     * ⚠⚠ **`image.upscale` is DELETED, not hidden** — it is out of
     * `NODE_TYPES` entirely (2026-09-22). A saved flow naming one is rewritten
     * on load by `WorkflowIo.migrateUpscaleNodes`, which turns the enlargement
     * on at the output it fed, so nothing breaks and nothing is left behind.
     */
    @Test fun theUpscaleNodeIsGone() {
        assertFalse(com.abrah.nightmare.NODE_TYPES.containsKey("image.upscale"))
        assertTrue(sections.values.flatten().flatten().none { it.name == "image.upscale" })
    }

    /** ⚠ Same for the segmenter — a saved flow with one must keep working. */
    @Test fun theSegmentNodeIsRetiredButStillRegistered() {
        assertTrue(com.abrah.nightmare.NODE_TYPES.containsKey("mask.segment_model"))
        assertTrue(sections.values.flatten().flatten().none { it.name == "mask.segment_model" })
    }

    /** ⚠ It was registered all along, but its card read like the SD one. */
    @Test fun videoIsInGenerate() {
        val generate = sections.getValue("generate")
        // 2026-10-14: Wan 2.2 joined Neodragon as the second video sampler; 2026-10-15 Fun Control.
        assertEquals(listOf("Image", "Neodragon", "Wan 2.2 14B", "Wan 2.2 Fun Control", "Wan 2.2 Turbo"), generate.map { it.first().paletteName })
        assertTrue(generate.any { card -> card.any { it.name == "nd.sample" } })
    }

    @Test fun inpaintIsItsOwnTab() {
        val inpaint = sections.getValue("inpaint")
        // ⭐ The samplers. ⚠⚠ **"Segment model" is gone since 2026-09-22** —
        // tap-to-select is a checkbox on the inpaint node now
        // (`SdSampler.TAP_SELECT`) and the node is retired
        // (`SelectObjectNode.hidden`). Hidden, not deleted: a flow saved with
        // one still loads, still wires into `segmenter` and still gets its Tap
        // tool, which `theSegmentNodeIsRetiredButStillRegistered` pins.
        assertEquals(listOf("Inpaint"), inpaint.map { it.first().paletteName })
        // ⭐ FLUX.2 since 2026-10-23: `flux2.inpaint` crops, edits and pastes back (`SdSampler.FLUX2_INPAINT`).
        assertEquals(listOf("SD 1.5", "SD 1.5 Swap", "SDXL", "SDXL Swap", "Anima", "FLUX.2", "Z-Image", "Qwen Image", "Krea 2", "Anima DiT"), inpaint.first().map { it.paletteVariant })
        assertFalse(sections.getValue("generate").flatten().any { it.name.endsWith(".inpaint") })
    }

    @Test fun threeFamiliesAreOneCard() {
        val generate = sections.getValue("generate")
        val sd = generate.single { card -> card.any { it.name == SdSampler.SDXL.name } }
        assertEquals(
            listOf("sd15.sample", "sd15swap.sample", "sdxl.sample", "sdxlswap.sample", "anima.sample", "flux2.sample", "zimage.sample", "qwen21.sample", "krea2.sample", "animadit.sample", "iris.sample", "moss.sample", "ideogram.sample", "flux3.sample"),
            sd.map { it.name },
        )
    }

    /** ⚠ The port that makes the Tap tool appear exists only on the inpaint types. */
    /**
     * ⚠⚠ **No sampler takes a segmenter PORT any more** (2026-09-22). Tap to
     * select is a checkbox on the inpaint node (`SdSampler.TAP_SELECT`) and
     * `mask.segment_model` is gone, so a socket for wiring one in is a socket
     * for a plug that no longer exists. ⚠ Only the inpaint samplers carry the
     * checkbox, which is what this now asserts.
     */
    @Test fun onlyInpaintTakesTapToSelect() {
        for (t in com.abrah.nightmare.SdSampler.ALL) {
            assertFalse(
                t.name,
                t.inputs.any { it.type == com.abrah.nightmare.SelectObjectNode.PORT_TYPE },
            )
            assertEquals(
                t.name,
                t.inpaint,
                t.widgets.any { it.name == com.abrah.nightmare.SdSampler.TAP_SELECT },
            )
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
