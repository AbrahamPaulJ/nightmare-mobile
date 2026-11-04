package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ⭐ An imported DiT streams when what is free cannot hold it ([BackendProcess.importBudget]). */
class ImportBudgetTest {

    private val import = ModelCatalog.byId("moss_image")!!.copy(isCustom = true)
    private val gb = 1_000_000_000L

    @Test
    fun aQ8MossImportStreamsWhenThePhoneIsShort() {
        // ⭐ The tester's converted fp16 MOSS (~5 GB as Q8_0) with 4.5 GB free on a 12 GB S25 Ultra (Saved #7339).
        assertEquals("4", BackendProcess.importBudget(import, 5 * gb, 11L shl 30, 4_569L shl 20))
        // With room for it and the headroom, whole.
        assertNull(BackendProcess.importBudget(import, 5 * gb, 11L shl 30, 8 * gb))
        // A small import, and a 16 GB phone, stay as they were.
        assertNull(BackendProcess.importBudget(import, 3 * gb, 11L shl 30, 2 * gb))
        assertNull(BackendProcess.importBudget(import, 5 * gb, 15L shl 30, 2 * gb))
        // Built-ins are measured per family, never through this.
        assertNull(BackendProcess.importBudget(ModelCatalog.byId("moss_image"), 5 * gb, 11L shl 30, 2 * gb))
    }

    @Test
    fun aKlein9bImportTheSizeOfOursRunsAsOursDoes() {
        // ⭐ S#7755: leejet's 5,616 MB Klein 9B Q4_0, imported with its own parts, 5.8 GB free on a 12 GB S25. At 4 GiB it
        // failed at segment 16/34; the built-in renders whole there (768², 45 s).
        val klein9 = ModelCatalog.byId("flux2_klein_9b")!!.copy(isCustom = true)
        assertEquals(5_616_208_032L, BackendProcess.builtInDitBytes(Family.FLUX2))
        assertNull(BackendProcess.importBudget(klein9, 5_616_208_032L, 11L shl 30, 5_790L shl 20))
        // ⚠ Bigger than anything we ship in the family: the 4 GiB rule still holds.
        assertEquals("4", BackendProcess.importBudget(klein9, 9 * gb, 11L shl 30, 5_790L shl 20))
    }
}
