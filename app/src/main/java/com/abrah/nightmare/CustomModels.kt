package com.abrah.nightmare

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * ⭐ Checkpoints the USER brought — imported from a zip, or copied into the
 * models directory by hand.
 *
 * ⚠⚠ **A custom model is a full [ModelSpec], not a special case.** It is merged
 * into [ModelCatalog.all], so `byId`, `backendTypeOf`, `resolutionOf`,
 * `SelectedModel.set` and the executor's context key all resolve it exactly
 * like a built-in. That is not tidiness: `backendTypeOf` **falls back to
 * `sd15npu` for an id it does not know**, so a custom SDXL model the catalogue
 * could not see would launch the backend `--type sd15npu` and render at 512 in
 * silence. Being invisible to `byId` is the failure mode, not being absent from
 * a list.
 *
 * ## How a folder's family is decided — three ways, in this order
 *
 * **1. A marker the person put there, including UPSTREAM's** ([UPSTREAM_MARKS]).
 * `ZIMAGE`, `KLEIN`, `ANIMA`, `SDXL`, `finished`, `npucustom` — read in
 * upstream's own precedence, so a folder that works in LocalDream works here
 * unchanged.
 *
 * ⚠⚠⚠ **This file used to argue the opposite**, at length: that a marker is
 * a claim that can be wrong, that upstream's importer writes `npucustom` onto
 * every import including SDXL ones, and therefore that inference from files is
 * strictly better. Every word of that is still TRUE and the conclusion was
 * still wrong, because it optimised for a mislabelled zip nobody had and taxed
 * a person who assembles folders by hand every single time. Reported
 * 2026-09-22: *"for most users they have to unzip, create the proper dummy file
 * and zip it, and or keep two zip files just to import a flux/z-image model.
 * That's a huge hassle and storage being used up."*
 *
 * ⇒ A marker is somebody SAYING what this is. Inference is a guess made when
 * nobody has. A guess does not outrank a statement, and `CLAUDE.md`'s rule —
 * follow upstream, justify every deviation — was the tiebreak all along.
 *
 * **2. The files, when no marker says.** The CLIP side is unambiguous: SD 1.5
 * ships one `clip_v2.mnn`, SDXL ships `clip_2.mnn` (CLIP-G) beside `clip.mnn`.
 * No archive contains both.
 *
 * **3. The weights themselves**, for a DiT folder with neither ([ditFamilyOf]).
 *
 * ⚠ The property worth keeping from 2: a *partial* directory can never be
 * MISCLASSIFIED, only unclassifiable — the discriminators are disjoint, so a
 * half-extracted SDXL model is never mistaken for an SD 1.5 one. It is listed
 * as partial with [ModelSpec.missing] naming what is absent, exactly like a
 * half-downloaded built-in.
 *
 * ⇒ A model directory pushed over adb (`notes/HANDOFF.md` §5) is picked up
 * with no extra step whichever of the three applies.
 *
 * ⚠ What IS taken from upstream — and what DreamUI dropped — is
 * [Config]: an optional `config.json` inside the model directory carrying the
 * checkpoint's own label and prompts. DreamUI replaced it with one hardcoded
 * neutral prompt for every import.
 */
object CustomModels {

    private const val TAG = "CustomModels"
    private const val BUFFER = 1 shl 16

    /** ⚠ SD 1.5's CLIP, and only SD 1.5's. See the class note. */
    private const val SD15_MARK = "clip_v2.mnn"

    /** ⚠ SDXL's second encoder (CLIP-G). Absent from every SD 1.5 archive. */
    private const val SDXL_MARK = "clip_2.mnn"

    /**
     * ⚠ Anima's first DiT half. Absent from SD 1.5 and SDXL, which ship one
     * `unet.bin`. ⚠⚠ Not `tokenizer_t5.json` alone and not the upstream `ANIMA`
     * marker file: a marker is a claim (see the class note), a 2 GB graph is not.
     */
    private const val ANIMA_MARK = "unet_part1.bin"

    /**
     * ⭐⭐⭐ **The DiT markers, and the one kind this app WRITES.**
     *
     * ⚠⚠⚠ Every other marker above is a file that came with the model — a
     * marker is a claim, and a 2 GB graph is a better claim than a note
     * saying so. A DiT package was held not to work that way: FLUX.2 Klein
     * and Z-Image ship the SAME four filenames, so nothing in the directory
     * distinguishes them.
     *
     * ⭐⭐⭐ **Rule changed 2026-09-22, at the user's ask.** The filenames do
     * not distinguish them; the first 36–60 KB of `dit.safetensors` does, and
     * [ditFamilyOf] reads it. ⇒ **A marker is no longer required.** It is
     * written by [importDit] and by [detectDit] as a CACHE, so a scan stays
     * one `stat` per directory, and a folder pushed over adb with no marker is
     * adopted on the next scan instead of being ignored.
     *
     * ⚠ What forced it: a user reported *"you have to import from the app
     * now"* and *"please get rid of the verifying stuff"*. The marker was the
     * wall, not the size and header checks they blamed — and it is the one
     * requirement nobody could have guessed from the outside, because the file
     * is empty and its name appears in no error message.
     *
     * ⚠ Empty files. Only the name means anything.
     */
    const val ZIMAGE_MARK = "zimage.dit"
    const val FLUX2_MARK = "flux2.dit"

    /**
     * ⭐⭐⭐ **UPSTREAM's marker filenames, honoured as-is.**
     *
     * Reported 2026-09-22 by a user who assembles model folders by hand:
     * *"a couple updates back, having only SDXL or ANIMA or KLEIN or ZIMAGE or
     * npucustom or v3 or finished files was enough. i want that back — also why
     * use flux2.dit instead of KLEIN?"*
     *
     * ⚠⚠⚠ **A fair question with no good answer.** `flux2.dit` and
     * `zimage.dit` were invented here while `xororz/local-dream` has used
     * `KLEIN` and `ZIMAGE` all along — and `CLAUDE.md` says in plain words to
     * follow upstream and treat a deviation as needing its own justification.
     * This one had none. The cost fell on people: a folder that works in
     * LocalDream had to be re-zipped with a second dummy file to work here, or
     * kept twice.
     *
     * ⇒ These are read FIRST, in upstream's own precedence
     * (`data/Model.kt`, `scanCustomModels`), and [markOf] WRITES `KLEIN` /
     * `ZIMAGE` now. Our two old names are still read so folders this app
     * already created keep working.
     *
     * ⚠ `finished` and `npucustom` do not name a family — upstream uses them
     * for a CPU and an NPU SD 1.5 respectively. This app has no CPU path, so
     * both read as SD 1.5 and a folder that is not one fails at LAUNCH with the
     * backend's own words, which is what §3b already argues for a QNN import.
     */
    val UPSTREAM_MARKS: List<Pair<String, Family>> = listOf(
        "ZIMAGE" to Family.ZIMAGE,
        "KLEIN" to Family.FLUX2,
        "ANIMA" to Family.ANIMA,
        "SDXL" to Family.SDXL,
        "finished" to Family.SD15,
        "npucustom" to Family.SD15,
    )

    /**
     * ⭐⭐ Where a DiT family's SHARED parts live — the text encoder, VAE
     * and tokenizer, which are ours and identical for every model of the
     * family. Beside the model directories, never inside one.
     *
     * ⚠⚠ The backend resolves them from here too, and the two must agree:
     * `backend-patches/010`, `ditFile()` in `main.cpp`. An imported model
     * that the app calls installed and the backend cannot open is the worst
     * of both.
     * ⚠ Symlinks would avoid the copy and do not work: this is FUSE-emulated
     * external storage.
     */
    const val DIT_SHARED = "_dit_shared"

    /** ⚠ npuforge's graph-contract marker, beside the weights. */
    const val LONG_CONTEXT_FILE = "qnn_context.txt"
    const val LONG_CONTEXT_231 = "231_masked_v1"

    /**
     * The scan result, as a process-global.
     *
     * ⚠ Same reasoning as [SelectedModel]: [ModelCatalog] is a static object
     * read from a static node registry built without a `Context`, so a scan
     * that needed one passed down would have to change every caller. It is
     * refreshed by [scan] and read by [ModelCatalog.all].
     *
     * ⚠⚠ Empty until [scan] runs, which is why [SelectedModel.load] calls it
     * rather than documenting an ordering rule: `load` drops a stored id the
     * catalogue cannot see, so a scan that happened afterwards would silently
     * reset a user whose selected checkpoint is a custom one back to
     * [V1_MODEL] on every launch. Both entry points already call `load`.
     */
    @Volatile
    private var scanned: List<ModelSpec> = emptyList()

    /** What the last [scan] found. ⚠ Merged into [ModelCatalog.all]. */
    val registered: List<ModelSpec> get() = scanned

    /**
     * Where a zip waits to be imported headlessly.
     *
     * ⚠ Deliberately the same shape as the plugin inbox
     * (`files/plugins-inbox/`, `notes/HANDOFF.md` §5) so there is one pattern
     * for "the user put a file here for the app to install", and so the import
     * is drivable with no screen and no SAF picker.
     */
    fun inbox(context: Context): File = File(context.getExternalFilesDir(null), "models-inbox")

    // ---- scanning --------------------------------------------------------

    /**
     * Re-reads the models directory and replaces [registered].
     *
     * ⚠ Re-scanned rather than cached, because the normal way a custom model
     * arrives is a copy made while the app was running.
     *
     * ⚠ Blocking (it stats a directory per model). Cheap enough for the main
     * thread at startup; the harness runs it on IO anyway.
     */
    fun scan(context: Context): List<ModelSpec> {
        val builtIn = ModelCatalog.builtIn.map { it.id }.toSet()
        val found = ModelCatalog.root(context).listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { dir ->
                when {
                    // ⚠ A collision would SHADOW a built-in: `byId` returns the
                    // first match, so a directory named `absolutereality` would
                    // answer for the catalogue entry and point its downloads at
                    // someone else's files.
                    dir.name in builtIn -> {
                        Log.w(TAG, "skipping '${dir.name}': id collides with a built-in")
                        null
                    }
                    else -> specFor(dir)
                }
            }
            .sortedBy { it.label.lowercase() }
        scanned = found
        Log.i(TAG, "scan found ${found.size}: ${found.joinToString { "${it.id}(${it.family})" }}")
        return found
    }

    /**
     * A [ModelSpec] for one directory, or null when it is not a model at all.
     *
     * ⚠ Null for an unrecognisable directory rather than a guess. The models
     * root also holds `cache/` subdirectories the backend writes, and a
     * half-copied tree with no CLIP file yet is not something to describe.
     */
    private fun specFor(dir: File): ModelSpec? {
        // ⭐⭐⭐ **A DECLARED family wins, and upstream's names count.**
        //
        // ⚠⚠ First and alone: a marker is somebody saying what this folder is,
        // and the file-based inference below is a guess made when nobody has.
        // Guessing over a statement is how a folder someone assembled by hand
        // gets classified as something else.
        // ⚠ Upstream's precedence, not ours: ZIMAGE, KLEIN, ANIMA, SDXL, then
        // the two that only mean "SD 1.5" ([UPSTREAM_MARKS]).
        UPSTREAM_MARKS.firstOrNull { (name, _) -> File(dir, name).isFile }
            ?.let { (_, family) -> return customSpec(dir, Config.read(dir), family) }

        val marked = listOfNotNull(
            Family.SD15.takeIf { File(dir, SD15_MARK).isFile },
            Family.SDXL.takeIf { File(dir, SDXL_MARK).isFile },
            Family.ANIMA.takeIf { File(dir, ANIMA_MARK).isFile },
            // ⚠ Our own two, kept readable for folders this app already wrote
            // before it adopted upstream's names.
            Family.ZIMAGE.takeIf { File(dir, ZIMAGE_MARK).isFile },
            Family.FLUX2.takeIf { File(dir, FLUX2_MARK).isFile },
        )
        // ⭐⭐⭐ **A DiT folder pushed over adb has no marker, and is a model
        // anyway** — [detectDit] reads the family out of `dit.safetensors`.
        // Reported by a user 2026-09-22: *"you have to import from the app
        // now"*. They were right, and nothing in a hand-assembled directory
        // could have told them to `touch zimage.dit`.
        //
        // ⚠ Only when no marker matched, so this costs one header read per
        // adopted directory, once — [detectDit] writes the marker.
        val found = if (marked.isNotEmpty()) marked else listOfNotNull(detectDit(dir))
        // ⚠ Two present is not a family, it is a mixed directory — someone
        // unpacked two archives into one place. Refusing beats picking one.
        if (found.size != 1) {
            if (found.size > 1) Log.w(TAG, "skipping '${dir.name}': markers for ${found.joinToString()}")
            return null
        }
        val spec = customSpec(dir, Config.read(dir), found.single())
        // ⭐ npuforge SDXL: 3x77 tokens (`backend-patches/005`). Read, never assumed.
        val long = spec.family == Family.SDXL && runCatching {
            File(dir, LONG_CONTEXT_FILE).readText().contains(LONG_CONTEXT_231)
        }.getOrDefault(false)
        return if (long) spec.copy(promptTokens = 231) else spec
    }

    /**
     * ⚠ Every family-dependent field comes from [ModelCatalog]'s own constants,
     * never from a literal here. A custom SDXL model that carried its own copy
     * of `SDXL_REQUIRED` would drift from the built-in entries the first time
     * the backend's file list changed, and the symptom is a 3.7 GB import that
     * reports "not installed" forever.
     */
    /** What [familyDefaults] answers: the three knobs a package may omit. */
    private data class Defaults(val scheduler: String, val steps: Int, val cfg: Double)

    /**
     * ⭐⭐⭐ **The generation defaults for a package that declares none.**
     *
     * ⚠⚠⚠ **A `when`, not a chain of `if (family == X)`, and that shape is
     * the whole point.** This was two ternaries reading
     * `if (family == ANIMA) 10 else DEFAULT_STEPS`, and when the DiT families
     * arrived on 2026-09-21 nobody extended them — so an imported Z-Image
     * inherited SD's `dpm`/20/7.5 instead of `euler`/8/1.0. It ran, which is
     * why nothing caught it: **cfg above 1 makes the engine compute the
     * unconditional pass as well**, so 20 steps at cfg 7.5 is ~5x the work of
     * 8 steps at cfg 1, and the output is burnt on a turbo checkpoint.
     * ⇒ An exhaustive `when` makes the next family a compile error.
     *
     * ⚠ `DEFAULT_*` is the BACKEND's default and is correct only for the two
     * QNN families it was written for. Every other family's numbers here are
     * copied from that family's built-in entries, not invented:
     * `ModelCatalog.dit()` and the nine Anima `config.json`s.
     */
    private fun familyDefaults(family: Family): Defaults = when (family) {
        // The backend's own defaults — neutral, and what every SD archive has
        // always been imported with.
        Family.SD15, Family.SDXL -> Defaults(
            ModelCatalog.DEFAULT_SCHEDULER, ModelCatalog.DEFAULT_STEPS, ModelCatalog.DEFAULT_CFG,
        )
        // ⚠ Every published Anima checkpoint is turbo (`euler`, 10, cfg 1 in
        // all nine `config.json`s), and `dpm` is not even a sampler there.
        Family.ANIMA -> Defaults("euler", 10, 1.0)
        // ⚠⚠ The DiT engine hardcodes euler and expects cfg 1; a fine-tune of
        // a turbo base is still a turbo model. Same numbers as the built-ins.
        Family.ZIMAGE -> Defaults("euler", 8, 1.0)
        Family.FLUX2 -> Defaults("euler", 4, 1.0)
    }

    private fun customSpec(dir: File, cfg: Config, family: Family): ModelSpec = ModelSpec(
        id = dir.name,
        label = cfg.label ?: dir.name,
        // ⚠⚠ EMPTY, and that is the definition of a custom model: there is no
        // URL that could produce these files. It is also why [ModelSpec.best]
        // is nullable — see the note there.
        builds = emptyList(),
        prompt = cfg.prompt.orEmpty(),
        negative = cfg.negative.orEmpty(),
        // ⭐ The FAMILY's defaults when the package says nothing
        // ([familyDefaults]) — `dpm`/20/7.5 is only right for SD 1.5 and SDXL.
        scheduler = cfg.scheduler?.takeIf { it in ModelCatalog.schedulersFor(family) }
            ?: familyDefaults(family).scheduler,
        steps = cfg.steps ?: familyDefaults(family).steps,
        cfg = cfg.cfg ?: familyDefaults(family).cfg,
        family = family,
        backendType = when (family) {
            Family.SD15 -> ModelCatalog.SD15_NPU
            Family.SDXL -> ModelCatalog.SDXL_NPU
            Family.ANIMA -> ModelCatalog.ANIMA_NPU
            // ⭐ Detected since 2026-09-21 — [importDit] writes [FLUX2_MARK]
            // and [ZIMAGE_MARK], so these two are reached by a real import.
            Family.FLUX2 -> ModelCatalog.KLEIN
            Family.ZIMAGE -> ModelCatalog.ZIMAGE
        },
        resolutions = listOf(
            when (family) {
                Family.SD15 -> ModelCatalog.SD15_NPU_RES
                Family.SDXL -> ModelCatalog.SDXL_NPU_RES
                Family.ANIMA -> ModelCatalog.ANIMA_NPU_RES
                Family.FLUX2, Family.ZIMAGE -> ModelCatalog.DIT_RES
            },
        ),
        requiredFiles = when (family) {
            Family.SD15 -> ModelCatalog.SD15_REQUIRED
            Family.SDXL -> ModelCatalog.SDXL_REQUIRED
            Family.ANIMA -> ModelCatalog.ANIMA_REQUIRED
            Family.FLUX2, Family.ZIMAGE -> ModelCatalog.DIT_REQUIRED
        },
        // ⚠ Not a preference: SDXL's UNet and Anima's two DiT halves do not fit
        // beside their encoders at 1024², and the backend needs telling
        // regardless of where the files came from.
        //
        // ⚠⚠⚠ **Per family, matching that family's built-in entries — NOT
        // `!= SD15`.** Written as the negation, this said `true` for FLUX.2,
        // which sets the engine's `te=disk` and re-made by accident a decision
        // that was taken deliberately and REVERSED on 2026-09-20: Klein sits at
        // 57% of RAM, stays resident and does not want it (`docs/LEGACY.md`,
        // `ModelCatalog.dit`). An imported checkpoint is the same weights as
        // the built-in, so it gets the same answer.
        lowram = when (family) {
            Family.SD15, Family.FLUX2 -> false
            Family.SDXL, Family.ANIMA, Family.ZIMAGE -> true
        },
        // ⚠⚠ **No arch claim.** Nothing in a QNN context directory says which
        // HTP it was compiled for — `QnnSystemContext` gives the IO contract
        // and nothing else — so any number here would be invented. A built-in
        // entry knows because we recorded which archive it came from; this one
        // cannot. ⇒ Let it try and let the failure say what happened, which is
        // upstream's position too (`../LocalDream/docs/DEVICE-SUPPORT.md`).
        // ⭐⭐ …but a DiT family's floor IS known, and the paragraph above
        // does not apply to it. The reason a QNN import claims nothing is that
        // the context binary alone says which HTP it was built for; a DiT
        // package has no context binary at all — the arch floor belongs to
        // `libdit_engine.so`, is the same for every checkpoint of the family,
        // and is the number the built-in entries and the Flows gate both use.
        minHtpArch = if (family.dit) ModelCatalog.DIT_MIN_ARCH else 0,
        isCustom = true,
    )

    // ---- importing -------------------------------------------------------

    /**
     * The last path segment of a zip entry name, splitting on **both**
     * separators.
     *
     * ⚠⚠ **`\` as well as `/`, and this is not defensive programming.** The ZIP
     * spec mandates forward slashes, but Windows PowerShell's `Compress-Archive`
     * writes backslashes — and it is the obvious way a contributor on Windows
     * packages a model they just converted, since the conversion pipeline is a
     * PC step (`../LocalDream/docs/CONVERSION.md`).
     *
     * ⚠⚠ The failure it caused was silent and misleading. Backslash is an
     * ordinary filename character on Android, so every entry extracted
     * *successfully* under a literal name like
     * `output_512\qnn_models_8gen2\clip_v2.mnn`; nothing threw, the directory
     * filled up, and the import was then rejected as "not a checkpoint" — which
     * blames the archive for holding the right files under the wrong names.
     * Measured on device 2026-09-10, and invisible to the JVM tests because
     * `ZipOutputStream` writes conformant names.
     *
     * ⚠ Note `java.util.zip` does NOT normalise this and neither do the usual
     * inspection tools: Python's `namelist()` shows forward slashes for the same
     * archive, which is what made the first reading of the bug wrong.
     *
     * ⭐ Taking the last segment is also what makes Zip Slip impossible here —
     * a `../../evil` entry flattens to `evil` — so the flattening the backend
     * needs and the traversal guard are the same line.
     */
    private fun basename(entry: String): String =
        entry.substringAfterLast('/').substringAfterLast('\\')

    /** ⚠ A directory name: no separators, no leading dot, not empty. */
    fun isValidName(name: String): Boolean =
        name.isNotBlank() &&
            name.none { it == '/' || it == '\\' || it == ':' } &&
            !name.startsWith(".")

    /**
     * ⭐ The name an import takes when the user left the box EMPTY — the zip's own
     * file name. Asked for 2026-09-16.
     *
     * ⚠ Made safe by the same rule [isValidName] checks (no separators, no
     * leading dot), and numbered past anything [taken] — a built-in id or a
     * model already on disk — because [import] DELETES an existing directory of
     * that name before unpacking. A typed name is the user's explicit choice; a
     * derived one must never silently replace a model.
     *
     * ⚠ A provider can report no name, or `document.zip`; both still give a
     * usable id rather than a refusal.
     */
    fun nameFromFile(displayName: String?, taken: Set<String>): String {
        // ⚠⚠ EVERY extension an import can arrive with, not just `.zip`.
        // The DiT import takes a bare `.safetensors`, and leaving the suffix on
        // produced the model directory — and the label on the card —
        // `intorealism_zitV90_(1).safetensors`.
        val base = displayName.orEmpty()
            .substringAfterLast('/')
            .let { n ->
                val ext = listOf(".zip", ".safetensors", ".gguf", ".ckpt")
                    .firstOrNull { n.endsWith(it, ignoreCase = true) }
                if (ext != null) n.dropLast(ext.length) else n
            }
            .map { if (it == '/' || it == '\\' || it == ':' || it.isWhitespace()) '_' else it }
            .joinToString("")
            .trimStart('.')
            .trim('_')
            .ifBlank { "imported" }
        if (base !in taken) return base
        var n = 2
        while ("${base}_$n" in taken) n++
        return "${base}_$n"
    }

    /** Whether [name] would shadow a catalogue entry. */
    fun isReserved(name: String): Boolean = ModelCatalog.builtIn.any { it.id == name }

    /**
     * Unpacks a zip of model files into `models/[name]/` and rescans.
     *
     * ⚠ **Flattened.** The archives people find on Hugging Face wrap everything
     * in a build directory (`output_512/qnn_models_8gen2/`), and the backend
     * needs the files directly in the model directory — each `.bin` refers to
     * its siblings by bare filename.
     *
     * ⚠ Written `<name>.part` then renamed, the same as [ModelInstaller]. That
     * is what makes the marker-less design safe: a file only ever appears under
     * its real name once it is complete, so an interrupted import cannot leave
     * a truncated `clip_2.mnn` that [scan] then reads as a family declaration.
     *
     * ⚠ Blocking, and it is gigabytes. Call it off the main thread.
     *
     * @param open opens the archive. ⚠ A lambda rather than a `File` or a
     *   `Uri`: the SAF picker hands back a `Uri` needing a `ContentResolver`
     *   and the headless inbox path has a plain file, and neither should be the
     *   other's problem.
     * @return the spec that was created.
     */
    /**
     * ⭐⭐⭐ **Import a DiT checkpoint — one plain `.safetensors`, no
     * conversion.**
     *
     * A DiT family loads its weights at run time, so a community fine-tune of
     * the same architecture just works — proven on device 2026-09-21 with a
     * CivitAI Z-Image model (`docs/ROADMAP.md` §2b). This is that, with a
     * button on it.
     *
     * ⚠⚠ [family] is given, never inferred. Klein and Z-Image ship
     * identical filenames and a bare `.safetensors` says nothing about which
     * it is; the tab the user pressed Import on is the only thing that knows.
     *
     * ⚠ The shared parts are copied ONCE into [DIT_SHARED] from the family's
     * installed built-in, so the second import of a family costs only its own
     * weights. That is also why the built-in has to be installed first: there
     * is nowhere else for them to come from.
     */
    fun importDit(
        context: Context,
        name: String,
        family: Family,
        open: () -> InputStream,
        onProgress: (ModelInstaller.Progress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): ModelSpec {
        require(isValidName(name)) { "\"$name\" is not a usable directory name" }
        require(!isReserved(name)) { "\"$name\" is the id of a built-in model; pick another name" }
        val mark = markOf(family)

        // ⚠⚠⚠ **Checked BEFORE the gigabytes.** Copying 6 GB and failing at
        // Run leaves a dead 8.8 GB directory and minutes of a person's time
        // spent to learn something the first kilobyte could have said.
        onProgress(ModelInstaller.Progress("checking the file", 0, 0))
        checkSafetensors(open, expect = family)

        val dir = File(ModelCatalog.root(context), name)
        // ⚠ A retry after a failure starts clean, exactly as [import] does.
        dir.deleteRecursively()
        dir.mkdirs()
        try {
            // ⭐⭐⭐ **The engine, then the parts, then the weights** — in
            // ascending order of cost, so the cheapest thing that can fail
            // fails first.
            //
            // ⚠⚠ `libdit_engine.so` is downloaded HERE as well as in
            // [ModelInstaller.installOnce], and that is the whole point of this
            // change: an import no longer requires a built-in DiT package, so
            // it can no longer assume the engine rode in with one. A checkpoint
            // that imports perfectly and has no code to run it is the same
            // class of failure the installer already guards against.
            if (!DitEngine.isInstalled(context)) {
                DitEngine.install(context, onProgress, isCancelled)
            }
            // ⚠⚠ Before the weights: these are 2.6 GB at worst and the
            // checkpoint is 4-9 GB, so a failure to produce them should not cost
            // the bigger copy. ⚠ `dir` exists already because the VAE lands in
            // it ([ensureDitParts]).
            ensureDitParts(context, family, dir, onProgress, isCancelled)
            val dest = File(dir, ModelSpec.DIT_WEIGHTS)
            open().use { input ->
                dest.outputStream().buffered(BUFFER).use { outS ->
                    val buf = ByteArray(BUFFER)
                    var done = 0L
                    while (true) {
                        if (isCancelled()) throw ModelInstaller.Cancelled()
                        val n = input.read(buf)
                        if (n <= 0) break
                        outS.write(buf, 0, n)
                        done += n
                        onProgress(ModelInstaller.Progress("importing", done, 0))
                    }
                }
            }
            if (dest.length() <= 0L) throw java.io.IOException("the picked file was empty")
            File(dir, mark).writeBytes(ByteArray(0))
            // ⭐ The weights are the user's, so their SIZE is not ours to check
            // ([ModelSpec.BRING_YOUR_OWN]).
            File(dir, ModelSpec.BRING_YOUR_OWN).writeBytes(ByteArray(0))
        } catch (t: Throwable) {
            dir.deleteRecursively()
            throw t
        }
        specFor(dir) ?: throw java.io.IOException(
            "imported, but the directory is not a model this app recognises"
        )
        // ⚠⚠⚠ **The catalogue has to be re-scanned HERE**, exactly as
        // [import] does it. A [ModelSpec] is not a model until `scan` has put
        // it in [registered]: `ModelCatalog.byId` reads that list and
        // `SelectedModel.set` requires a hit — so returning a freshly built
        // spec without scanning made the very next `selectModel` throw
        // "unknown model", and the import reported itself as FAILED after
        // copying 6 GB perfectly well.
        scan(context)
        // ⚠ Re-read from the scan so the returned spec is the one the
        // catalogue now holds, rather than an equal-looking copy.
        val installed = registered.firstOrNull { it.id == name }
            ?: throw java.io.IOException("imported, but the catalogue did not pick it up")
        Log.i(TAG, "imported '$name' as ${installed.family} (${installed.bytesOnDisk(context)} bytes)")
        return installed
    }

    /**
     * ⭐⭐⭐ **The two files that are the same for BOTH DiT families** —
     * measured on device 2026-09-21, not assumed.
     *
     * `llm.gguf` is `47ae93b4c5355273d6333ea12f52bd36` and `tokenizer.json` is
     * `6423133b9cc1a2077b57822c30c211aa` in the Klein 4B package AND in the
     * Z-Image Turbo one — byte-identical, 2.27 GB of it. Both families embed
     * Qwen3-4B, so this is a property of the models rather than a coincidence
     * of packaging.
     *
     * ⚠⚠⚠ **`vae.safetensors` is NOT**: Klein's is
     * `1f01ec904ab02e44c0b4d04c83dc856d` (336,213,556 bytes) and Z-Image's is
     * `5c8c2087d13d0951b36be6dfbd3cd157` (335,304,388). They are different
     * files with different sizes, and [DIT_SHARED] is ONE directory for both
     * families — which is the bug this constant exists to end. Until
     * 2026-09-21 an import of either family copied its own VAE over the shared
     * one, so importing a FLUX checkpoint silently gave every previously
     * imported Z-Image the wrong VAE, and re-importing a Z-Image flipped it
     * back. The two families' imports could not coexist.
     *
     * ⇒ The family-agnostic two stay shared; the VAE goes in the importing
     * model's OWN directory, which `ditFile()` already prefers
     * (`backend-patches/010`) — so this needed no backend change.
     */
    private val DIT_FAMILY_AGNOSTIC = setOf("llm.gguf", "tokenizer.json")

    /**
     * ⭐⭐⭐ **Puts the text encoder, VAE and tokenizer where an imported
     * checkpoint's backend will find them — copying them if any model on the
     * device has them, and DOWNLOADING them if none does.**
     *
     * ⚠⚠ **It used to refuse instead**, with *"install <family> first — an
     * imported checkpoint uses its text encoder, VAE and tokenizer, and there
     * is nowhere else to get them"*. That sentence was wrong about the last
     * clause: the parts have public URLs, they are already declared as
     * [RemoteFile]s on the built-in spec, and [ModelInstaller.fetch] is the one
     * downloader in the app. Reported 2026-09-21 by a user who pressed Import
     * on the FLUX.2 tab and was told to download a 6.7 GB package to get 2.6 GB
     * of it. ⇒ An import now costs **2.6 GB at most**, and nothing at all
     * once either DiT family is installed.
     *
     * ⚠ Copy before download, always, and the search widens on purpose:
     * the family's built-in, then any OTHER model directory holding the file at
     * exactly its published size — which is what makes a SECOND import of the
     * same family free, and what lets an existing [DIT_SHARED] VAE serve the
     * family it actually belongs to.
     *
     * ⚠⚠ Size-checked against [RemoteFile.bytes] at every step, never
     * existence. A half-fetched 2.2 GB text encoder is a real file of the right
     * name, and [ModelSpec.missing] would call the import complete.
     */
    /**
     * ⭐⭐ One shared part and how it is going to be obtained — see [ditPartsPlan].
     *
     * ⚠ [from] null means DOWNLOAD. The distinction is the whole reason this
     * is a value rather than a side effect: which files are copied and which
     * are fetched is the behaviour worth testing, and it is not observable
     * once the copying has happened.
     */
    data class PartStep(val file: RemoteFile, val dest: File, val from: File?)

    /**
     * ⭐⭐⭐ **What an import of [family] still needs, and where each piece
     * will come from** — the decision, separated from the doing.
     *
     * ⚠ Copy before download, always, and the search widens on purpose: the
     * family's built-in, then any OTHER model directory holding the file at
     * exactly its published size — which is what makes a SECOND import of the
     * same family free.
     *
     * ⚠⚠ Size-checked against [RemoteFile.bytes] at every step, never
     * existence. A half-fetched 2.2 GB text encoder is a real file of the right
     * name, and [ModelSpec.missing] would call the import complete.
     */
    internal fun ditPartsPlan(context: Context, family: Family, modelDir: File): List<PartStep> {
        val donor = ModelCatalog.builtIn.firstOrNull { it.family == family && it.isDit }
            ?: throw IOException("${family.label} has no built-in package to take its parts from")
        val shared = File(ModelCatalog.root(context), DIT_SHARED)
        // ⚠ Where each file has to END UP. The two family-agnostic ones are
        // read from [DIT_SHARED] by `ditFile()`; the VAE is read from the
        // model's own directory, which that same function prefers.
        fun destOf(name: String) =
            if (name in DIT_FAMILY_AGNOSTIC) File(shared, name) else File(modelDir, name)

        // ⚠⚠ Every model directory, the family's own built-in FIRST so the
        // right VAE wins when two could serve.
        val own = donor.dir(context)
        val roots = listOf(own) +
            ModelCatalog.root(context).listFiles().orEmpty().filter { it.isDirectory && it != own }

        return donor.files
            .filter { it.name != ModelSpec.DIT_WEIGHTS }
            .mapNotNull { f ->
                val dest = destOf(f.name)
                if (dest.length() == f.bytes) return@mapNotNull null
                // ⚠⚠⚠ A family-specific file may only be copied from something
                // that PROVES it belongs to this family: the family's own
                // built-in, or a directory carrying this family's import
                // marker. The size check would already refuse a cross-family
                // VAE, because the two differ by 909 KB — but that is an
                // accident of these two packages rather than a rule, and
                // leaning on it is how the shared-VAE bug happened.
                //
                // ⚠⚠ [DIT_SHARED] is the ONE exception, and only at an exact
                // size match. A VAE left there by an import made before
                // 2026-09-21 carries no marker and cannot say which family
                // wrote it — but its length can, and being wrong costs a 336 MB
                // download rather than a bad render. ⇒ Read, never written: the
                // file stays, because imports made under the old code still
                // resolve their VAE through it.
                val allowed = if (f.name in DIT_FAMILY_AGNOSTIC) roots else roots.filter { d ->
                    d == own || File(d, markOf(family)).isFile || d == shared
                }
                val src = allowed.map { File(it, f.name) }
                    .firstOrNull { it != dest && it.length() == f.bytes }
                PartStep(f, dest, src)
            }
    }

    /**
     * ⭐⭐⭐ **Puts the text encoder, VAE and tokenizer where an imported
     * checkpoint's backend will find them — copying them from any model on the
     * device that has them, and DOWNLOADING them if none does.**
     *
     * ⚠⚠ **It used to refuse instead**, with *"install <family> first — an
     * imported checkpoint uses its text encoder, VAE and tokenizer, and there
     * is nowhere else to get them"*. That sentence was wrong about its last
     * clause: the parts have public URLs, they are already declared as
     * [RemoteFile]s on the built-in spec, and [ModelInstaller.fetch] is the one
     * downloader in the app. Reported 2026-09-21 by a user who pressed Import
     * on the FLUX.2 tab and was told to download a 6.7 GB package to obtain 2.6
     * GB of it. ⇒ An import now costs **2.6 GB at most**, and nothing at all
     * once either DiT family is installed.
     */
    private fun ensureDitParts(
        context: Context,
        family: Family,
        modelDir: File,
        onProgress: (ModelInstaller.Progress) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val plan = ditPartsPlan(context, family, modelDir)
        if (plan.isEmpty()) return
        // ⚠⚠ The DOWNLOAD only, not the copies. A copy needs the space too,
        // but `copyTo` reports ENOSPC perfectly well and the copy path behaved
        // this way before 2026-09-21 — this check exists for the gigabytes this
        // change newly makes possible, and widening it would be a second
        // behaviour change hiding inside the first.
        ModelInstaller.requireFreeSpace(modelDir, plan.filter { it.from == null }.sumOf { it.file.bytes })

        val total = plan.sumOf { it.file.bytes }
        var before = 0L
        // ⚠ Copies first: they are the cheap ones, so a cancelled import still
        // leaves progress made rather than a half-fetched 2.2 GB file.
        for (step in plan.sortedBy { it.from == null }) {
            if (isCancelled()) throw ModelInstaller.Cancelled()
            val f = step.file
            step.dest.parentFile?.mkdirs()
            if (step.from != null) {
                onProgress(ModelInstaller.Progress("preparing the shared parts", before, total))
                step.from.copyTo(step.dest, overwrite = true)
            } else {
                ModelInstaller.fetch(f.url, step.dest, f.bytes, "downloading the shared parts", { p ->
                    onProgress(ModelInstaller.Progress(p.phase, before + p.done, total))
                }, isCancelled)
            }
            if (step.dest.length() != f.bytes) {
                throw IOException("${f.name}: got ${step.dest.length()} bytes, expected ${f.bytes}")
            }
            before += f.bytes
        }
        Log.i(TAG, "dit parts ready for ${family.label}: " +
            "${plan.count { it.from != null }} copied, ${plan.count { it.from == null }} downloaded")
    }

    /** ⚠ The marker an import of [family] writes — see [importDit]. */
    private fun markOf(family: Family): String = when (family) {
        // ⚠⚠ UPSTREAM's names since 2026-09-22 — see [UPSTREAM_MARKS]. A folder
        // this app writes is now one LocalDream can read, and the reverse was
        // always true; it is the same model either way.
        Family.ZIMAGE -> "ZIMAGE"
        Family.FLUX2 -> "KLEIN"
        else -> throw IllegalArgumentException("$family does not import a plain .safetensors")
    }

    /**
     * ⭐⭐ A structural check of a safetensors file, from its first bytes.
     *
     * The format is an 8-byte little-endian header length, that many bytes of
     * JSON naming every tensor, then the data. ⇒ Reading a few hundred KB
     * says whether this is a safetensors at all, and whether it is obviously
     * the WRONG kind.
     *
     * ⚠⚠ It does not verify the architecture, and is not pretending to.
     * The engine does that properly — it reads the layer count, hidden size and
     * channels out of the file and refuses a mismatch (`docs/ROADMAP.md` §2b).
     * This only catches the cheap mistakes early: a zip, a truncated download,
     * or an SD/SDXL checkpoint picked by hand.
     */
    private fun safetensorsHeader(open: () -> InputStream): String {
        val head = ByteArray(8)
        val json: String
        open().use { input ->
            if (input.readNBytes(head, 0, 8) != 8) {
                throw java.io.IOException("this file is too small to be a checkpoint")
            }
            var len = 0L
            for (i in 7 downTo 0) len = (len shl 8) or (head[i].toLong() and 0xFF)
            // ⚠ A safetensors header is tens of KB to a few MB. A wild number
            // here means the file is not one — a zip reads as ~1.2 quintillion.
            if (len !in 2..(64L * 1024 * 1024)) {
                throw java.io.IOException(
                    "this is not a .safetensors file (its header claims $len bytes)"
                )
            }
            val body = ByteArray(minOf(len, 512L * 1024).toInt())
            input.readNBytes(body, 0, body.size)
            json = String(body, Charsets.UTF_8)
        }
        if (!json.trimStart().startsWith("{")) {
            throw java.io.IOException("this is not a .safetensors file (no tensor table)")
        }
        return json
    }

    /**
     * ⭐⭐⭐ **Which DiT family a checkpoint belongs to, read off its own
     * tensor names** — or null when nothing in it says.
     *
     * ⚠⚠⚠ **This is the claim that [ZIMAGE_MARK] was invented to work
     * around, and it was wrong.** That comment says *"FLUX.2 Klein and Z-Image
     * ship the SAME four filenames, so nothing in the directory distinguishes
     * them"*. The FILENAMES do not, and the first 36–60 KB of
     * `dit.safetensors` does, unmistakably. Measured 2026-09-22 against both
     * packages on the phone:
     *
     * ```
     * Klein 4B   309 tensors  double_blocks.N.img_attn.qkv.weight, single_blocks.N.linear1.weight, img_in.weight, txt_in.weight
     * Z-Image    453 tensors  model.diffusion_model.layers.N.attention.qkv.weight, cap_embedder.N.weight, noise_refiner.N…, context_refiner.N…
     * ```
     *
     * ⚠ Matched on the BLOCK names, not on the `model.diffusion_model.`
     * prefix: our Z-Image repack carries that prefix and Klein's does not, but
     * a community repack may carry either, and the prefix is exactly the part
     * a repacker changes. `cap_embedder`/`noise_refiner` is Lumina2's shape and
     * `double_blocks`/`single_blocks` is FLUX's, and neither survives being
     * repacked as the other family.
     *
     * ⚠⚠ **Two hits required, and exactly one family.** One substring could
     * appear in a merge or a wrapper; both families matching means the file is
     * not what either of them looks like, and guessing beats nothing only when
     * being wrong is cheap. Here it costs a 6 GB import.
     */
    fun ditFamilyOf(header: String): Family? {
        val signatures = mapOf(
            Family.ZIMAGE to listOf("cap_embedder", "noise_refiner.", "context_refiner.", "attention.qkv."),
            Family.FLUX2 to listOf("double_blocks.", "single_blocks.", "img_in.", "txt_in."),
        )
        val hits = signatures.filterValues { keys -> keys.count(header::contains) >= 2 }.keys
        return hits.singleOrNull()
    }

    /**
     * ⭐⭐ The family of a marker-less DiT directory, and the marker written so
     * the next scan costs a `stat` again.
     *
     * ⚠ Best effort on the write: a directory the app cannot write to is
     * re-detected on every scan — slower, still correct. The marker is a cache,
     * not the mechanism, which is the whole change.
     *
     * ⚠⚠ A checkpoint still being COPIED reads as a family the moment its
     * header lands, so a folder mid-`adb push` can appear in the list and fail
     * at Run. That is the same "listed and incomplete" state a half-downloaded
     * built-in is in (`ModelSpec.missing`), it corrects itself on the next
     * scan, and the alternative is a size threshold no file declares. ⇒ Left
     * deliberately, not overlooked.
     */
    private fun detectDit(dir: File): Family? {
        val weights = File(dir, ModelSpec.DIT_WEIGHTS)
        if (!weights.isFile || weights.length() <= 0L) return null
        val family = runCatching { ditFamilyOf(safetensorsHeader { weights.inputStream() }) }
            .getOrNull() ?: return null
        runCatching { File(dir, markOf(family)).createNewFile() }
        Log.i(TAG, "adopted '${dir.name}' as $family from its tensor names")
        return family
    }

    private fun checkSafetensors(open: () -> InputStream, expect: Family? = null) {
        val json = safetensorsHeader(open)
        // ⚠⚠ Named signatures of the families that do NOT import this way.
        // Picking an SD or SDXL checkpoint here is the likely mistake, and
        // those need the conversion pipeline, not a copy.
        val wrong = mapOf(
            "model.diffusion_model.input_blocks" to "an SD 1.5 or SDXL checkpoint",
            "conditioner.embedders" to "an SDXL checkpoint",
            "cond_stage_model.transformer" to "an SD 1.5 checkpoint",
        )
        for ((key, what) in wrong) {
            if (json.contains(key)) {
                throw java.io.IOException(
                    "that looks like $what. Those need converting for the NPU and " +
                        "import as a .zip on their own tab, not here"
                )
            }
        }
        // ⭐ …and the mistake the list above cannot catch: the RIGHT kind of
        // file on the WRONG family's tab. Only a confident disagreement is an
        // error — [ditFamilyOf] returning null means the file says nothing
        // either way, and the engine is the one that decides that properly.
        val actual = ditFamilyOf(json)
        if (expect != null && actual != null && actual != expect) {
            throw java.io.IOException(
                "that is a ${actual.label} checkpoint, not a ${expect.label} one — " +
                    "import it on the ${actual.label} tab"
            )
        }
    }

    fun import(
        context: Context,
        name: String,
        open: () -> InputStream,
        onProgress: (ModelInstaller.Progress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): ModelSpec {
        require(isValidName(name)) { "\"$name\" is not a usable directory name" }
        require(!isReserved(name)) { "\"$name\" is the id of a built-in model; pick another name" }

        val dir = File(ModelCatalog.root(context), name)
        // ⚠ A retry after a failure must start clean, or a stale file from the
        // previous attempt counts toward "complete" and the model reads as
        // installed while missing whatever failed.
        dir.deleteRecursively()
        dir.mkdirs()

        try {
            open().use { raw ->
                ZipInputStream(raw.buffered(BUFFER)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val entryName = basename(entry.name)
                        // ⚠ `__MACOSX` and dotfiles: a zip made on a Mac carries
                        // a parallel tree of `._` resource forks with the same
                        // basenames, and flattening puts them on top of the real
                        // files. Upstream skips them; DreamUI does not.
                        if (entry.isDirectory ||
                            entryName.isEmpty() ||
                            entryName.startsWith(".") ||
                            entry.name.startsWith("__MACOSX")
                        ) {
                            zip.closeEntry()
                            continue
                        }
                        onProgress(ModelInstaller.Progress("extracting $entryName", 0, 0))
                        val target = File(dir, entryName)
                        val tmp = File(dir, "$entryName.part")
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(BUFFER)
                            while (true) {
                                if (isCancelled()) throw ModelInstaller.Cancelled()
                                val n = zip.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                            }
                        }
                        if (!tmp.renameTo(target)) {
                            tmp.copyTo(target, overwrite = true)
                            tmp.delete()
                        }
                        zip.closeEntry()
                    }
                }
            }
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }

        val spec = specFor(dir)
        if (spec == null) {
            dir.deleteRecursively()
            // ⚠⚠ Names the discriminator rather than saying "invalid". The
            // likely causes are a zip of something else entirely and an archive
            // whose CLIP file is nested deeper than one directory, and the user
            // can tell those apart only if told what was looked for.
            throw IOException(
                "not a checkpoint: no $SD15_MARK (SD 1.5), $SDXL_MARK (SDXL) or " +
                    "$ANIMA_MARK (Anima) in the archive",
            )
        }
        scan(context)
        // ⚠ Re-read from the scan so the returned spec is the one the catalogue
        // now holds, rather than an equal-looking copy.
        val installed = registered.firstOrNull { it.id == name } ?: spec
        val missing = installed.missing(context)
        if (missing.isNotEmpty()) {
            // ⚠ Kept, not deleted. The files are the user's and a near-miss
            // archive is worth having on disk so the card can name what is
            // absent — deleting it would mean re-importing to find out.
            Log.w(TAG, "imported '$name' is incomplete, missing ${missing.joinToString()}")
        }
        Log.i(TAG, "imported '$name' as ${installed.family} (${installed.bytesOnDisk(context)} bytes)")
        return installed
    }

    /**
     * Imports every checkpoint sitting in [inbox], deleting each on success.
     *
     * ⚠ The model name is the file's basename, so `my-model.zip` becomes
     * `models/my-model/`. That is the whole naming rule and it is the one a
     * shell user can predict.
     *
     * ⭐⭐⭐ **`.safetensors` too, since 2026-09-21, and the name carries the
     * FAMILY**: `my-flux.flux2.safetensors` and `mine.zimage.safetensors`.
     *
     * ⚠⚠ Because a DiT checkpoint cannot say which family it is — the same
     * reason the Models tab takes it from the TAB rather than the file
     * ([importDit]). A shell has no tab, so the name is where it goes.
     *
     * ⚠⚠⚠ **The gap this closes is why the FLUX import path reached
     * 2026-09-21 unrun on a device.** `model_import` handled zips only, so the
     * whole DiT import path had no headless route at all and could only be
     * exercised through a SAF picker by hand — which is to say, it was not
     * exercised. A path with no way to drive it is a path that is not tested.
     *
     * @return one line per file, for a log.
     */
    fun importInbox(
        context: Context,
        onProgress: (ModelInstaller.Progress) -> Unit = {},
    ): List<String> {
        val files = inbox(context).listFiles().orEmpty().filter {
            it.isFile && (it.extension.equals("zip", ignoreCase = true) ||
                it.extension.equals("safetensors", ignoreCase = true))
        }
        if (files.isEmpty()) return emptyList()
        return files.map { file ->
            val stem = file.nameWithoutExtension
            // ⚠ The family suffix is stripped from the NAME as well — a model
            // called `my-flux.flux2` would be a directory with a dot in it and a
            // name the user did not choose.
            val dit = if (file.extension.equals("safetensors", ignoreCase = true)) {
                val suffix = stem.substringAfterLast('.', "")
                DIT_INBOX_FAMILIES[suffix.lowercase()]
            } else null
            val name = if (dit != null) stem.substringBeforeLast('.') else stem
            try {
                val spec = if (dit != null) {
                    importDit(context, name, dit, { file.inputStream() }, onProgress)
                } else if (file.extension.equals("safetensors", ignoreCase = true)) {
                    throw IOException(
                        "a .safetensors needs its family in the name — " +
                            "$name.<${DIT_INBOX_FAMILIES.keys.joinToString("|")}>.safetensors"
                    )
                } else {
                    import(context, name, { file.inputStream() }, onProgress)
                }
                file.delete()
                val missing = spec.missing(context)
                if (missing.isEmpty()) {
                    "ok   ${spec.id} -> ${spec.family.label} ${spec.native}, " +
                        "${spec.bytesOnDisk(context) shr 20} MB"
                } else {
                    "warn ${spec.id} incomplete — missing ${missing.joinToString()}"
                }
            } catch (e: Exception) {
                // ⚠ The file is KEPT on failure so a retry needs no second push.
                "FAIL $name — ${e.message}"
            }
        }
    }

    /** ⚠ The family suffixes [importInbox] accepts on a `.safetensors`. */
    private val DIT_INBOX_FAMILIES = mapOf(
        "flux2" to Family.FLUX2,
        "zimage" to Family.ZIMAGE,
    )

    // ---- config.json -----------------------------------------------------

    /**
     * Optional per-model metadata, read from `config.json` in the model
     * directory.
     *
     * ⭐ Taken from the ORIGINAL local-dream (`data/ModelConfig.kt`), which
     * DreamUI dropped in favour of one hardcoded prompt for every import. A
     * checkpoint's prompt style is the thing its author knows and we cannot
     * guess — an anime model and a photographic one want opposite negatives —
     * so the archive gets to say.
     *
     * ⚠ **Metadata only, and deliberately no family or resolution key.** Those
     * are inferred from the files (see the class note) and an override would be
     * a way to make the catalogue disagree with the disk — which is the exact
     * failure this design exists to prevent.
     *
     * ⚠ Null means "not specified"; every field falls back. A malformed file is
     * a warning and an empty config, never a failed import: the model's files
     * are fine and a bad `config.json` should not cost the user the download.
     */
    data class Config(
        val label: String? = null,
        val prompt: String? = null,
        val negative: String? = null,
        /**
         * ⭐⭐ The sampler this checkpoint was tuned for — the field that turns
         * `config.json` from a nicety into the thing that makes a distilled
         * model usable. Measured on device 2026-09-10: an imported SDXL model
         * published as "8-12 steps, CFG 1.5, Euler A" rendered crunchy and
         * over-sharpened under the backend's `dpm` default and cleanly under
         * `euler_a`, everything else held fixed.
         *
         * ⚠ **Validated against [ModelCatalog.SCHEDULERS], and an unknown value
         * is dropped with a warning.** The backend does not reject one — it
         * falls through to `dpm` in silence — so a typo here would otherwise
         * produce exactly the bug this field exists to fix, and blame the
         * checkpoint.
         */
        val scheduler: String? = null,
        /**
         * ⭐ Step count and guidance, the other two thirds of a distilled
         * checkpoint's recipe. ⚠ Clamped to the widget's range rather than
         * refused: an author who writes 60 steps meant "a lot", and dropping
         * the value entirely would silently give them 20.
         */
        val steps: Int? = null,
        val cfg: Double? = null,
    ) {
        companion object {
            private const val FILE = "config.json"

            fun read(dir: File): Config {
                val file = File(dir, FILE)
                if (!file.isFile) return Config()
                return try {
                    val json = JSONObject(file.readText())
                    Config(
                        label = json.str("label"),
                        // ⚠ Upstream's key names, so a `config.json` written for
                        // local-dream is read here unchanged.
                        prompt = json.str("default_prompt"),
                        negative = json.str("default_negative_prompt"),
                        steps = json.num("default_steps")?.let {
                            it.toInt().coerceIn(ModelCatalog.STEPS_RANGE)
                        },
                        cfg = json.num("default_cfg")?.coerceIn(ModelCatalog.CFG_RANGE),
                        scheduler = json.str("default_scheduler")?.let { v ->
                            v.takeIf { it in ModelCatalog.SCHEDULERS }.also {
                                if (it == null) {
                                    Log.w(TAG, "ignoring unknown scheduler '$v' in ${file.path}")
                                }
                            }
                        },
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "ignoring ${file.path}: ${e.message}")
                    Config()
                }
            }

            /** ⚠ NaN is how `optDouble` reports absent, and it is not a number. */
            private fun JSONObject.num(key: String): Double? {
                val v = optDouble(key)
                return if (v.isNaN()) null else v
            }

            private fun JSONObject.str(key: String): String? =
                if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null
        }
    }
}
