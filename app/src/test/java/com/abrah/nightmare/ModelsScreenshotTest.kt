package com.abrah.nightmare

import androidx.compose.runtime.Composable
import com.abrah.nightmare.ui.LibraryScreen
import com.abrah.nightmare.ui.LibraryTab
import com.abrah.nightmare.ui.ModelRow
import com.abrah.nightmare.ui.ModelsScreen
import com.abrah.nightmare.ui.NightmareTheme
import com.github.takahirom.roborazzi.captureRoboImage
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
                    "downloading ${spec.label}", (build.bytes * fraction).toLong(), build.bytes,
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

    /** A failed install has to say why — "size mismatch" is the common one. */
    @Test
    fun withAnError() = shoot("models-error") {
        ModelsScreen(
            rows = rows(installed = setOf(V1_MODEL), selected = V1_MODEL),
            busy = false,
            error = "size mismatch for QteaMix_qnn2.28_8gen2.zip: 913410048 != 1056615116",
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
}
