package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ⭐ `<lora:` autocomplete (GitHub #19): where the open tag is, what it offers, what a pick writes. */
class LoraAutocompleteTest {

    @Test
    fun theOpenTagIsFoundOnlyWhileTypingIt() {
        val t = "1girl, <lora:ani"
        assertEquals(PromptLoras.Open(7, "ani"), PromptLoras.openTagAt(t, t.length))
        assertEquals(PromptLoras.Open(0, ""), PromptLoras.openTagAt("<LoRA:", 6))
        assertNull(PromptLoras.openTagAt("<lora:anime:0.8>, smile", 23))
        assertNull(PromptLoras.openTagAt("1girl, smile", 5))
        // ⚠ Past the name's colon the strength is being typed: no chips over it.
        assertNull(PromptLoras.openTagAt("<lora:anime:0.", 14))
    }

    @Test
    fun aPickWritesTheTagAndSelectsItsStrength() {
        val t = "1girl, <lora:ani, smile"
        val open = PromptLoras.openTagAt(t, 16)!!
        val (out, from, to) = PromptLoras.complete(t, open, 16, "anime_style.safetensors")
        assertEquals("1girl, <lora:anime_style:1.0>, smile", out)
        assertEquals("1.0", out.substring(from, to))
    }

    @Test
    fun aTagAlreadyClosedIsReplacedWhole() {
        val t = "<lora:an:0.5>, smile"
        val open = PromptLoras.openTagAt(t, 8)!!
        val (out, _, _) = PromptLoras.complete(t, open, 8, "anime.safetensors")
        assertEquals("<lora:anime:1.0>, smile", out)
    }

    @Test
    fun activeLorasComeFirstThenTheSamplersBase() {
        val files = listOf(
            "zz_sdxl.safetensors" to LoraLibrary.Base.SDXL,
            "aa_flux.safetensors" to LoraLibrary.Base.FLUX,
            "mm_anima.safetensors" to LoraLibrary.Base.ANIMA,
        )
        val hint = PromptLoras.Hint(active = setOf("zz_sdxl.safetensors"), bases = setOf(LoraLibrary.Base.ANIMA))
        assertEquals(
            listOf("zz_sdxl.safetensors", "mm_anima.safetensors", "aa_flux.safetensors"),
            PromptLoras.rank(files, "", hint),
        )
        assertEquals(listOf("mm_anima.safetensors"), PromptLoras.rank(files, "ANI", hint))
    }
}
