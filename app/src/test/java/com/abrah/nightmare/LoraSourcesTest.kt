package com.abrah.nightmare

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ⭐ The LoRA browser's parsing and naming, against REAL responses recorded
 * 2026-10-07 (`src/test/resources/lora/`, descriptions trimmed): a CivitAI search
 * for SD 1.5 + SDXL, a Hugging Face search and one Hugging Face repo.
 * ⚠ Robolectric for the real `org.json` — the JVM's android.jar has stubs.
 */
@RunWith(RobolectricTestRunner::class)
class LoraSourcesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun res(name: String) =
        javaClass.classLoader!!.getResourceAsStream("lora/$name")!!.readBytes().decodeToString()

    private val civitai by lazy { JSONObject(res("civitai_search.json")).getJSONArray("items") }

    @Test
    fun civitaiKeepsOnlyTheTargetsVersions() {
        val sd15 = LoraSources.parseCivitaiItems(civitai, LoraSources.Target.SD15, mature = false)
        assertEquals(listOf("58390", "82098", "25995"), sd15.map { it.id })
        val sdxl = LoraSources.parseCivitaiItems(civitai, LoraSources.Target.SDXL, mature = false)
        assertEquals(listOf("122359"), sdxl.map { it.id })
        assertEquals(listOf("135867", "133241"), sdxl.single().versions.map { it.id })
    }

    @Test
    fun civitaiFileTriggerAndUrls() {
        val hit = LoraSources.parseCivitaiItems(civitai, LoraSources.Target.SD15, mature = false)
            .first { it.id == "25995" }
        val v = hit.versions.first { it.id == "32988" }
        assertEquals("blindbox_v1_mix.safetensors", v.file!!.name)
        assertEquals("https://civitai.com/api/download/models/32988", v.file!!.url)
        assertEquals(64, v.file!!.sha256!!.length)
        assertEquals(listOf("full body, chibi,"), v.trigger)
        assertEquals("https://civitai.com/models/25995", hit.pageUrl)
        assertTrue(hit.thumb!!.contains("/width=256/"))
    }

    @Test
    fun matureUsesCivitaiRed() {
        val hit = LoraSources.parseCivitaiItems(civitai, LoraSources.Target.SDXL, mature = true).single()
        assertEquals("https://civitai.red/api/download/models/135867", hit.versions.first().file!!.url)
        assertEquals("https://civitai.red/models/122359", hit.pageUrl)
    }

    @Test
    fun huggingFaceSearchAndFiles() {
        val hits = LoraSources.parseHfItems(JSONArray(res("hf_search.json")))
        assertEquals(3, hits.size)
        assertTrue(hits.all { it.id.contains('/') && it.versions.isEmpty() })
        assertTrue(hits.mapNotNull { it.thumb }.all { it.startsWith("https://") })

        val files = LoraSources.parseHfFiles(
            "ntc-ai/SDXL-LoRA-slider.anime", JSONObject(res("hf_repo.json")), LoraSources.Target.SDXL,
        )
        val f = files.single().file!!
        assertEquals("anime.safetensors", f.name)
        assertEquals(8_789_076L, f.bytes)
        assertTrue(f.sha256!!.startsWith("996f4ac936d3"))
        assertEquals("https://huggingface.co/ntc-ai/SDXL-LoRA-slider.anime/resolve/main/anime.safetensors", f.url)
        assertEquals(listOf("anime"), files.single().trigger)
    }

    @Test
    fun links() {
        assertEquals(
            LoraSources.Link.Civitai("122359", "135867"),
            LoraSources.parseLink("https://civitai.com/models/122359/detail-tweaker-xl?modelVersionId=135867"),
        )
        assertEquals(LoraSources.Link.Civitai("58390", null), LoraSources.parseLink(" https://civitai.red/models/58390 "))
        assertEquals(
            LoraSources.Link.HuggingFace("ntc-ai/SDXL-LoRA-slider.anime"),
            LoraSources.parseLink("https://huggingface.co/ntc-ai/SDXL-LoRA-slider.anime/tree/main"),
        )
        assertNull(LoraSources.parseLink("https://huggingface.co/datasets/foo"))
        assertNull(LoraSources.parseLink("anime eyes"))
    }

    @Test
    fun failuresSayWhatToDo() {
        assertTrue(LoraSources.failureOf(451, "", null) is LoraSources.Failure.RegionBlocked)
        assertTrue(LoraSources.failureOf(401, "", null) is LoraSources.Failure.NeedsKey)
        assertTrue(LoraSources.failureOf(401, "", "k") is LoraSources.Failure.BadKey)
        // ⚠ The body CivitAI actually sent (2026-10-07).
        val early = LoraSources.failureOf(
            403,
            """{"error":"Early Access","deadline":"2026-10-11T14:30:00.000Z","message":"This asset is in Early Access."}""",
            "k",
        )
        assertEquals("2026-10-11T14:30:00.000Z", (early as LoraSources.Failure.EarlyAccess).until)
        assertTrue(LoraSources.failureOf(500, "", null) is LoraSources.Failure.Http)
    }

    @Test
    fun targetsAreTheSwapFamiliesOnly() {
        assertEquals(LoraSources.Target.SD15, LoraSources.Target.of(Family.SD15_SWAP))
        assertEquals(LoraSources.Target.SDXL, LoraSources.Target.of(Family.SDXL_SWAP))
        assertNull(LoraSources.Target.of(Family.SDXL))
        assertNull(LoraSources.Target.of(Family.FLUX2))
    }

    @Test
    fun aDifferentFileUnderTheSameNameIsKept() {
        val dir = tmp.newFolder("_loras")
        val v = LoraSources.Version("135867", "v1", "SDXL 1.0", emptyList(), null)
        val f = LoraSources.LoraFile("add-detail.safetensors", 10, null, "u")
        assertEquals("add-detail.safetensors", LoraInstall.targetName(dir, f, v, 10))
        java.io.File(dir, "add-detail.safetensors").writeBytes(ByteArray(10))
        assertEquals("add-detail.safetensors", LoraInstall.targetName(dir, f, v, 10))
        assertEquals("add-detail_135867.safetensors", LoraInstall.targetName(dir, f, v, 11))
    }

    @Test
    fun sha256OfAKnownString() {
        val f = tmp.newFile().apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", LoraInstall.sha256(f) { false })
    }
}
