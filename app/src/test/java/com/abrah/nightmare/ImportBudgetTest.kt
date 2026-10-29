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
}
