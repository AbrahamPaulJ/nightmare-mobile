package com.abrah.nightmare

import com.abrah.nightmare.canvas.PictureParams
import com.abrah.nightmare.canvas.Workflow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** ⭐ GitHub #133: a ComfyUI or A1111 picture's values into Text to image. */
@RunWith(RobolectricTestRunner::class)
class PictureParamsTest {

    private val comfy = """
        {"3": {"class_type": "KSampler", "inputs": {"seed": 8566257, "steps": 20, "cfg": 8, "sampler_name": "euler",
               "scheduler": "normal", "denoise": 1, "model": ["10", 0], "positive": ["6", 0], "negative": ["7", 0],
               "latent_image": ["5", 0]}},
         "4": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": "SDXL\\juggernaut.safetensors"}},
         "5": {"class_type": "EmptyLatentImage", "inputs": {"width": 832, "height": 1216, "batch_size": 1}},
         "6": {"class_type": "CLIPTextEncode", "inputs": {"text": ["12", 0], "clip": ["10", 1]}},
         "7": {"class_type": "CLIPTextEncode", "inputs": {"text": "blurry, lowres", "clip": ["10", 1]}},
         "10": {"class_type": "LoraLoader", "inputs": {"lora_name": "styles/ink.safetensors", "strength_model": 0.7,
                "strength_clip": 0.7, "model": ["4", 0], "clip": ["4", 1]}},
         "11": {"class_type": "Power Lora Loader (rgthree)", "inputs": {"lora_1": {"on": true, "lora": "detail.safetensors", "strength": 0.5},
                "lora_2": {"on": false, "lora": "off.safetensors", "strength": 1}}},
         "12": {"class_type": "PrimitiveString", "inputs": {"value": "a lighthouse at dusk"}}}
    """.trimIndent()

    @Test
    fun comfyUiValuesFollowTheSamplersWires() {
        val r = PictureParams.of(mapOf("prompt" to comfy, "workflow" to "{}"))!!
        assertEquals("ComfyUI", r.from)
        assertEquals("a lighthouse at dusk", r.prompt)
        assertEquals("blurry, lowres", r.negative)
        assertEquals(8566257L, r.seed)
        assertEquals(20, r.steps)
        assertEquals(8.0, r.cfg!!, 1e-9)
        assertEquals(832 to 1216, r.width to r.height)
        assertEquals("euler", r.sampler)
        assertEquals("juggernaut.safetensors", r.model)
        assertEquals(listOf(LoraSpec.Entry("ink.safetensors", 0.7), LoraSpec.Entry("detail.safetensors", 0.5)), r.loras)
    }

    @Test
    fun a1111ParametersAndItsLoraTags() {
        val r = PictureParams.of(mapOf("parameters" to """
            a cat on a sofa, <lora:ink:0.8>, soft light
            Negative prompt: blurry
            Steps: 28, Sampler: DPM++ 2M, CFG scale: 6.5, Seed: 42, Size: 768x1024, Model: dream, Lora hashes: "ink: abc, x: def"
        """.trimIndent()))!!
        assertEquals("A1111", r.from)
        assertEquals("a cat on a sofa, soft light", r.prompt)
        assertEquals("blurry", r.negative)
        assertEquals(28, r.steps)
        assertEquals(6.5, r.cfg!!, 1e-9)
        assertEquals(42L, r.seed)
        assertEquals(768 to 1024, r.width to r.height)
        assertEquals("dream", r.model)
        assertEquals(listOf(LoraSpec.Entry("ink.safetensors", 0.8)), r.loras)
    }

    @Test
    fun aPictureWithNeitherIsNotRead() {
        assertNull(PictureParams.of(mapOf("Software" to "x")))
        assertNull(PictureParams.of(mapOf("prompt" to "not json")))
    }

    @Test
    fun theValuesLandOnTextToImage() {
        val flow = Workflow(Graph(listOf(
            Node("prompt", "core.prompt", mapOf("prompt" to "p", "negative" to "n")),
            Node("generate", "x", mapOf("width" to "1024", "height" to "1024", "seed" to "0")),
        )), emptyMap())
        val r = PictureParams.of(mapOf("prompt" to comfy))!!
        val dit = PictureParams.apply(flow, r, listOf("euler", "dpm++2m"), gridded = { (it + 32) / 64 * 64 }).graph.byId
        assertEquals("a lighthouse at dusk", dit["prompt"]!!.params["prompt"])
        assertEquals("8566257", dit["generate"]!!.params["seed"])
        assertEquals("832", dit["generate"]!!.params["width"])
        assertEquals("1216", dit["generate"]!!.params["height"])
        assertEquals("euler", dit["generate"]!!.params["scheduler"])
        assertEquals("ink.safetensors@0.7, detail.safetensors@0.5", dit["generate"]!!.params[SdSampler.LORAS])
        // An SD checkpoint keeps its own launch-bound size.
        val sd = PictureParams.apply(flow, r, emptyList(), gridded = null).graph.byId
        assertEquals("1024", sd["generate"]!!.params["width"])
        assertNull(sd["generate"]!!.params["scheduler"])
    }
}
