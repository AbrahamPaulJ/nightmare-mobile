package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ⭐ Tag autocomplete's dictionary (Local Dream's formats), on the JVM. */
class TagDictionaryTest {

    private val main = TagDictionary.parseMain(
        sequenceOf(
            "﻿1girl,0,6008644,\"1girls,sole_female\"",
            "long_hair,0,4350743,\"/lh,longhair\"",
            "long_sleeves,0,900000,",
            "hair_ornament,0,800000",
            "ganyu_(genshin_impact),4,50000,\"ganyu\"",
            "long_hair,0,1,",   // a duplicate — the first wins
        ),
    ).also { list ->
        val tr = TagDictionary.parseTranslation(sequenceOf("long_hair,长发", "1girl,0,1个女孩"))
        for (e in list) e.translation = tr[e.tag]
    }

    @Test
    fun prefixMatchesComeFirstByPostCount() {
        val s = TagDictionary.suggestFrom(main, "long", 12).map { it.tag }
        assertEquals(listOf("long_hair", "long_sleeves"), s)
    }

    @Test
    fun spacesMatchUnderscoresAndAliasesFind() {
        assertEquals("long_hair", TagDictionary.suggestFrom(main, "long h", 12).first().tag)
        val alias = TagDictionary.suggestFrom(main, "longh", 12).first()
        assertEquals("long_hair", alias.tag)
        assertEquals("longhair", alias.alias)
        // ⚠ A substring after the prefixes: "hair" finds hair_ornament (prefix) before long_hair (inside).
        assertEquals(listOf("hair_ornament", "long_hair"), TagDictionary.suggestFrom(main, "hair", 12).map { it.tag })
    }

    @Test
    fun anotherScriptSearchesTheTranslations() {
        val s = TagDictionary.suggestFrom(main, "长", 12)
        assertEquals("long_hair", s.single().tag)
        assertEquals("长发", s.single().translation)
        // ⚠ The LAST non-number column is the translation: `1girl,0,1个女孩`.
        assertEquals("1个女孩", TagDictionary.suggestFrom(main, "女孩", 12).single().translation)
    }

    @Test
    fun oneLetterIsNotEnough() {
        assertTrue(TagDictionary.suggestFrom(main, "l", 12).isEmpty())
    }

    @Test
    fun aTagGoesInWithSpacesEscapesAndASeparator() {
        assertEquals("ganyu \\(genshin impact\\)", TagDictionary.promptText("ganyu_(genshin_impact)"))
        val text = "masterpiece, lon"
        val range = TagDictionary.wordAt(text, text.length)!!
        assertEquals("lon", text.substring(range))
        assertEquals("masterpiece, long hair, " to 24, TagDictionary.complete(text, range, "long_hair"))
        // ⚠ Mid-prompt: the comma that follows is reused, not doubled.
        val mid = "lon, smile"
        assertEquals("long hair, smile" to 9, TagDictionary.complete(mid, TagDictionary.wordAt(mid, 3)!!, "long_hair"))
    }

    @Test
    fun nothingToCompleteRightAfterASeparator() {
        assertNull(TagDictionary.wordAt("masterpiece, ", 13))
        assertNull(TagDictionary.wordAt("", 0))
    }
}
