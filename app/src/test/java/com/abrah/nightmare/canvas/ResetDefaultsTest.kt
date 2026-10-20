package com.abrah.nightmare.canvas

import com.abrah.nightmare.Family
import com.abrah.nightmare.Graph
import com.abrah.nightmare.ModelCatalog
import com.abrah.nightmare.NODE_TYPES
import com.abrah.nightmare.Node
import com.abrah.nightmare.SdSampler
import org.junit.Assert.assertEquals
import org.junit.Test

/** ⭐ The Default view's Reset defaults ([CanvasState.resetSamplerDefaults]): what goes back, what stays. */
class ResetDefaultsTest {

    private val spec = ModelCatalog.all.first { it.family == Family.SDXL }

    private fun reset(params: Map<String, String>): Map<String, String> {
        val s0 = CanvasState(workflow = Workflow(Graph(listOf(Node("gen", "sdxl.sample", params = params))), emptyMap()))
        return s0.resetSamplerDefaults("gen", NODE_TYPES).workflow.graph.byId.getValue("gen").params
    }

    @Test
    fun theModelsRecipeComesBackAndWhatWasPickedStays() {
        val after = reset(mapOf(
            "model" to spec.id, "seed" to "42", SdSampler.LORAS to "a.safetensors@0.8",
            "width" to "896", "height" to "1152",
            "steps" to "57", "cfg" to "13.0", "scheduler" to "bogus", "denoise" to "0.31",
        ))
        assertEquals(spec.id, after["model"])
        assertEquals("42", after["seed"])
        assertEquals("a.safetensors@0.8", after[SdSampler.LORAS])
        assertEquals("896", after["width"])
        assertEquals("1152", after["height"])
        assertEquals(spec.steps.toString(), after["steps"])
        assertEquals(spec.cfg.toString(), after["cfg"])
        assertEquals(spec.scheduler, after["scheduler"])
        val denoise = NODE_TYPES.getValue("sdxl.sample").widgets.first { it.name == "denoise" }.default
        assertEquals(denoise, after["denoise"])
    }

    /** ⚠ Advanced's Reset node is unchanged: every knob, the seed with them. */
    @Test
    fun resetNodeStillResetsEverything() {
        val s0 = CanvasState(workflow = Workflow(Graph(listOf(Node("gen", "sdxl.sample", params = mapOf("seed" to "42")))), emptyMap()))
        val after = s0.resetNode("gen", NODE_TYPES).workflow.graph.byId.getValue("gen").params
        assertEquals("0", after["seed"])
    }
}
