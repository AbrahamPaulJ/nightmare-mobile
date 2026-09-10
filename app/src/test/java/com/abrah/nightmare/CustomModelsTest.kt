package com.abrah.nightmare

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Checkpoints the user brought.
 *
 * ⭐ The tests worth having here are about **classification**, not unzipping.
 * An import that puts the files in the right place and then calls an SDXL model
 * SD 1.5 is worse than one that fails: `backendTypeOf` would answer `sd15npu`,
 * the backend would launch at 512, and the render would succeed at the wrong
 * size in silence (`ModelCatalog.all`'s note).
 *
 * ⚠ Robolectric for a `Context` — the models root is `getExternalFilesDir`.
 */
@RunWith(RobolectricTestRunner::class)
class CustomModelsTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun root() = ModelCatalog.root(ctx)

    @Before
    @After
    fun clean() {
        root().deleteRecursively()
        CustomModels.scan(ctx)
    }

    /** A model directory holding [files], each one byte. */
    private fun onDisk(name: String, files: List<String>): File {
        val dir = File(root(), name).apply { mkdirs() }
        for (f in files) File(dir, f).writeText("x")
        return dir
    }

    private fun zipOf(files: List<String>, prefix: String = ""): File {
        val f = File.createTempFile("model", ".zip")
        ZipOutputStream(f.outputStream()).use { z ->
            for (n in files) {
                z.putNextEntry(ZipEntry("$prefix$n"))
                z.write("x".toByteArray())
                z.closeEntry()
            }
        }
        return f
    }

    // ---- classification --------------------------------------------------

    @Test
    fun sd15IsRecognisedFromItsClipFile() {
        onDisk("mine", ModelCatalog.SD15_REQUIRED)
        val spec = CustomModels.scan(ctx).single()
        assertEquals("mine", spec.id)
        assertEquals(Family.SD15, spec.family)
        assertEquals(ModelCatalog.SD15_NPU, spec.backendType)
        assertEquals(ModelCatalog.SD15_NPU_RES, spec.native)
        assertTrue(spec.isCustom)
        assertFalse("SD 1.5 must not ask for --lowram", spec.lowram)
    }

    @Test
    fun sdxlIsRecognisedFromItsSecondEncoder() {
        onDisk("mine", ModelCatalog.SDXL_REQUIRED)
        val spec = CustomModels.scan(ctx).single()
        assertEquals(Family.SDXL, spec.family)
        assertEquals(ModelCatalog.SDXL_NPU, spec.backendType)
        assertEquals(ModelCatalog.SDXL_NPU_RES, spec.native)
        // ⚠⚠ THE regression this whole design exists to prevent: an SDXL model
        // classified SD 1.5 launches `--type sd15npu`, the backend renders 512,
        // and nothing reports a mismatch.
        assertFalse(spec.backendType == ModelCatalog.SD15_NPU)
        assertTrue("SDXL needs --lowram", spec.lowram)
    }

    /**
     * ⭐⭐ The property the marker designs cannot offer: a half-extracted
     * directory is unclassifiable, never MISclassified.
     */
    @Test
    fun aPartialSdxlIsNeverMistakenForSd15() {
        // Everything an SDXL archive holds except the discriminator.
        onDisk("half", ModelCatalog.SDXL_REQUIRED - "clip_2.mnn")
        assertTrue(
            "a partial SDXL must not be listed as anything",
            CustomModels.scan(ctx).isEmpty(),
        )
    }

    /** ⚠ A directory holding both is a mixed unpack, not a family. */
    @Test
    fun bothDiscriminatorsIsRefused() {
        onDisk("mixed", ModelCatalog.SD15_REQUIRED + ModelCatalog.SDXL_REQUIRED)
        assertTrue(CustomModels.scan(ctx).isEmpty())
    }

    /** ⚠ The backend writes `cache/` under the models root; it is not a model. */
    @Test
    fun anUnrecognisableDirectoryIsIgnored() {
        onDisk("cache", listOf("prompts.bin"))
        assertTrue(CustomModels.scan(ctx).isEmpty())
    }

    /**
     * ⚠ Incomplete but classifiable is LISTED, with [ModelSpec.missing] naming
     * the gap — the card has no Download button to offer, so this listing is
     * the only place a user is ever told what is absent.
     */
    @Test
    fun anIncompleteModelIsListedAndSaysWhatIsMissing() {
        onDisk("partial", listOf("clip_v2.mnn", "unet.bin"))
        val spec = CustomModels.scan(ctx).single()
        assertFalse(spec.installed(ctx))
        assertTrue("vae_decoder.bin" in spec.missing(ctx))
        assertFalse("the file it has must not be reported missing", "unet.bin" in spec.missing(ctx))
    }

    // ---- catalogue integration -------------------------------------------

    /**
     * ⭐⭐ The point of the whole design: a custom model is resolvable by
     * [ModelCatalog.byId], because that is what `backendTypeOf`,
     * `resolutionOf`, `SelectedModel.set` and the executor's context key read.
     */
    @Test
    fun aCustomModelResolvesThroughTheCatalogue() {
        onDisk("mine", ModelCatalog.SDXL_REQUIRED)
        CustomModels.scan(ctx)
        assertNotNull(ModelCatalog.byId("mine"))
        assertEquals(ModelCatalog.SDXL_NPU, ModelCatalog.backendTypeOf("mine"))
        assertEquals(ModelCatalog.SDXL_NPU_RES, ModelCatalog.resolutionOf("mine"))
        assertTrue(ModelCatalog.all.any { it.id == "mine" })
        // ⚠ And is NOT in `builtIn`, which is what the device sheet counts.
        assertFalse(ModelCatalog.builtIn.any { it.id == "mine" })
    }

    @Test
    fun selectingACustomModelIsAllowedAndSurvivesAReload() {
        onDisk("mine", ModelCatalog.SD15_REQUIRED)
        CustomModels.scan(ctx)
        SelectedModel.set(ctx, "mine")
        assertEquals("mine", SelectedModel.id)
        // ⚠⚠ The ordering trap: `load` drops an id the catalogue cannot see, so
        // it has to scan FIRST. Before it did, a user whose selected checkpoint
        // was imported was reset to V1_MODEL on every launch.
        SelectedModel.load(ctx)
        assertEquals("mine", SelectedModel.id)
        SelectedModel.set(ctx, V1_MODEL)
    }

    /** ⚠ A stored id whose directory is gone still falls back. */
    @Test
    fun aDeletedCustomModelFallsBackOnReload() {
        onDisk("mine", ModelCatalog.SD15_REQUIRED)
        CustomModels.scan(ctx)
        SelectedModel.set(ctx, "mine")
        File(root(), "mine").deleteRecursively()
        SelectedModel.load(ctx)
        assertEquals(V1_MODEL, SelectedModel.id)
    }

    /**
     * ⚠⚠ A directory named after a built-in must not shadow it: [ModelCatalog.byId]
     * returns the first match, so the catalogue entry would answer with the
     * user's files and point its downloads at them.
     */
    @Test
    fun aCollidingNameIsSkipped() {
        onDisk(V1_MODEL, ModelCatalog.SDXL_REQUIRED)
        assertTrue(CustomModels.scan(ctx).isEmpty())
        // The built-in still answers, still as SD 1.5.
        assertEquals(Family.SD15, ModelCatalog.byId(V1_MODEL)!!.family)
        assertFalse(ModelCatalog.byId(V1_MODEL)!!.isCustom)
    }

    /** ⚠ `builds.first()` threw here; a custom model has none. */
    @Test
    fun aCustomModelHasNoBuildAndDoesNotThrowForOne() {
        onDisk("mine", ModelCatalog.SD15_REQUIRED)
        val spec = CustomModels.scan(ctx).single()
        assertNull(spec.best)
        assertNull(
            spec.buildFor(
                DeviceProbe.Caps(arch = 79, vtcmMb = 8, measured = true, soc = "SM8750"),
            ),
        )
    }

    // ---- importing -------------------------------------------------------

    @Test
    fun importUnpacksFlattenedAndClassifies() {
        val zip = zipOf(ModelCatalog.SDXL_REQUIRED, prefix = "output_1024/qnn_models_8gen3/")
        val spec = CustomModels.import(ctx, "brought", { zip.inputStream() })
        assertEquals(Family.SDXL, spec.family)
        assertTrue(spec.installed(ctx))
        // ⚠ Flattened: the backend resolves siblings by bare filename, so a
        // nested tree is an install that cannot start.
        assertTrue(File(root(), "brought/clip_2.mnn").isFile)
        assertFalse(File(root(), "brought/output_1024").exists())
    }

    /**
     * ⚠⚠ **A REAL archive from Windows PowerShell's `Compress-Archive`**, which
     * writes `\` separators in violation of the ZIP spec.
     *
     * ⭐ This is a checked-in binary fixture rather than a synthesised zip on
     * purpose: the bug is *in the bytes the tool produces*, and every zip this
     * suite builds with `ZipOutputStream` is conformant and therefore cannot
     * reproduce it. Found on the device 2026-09-10, after the JVM tests above
     * had all passed.
     *
     * ⚠ The failure it guards is silent: backslash is an ordinary filename
     * character on Android, so the entries extracted *successfully* under names
     * like `output_512\qnn_models_8gen2\clip_v2.mnn` and the import was then
     * rejected as "not a checkpoint" — blaming the archive for holding the
     * right files under the wrong names.
     */
    @Test
    fun aWindowsCompressArchiveZipImportsCorrectly() {
        val bytes = javaClass.classLoader!!
            .getResourceAsStream("windows-compress-archive.zip")!!.readBytes()
        val spec = CustomModels.import(ctx, "from-windows", { bytes.inputStream() })
        assertEquals(Family.SD15, spec.family)
        assertTrue("every required file must be present", spec.installed(ctx))
        // ⚠ The exact shape of the bug: a file named for its whole path.
        assertTrue(File(root(), "from-windows/clip_v2.mnn").isFile)
        assertNull(
            "no entry may keep a path separator in its name",
            File(root(), "from-windows").listFiles().orEmpty()
                .firstOrNull { '\\' in it.name || '/' in it.name },
        )
        // ⚠ And the config.json inside it is read, so the whole path works.
        assertEquals("Inbox Test", spec.label)
    }

    /**
     * ⭐ Flattening to the last segment is also the traversal guard: an entry
     * that tries to climb out lands in the model directory under a bare name.
     */
    @Test
    fun aTraversingEntryCannotEscapeTheModelDirectory() {
        val f = File.createTempFile("evil", ".zip")
        ZipOutputStream(f.outputStream()).use { z ->
            for (n in ModelCatalog.SD15_REQUIRED) {
                z.putNextEntry(ZipEntry(n)); z.write("x".toByteArray()); z.closeEntry()
            }
            z.putNextEntry(ZipEntry("../../../evil.txt")); z.write("x".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("..\\..\\evil2.txt")); z.write("x".toByteArray()); z.closeEntry()
        }
        CustomModels.import(ctx, "evil", { f.inputStream() })
        assertTrue(File(root(), "evil/evil.txt").isFile)
        assertTrue(File(root(), "evil/evil2.txt").isFile)
        assertFalse(File(root().parentFile, "evil.txt").exists())
        assertFalse(File(root(), "../evil2.txt").exists())
    }

    /** ⚠ A Mac's resource forks share the basenames and would land on top. */
    @Test
    fun macosxJunkIsSkipped() {
        val zip = zipOf(
            ModelCatalog.SD15_REQUIRED + listOf(".DS_Store"),
            prefix = "",
        ).let { plain ->
            // Rebuild with a __MACOSX sibling for each entry.
            val f = File.createTempFile("mac", ".zip")
            ZipOutputStream(f.outputStream()).use { z ->
                for (n in ModelCatalog.SD15_REQUIRED) {
                    z.putNextEntry(ZipEntry(n)); z.write("x".toByteArray()); z.closeEntry()
                    z.putNextEntry(ZipEntry("__MACOSX/._$n")); z.write("junk".toByteArray()); z.closeEntry()
                }
                z.putNextEntry(ZipEntry(".DS_Store")); z.write("junk".toByteArray()); z.closeEntry()
            }
            plain.delete()
            f
        }
        CustomModels.import(ctx, "mac", { zip.inputStream() })
        val names = File(root(), "mac").listFiles().orEmpty().map { it.name }.toSet()
        assertEquals(ModelCatalog.SD15_REQUIRED.toSet(), names)
    }

    /** ⚠ Nothing recognisable: the directory is removed, not left half-made. */
    @Test
    fun importOfSomethingElseFailsAndCleansUp() {
        val zip = zipOf(listOf("readme.txt", "model.safetensors"))
        assertThrows(java.io.IOException::class.java) {
            CustomModels.import(ctx, "junk", { zip.inputStream() })
        }
        assertFalse(File(root(), "junk").exists())
    }

    /**
     * ⚠ An incomplete-but-classifiable archive is KEPT. The files are the
     * user's, and deleting would mean re-importing gigabytes to find out what
     * was absent.
     */
    @Test
    fun anIncompleteImportIsKeptSoItCanSayWhy() {
        val zip = zipOf(listOf("clip_v2.mnn", "unet.bin"))
        val spec = CustomModels.import(ctx, "short", { zip.inputStream() })
        assertFalse(spec.installed(ctx))
        assertTrue(File(root(), "short/unet.bin").isFile)
        assertTrue(spec.missing(ctx).isNotEmpty())
    }

    /** ⚠ A retry must not count last attempt's files toward "complete". */
    @Test
    fun reimportingReplacesRatherThanMerges() {
        CustomModels.import(ctx, "again", { zipOf(ModelCatalog.SD15_REQUIRED).inputStream() })
        assertTrue(ModelCatalog.byId("again")!!.installed(ctx))
        CustomModels.import(ctx, "again", { zipOf(listOf("clip_v2.mnn")).inputStream() })
        assertFalse(
            "the first import's files must not survive the second",
            ModelCatalog.byId("again")!!.installed(ctx),
        )
    }

    @Test
    fun reservedAndMalformedNamesAreRefused() {
        val zip = zipOf(ModelCatalog.SD15_REQUIRED)
        assertThrows(IllegalArgumentException::class.java) {
            CustomModels.import(ctx, V1_MODEL, { zip.inputStream() })
        }
        for (bad in listOf("", "  ", "a/b", "a\\b", "a:b", ".hidden")) {
            assertFalse("\"$bad\" must be refused", CustomModels.isValidName(bad))
        }
        assertTrue(CustomModels.isValidName("my model 2"))
    }

    // ---- config.json -----------------------------------------------------

    /** ⭐ Upstream's mechanism, which DreamUI dropped. Upstream's key names. */
    @Test
    fun configJsonSuppliesLabelAndPrompts() {
        val dir = onDisk("mine", ModelCatalog.SD15_REQUIRED)
        File(dir, "config.json").writeText(
            """{"label":"My Checkpoint","default_prompt":"a cat","default_negative_prompt":"blurry"}""",
        )
        val spec = CustomModels.scan(ctx).single()
        assertEquals("mine", spec.id)
        assertEquals("My Checkpoint", spec.label)
        assertEquals("a cat", spec.prompt)
        assertEquals("blurry", spec.negative)
    }

    /**
     * ⭐⭐ The field that makes `config.json` load-bearing: a distilled
     * checkpoint declares the sampler it was tuned for, and a new sampler node
     * picks it up. Without it the backend's `dpm` default applies and the model
     * renders crunchy at its own published settings.
     */
    @Test
    fun configJsonSuppliesTheScheduler() {
        val dir = onDisk("mine", ModelCatalog.SDXL_REQUIRED)
        File(dir, "config.json").writeText("{\"default_scheduler\":\"euler_a\"}")
        assertEquals("euler_a", CustomModels.scan(ctx).single().scheduler)
    }

    /**
     * ⚠⚠ An unknown sampler is DROPPED for the backend's default, not passed
     * through. `Pipeline.hpp` does not reject an unknown string — it falls
     * through to `dpm` in silence — so forwarding a typo would reproduce
     * exactly the bug this field exists to fix and blame the checkpoint.
     */
    @Test
    fun anUnknownSchedulerFallsBackRatherThanBeingForwarded() {
        val dir = onDisk("mine", ModelCatalog.SD15_REQUIRED)
        File(dir, "config.json").writeText("{\"default_scheduler\":\"euler-a\"}")
        assertEquals(ModelCatalog.DEFAULT_SCHEDULER, CustomModels.scan(ctx).single().scheduler)
    }

    /**
     * ⭐⭐ The full distilled-model recipe from one file: this is what makes an
     * imported turbo checkpoint usable without the user knowing anything.
     */
    @Test
    fun configJsonSuppliesTheWholeRecipe() {
        val dir = onDisk("turbo", ModelCatalog.SDXL_REQUIRED)
        File(dir, "config.json").writeText(
            "{\"default_scheduler\":\"euler_a\",\"default_steps\":10,\"default_cfg\":1.5}"
        )
        val spec = CustomModels.scan(ctx).single()
        assertEquals("euler_a", spec.scheduler)
        assertEquals(10, spec.steps)
        assertEquals(1.5, spec.cfg, 0.001)
    }

    /**
     * ⚠ Out-of-range values are CLAMPED, not dropped. An author who writes 60
     * steps meant "a lot", and silently giving them 20 would be worse than
     * giving them the 50 the slider can express.
     */
    @Test
    fun outOfRangeStepsAndCfgAreClamped() {
        val dir = onDisk("wild", ModelCatalog.SD15_REQUIRED)
        File(dir, "config.json").writeText(
            "{\"default_steps\":600,\"default_cfg\":99.0}"
        )
        val spec = CustomModels.scan(ctx).single()
        assertEquals(ModelCatalog.STEPS_RANGE.last, spec.steps)
        assertEquals(ModelCatalog.CFG_RANGE.endInclusive, spec.cfg, 0.001)
    }

    /** ⚠ No config at all is the backend's default, so nothing changes. */
    @Test
    fun noConfigMeansTheBackendDefault() {
        onDisk("mine", ModelCatalog.SD15_REQUIRED)
        assertEquals(ModelCatalog.DEFAULT_SCHEDULER, CustomModels.scan(ctx).single().scheduler)
    }

    /**
     * ⚠ A malformed config must not cost the user the import — the model's
     * files are fine and only the metadata is unreadable.
     */
    @Test
    fun aBrokenConfigIsIgnoredNotFatal() {
        val dir = onDisk("mine", ModelCatalog.SD15_REQUIRED)
        File(dir, "config.json").writeText("{ this is not json")
        val spec = CustomModels.scan(ctx).single()
        assertEquals("mine", spec.label)
        assertTrue(spec.installed(ctx))
    }

    /**
     * ⚠⚠ `config.json` is metadata ONLY. A family key would be a way for the
     * archive to contradict its own files, which is the failure this design
     * exists to prevent — so an SDXL directory claiming SD 1.5 stays SDXL.
     */
    @Test
    fun configCannotOverrideTheInferredFamily() {
        val dir = onDisk("mine", ModelCatalog.SDXL_REQUIRED)
        File(dir, "config.json").writeText(
            """{"family":"SD15","backendType":"sd15npu","resolution":512}""",
        )
        val spec = CustomModels.scan(ctx).single()
        assertEquals(Family.SDXL, spec.family)
        assertEquals(ModelCatalog.SDXL_NPU, spec.backendType)
    }

    // ---- the inbox -------------------------------------------------------

    /** ⭐ The headless path: a zip pushed to `models-inbox/`, no picker. */
    @Test
    fun theInboxImportsByBasenameAndDeletesOnSuccess() {
        val inbox = CustomModels.inbox(ctx).apply { mkdirs() }
        val zip = File(inbox, "from-adb.zip")
        zipOf(ModelCatalog.SD15_REQUIRED).copyTo(zip, overwrite = true)
        val lines = CustomModels.importInbox(ctx)
        assertEquals(1, lines.size)
        assertTrue(lines.single(), lines.single().startsWith("ok"))
        assertNotNull(ModelCatalog.byId("from-adb"))
        assertFalse("a consumed archive must not be imported twice", zip.exists())
    }

    /** ⚠ A failed archive is KEPT, so a retry needs no second push. */
    @Test
    fun aFailedInboxImportKeepsTheZip() {
        val inbox = CustomModels.inbox(ctx).apply { mkdirs() }
        val zip = File(inbox, "nonsense.zip")
        zipOf(listOf("readme.txt")).copyTo(zip, overwrite = true)
        val lines = CustomModels.importInbox(ctx)
        assertTrue(lines.single(), lines.single().startsWith("FAIL"))
        assertTrue(zip.exists())
    }
}
