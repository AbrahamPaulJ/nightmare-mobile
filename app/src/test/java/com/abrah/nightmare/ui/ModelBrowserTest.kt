package com.abrah.nightmare.ui

import com.abrah.nightmare.Family
import com.abrah.nightmare.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐ The Models browser's pure half: which rows a chip, a query and a family
 * show, and in which section — Installed, Available, or "Not for this phone".
 */
class ModelBrowserTest {

    private val specs = ModelCatalog.builtIn
    private fun spec(id: String) = specs.first { it.id == id }

    private val absInpaint = specs.first { it.backendType == ModelCatalog.SD15_NPU_INPAINT }
    private val flux = specs.first { it.family == Family.FLUX2 }
    private val sd15 = spec(com.abrah.nightmare.V1_MODEL)
    private val sdxl = specs.first { it.family == Family.SDXL }

    private fun row(s: com.abrah.nightmare.ModelSpec, installed: Boolean = false, selected: Boolean = false, supported: Boolean = true) =
        ModelRow(spec = s, build = if (supported) s.builds.first() else null, installed = installed, selected = selected)

    @Test
    fun kindsSplitByCapability() {
        assertTrue(absInpaint.isInpaintModel())
        assertFalse(sd15.isInpaintModel())
        assertTrue(matchesKind(flux, ModelKind.EDIT))
        assertFalse(matchesKind(sd15, ModelKind.EDIT))
        assertTrue(matchesKind(absInpaint, ModelKind.INPAINT))
        assertTrue(matchesKind(sd15, ModelKind.GENERATE))
        assertFalse(matchesKind(sd15, ModelKind.VIDEO))
    }

    @Test
    fun queryMatchesNameFamilyCapabilityAndPrompt() {
        assertTrue(matchesQuery(flux, "flux"))
        assertTrue(matchesQuery(absInpaint, "inpaint"))
        assertTrue(matchesQuery(sd15, "  "))
        assertFalse(matchesQuery(sd15, "flux"))
        // ⭐ "anime" finds an anime checkpoint through its starter prompt.
        val anime = specs.first { it.id == "anythingv5" }
        assertTrue(matchesQuery(anime, "anime"))
    }

    @Test
    fun unsupportedGoToTheirOwnSectionAndInUseLeads() {
        val rows = listOf(
            row(sdxl, supported = false),
            row(sd15, installed = true),
            row(absInpaint, installed = true, selected = true),
            row(flux),
        )
        val s = sectionsOf(rows, ModelKind.ALL, "")
        assertEquals(listOf(absInpaint.id, sd15.id), s.installed.map { it.spec.id })
        assertEquals(listOf(flux.id), s.available.map { it.spec.id })
        assertEquals(listOf(sdxl.id), s.unsupported.map { it.spec.id })
        // ⚠ An INSTALLED model is never "unsupported", whatever its build.
        assertFalse(row(sdxl, installed = true, supported = false).unsupported())
    }

    @Test
    fun aFamilyPageShowsOnlyItsFamilyAndSubKind() {
        val rows = listOf(row(sd15), row(absInpaint), row(flux))
        val page = sectionsOf(rows, ModelKind.ALL, "", Family.SD15, SubKind.INPAINT)
        assertEquals(listOf(absInpaint.id), (page.installed + page.available).map { it.spec.id })
        val base = sectionsOf(rows, ModelKind.ALL, "", Family.SD15, SubKind.BASE)
        assertEquals(listOf(sd15.id), base.available.map { it.spec.id })
    }

    @Test
    fun archLabels() {
        assertEquals("8 Elite+", archLabel(79))
        assertEquals("8 Gen 3+", archLabel(75))
        assertEquals("8 Gen 2+", archLabel(73))
    }
}
