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
 * ## ⚠ Why this does not follow either upstream
 *
 * Both reference apps declare a model's family with a **marker file** —
 * `npucustom` / `SDXL` / `ANIMA` / `finished` upstream, only `npucustom` in
 * DreamUI. A marker is a claim that can be wrong, and upstream's own zip
 * importer proves it: `ModelListScreen.kt` writes `npucustom` at the end of
 * **every** import including an SDXL one, so an SDXL zip imported through its
 * UI is mislabelled SD 1.5 by the tool that unpacked it.
 *
 * ⇒ **We infer the family from the files instead**, which cannot disagree with
 * what is on disk. The discriminator is the CLIP side and it is unambiguous:
 * SD 1.5 ships one `clip_v2.mnn`, SDXL ships `clip_2.mnn` (CLIP-G) beside
 * `clip.mnn`. No archive contains both.
 *
 * ⚠ The interesting property is that a *partial* directory can never be
 * MISCLASSIFIED, only unclassifiable — the discriminators are disjoint, so a
 * half-extracted SDXL model is never mistaken for an SD 1.5 one. It is listed
 * as partial with [ModelSpec.missing] naming what is absent, exactly like a
 * half-downloaded built-in.
 *
 * ⇒ **No marker file, and therefore no marker-written-last dance.** A model
 * directory pushed over adb (`notes/HANDOFF.md` §5) is picked up with no extra
 * step, which the marker designs cannot do.
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
        val sdxl = File(dir, SDXL_MARK).isFile
        val sd15 = File(dir, SD15_MARK).isFile
        // ⚠ Both present is not a family, it is a mixed directory — someone
        // unpacked two archives into one place. Refusing beats picking one.
        if (sdxl == sd15) {
            if (sdxl) Log.w(TAG, "skipping '${dir.name}': has both $SD15_MARK and $SDXL_MARK")
            return null
        }
        val cfg = Config.read(dir)
        return if (sdxl) customSpec(dir, cfg, Family.SDXL) else customSpec(dir, cfg, Family.SD15)
    }

    /**
     * ⚠ Every family-dependent field comes from [ModelCatalog]'s own constants,
     * never from a literal here. A custom SDXL model that carried its own copy
     * of `SDXL_REQUIRED` would drift from the built-in entries the first time
     * the backend's file list changed, and the symptom is a 3.7 GB import that
     * reports "not installed" forever.
     */
    private fun customSpec(dir: File, cfg: Config, family: Family): ModelSpec = ModelSpec(
        id = dir.name,
        label = cfg.label ?: dir.name,
        // ⚠⚠ EMPTY, and that is the definition of a custom model: there is no
        // URL that could produce these files. It is also why [ModelSpec.best]
        // is nullable — see the note there.
        builds = emptyList(),
        prompt = cfg.prompt.orEmpty(),
        negative = cfg.negative.orEmpty(),
        // ⚠ The backend's default when the archive says nothing, which is
        // today's behaviour for every model -- so adding this field changes no
        // existing import's output until its author declares otherwise.
        scheduler = cfg.scheduler ?: ModelCatalog.DEFAULT_SCHEDULER,
        steps = cfg.steps ?: ModelCatalog.DEFAULT_STEPS,
        cfg = cfg.cfg ?: ModelCatalog.DEFAULT_CFG,
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
        // ⚠ Not a preference: SDXL's UNet does not fit beside its VAE and two
        // encoders at 1024², and the backend needs telling regardless of where
        // the files came from.
        lowram = family == Family.SDXL,
        // ⚠⚠ **No arch claim.** Nothing in a QNN context directory says which
        // HTP it was compiled for — `QnnSystemContext` gives the IO contract
        // and nothing else — so any number here would be invented. A built-in
        // entry knows because we recorded which archive it came from; this one
        // cannot. ⇒ Let it try and let the failure say what happened, which is
        // upstream's position too (`../LocalDream/docs/DEVICE-SUPPORT.md`).
        minHtpArch = 0,
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
                "not a checkpoint: no $SD15_MARK (SD 1.5) or $SDXL_MARK (SDXL) in the archive",
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
     * Imports every zip sitting in [inbox], deleting each on success.
     *
     * ⚠ The model name is the zip's basename, so `my-model.zip` becomes
     * `models/my-model/`. That is the whole naming rule and it is the one a
     * shell user can predict.
     *
     * @return one line per archive, for a log.
     */
    fun importInbox(
        context: Context,
        onProgress: (ModelInstaller.Progress) -> Unit = {},
    ): List<String> {
        val zips = inbox(context).listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("zip", ignoreCase = true) }
        if (zips.isEmpty()) return emptyList()
        return zips.map { zip ->
            val name = zip.nameWithoutExtension
            try {
                val spec = import(context, name, { zip.inputStream() }, onProgress)
                zip.delete()
                val missing = spec.missing(context)
                if (missing.isEmpty()) {
                    "ok   ${spec.id} -> ${spec.family.label} ${spec.native}, " +
                        "${spec.bytesOnDisk(context) shr 20} MB"
                } else {
                    "warn ${spec.id} incomplete -- missing ${missing.joinToString()}"
                }
            } catch (e: Exception) {
                // ⚠ The zip is KEPT on failure so a retry needs no second push.
                "FAIL $name -- ${e.message}"
            }
        }
    }

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
