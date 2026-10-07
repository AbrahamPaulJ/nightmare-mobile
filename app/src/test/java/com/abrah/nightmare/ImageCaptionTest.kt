package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ⭐ The describe button's pure parts (`docs/FLORENCE.md`): turning token ids back
 * into text, the next-token rule, and where a caption goes in a prompt.
 */
@RunWith(RobolectricTestRunner::class)
class ImageCaptionTest {

    /** BART's first ids are `<s> <pad> </s> <unk>`; `Ġ` stands for a space. */
    private val vocab = ByteLevel.vocab(
        """{"<s>":0,"<pad>":1,"</s>":2,"<unk>":3,"A":4,"Ġgreen":5,"Ġcar":6,".":7,"Ġcaf":8,"Ã©":9,"<mask>":10}""",
    )

    @Test
    fun decodesBytesAndSkipsSpecials() {
        // The phone's real sequence starts `2, 0` (decoder start, forced BOS) and ends `2`.
        assertEquals("A green car.", ByteLevel.decode(longArrayOf(2, 0, 4, 5, 6, 7, 2), vocab))
    }

    @Test
    fun multiByteUtf8SurvivesAcrossTheStandIns() {
        // `é` is two bytes, spelled `Ã©` in the vocab.
        assertEquals("café", ByteLevel.decode(longArrayOf(0, 8, 9, 2), vocab))
    }

    @Test
    fun locationAndTaskTokensPastTheVocabAreDropped() {
        // ⚠ Florence's `<loc_*>` ids sit past vocab.json; a caption never wants them.
        assertEquals("A car", ByteLevel.decode(longArrayOf(0, 4, 50_300, 6, 10, 2), vocab))
    }

    @Test
    fun firstTokenIsForcedToBos() {
        val logits = FloatArray(12) { 0f }.also { it[7] = 9f }
        assertEquals(0L, ImageCaption.pickNext(logits, listOf(2L), first = true))
    }

    @Test
    fun aThreeGramIsNeverRepeated() {
        // Said so far: 5 6 7 5 6 — a 7 next would repeat "5 6 7".
        val out = listOf(2L, 0L, 5L, 6L, 7L, 5L, 6L)
        val logits = FloatArray(12) { 0f }.also { it[7] = 9f; it[4] = 5f }
        assertEquals(4L, ImageCaption.pickNext(logits, out, first = false))
    }

    @Test
    fun aCaptionGoesAfterThePromptAsOneMorePiece() {
        assertEquals("a car", ImageCaption.appended("", "a car"))
        assertEquals("a car", ImageCaption.appended("   ", "a car"))
        assertEquals("masterpiece, best quality, a car", ImageCaption.appended("masterpiece, best quality", "a car"))
        assertEquals("masterpiece, a car", ImageCaption.appended("masterpiece,", "a car"))
        assertEquals("masterpiece, a car", ImageCaption.appended("masterpiece, \n", "a car"))
    }

    /** ⭐ Which checkpoints open the dialog on Tags — anime ones, by the catalogue's own sign. */
    @Test
    fun animeCheckpointsReadTags() {
        fun reads(id: String) = DescribeMode.readsTags(ModelCatalog.byId(id)!!)
        assertEquals(true, reads("illustrious_xl_swap"))
        assertEquals(true, reads("sdxl_illustrious"))
        assertEquals(true, reads("sdxl_pony"))
        assertEquals(true, ModelCatalog.all.filter { it.family == Family.ANIMA }.all { DescribeMode.readsTags(it) })
        assertEquals(false, reads("juggernaut_xl_swap"))
        assertEquals(false, reads("sdxl_dreamshaper"))
    }

    // ---- the tagger -----------------------------------------------------------

    private val tags = ImageTagger.readTags(
        listOf(
            "tag_id,name,category,count",
            "9999999,general,9,1", "9999998,sensitive,9,1", "9999997,questionable,9,1", "9999996,explicit,9,1",
            "1,1girl,0,1", "2,long_hair,0,1", "3,^_^,0,1", "4,power_(chainsaw_man),4,1",
            "5,\"tag,with,commas\",0,1", "6,hat,0,1",
        ).joinToString("\n", postfix = "\n"),
    )

    @Test
    fun theCsvIsReadWithQuotedNames() {
        assertEquals(10, tags.size)
        assertEquals(ImageTagger.Tag("tag,with,commas", 0), tags[8])
        assertEquals(4, tags[7].category)
    }

    /** ⭐ Characters first, then general tags by confidence, then the rating — escaped for SD. */
    @Test
    fun theTagsBecomeAPrompt() {
        //                     general sens  quest expl 1girl long  ^_^   power commas hat
        val p = floatArrayOf(0.9f, 0.1f, 0.0f, 0.0f, 0.99f, 0.5f, 0.6f, 0.9f, 0.1f, 0.3f)
        assertEquals("power \\(chainsaw man\\), 1girl, ^_^, long hair, general", ImageTagger.prompt(tags, p))
    }

    @Test
    fun aCharacterNeedsTheHigherThreshold() {
        val p = floatArrayOf(0.1f, 0.0f, 0.0f, 0.8f, 0.99f, 0.0f, 0.0f, 0.8f, 0.0f, 0.0f)
        assertEquals("1girl, explicit", ImageTagger.prompt(tags, p))
    }
}
