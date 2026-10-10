package com.abrah.nightmare

import com.abrah.nightmare.LoraLibrary.Base
import com.abrah.nightmare.LoraLibrary.Item
import com.abrah.nightmare.LoraLibrary.Sort
import org.junit.Assert.assertEquals
import org.junit.Test

/** ⭐ The LoRA library view's two pure halves: the base read from a header, and the order. */
class LoraLibraryTest {

    @Test
    fun theBaseComesFromKohyaMetadataFirst() {
        assertEquals(Base.SDXL, LoraLibrary.classify("""{"__metadata__":{"ss_base_model_version":"sdxl_base_v1-0"},"lora_te_x":1}"""))
        assertEquals(Base.SD15, LoraLibrary.classify("""{"__metadata__":{"ss_base_model_version":"sd_v1"}}"""))
        assertEquals(Base.FLUX, LoraLibrary.classify("""{"__metadata__":{"ss_base_model_version":"flux1"}}"""))
    }

    @Test
    fun elseFromTheTensorNames() {
        assertEquals(Base.SDXL, LoraLibrary.classify("""{"lora_te2_text_model_encoder":1,"lora_unet_input_blocks_4_1":2}"""))
        assertEquals(Base.SD15, LoraLibrary.classify("""{"lora_te_text_model":1,"lora_unet_down_blocks_0_attentions_0":2}"""))
        assertEquals(Base.FLUX, LoraLibrary.classify("""{"transformer.single_transformer_blocks.0.attn":1}"""))
        assertEquals(Base.ZIMAGE, LoraLibrary.classify("""{"diffusion_model.noise_refiner.0":1}"""))
        assertEquals(Base.QWEN, LoraLibrary.classify("""{"transformer_blocks.0.img_mlp.net":1}"""))
        assertEquals(Base.OTHER, LoraLibrary.classify("""{"something":1}"""))
    }

    private val items = listOf(
        Item("b.safetensors", 300, added = 1, base = Base.SDXL),
        Item("a.safetensors", 100, added = 3, base = Base.SD15),
        Item("c.safetensors", 200, added = 2, base = Base.SDXL),
    )

    private fun names(sort: Sort, base: Base? = null, q: String = "", fav: Set<String> = emptySet(), uses: Map<String, Int> = emptyMap()) =
        LoraLibrary.arrange(items, sort, base, q, fav, uses).map { it.name.first().toString() }

    @Test
    fun itSortsFiltersAndPutsFavouritesFirst() {
        assertEquals(listOf("a", "b", "c"), names(Sort.NAME))
        assertEquals(listOf("a", "c", "b"), names(Sort.NEWEST))
        assertEquals(listOf("b", "c", "a"), names(Sort.SIZE))
        assertEquals(listOf("c", "a", "b"), names(Sort.MOST_USED, uses = mapOf("c.safetensors" to 5, "a.safetensors" to 1)))
        assertEquals(listOf("b", "c"), names(Sort.NAME, base = Base.SDXL))
        assertEquals(listOf("c"), names(Sort.NAME, q = "C.SAFE"))
        assertEquals(listOf("c", "a", "b"), names(Sort.NAME, fav = setOf("c.safetensors")))
    }
}
