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
        assertEquals(Base.ANIMA, LoraLibrary.classify("""{"diffusion_model.blocks.0.adaln_modulation_cross_attn.1.lora_A.weight":1}"""))
        assertEquals(Base.ANIMA, LoraLibrary.classify("""{"lora_unet_blocks_0_cross_attn_k_proj.lora_down.weight":1}"""))
        // ⭐ MOSS-Image (Saved #7155): lodestones' DMD LoRA's own names, and kohya's.
        assertEquals(Base.MOSS, LoraLibrary.classify("""{"diffusion_model.layers.0.proj.ffn_up_gate.lora_down.weight":1,"diffusion_model.layers.0.proj.k.alpha":2}"""))
        assertEquals(Base.MOSS, LoraLibrary.classify("""{"lora_unet_layers_0_proj_ffn_up_gate.lora_down.weight":1}"""))
        assertEquals(Base.MOSS, LoraLibrary.Base.of(com.abrah.nightmare.Family.MOSS))
        assertEquals(Base.OTHER, LoraLibrary.classify("""{"something":1}"""))
    }

    /**
     * ⭐ The families added 2026-10-15 (Telegram #6322: Krea 2 and Z-Image LoRAs sat unsorted). Wan's
     * keys are Kijai's FastWan 5B LoRA's, read off the real file; Anima's `k_proj` must not read as Wan.
     */
    @Test
    fun krea2ZImageAndWanFromTheirNames() {
        assertEquals(Base.WAN, LoraLibrary.classify("""{"diffusion_model.blocks.0.self_attn.q.lora_down.weight":1,"diffusion_model.blocks.0.ffn.0.lora_up.weight":2}"""))
        assertEquals(Base.WAN, LoraLibrary.classify("""{"lora_unet_blocks_0_self_attn_q.lora_down.weight":1,"lora_unet_blocks_0_ffn_0.lora_up.weight":2}"""))
        assertEquals(Base.ANIMA, LoraLibrary.classify("""{"diffusion_model.blocks.0.cross_attn.k_proj.lora_A.weight":1,"diffusion_model.blocks.0.self_attn.q_proj.lora_A.weight":2}"""))
        assertEquals(Base.ZIMAGE, LoraLibrary.classify("""{"diffusion_model.layers.0.attention.to_q.lora_A.weight":1}"""))
        assertEquals(Base.ZIMAGE, LoraLibrary.classify("""{"diffusion_model.layers.3.feed_forward.w1.lora_A.weight":1}"""))
        assertEquals(Base.KREA2, LoraLibrary.classify("""{"diffusion_model.blocks.0.attn.wq.lora_A.weight":1}"""))
        assertEquals(Base.KREA2, LoraLibrary.Base.of(Family.KREA2))
        assertEquals(Base.WAN, LoraLibrary.Base.of(Family.WAN))
    }

    /** ⭐ A base set by hand wins over the header's, and the header's stays readable as `detected`. */
    @Test
    fun aBaseSetByHandWins() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val head = """{"something":1}""".toByteArray()
        java.io.File(dir, "krea_thing.safetensors").writeBytes(
            java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(head.size.toLong()).array() + head,
        )
        val auto = LoraLibrary.list(dir).single()
        assertEquals(Base.OTHER, auto.base)
        val manual = LoraLibrary.list(dir, mapOf("krea_thing.safetensors" to Base.KREA2)).single()
        assertEquals(Base.KREA2, manual.base)
        assertEquals(Base.OTHER, manual.detected)
        dir.deleteRecursively()
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

    /** ⭐ GitHub #36: folders first, alphabetical, each in the picker's order; then the loose ones. */
    @Test
    fun foldersGroupInThePickersOrder() {
        val names = listOf("zeta", "alpha", "mid", "beta", "loose")
        val folders = mapOf("zeta" to "Style", "alpha" to "Style", "mid" to "anatomy", "beta" to "Style")
        val (groups, loose) = LoraLibrary.grouped(names, folders)
        assertEquals(listOf("anatomy" to listOf("mid"), "Style" to listOf("zeta", "alpha", "beta")), groups)
        assertEquals(listOf("loose"), loose)
        val items = names.map { LoraLibrary.Item(it, 1L, 0L, if (it == "mid") LoraLibrary.Base.SDXL else LoraLibrary.Base.ANIMA) }
        assertEquals(listOf("Style"), LoraLibrary.foldersFor(items, folders, LoraLibrary.Base.ANIMA))
        assertEquals(listOf("anatomy", "Style"), LoraLibrary.foldersFor(items, folders, null))
    }
}
