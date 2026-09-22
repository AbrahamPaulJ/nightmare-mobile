package com.abrah.nightmare

import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ⭐⭐⭐ **A folder that works in LocalDream works here, unchanged.**
 *
 * Reported 2026-09-22 by someone who assembles model folders by hand:
 *
 * > for most users they have to unzip, create the proper dummy file (KLEIN or
 * > flux2.dit) and zip it, and or keep two zip files just to import a
 * > flux/z-image model. That's a huge hassle and storage being used up.
 *
 * ⚠⚠ The fixtures below are their screenshots, literally: `flux-Ld` holding
 * `KLEIN` did not work and `flux-nightmare` holding `flux2.dit` did. This class
 * is what stops that pair diverging again.
 *
 * ⚠ Upstream's precedence is upstream's (`data/Model.kt`, `scanCustomModels`),
 * and it is asserted rather than described — a folder carrying two markers is
 * the case where "we follow upstream" has to mean something exact.
 */
@RunWith(RobolectricTestRunner::class)
class UpstreamMarkerTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun folder(name: String, vararg files: String): File =
        File(ModelCatalog.root(ctx), name).apply {
            mkdirs()
            for (f in files) File(this, f).writeText("")
        }

    @After
    fun clean() {
        ModelCatalog.root(ctx).deleteRecursively()
        CustomModels.scan(ctx)
    }

    private fun familyOf(dir: String): Family? =
        CustomModels.scan(ctx).firstOrNull { it.id == dir }?.family

    /** ⭐ Their exact folder, which used to be ignored. */
    @Test
    fun aKleinMarkerIsAFluxFolder() {
        folder("flux-Ld", "KLEIN", "llm.gguf", "dit.safetensors", "tokenizer.json", "vae.safetensors")
        assertEquals(Family.FLUX2, familyOf("flux-Ld"))
    }

    @Test
    fun aZimageMarkerIsAZImageFolder() {
        folder("zit", "ZIMAGE", "dit.safetensors")
        assertEquals(Family.ZIMAGE, familyOf("zit"))
    }

    /** ⚠ The SDXL folder from the same report — a bare `SDXL` file is enough. */
    @Test
    fun anSdxlMarkerIsAnSdxlFolder() {
        folder("NoobAi", "SDXL")
        assertEquals(Family.SDXL, familyOf("NoobAi"))
    }

    @Test
    fun anAnimaMarkerIsAnAnimaFolder() {
        folder("rin", "ANIMA")
        assertEquals(Family.ANIMA, familyOf("rin"))
    }

    /**
     * ⚠⚠ `npucustom` and `finished` name no family upstream — they mean an NPU
     * and a CPU SD 1.5. This app has no CPU path, so both read as SD 1.5 and a
     * folder that is not one fails at LAUNCH with the backend's own words.
     */
    @Test
    fun theTwoGenericMarkersReadAsSd15() {
        folder("npu", "npucustom")
        folder("cpu", "finished")
        assertEquals(Family.SD15, familyOf("npu"))
        assertEquals(Family.SD15, familyOf("cpu"))
    }

    /** ⭐ …and the names this app used to write still work, for folders it made. */
    @Test
    fun ourOwnOldMarkersStillWork() {
        folder("mine", CustomModels.FLUX2_MARK, "dit.safetensors")
        assertEquals(Family.FLUX2, familyOf("mine"))
    }

    /**
     * ⚠⚠⚠ **Upstream's precedence, asserted.** A folder carrying both `ZIMAGE`
     * and `SDXL` is ZIMAGE there, so it is ZIMAGE here. This is the assertion
     * that gives "we follow upstream" a testable meaning.
     */
    @Test
    fun precedenceIsUpstreams() {
        folder("both", "SDXL", "ZIMAGE")
        assertEquals(Family.ZIMAGE, familyOf("both"))
    }

    /**
     * ⭐⭐ A marker BEATS the file-based guess, which is the whole change. A
     * folder holding SDXL's own CLIP file and a `KLEIN` marker is what its owner
     * says it is.
     */
    @Test
    fun aMarkerOutranksTheFileGuess() {
        folder("declared", "KLEIN", "clip_2.mnn")
        assertEquals(Family.FLUX2, familyOf("declared"))
    }

    /** ⚠ And with no marker at all, the CLIP inference still decides. */
    @Test
    fun noMarkerStillInfersFromTheFiles() {
        folder("guessed", "clip_2.mnn")
        assertEquals(Family.SDXL, familyOf("guessed"))
    }
}
