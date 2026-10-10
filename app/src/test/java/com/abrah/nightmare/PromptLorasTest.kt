package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ⭐ `<lora:name:w>` in a prompt: stripped, resolved, merged (the user's call, 2026-10-10). */
class PromptLorasTest {

    @Test
    fun aCivitaiPromptLosesItsTagsAndKeepsItsWords() {
        val s = PromptLoras.extract("<lora:foobar:0.9>, masterpiece, 1girl, <LORA:detail_slider:-0.5>, smile")
        assertEquals("masterpiece, 1girl, smile", s.text)
        assertEquals(listOf(PromptLoras.Tag("foobar", 0.9), PromptLoras.Tag("detail_slider", -0.5)), s.tags)
    }

    @Test
    fun theWeightIsOptionalAndExtraFieldsAreIgnored() {
        assertEquals(listOf(PromptLoras.Tag("style", 1.0)), PromptLoras.extract("a <lora:style> b").tags)
        assertEquals(listOf(PromptLoras.Tag("x", 0.7)), PromptLoras.extract("<lyco:x:0.7:0.3>").tags)
        assertEquals("a b", PromptLoras.extract("a <lora:style> b").text)
    }

    @Test
    fun aPromptWithoutTagsIsUntouched() {
        val text = "masterpiece,  1girl, (smile:1.2)"
        assertEquals(text, PromptLoras.extract(text).text)
    }

    @Test
    fun aTagFindsItsFileWithOrWithoutTheExtension() {
        val installed = listOf("FooBar.safetensors", "other.safetensors")
        assertEquals("FooBar.safetensors", PromptLoras.resolve("foobar", installed))
        assertEquals("FooBar.safetensors", PromptLoras.resolve("foobar.safetensors", installed))
        assertNull(PromptLoras.resolve("missing", installed))
        // ⚠ A path is reduced to its file name: nothing outside the folder.
        assertEquals("other.safetensors", PromptLoras.resolve("../../other", installed))
    }

    @Test
    fun theTagWinsOverTheNodesOwnEntryForTheSameFile() {
        val merged = PromptLoras.merge(
            "a.safetensors@0.5, b.safetensors",
            listOf(LoraSpec.Entry("A.safetensors", 0.9)),
        )
        assertEquals("b.safetensors, A.safetensors@0.9", merged)
    }
}
