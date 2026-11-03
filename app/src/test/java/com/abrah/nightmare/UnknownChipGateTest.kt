package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐⭐⭐ A chip no table knows must not be told it cannot run anything.
 *
 * ⚠⚠ Reported 2026-09-20 by a Snapdragon 8 Gen 5 owner: every SDXL, Anima and
 * FLUX row read *"это устройство не сможет её запустить"* — this device cannot
 * run it. Their `Build.SOC_MODEL` is not one of the strings in `SOC_TO_ARCH`,
 * so `caps()` fell to the v68 / 2 MB floor and every gate above SD 1.5 `_min`
 * failed. The app stated as fact something it had only assumed.
 *
 * ⚠ We cannot hold an 8 Gen 5, so the gate is tested through the part-number
 * parse directly rather than through `Build.SOC_MODEL`. That is the whole of
 * the new behaviour; the rest of `caps()` is unchanged.
 */
class UnknownChipGateTest {

    /** The newest part number the tables know, whatever it is today. */
    private val newestKnown = 8850

    /**
     * ⭐ The reported case. An unrecognised variant of a chip at or above the
     * newest we know gets the newest arch, not the floor.
     */
    @Test
    fun anUnknownNewerChipIsNotFlooredToV68() {
        for (soc in listOf("SM8850-AC", "SM8860")) {
            val arch = DeviceProbe.archFromPartNumber(soc)
            assertNotNull("$soc was not recognised as newer than $newestKnown", arch)
            assertTrue(
                "$soc resolved to v$arch, no better than the floor",
                arch!! > DeviceProbe.FLOOR_ARCH,
            )
        }
    }

    /**
     * ⚠⚠ And an OLDER unknown chip keeps the floor. Qualcomm's numbering is
     * only roughly chronological — the SM8635 is an "8s Gen 3", newer by name
     * than an 8 Gen 2 and with less than 8 MB of VTCM — so guessing upward on
     * anything below the newest known part is how a user is handed a gigabyte
     * their HTP then refuses. That failure is worse than the one being fixed.
     */
    @Test
    fun anUnknownOlderChipKeepsTheFloor() {
        // ⚠ SM8635 left this list when the table learned it (v73, 2026-10-17); SM8625 is a made-up "s" part.
        for (soc in listOf("SM6375", "SM7325", "SM8625", "SM4350")) {
            assertNull(
                "$soc was guessed upward, which risks an unusable download",
                DeviceProbe.archFromPartNumber(soc),
            )
        }
    }

    /**
     * ⭐ The 8s Gen 3 (SM8635): v73 with under 8 MB of VTCM. The four v73 DiT builds as alpha (ggml sizes its work
     * to the session's VTCM), SD 1.5's `_min`, no SDXL, no v75 DiT (2026-10-17).
     */
    @Test
    fun the8sGen3GetsTheV73DitBuildsAndNothingThatNeeds8Mb() {
        val caps = DeviceProbe.Caps(arch = 73, vtcmMb = DeviceProbe.FLOOR_VTCM_MB, measured = false, soc = "SM8635")
        for (id in listOf("z_image_turbo", "flux2_klein_9b", "anima_turbo_1_1")) {
            // ⚠ At most 73: Anima Turbo's floor is v69 since the 8 Gen 1 alpha (`docs/DEVICES.md` §3c).
            val floor = ModelCatalog.byId(id)!!.buildFor(caps)?.minArch
            assertTrue("$id on the 8s Gen 3: $floor", floor != null && floor <= 73)
        }
        assertNull("Krea 2 is v75", ModelCatalog.byId("krea2_turbo")!!.buildFor(caps))
        assertEquals(DeviceProbe.FLOOR_VTCM_MB, DeviceProbe.FLOOR_VTCM_MB.coerceAtMost(ModelCatalog.ditVtcmFloor(73)))
        assertEquals(8, ModelCatalog.ditVtcmFloor(75))
    }

    /** ⚠ Anything that is not a Snapdragon part gets no guess at all. */
    @Test
    fun aNonQualcommOrMalformedChipGetsNoGuess() {
        for (soc in listOf("", "EXYNOS2400", "TENSOR G4", "SM", "SMXXXX")) {
            assertNull("$soc produced a guess", DeviceProbe.archFromPartNumber(soc))
        }
    }

    /**
     * ⭐⭐ Within the newest generation the guess is an arch the APK ships libraries for.
     * Guessing an arch whose Skel is absent makes NPU init fail outright with an error that
     * names neither the arch nor the file.
     */
    @Test
    fun theGuessedArchIsOneWeShipLibrariesFor() {
        val arch = DeviceProbe.archFromPartNumber("SM8860")
        assertNotNull(arch)
        assertTrue(
            "guessed v$arch, which is not in STAGED_ARCHES ${DeviceProbe.STAGED_ARCHES}",
            arch in DeviceProbe.STAGED_ARCHES,
        )
    }

    /**
     * The downloaded runtime ([QnnPack], QAIRT 2.51 for every arch since 1.6.273) holds a whole trio for every arch
     * the probe can name, and nothing else: a named arch with no trio is a phone whose NPU init fails.
     */
    @Test
    fun theDownloadedRuntimeServesEveryArchTheAppNames() {
        assertEquals((DeviceProbe.STAGED_ARCHES + DeviceProbe.PACK_ARCHES).sorted(), QnnPack.RUNTIME_ARCHES)
        for (a in QnnPack.RUNTIME_ARCHES) {
            for (n in listOf("libQnnHtpV$a.so", "libQnnHtpV${a}Skel.so", "libQnnHtpV${a}Stub.so")) {
                assertTrue(n, n in QnnPack.FILES)
            }
        }
        assertTrue("libQnnHtp.so" in QnnPack.FILES && "libQnnSystem.so" in QnnPack.FILES)
        assertEquals(2 + 3 * QnnPack.RUNTIME_ARCHES.size, QnnPack.FILES.size)
        // A file at its exact size is kept; any other size (a 2.50 one) is fetched again.
        val dir = java.nio.file.Files.createTempDirectory("qnnrt").toFile()
        val stub = "libQnnHtpV79Stub.so"
        java.io.RandomAccessFile(java.io.File(dir, stub), "rw").use { it.setLength(QnnPack.FILES.getValue(stub).first) }
        java.io.RandomAccessFile(java.io.File(dir, "libQnnHtp.so"), "rw").use { it.setLength(3_978_976L) }
        assertEquals(QnnPack.FILES.getValue("libQnnHtp.so").first, QnnPack.missingBytes(dir, listOf(stub, "libQnnHtp.so")))
        dir.deleteRecursively()
    }

    /**
     * ⭐⭐⭐ One generation newer (SM8950 / SM8975, the 8 Elite Gen 6) is v85 since 1.6.202: its runtime is
     * DOWNLOADED ([QnnPack], the user's call 2026-10-19), not refused, and every NPU build runs forward on it.
     * ⚠ NOT handed v81 (a user's npuforge compile died "Device Creation failure", 2026-10-09). Two generations
     * on is still newer-than-staged and refused.
     */
    @Test
    fun theNextGenerationIsV85AndTheOneAfterIsRefused() {
        for (soc in listOf("SM8950", "SM8975")) {
            val arch = DeviceProbe.archFromPartNumber(soc)
            assertEquals("$soc", 85, arch)
            val caps = DeviceProbe.Caps(arch!!, 8, measured = false, soc = soc)
            assertFalse("$soc flagged too new", caps.npuTooNew)
            assertTrue("$soc refused a v79 context", caps.supportsArch(79))
        }
        val far = DeviceProbe.archFromPartNumber("SM9050P")
        assertEquals(DeviceProbe.NEWER_THAN_STAGED, far)
        val caps = DeviceProbe.Caps(far!!, 8, measured = false, soc = "SM9050P")
        assertTrue(caps.npuTooNew)
        assertTrue("counted as unknown, it would be offered everything", caps.known)
        assertFalse(caps.supportsArch(68))
    }

    /** ⭐ A MEASURED v85 runs what a v81 runs (the pack's runtime); an arch past every pack is refused. */
    @Test
    fun aMeasuredV85RunsAndAnUnknownArchDoesNot() {
        assertTrue(DeviceProbe.Caps(85, 8, measured = true, soc = "SM8975").supportsArch(79))
        val caps = DeviceProbe.Caps(89, 8, measured = true, soc = "SM9050")
        assertFalse(caps.supportsArch(79))
        assertTrue(caps.npuTooNew)
    }

    /**
     * ⭐⭐ GitHub #85: a MediaTek phone (MT6835) read as an unknown chip at the v68 floor, was offered every flow and
     * the `_min` builds, and failed every launch with `QnnDsp No Snapdragon SOC detected`. A chip KNOWN not to be a
     * Snapdragon now runs no NPU build and no flow; a Snapdragon, or a chip that says nothing, is unaffected.
     */
    @Test
    fun aChipKnownNotToBeSnapdragonRunsNothing() {
        assertFalse(DeviceProbe.qualcommVendor("Mediatek", "MT6835"))
        assertFalse(DeviceProbe.qualcommVendor("", "MT6989"))
        assertFalse(DeviceProbe.qualcommVendor("Samsung", "S5E9945"))
        assertFalse(DeviceProbe.qualcommVendor("Google", "TENSOR G4"))
        // ⚠ The controls: a Snapdragon by its maker, by its part number whatever the maker says, and a silent chip.
        assertTrue(DeviceProbe.qualcommVendor("QTI", "SM8750"))
        assertTrue(DeviceProbe.qualcommVendor("Samsung", "SM8750"))
        assertTrue(DeviceProbe.qualcommVendor("", "SM8975"))
        assertTrue(DeviceProbe.qualcommVendor("", ""))
        assertTrue(DeviceProbe.qualcommVendor("Xiaomi", "SM7750"))

        val mtk = DeviceProbe.Caps(DeviceProbe.FLOOR_ARCH, 2, measured = false, soc = "MT6835", qualcomm = false)
        assertTrue("a known non-Snapdragon is not an unknown chip to be offered everything", mtk.known)
        assertFalse(mtk.supportsArch(DeviceProbe.FLOOR_ARCH))
        assertTrue(com.abrah.nightmare.canvas.RECIPES.none { it.runsOnDevice(mtk) })
        assertEquals(emptyList<String>(), ModelCatalog.builtIn.filter { it.buildFor(mtk) != null }.map { it.id })

        val unknown = DeviceProbe.Caps(DeviceProbe.FLOOR_ARCH, 2, measured = false, soc = "")
        assertTrue(com.abrah.nightmare.canvas.RECIPES.all { it.runsOnDevice(unknown) })
    }

    /**
     * ⚠ A known chip must still come from the TABLE, not the parse — the
     * tables carry real per-chip facts the part number cannot imply.
     */
    @Test
    fun aKnownChipIsUnaffected() {
        assertEquals(
            "the parse overrode a chip the table knows",
            DeviceProbe.SOC_TO_ARCH["SM8750"],
            79,
        )
    }
}
