package com.abrah.nightmare

import androidx.compose.runtime.Composable
import com.abrah.nightmare.ui.LibraryScreen
import com.abrah.nightmare.ui.LibraryTab
import com.abrah.nightmare.ui.ModelRow
import com.abrah.nightmare.ui.ModelsScreen
import com.abrah.nightmare.ui.NightmareTheme
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.graphics.asImageBitmap
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The model picker, drawn on the JVM.
 *
 * ⚠⚠ This is not only a drift check. The picker is driven headlessly by the
 * `models` / `model_install` ops, so every part of it EXCEPT the composable had
 * been exercised on device — a screen that crashed the moment it opened would
 * have shipped looking fully verified. Rendering it here is the cheapest way to
 * find that out without foregrounding the app on someone's phone.
 *
 * ⚠ The three states are the three a user actually meets: nothing installed
 * (a fresh install), a download running, and a model in use beside one that is
 * merely present.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ModelsScreenshotTest {

    /**
     * ⚠⚠ Wrapped in [LibraryScreen], because that is the only way these screens
     * are ever drawn now: they are TABS, and neither carries its own header or
     * window insets any more. A golden of the bare body would pin a composition
     * no user can reach — and would not have caught the header at all.
     */
    private fun shoot(
        name: String,
        tab: LibraryTab = LibraryTab.MODELS,
        body: @Composable () -> Unit,
    ) {
        captureRoboImage(filePath = "src/test/screenshots/$name.png") {
            NightmareTheme(darkTheme = true) {
                LibraryScreen(
                    tab = tab,
                    onTab = {},
                    onClose = {},
                    models = { if (tab == LibraryTab.MODELS) body() },
                    flows = { if (tab == LibraryTab.FLOWS) body() },
                )
            }
        }
    }

    private fun rows(
        installed: Set<String> = emptySet(),
        selected: String? = null,
        downloading: String? = null,
        fraction: Float = 0f,
    ) = ModelCatalog.all.map { spec ->
        // ⚠ The fixture pins an 8 Elite (v79, 8 MB), which is the device the
        // goldens were recorded on. A row's size and its tier chip both depend
        // on the DEVICE now, so the golden has to name one -- otherwise it
        // would pin whatever the JVM's `Build.SOC_MODEL` happens to be.
        val build = spec.buildFor(
            DeviceProbe.Caps(arch = 79, vtcmMb = 8, measured = true, soc = "SM8750")
        )
        ModelRow(
            spec = spec,
            build = build,
            installed = spec.id in installed,
            selected = spec.id == selected,
            progress = if (spec.id == downloading && build != null) {
                ModelInstaller.Progress(
                    "downloading", (build.bytes * fraction).toLong(), build.bytes,
                )
            } else {
                null
            },
            onDisk = if (spec.id in installed) 1_298_000_000L else 0L,
        )
    }

    /**
     * ⭐ A fresh install: the whole catalogue, none of it here yet.
     *
     * ⚠ It is now two families and fifteen rows, so this golden is also the
     * check that an SDXL row draws the same as an SD 1.5 one — a longer label
     * ("Pony Diffusion v6 XL") and a size with an extra digit are exactly what
     * makes a row wrap or a button slide off a 411dp phone.
     */
    @Test
    fun nothingInstalled() = shoot("models-empty") {
        ModelsScreen(
            rows = rows(), busy = false, error = null,
            onInstall = {}, onCancel = {}, onDelete = {}, onSelect = {},
        )
    }

    @Test
    fun oneInUseAndOneSpare() = shoot("models-installed") {
        ModelsScreen(
            rows = rows(installed = setOf(V1_MODEL, "qteamix"), selected = V1_MODEL),
            busy = false, error = null,
            onInstall = {}, onCancel = {}, onDelete = {}, onSelect = {},
        )
    }

    /** ⚠ Busy: every other action is disabled while a gigabyte is in flight. */
    @Test
    fun downloading() = shoot("models-downloading") {
        ModelsScreen(
            rows = rows(installed = setOf(V1_MODEL), selected = V1_MODEL, downloading = "qteamix", fraction = 0.42f),
            busy = true, error = null,
            onInstall = {}, onCancel = {}, onDelete = {}, onSelect = {},
        )
    }

    /**
     * ⭐⭐ The VIDEO tab — the third kind of model.
     *
     * ⚠ `rows = emptyList()` is not laziness: with no checkpoint families and
     * no upscalers, the video tab IS page 0, so this renders it without
     * teaching `SwipeTabs` to start on a chosen page. It also happens to be a
     * real state — a phone that downloaded the video models and no checkpoint.
     */
    @Test
    fun videoModelsNotInstalled() = shoot("models-video") {
        ModelsScreen(
            rows = emptyList(), busy = false, error = null,
            onInstall = {}, onCancel = {}, onDelete = {}, onSelect = {},
            video = com.abrah.nightmare.ui.VideoRow(
                installedBytes = 0,
                totalBytes = 8_596_825_402L,
                missing = listOf("clipg", "mmdit_s0fs"),
                weightsMissing = emptyList(),
                supported = true,
            ),
        )
    }

    /**
     * ⚠ Part-way through, with the host-side weights already down.
     *
     * ⚠⚠ They are fetched FIRST although they are 0.7% of the bytes: the app
     * cannot render a frame without them, so a cancel at 99% must not leave
     * 8.5 GB on disk that still cannot make a video.
     */
    @Test
    fun videoModelsPartlyDownloaded() = shoot("models-video-weights") {
        ModelsScreen(
            rows = emptyList(), busy = true, error = null,
            onInstall = {}, onCancel = {}, onDelete = {}, onSelect = {},
            video = com.abrah.nightmare.ui.VideoRow(
                installedBytes = 1_460_000_000L,
                totalBytes = 8_596_825_402L,
                missing = listOf("clipg", "mmdit_s0fs", "mmdit_s1fs"),
                weightsMissing = emptyList(),
                supported = true,
                progress = ModelInstaller.Progress("downloading clipg", 1_460_000_000L, 8_596_825_402L),
            ),
        )
    }

    /** A failed install has to say why — "size mismatch" is the common one. */
    @Test
    fun withAnError() = shoot("models-error") {
        ModelsScreen(
            rows = rows(installed = setOf(V1_MODEL), selected = V1_MODEL),
            busy = false,
            error = "the download stopped short — 871 of 1007 MB arrived " +
                "(QteaMix_qnn2.28_8gen2.zip). Download again to resume.",
            onInstall = {}, onCancel = {}, onDelete = {}, onSelect = {},
        )
    }

    /**
     * ⭐⭐ Imported checkpoints beside the catalogue, plus the Import card.
     *
     * ⚠⚠ This golden exists because every OTHER models golden passes
     * [ModelsScreen]'s `onImport` as null and therefore draws none of this. The
     * import path would otherwise have shipped with no rendered coverage at
     * all, which is precisely the shape of the bug `docs/UI.md` §5 collects:
     * fully exercised headlessly, never once drawn.
     *
     * ⚠ Both custom states are here on purpose. A COMPLETE import must show
     * Delete/Use like any installed model, and an INCOMPLETE one must show
     * "missing …" with a Remove — before [ModelSpec.isCustom] existed the
     * second fell through to the `build == null` arm and read "Unsupported",
     * blaming the phone for a truncated zip.
     */
    @Test
    fun importedModelsAndTheImportCard() = shoot("models-imported") {
        val custom = listOf(
            ModelRow(
                spec = customSpec("my-sd15-mix", Family.SD15),
                build = null, installed = true, selected = false, onDisk = 1_298_000_000L,
            ),
            ModelRow(
                spec = customSpec("half-copied", Family.SD15),
                build = null, installed = false, selected = false,
                missing = listOf("vae_decoder.bin", "vae_encoder.bin"),
            ),
        )
        ModelsScreen(
            rows = rows(installed = setOf(V1_MODEL), selected = V1_MODEL) + custom,
            busy = false, error = null,
            onInstall = {}, onCancel = {}, onDelete = {}, onSelect = {},
            onImport = {},
        )
    }

    /**
     * ⚠ Built the way [CustomModels] builds one — empty builds, no arch claim —
     * rather than a hand-made [ModelSpec] that could drift from it. The fields
     * the card reads are `isCustom`, `family`, `native` and `label`.
     */
    private fun customSpec(id: String, family: Family) = ModelSpec(
        id = id,
        label = id,
        builds = emptyList(),
        prompt = "",
        negative = "",
        family = family,
        backendType = if (family == Family.SDXL) ModelCatalog.SDXL_NPU else ModelCatalog.SD15_NPU,
        resolutions = listOf(
            if (family == Family.SDXL) ModelCatalog.SDXL_NPU_RES else ModelCatalog.SD15_NPU_RES,
        ),
        requiredFiles = if (family == Family.SDXL) {
            ModelCatalog.SDXL_REQUIRED
        } else {
            ModelCatalog.SD15_REQUIRED
        },
        minHtpArch = 0,
        isCustom = true,
    )

    /** ⭐ The Workflows tab: recommended graphs, and the user's own. */
    @Test
    fun workflowsWithSavedOnes() = shoot("workflows", LibraryTab.FLOWS) {
        com.abrah.nightmare.ui.WorkflowsScreen(
            recipes = com.abrah.nightmare.canvas.RECIPES,
            saved = listOf(
                com.abrah.nightmare.canvas.SavedWorkflow("portrait tweak", 0),
                com.abrah.nightmare.canvas.SavedWorkflow("cat on grass", 0),
            ),
            error = null,
            onOpenRecipe = {}, onOpenSaved = {}, onDeleteSaved = {},
        )
    }

    /** ⚠ And empty, which is what a new user sees — it must say HOW to fill it. */
    @Test
    fun workflowsWithNothingSaved() = shoot("workflows-empty", LibraryTab.FLOWS) {
        com.abrah.nightmare.ui.WorkflowsScreen(
            recipes = com.abrah.nightmare.canvas.RECIPES,
            saved = emptyList(),
            error = null,
            onOpenRecipe = {}, onOpenSaved = {}, onDeleteSaved = {},
        )
    }

    /** ⚠ The selection row at 360dp: count, All and six icons must fit ONE row. */
    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun historySelectingNarrow() = history(name = "history-selecting-360", selected = setOf("r1", "r2", "r3"))

    /** ⭐ History — the big preview over the grid (DreamUI's layout, 2026-09-17). */
    @Test
    fun history() = history(name = "history", selected = emptySet())

    private fun history(name: String, selected: Set<String>) {
        val colours = listOf(0xFF3A6EA5.toInt(), 0xFFA53A6E.toInt(), 0xFF6EA53A.toInt(), 0xFFA5873A.toInt())
        fun pic(c: Int): androidx.compose.ui.graphics.ImageBitmap {
            val b = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
            b.eraseColor(c)
            return b.asImageBitmap()
        }
        val items = (0 until 9).map { i ->
            com.abrah.nightmare.canvas.Result(
                id = "r$i", savedAt = i.toLong(), seed = "12$i", model = "qteamix",
                prompt = "a cat on grass, number $i", width = 512, height = 512,
                favourite = i % 3 == 0,
            )
        }
        val thumbs = items.associate { it.id to pic(colours[items.indexOf(it) % colours.size]) }
        captureRoboImage(filePath = "src/test/screenshots/$name.png") {
            NightmareTheme(darkTheme = true) {
                LibraryScreen(
                    tab = LibraryTab.RESULTS, onTab = {}, onClose = {},
                    models = {}, flows = {},
                    results = {
                        com.abrah.nightmare.ui.ResultsScreen(
                            groups = items.map { com.abrah.nightmare.canvas.ResultGroup(null, listOf(it)) },
                            results = items,
                            thumbnailFor = { thumbs[it] },
                            onOpenFlow = {}, onView = {}, onDelete = {},
                            onDiskBytes = 42L shl 20,
                            selected = selected,
                        )
                    },
                )
            }
        }
    }
}
