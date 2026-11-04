package com.abrah.nightmare

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ⭐ MOSS-Image's layout prompt (GitHub #77): the compiler must give what LinXce/Comfyui-Moss-LayoutPrompt gives, so
 * the first two cases are the plugin's own (`tests/test_layout.py`); the rest are this app's side of it.
 */
@RunWith(RobolectricTestRunner::class)
class MossLayoutTest {

    private fun poster(bbox: String) = """{"width":1536,"height":1024,"bbox_coord_space":"abs","bbox_format":"xyxy",
        "summary":{"caption":"A poster."},"items":[{"label":"text","caption":"Large title.","text":"EXPLORE","bbox":$bbox}]}"""

    private fun compiledBox(draft: String): List<Long> {
        val (layout, caption) = MossLayout.parse(draft)!!
        val out = JSONObject(MossLayout.compile(layout, caption, 1536, 1024)!!)
        val b = out.getJSONArray("items").getJSONObject(0).getJSONArray("bbox")
        return (0 until 4).map { b.getLong(it) }
    }

    @Test
    fun nearIntegerEdgesMatchTheReferenceCompiler() {
        assertEquals(listOf(-240L, 160L, -64L, 640L), compiledBox(poster("[927.9999999999, 271.9999999999, 1408.0000000001, 448.0000000001]")))
    }

    @Test
    fun fractionalEdgesStillEncloseTheVisibleArea() {
        assertEquals(listOf(-241L, 159L, -63L, 641L), compiledBox(poster("[927.9999999, 271.9999999, 1408.0000001, 448.0000001]")))
    }

    /** ⭐ Python's compact `json.dumps(ensure_ascii=False)`: no spaces, `/` and non-ASCII as they are. */
    @Test
    fun theOutputIsThePluginsText() {
        val l = MossLayout.Layout(listOf(MossLayout.Box("text", "a sign", "A/B 世界", 0.25, 0.0, 0.75, 0.5)))
        assertEquals(
            """{"summary":{"caption":"A street \"at\" night"},"items":[{"label":"text","caption":"a sign","text":"A/B 世界",""" +
                """"bbox":[-512,-256,0,256]}],"bbox_coord_space":"abs_centralized","bbox_format":"yxyx"}""",
            MossLayout.compile(l, "A street \"at\" night", 1024, 1024),
        )
    }

    /** ⭐ Boxes are kept as fractions, so a picture size change keeps them where they were drawn. */
    @Test
    fun theNodeParamRoundTripsAndFollowsTheSize() {
        val l = MossLayout.Layout(listOf(MossLayout.Box("character", "a knight", "", 0.0, 0.0, 0.5, 1.0)), meta = listOf("poster"))
        assertEquals(l, MossLayout.read(MossLayout.write(l)))
        val wide = JSONObject(MossLayout.compile(l, "", 1344, 768)!!)
        assertEquals("[-384,-672,384,0]", wide.getJSONArray("items").getJSONObject(0).getJSONArray("bbox").toString())
        assertEquals("", MossLayout.write(MossLayout.Layout()))
        assertEquals(MossLayout.Layout(), MossLayout.read("not json"))
    }

    /** ⭐ A compiled prompt pasted back reads as the same boxes (the plugin's `source` accepts one too). */
    @Test
    fun aCompiledPromptReadsBack() {
        val l = MossLayout.Layout(listOf(MossLayout.Box("object", "a moon", "", 0.5, 0.125, 0.75, 0.375)))
        val text = MossLayout.compile(l, "night sky", 1024, 1024)!!
        val (back, caption) = MossLayout.parse("Here it is: $text thanks", 1024, 1024)!!
        assertEquals("night sky", caption)
        assertEquals(text, MossLayout.compile(back, caption, 1024, 1024))
    }

    @Test
    fun anOrdinaryPromptIsLeftAlone() {
        assertNull(MossLayout.forRender(null, "a cat on a sofa", 1024, 1024))
        assertNull(MossLayout.forRender("", "a {curly} cat", 1024, 1024))
        // ⭐ The node's layout wins, with the prompt as its caption.
        val l = MossLayout.write(MossLayout.Layout(listOf(MossLayout.Box("object", "a cat", "", 0.0, 0.0, 1.0, 1.0))))
        assertTrue(MossLayout.forRender(l, "a cozy room", 1024, 1024)!!.startsWith("""{"summary":{"caption":"a cozy room"}"""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aTextBoxWithoutTextIsRefused() {
        MossLayout.compile(MossLayout.Layout(listOf(MossLayout.Box("text", "a sign", "", 0.0, 0.0, 0.5, 0.5))), "", 1024, 1024)
    }
}
