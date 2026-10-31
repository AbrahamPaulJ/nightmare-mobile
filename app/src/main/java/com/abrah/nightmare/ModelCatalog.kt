package com.abrah.nightmare

import android.content.Context
import java.io.File

/**
 * ⭐ One file of a plain-file model package: where it is published, what the
 * backend expects it to be called, and its exact served size — THE integrity
 * check, exactly as a zip's byte count is ([ModelInstaller.fetch]).
 */
data class RemoteFile(
    val url: String,
    val name: String,
    val bytes: Long,
    /**
     * ⭐ Set when [name] is MADE on the phone from what [url] serves (Anima's
     * official BF16 DiT → Q8_0 GGUF, [AnimaGguf]): the download is
     * [sourceBytes] long, fetched beside the output, converted, size-checked
     * against [bytes] and deleted. [bytes] stays the installed file's size, so
     * every "is it installed" check is unchanged.
     */
    val sourceBytes: Long = 0L,
    val convert: Converter? = null,
)

/**
 * ⭐ Turns a downloaded source file into the file the backend loads ([RemoteFile.convert]).
 * Returns the bytes written to [dst], counted on the open file after a sync: ⚠ `File.length()` read
 * right after the write said 0 for a 2.28 GB file on Android 17 (two S25s, 2026-10-16).
 */
fun interface Converter {
    fun run(src: java.io.File, dst: java.io.File, onProgress: (done: Long, total: Long) -> Unit, isCancelled: () -> Boolean): Long
}

/**
 * ⭐ What a checkpoint IS, as opposed to which files it ships.
 *
 * ⚠ The family decides the **node vocabulary** and the native resolution, and
 * nothing else (`docs/MODELS.md` §2). Two checkpoints of the same family are
 * interchangeable in a graph; two families are not, because `sd.sample` on an
 * SDXL model is a different `--type` and therefore a different [ContextKey].
 */
enum class Family(
    val label: String,
    /**
     * ⭐⭐⭐ **A general-purpose starter prompt for the whole family**, and the
     * fallback for any checkpoint that carries none of its own.
     *
     * ⚠⚠ **No SUBJECT in it.** Every per-model prompt in this file names one
     * — "a cat on grass", "1girl, solo, cute, white hair" — because upstream
     * wrote them as demos of a checkpoint's style. That is right for a
     * checkpoint we know and wrong as a default: a person who came to draw
     * something has to delete a cat before they can start. These are quality
     * tags only, so the box is finished by typing what you want.
     *
     * ⚠ It does NOT replace [ModelSpec.prompt] — a checkpoint's own text is
     * still what that checkpoint opens on ([ModelSpec.starterPrompt]), and the
     * upstream-verbatim rule above is untouched. This fills the two gaps that
     * rule leaves: an IMPORTED model, whose prompt is empty by design, and the
     * user asking for a neutral starting point. The user's ask, 2026-09-15.
     */
    val prompt: String,
    val negative: String,
    /** ⚠ For ids — a new node is `sdxl_inpaint`, never `SDXL Inpaint` with a space. */
    val slug: String,
    /**
     * ⭐⭐ Rendered WHOLE by `libdit_engine.so` (upstream local-dream 3.0): one
     * `/generate` call, prompt and optional picture in, picture out. No
     * conditioning or latent handles exist for it, no patches, no inpaint, and
     * its size is a request field rather than a launch one.
     */
    val dit: Boolean = false,
    /**
     * ⭐ A NATIVE EDIT model: the engine takes the base picture as a clean
     * reference latent (`PipelineDit::isNativeEditModel`), so it has a
     * `reference` port, "Image edit" as its job, denoise born at 1.0 and green
     * padding. The ONE place that says which families those are — FLUX.2
     * Klein, and Qwen Image 2.1 since upstream 440899f.
     */
    val edit: Boolean = false,
) {
    SD15("SD 1.5", GP_SD15, GP_SD15_NEG, "sd15"),
    /**
     * ⭐⭐ **SD 1.5 Swap** — the user's name, 2026-09-29. An SD 1.5 checkpoint
     * converted by npuforge into the TEMPLATE UNet whose LoRA and ControlNet
     * residuals are graph inputs, so both change per render with no conversion
     * (`backend-patches/015`, `../npuforge/docs/SD15-LORA-CN-TEMPLATE.md`).
     *
     * ⚠⚠ Its own family, not a flag on [SD15]: a PORT is per node type, and
     * the `control` port exists only here — the FLUX.2 `reference` precedent.
     * Same launch type ([ModelCatalog.SD15_NPU]) and prompts as SD 1.5; 512²
     * only (the template ships no resolution patches). Recognised by
     * [TemplateLora.TARGETS_FILE] beside the UNet ([CustomModels]).
     */
    SD15_SWAP("SD 1.5 Swap", GP_SD15, GP_SD15_NEG, "sd15swap"),
    SDXL("SDXL", GP_SDXL, GP_SDXL_NEG, "sdxl"),
    /**
     * ⭐⭐ **SDXL Swap** — an SDXL checkpoint converted by npuforge (1.0.10+) into
     * the SDXL Swap TEMPLATE UNet (`../npuforge/docs/SDXL-SWAP-TEMPLATE.md`): its
     * LoRA (700 rank-64 targets), ControlNet residuals and IP-Adapter K/V are
     * graph inputs. **This app feeds the LoRA** (`backend-patches/018` binds them
     * by name on the 231-masked SDXL path); ControlNet and IP-Adapter inputs stay
     * zero — the identity — until their SDXL models exist on the phone.
     * Same launch type, prompts and 1024² as [SDXL]; its own family for the
     * [SD15_SWAP] reason (the LoRA widget is per node type). Recognised by
     * [TemplateLora.TARGETS_FILE] beside an SDXL UNet ([CustomModels]).
     */
    SDXL_SWAP("SDXL Swap", GP_SDXL, GP_SDXL_NEG, "sdxlswap"),
    ANIMA("Anima", GP_ANIMA, GP_ANIMA_NEG, "anima"),
    FLUX2("FLUX.2", GP_DIT, GP_DIT_NEG, "flux2", dit = true, edit = true),
    ZIMAGE("Z-Image", GP_DIT, GP_DIT_NEG, "zimage", dit = true),
    QWEN21("Qwen Image", GP_DIT, GP_DIT_NEG, "qwen21", dit = true, edit = true),
    /**
     * ⚠ Text to image ONLY, although the engine can edit with it: the fork's
     * own README shows its edits failing (the user's call, 2026-09-28).
     */
    KREA2("Krea 2", GP_DIT, GP_DIT_NEG, "krea2", dit = true),
    /**
     * ⭐⭐ **Anima on the DiT engine** (`docs/ANIMA-GGML.md`): the same model as
     * [ANIMA], rendered whole by `libdit_engine.so` instead of QNN context
     * binaries — so any size, any Anima `.safetensors`, and faster (56 s vs 82 s
     * at 1024², 10 steps). Its own family because the node vocabulary is the
     * DiT one ([dit]), not [ANIMA]'s latent-handle samplers. Anima's own tag
     * prompts, not the DiT sentences: the text encoder is the same Qwen3-0.6B.
     */
    ANIMA_DIT("Anima DiT", GP_ANIMA, GP_ANIMA_NEG, "animadit", dit = true),
    /**
     * ⭐⭐ **Iris 3B Turbo** on the DiT engine (`docs/IRIS.md`, engine `dit/022`): a pixel-space
     * model, so NO VAE, the Qwen3-VL-4B text encoder Krea 2 uses, and 4 steps on Turbo's own
     * schedule (the engine's Iris default). ⚠ Text to image only, and only at its trained sizes
     * ([ModelSpec.fixedSizes]).
     */
    IRIS("Iris", GP_DIT, GP_DIT_NEG, "iris", dit = true),
    /**
     * ⭐⭐ **MOSS-Image** (OpenMOSS, Apache 2.0) on the DiT engine (`docs/MOSS.md`, engine `dit/028`): a 5B DiT,
     * Qwen3.5 9B as its text encoder, the FLUX.2 VAE, and lodestones' 8-step DMD LoRA applied on EVERY render
     * ([ModelSpec.ownLora]). ⚠ Text to image only: nothing else measured.
     */
    MOSS("MOSS-Image", GP_DIT, GP_DIT_NEG, "moss", dit = true),
    /**
     * ⭐⭐ **Ideogram 4** (Ideogram, non-commercial) on the DiT engine (`docs/ROADMAP.md` "Models past Wan 5B", engine
     * `dit/029`): a 9.3B single-stream DiT, Qwen3-VL-8B as its text encoder, the FLUX.2 VAE, and ostris' TurboTime LoRA
     * applied on EVERY render ([ModelSpec.ownLora]): 8 steps, no CFG, no unconditional model. ⚠ Text to image only.
     */
    IDEOGRAM("Ideogram 4", GP_DIT, GP_DIT_NEG, "ideogram", dit = true),
    /**
     * ⭐⭐ **FLUX.3 Image Klein** (Black Forest Labs) on the DiT engine (`docs/DIT.md` §FLUX.3, engine `dit/042`, a
     * backport of upstream sd.cpp's #2143): a new 3072-wide DiT with per-mode early blocks, Krea 2's Qwen3-VL-4B text
     * encoder and the FLUX.2 VAE. The distilled Klein: 16 steps, no CFG. ⭐ An editor too since 1.6.258 (GitHub #72;
     * the engine's edit matched upstream at 51 dB, `docs/FLUX3.md` §3); its own family because FLUX.2's [FLUX2] launch is
     * Klein 4B's shared parts.
     */
    FLUX3("FLUX.3", GP_DIT, GP_DIT_NEG, "flux3", dit = true, edit = true),
    /** ⭐ Bria's Fibo 2.0 Edit Turbo (GH #69, `dit/056`): an 8B editor on Z-Image's DiT, Qwen3-VL-4B with its vision. */
    FIBO2("Fibo 2.0", GP_DIT, GP_DIT_NEG, "fibo2", dit = true, edit = true),
    /**
     * ⭐⭐ **Wan 2.2 TI2V 5B video** on the DiT engine (`docs/VIDEO-GGML.md`): a prompt, or a
     * prompt and a start picture, into a clip through `/video` (backend 028, engine ABI 106).
     * ⚠⚠ NOT a checkpoint: its one model ([ModelCatalog.WAN22]) is kept out of
     * [ModelCatalog.all], so no picture flow, picker or Models list offers it. The family
     * exists for the launch path ([dit] picks the engine, its DSP env and its checks).
     */
    WAN("Wan video", GP_DIT, GP_DIT_NEG, "wan", dit = true),
}

// ---- the general-purpose prompts --------------------------------------------

/**
 * ⚠ The DiT families read SENTENCES through a Qwen3 text encoder, not CLIP
 * tags, and both published checkpoints are distilled to cfg 1 — where a
 * negative prompt is not read at all. Upstream ships them with an empty one.
 */
private const val GP_DIT = "a detailed photo,"

/**
 * ⭐⭐ **A starting negative for the DiT families** — the user's ask,
 * 2026-09-19, replacing the empty string that stood here because upstream ships
 * one.
 *
 * ⚠⚠⚠ **It does nothing at cfg 1, and that is not a bug in it.** Both shipped
 * checkpoints are guidance-distilled and open at cfg 1.0, where
 * `stable-diffusion.cpp` skips the unconditional pass entirely — the text
 * reaches the engine through `dit_gen_params.negative_prompt` and is never
 * encoded. Raise cfg above 1 on the node and it starts being read, at roughly
 * twice the time per step. ⇒ It is here so the box is not empty and so the knob
 * works the moment it can, NOT because it changes today's default render. The
 * decision was made knowing that (the user's call, asked and answered).
 *
 * ⚠ SENTENCE-shaped, not a CLIP tag list. Qwen3 reads prose, so `GP_SD15_NEG`'s
 * comma-separated tags would be read as a sentence about commas — the same
 * reason [GP_DIT] is a phrase rather than "masterpiece, best quality".
 */
private const val GP_DIT_NEG =
    "blurry, low quality, distorted, deformed, watermark, text, oversaturated"

// ⚠ Top-level rather than inside [ModelCatalog]: [Family] is declared above it
// and an enum's constructor arguments must be compile-time constants it can see.

/**
 * ⚠ SD 1.5 reads TAGS, not sentences — this is a tag list by design, and it is
 * the shape every per-model SD 1.5 prompt in this file has.
 */
private const val GP_SD15 =
    "masterpiece, best quality, ultra-detailed, sharp focus, 8k,"

/**
 * ⚠ The union of what the per-model SD 1.5 negatives agree on, with anything
 * style-specific dropped — no "cartoon, anime" (that belongs to the
 * photographic checkpoints) and no "realistic photo" (that belongs to the anime
 * ones). A general negative that fights half the catalogue is not general.
 */
private const val GP_SD15_NEG =
    "worst quality, low quality, normal quality, lowres, blurry, out of focus, " +
        "jpeg artifacts, signature, watermark, text, error, bad anatomy, " +
        "bad hands, missing fingers, extra digit, cropped,"

/** ⚠ SDXL needs fewer quality tags than SD 1.5 — it was trained on captions. */
private const val GP_SDXL =
    "masterpiece, best quality, highly detailed, sharp focus,"

/**
 * ⚠ The quality prefix every published Anima checkpoint's own `config.json`
 * opens with — `masterpiece, best quality, score_7` — without the demo that
 * follows it (a girl holding a sign), for the no-subject reason above.
 * ⚠⚠ Led by the rating tag `safe`: without one Anima picks its own rating, and
 * the bare prefix rendered NSFW (2026-10-13). At cfg 1 the negative is never
 * read, so the rating has to be in the POSITIVE (`docs/ANIMA-GGML.md` §6).
 */
private const val GP_ANIMA = "safe, masterpiece, best quality, score_7,"

/**
 * ⚠⚠ Unread at the published cfg of 1 — every Anima `config.json` says so in
 * its own words. It is here for a user who raises cfg, where it starts to matter.
 */
private const val GP_ANIMA_NEG =
    "worst quality, low quality, lowres, blurry, jpeg artifacts, bad anatomy, " +
        "bad hands, extra fingers, watermark, signature, text,"

/** ⚠ Upstream's own general SDXL negative, which eight of the ten already use. */
private const val GP_SDXL_NEG =
    "lowres, bad anatomy, bad hands, text, error, missing fingers, extra digit, " +
        "fewer digits, cropped, worst quality, low quality, normal quality, " +
        "jpeg artifacts, signature, watermark, username, blurry,"

/**
 * Where a model runs.
 *
 * ⚠ This decides the **device gate** and the speed, and nothing else:
 * `sd15cpu` and `sd15npu` are the same [Family] and drive the same nodes
 * (`docs/MODELS.md` §2). It is separate from [Family] precisely so a CPU
 * fallback is a second runtime rather than a second vocabulary.
 */
enum class Runtime { NPU, CPU }

/**
 * A resolution a model's graphs were compiled for.
 *
 * ⚠⚠ Not a preference. `--patch` binds at backend launch like everything else
 * in a [ContextKey], and `sdxl`/`anima` **force 1024 inside the backend's
 * request parser whatever the client sends** -- so a graph asking for 512 on
 * one of those renders 1024 in silence. That is the reason this lives on the
 * model rather than as a `"512"` default next to the widget.
 */
data class Res(val width: Int, val height: Int) {
    override fun toString() = "${width}x$height"

    val isSquare: Boolean get() = width == height

    /** Pixels, so a list of these sorts by cost. */
    val area: Int get() = width * height

    /** width / height, for anything that has to match the output's shape. */
    val aspect: Float get() = width.toFloat() / height.toFloat()

    /**
     * ⭐ The zstd patch this resolution needs, or **null for the base 512²**
     * that `unet.bin` already is.
     *
     * ⚠ Matches what the conversion pipeline emits, and the two forms are not
     * interchangeable: `768.patch` for a square, `512x768.patch` for a
     * rectangle (`npuconvert/export.sh`). ⚠⚠ Per width AND height — handing a
     * portrait build the square patch is a graph of the wrong shape, and the
     * backend does not check.
     */
    val patchName: String?
        get() = when {
            this == ModelCatalog.SD15_NPU_RES -> null
            isSquare -> "$width.patch"
            else -> "${width}x$height.patch"
        }

    companion object {
        /** `768.patch` and `512x768.patch` respectively. */
        private val SQUARE_PATCH = Regex("""^(\d+)\.patch$""")
        private val RECT_PATCH = Regex("""^(\d+)x(\d+)\.patch$""")

        /**
         * The inverse of [toString] — `"768x512"` back to a [Res].
         *
         * ⚠ Shared by the chips and by the `res_use` harness op so a size typed
         * on a command line and one tapped on screen cannot parse differently.
         */
        fun fromLabel(s: String): Res? {
            val p = s.split("x")
            if (p.size != 2) return null
            val w = p[0].trim().toIntOrNull() ?: return null
            val h = p[1].trim().toIntOrNull() ?: return null
            return if (w > 0 && h > 0) Res(w, h) else null
        }

        /** Parses a patch filename back into a resolution, or null when it is not one. */
        fun fromPatch(name: String): Res? {
            SQUARE_PATCH.matchEntire(name)?.let { m ->
                val n = m.groupValues[1].toIntOrNull() ?: return null
                return Res(n, n)
            }
            RECT_PATCH.matchEntire(name)?.let { m ->
                val w = m.groupValues[1].toIntOrNull() ?: return null
                val h = m.groupValues[2].toIntOrNull() ?: return null
                return Res(w, h)
            }
            return null
        }
    }
}

/**
 * ⭐⭐ ONE published build of a checkpoint, and the two numbers that decide
 * whether this phone can load it.
 *
 * ⚠⚠ A checkpoint is published several ways and **only the graph config
 * differs** — same weights, same pictures. `_8gen2` is a v73 context wanting
 * 8 MB of VTCM, `_8gen1` a v69 one wanting 8 MB, `_min` a v68 one wanting 2 MB.
 * A QNN context runs **forward only**, so a v69 HTP cannot load a v73 build at
 * all: it is not slower, it fails at load after the whole download.
 *
 * ⇒ Serving the wrong tier is the difference between an app that works on one
 * phone and an app that works. `../LocalDream/docs/DEVICE-SUPPORT.md` §5.
 *
 * ⚠ [bytes] is per-tier and is **data, not arithmetic** — the tiers differ in
 * both directions and by tens of MB. It is the only integrity check
 * [ModelInstaller] has, so every one of these was HEAD-checked against the repo.
 */
data class Build(
    /** The archive-name suffix, e.g. `_8gen2`. Also what the user is told they got. */
    val tier: String,
    val archive: String,
    val bytes: Long,
    /** The HTP arch this CONTEXT needs. A lower-arch device cannot load it. */
    val minArch: Int,
    val minVtcmMb: Int,
    /**
     * ⭐ Further archives extracted into the same model dir — archive name to
     * served byte count, on the same base URL.
     *
     * ⚠⚠ Per BUILD, not per model, because what they carry is resolution
     * patches: a patch is a byte-diff against ONE `unet.bin`, so an `_8gen2`
     * patch reconstructs garbage against a `_min` install. Only the build it
     * was cut from may fetch it, and [ModelSpec.availableResolutions] then
     * discovers it from disk like any other patch.
     */
    val extras: List<Pair<String, Long>> = emptyList(),
    /**
     * ⭐ The phone RAM (`MemTotal`) below which this build cannot run at all —
     * 0 for none. Krea 2 Turbo's 6.5 GB DiT was reaped by lmkd while loading
     * on a 12 GB phone (2026-09-28), so it is offered on the 16 GB class only.
     *
     * ⚠ Unknown RAM (0) is offered the model, for the reason
     * [DeviceProbe.Caps.known] gives about an unknown chip: trying is
     * recoverable, hiding is not.
     */
    val minRamBytes: Long = 0L,
    /**
     * ⭐ A plain-file package's files FOR THIS BUILD — empty means the model's
     * own [ModelSpec.files]. Qwen Image 2.1 is the one user (2026-10-06): its FP8
     * DiT needs the 16 GB class (measured: the 6.8 GB DiT got the FOREGROUND app
     * lmkd-killed on the 12 GB S25 Ultra), so it ships Q4_0 below that.
     */
    val files: List<RemoteFile> = emptyList(),
) {
    fun runsOn(caps: DeviceProbe.Caps): Boolean =
        caps.supportsArch(minArch) && caps.vtcmMb >= minVtcmMb && ramOk(caps)

    /** ⚠ See [minRamBytes]. */
    fun ramOk(caps: DeviceProbe.Caps): Boolean =
        minRamBytes <= 0L || caps.ramBytes <= 0L || caps.ramBytes >= minRamBytes
}

/**
 * ⭐ A licence the person accepts before a model downloads ([ModelSpec.licence]): the dialog's title and terms, and
 * the full text's address. Accepted once a model, remembered in [Prefs.licenceAccepted].
 */
data class ModelLicence(val titleRes: Int, val termsRes: Int, val url: String)

/**
 * Which checkpoints exist, where they come from, and whether one is installed.
 *
 * ⚠ This file is DATA plus disk state; fetching lives in [ModelInstaller] and
 * the picking UI in `ui/ModelsScreen.kt`. Before it existed the model was a
 * free-text widget defaulting to a directory somebody had pushed over adb by
 * hand, so a user had no way to obtain a model at all.
 *
 * ⭐ The set is **local-dream's own five SD1.5 NPU checkpoints**. That is the
 * base-model support this project is a fork of, and `docs/ARCHITECTURE.md` §5.1
 * pins [V1_MODEL] as v1's default out of the five. The rest are downloadable
 * because a graph may use any ONE of them — §5.2 pins one context key per
 * *graph*, not one model per install, and the executor already refuses a graph
 * that names two.
 */
data class ModelSpec(
    /** The directory name, and the value of every node's `model` param. */
    val id: String,
    val label: String,
    /**
     * ⭐⭐ Every published build, **best first**.
     *
     * ⚠⚠ Each carries its own EXACT size, and that size is the only integrity
     * check that exists: there is no published checksum, and
     * `HttpURLConnection` reports success on a truncated body — a short read
     * looks like a finished download and fails later as a corrupt model.
     *
     * ⚠ Ordered by preference, not by size, because [buildFor] takes the FIRST
     * one the device can load. Highest arch first: it is the fastest build the
     * hardware will accept.
     */
    val builds: List<Build>,
    /** A prompt that suits this checkpoint. Model data, never translated: SD1.5 reads English tags. */
    val prompt: String,
    val negative: String,
    val family: Family = Family.SD15,
    val runtime: Runtime = Runtime.NPU,
    /**
     * ⭐ The backend's `--type` -- the first third of a [ContextKey], and one of
     * the three things bound at process launch.
     *
     * ⚠ Derived from [family] + [runtime] + capability in principle, and stored
     * explicitly anyway: `sd15npu_inpaint` and `sd15npu_control_inpaint` are the
     * same family and runtime as `sd15npu`, so the mapping is not a function of
     * two enums and pretending it is would need a third enum to fix it later.
     */
    val backendType: String = ModelCatalog.SD15_NPU,
    /**
     * ⚠ **First is native** -- what a new node is sized to, and what the locked
     * `width`/`height` widgets show. The rest are what the resolution patches in
     * the archive make reachable; nothing reads them yet, because a second
     * resolution is a second [ContextKey] and v1 pins one (§5.2).
     */
    val resolutions: List<Res> = listOf(ModelCatalog.SD15_NPU_RES),
    /**
     * ⚠ Per-family, because SDXL is a **different repository** —
     * `xororz/sdxl-qnn`, not `xororz/sd-qnn`. It was one constant while there
     * was one family.
     */
    val baseUrl: String = ModelCatalog.SD15_BASE_URL,
    /**
     * ⚠⚠ What the backend needs before it will start, **per family**.
     * SD 1.5 wants one `clip_v2.mnn`; SDXL wants two encoders with their own
     * embedding tables and CLIP-G's external weight file. A file list that is
     * merely close makes a 3.7 GB model report "not installed" forever, with no
     * error anywhere — nothing reads a required list and asks why.
     */
    val requiredFiles: List<String> = ModelCatalog.SD15_REQUIRED,
    /**
     * The archive tier, which is a statement about the CHIP: `_8gen2` is a v73
     * context, `_8gen3` a v75 one. ⚠ Named so it can be checked rather than
     * trusted — [ModelCatalog.TIER] used to be one constant spliced into every
     * filename, which cannot express a catalogue with two.
     */
    val tier: String = ModelCatalog.TIER,
    /**
     * ⚠ The HTP architecture below which this model cannot load AT ALL.
     * SDXL is compiled `_8gen3`, so 75.
     *
     * ⚠⚠ **Not what gates a device.** [buildFor] reads [builds] (each [Build.minArch]) and never this
     * field; it survives only as `ModelBrowser`'s fallback for an entry with no builds (an import).
     * A new entry's chip floor goes in its builds, or this says one thing and the gate another.
     */
    val minHtpArch: Int = 68,
    /**
     * `--lowram`: load and release each stage rather than holding the whole
     * pipeline resident. ⚠ Not a preference — SDXL's UNet is ~3× SD 1.5's at
     * 1024², and DreamUI sets it on every SDXL checkpoint.
     *
     * ⚠⚠ **One flag, TWO meanings, decided by the family.** On a DiT package
     * `main.cpp` does not stage anything — it turns this into the engine's
     * `params_backend = "te=disk"`, which streams the text encoder's parameters
     * off disk and holds the rest. Same word, same flag, different mechanism;
     * what the two share is "this model does not fit resident on a 12 GB
     * phone". ⇒ Read the branch in `createPipeline` before reasoning about what
     * it costs for a given model.
     */
    val lowram: Boolean = false,
    /**
     * ⭐⭐ The sampler this checkpoint was TUNED for, and the default a new
     * `sd.sample` node picks up.
     *
     * ⚠⚠ Not cosmetic, and not a small effect. Measured on device 2026-09-10
     * against an imported distilled SDXL checkpoint at its published settings
     * (10 steps, cfg 1.5): `dpm` produced a crunchy, over-sharpened picture
     * with halos, `euler_a` a clean natural one — same seed, same steps, same
     * cfg, same weights. A distilled model is trained for a particular sampler
     * and the wrong one does not fail, it looks *bad*.
     *
     * ⚠ [ModelCatalog.DEFAULT_SCHEDULER] for everything we publish, because
     * that is what the backend used for all of them before the field was sent
     * — so this changes no built-in's output. A checkpoint that wants
     * otherwise says so: a custom model through `config.json`'s
     * `default_scheduler` ([CustomModels.Config]), a catalogue entry by setting
     * this.
     */
    val scheduler: String = ModelCatalog.DEFAULT_SCHEDULER,
    /**
     * ⭐⭐ The step count and guidance this checkpoint wants — the other two
     * thirds of the same problem [scheduler] solves.
     *
     * ⚠⚠ A **distilled** model is the case that makes these model data rather
     * than user preference. This app's node defaults (20 steps, cfg 7.5) are
     * right for an ordinary checkpoint and badly wrong for a distilled one,
     * which publishes something like 10 steps at cfg 1.5 — and at 7.5 it does
     * not fail, it renders burnt and oversaturated. Measured on device
     * 2026-09-10 against an imported SDXL checkpoint whose page says exactly
     * that.
     *
     * ⚠ The defaults here are the node's own, so a catalogue entry that says
     * nothing behaves exactly as before.
     */
    val steps: Int = ModelCatalog.DEFAULT_STEPS,
    val cfg: Double = ModelCatalog.DEFAULT_CFG,
    /**
     * ⭐ The user brought this one — see [CustomModels].
     *
     * ⚠⚠ It is **not** derivable from `builds.isEmpty()`, though today the two
     * coincide. The empty build list says "nothing to download"; this says
     * "nothing we published, so we know nothing about it" — which is what the
     * UI must branch on. A built-in whose archives were withdrawn would have
     * the first property and not the second, and would want the "unsupported"
     * wording rather than the "you imported this" wording.
     */
    val isCustom: Boolean = false,
    /**
     * ⭐ Converted and hosted by a community member, not by us ([ModelCatalog.communityModels]): a "Community" chip
     * on its family page and a badge on its row.
     */
    val community: Boolean = false,
    /**
     * ⭐ A licence the person must accept before the download starts ([HarnessViewModel.installModel] asks, once a
     * model): MiniMax H3's territory and output terms. Null for every model whose licence the README covers.
     */
    val licence: ModelLicence? = null,
    /**
     * ⭐ Made for adult content: listed (Models, the video dropdown, the palette) only with Settings' Show mature
     * content on ([Prefs.matureContent]), with the CivitAI NSFW search (the user, 2026-10-30). Installed, it still runs.
     */
    val mature: Boolean = false,
    /**
     * ⭐ Measured on the GPU route ([DitGpu], `docs/GPU-ROUTES.md` §8): where [DitGpu.wanted], it renders there from
     * its GGUF Q4_0 build ([buildFor]). Klein 4B and Z-Image Turbo; the bigger DiTs wait for their limit tests.
     */
    val gpu: Boolean = false,
    /**
     * ⭐ An absolute folder this model lives in, outside the models root — Local Dream's imports
     * ([LocalDreamModels]). Null for everything else: [dir] is then root + [id].
     */
    val home: String? = null,
    /**
     * ⭐ How many CLIP tokens the UNet reads: 77, or 231 for an npuforge SDXL
     * export (`qnn_context.txt` = `231_masked_v1` — three 77-token chunks and an
     * attention mask; `backend-patches/005`). Only the prompt COUNT reads it:
     * the backend detects the contract from the same file on its own.
     */
    val promptTokens: Int = 77,
    /**
     * ⭐⭐ A package of PLAIN FILES rather than a zip — the DiT families, whose
     * weights are fetched from the repositories that publish them (upstream's
     * `packageFiles`). Empty for everything else. When non-empty, the installer
     * fetches these into the model dir and [builds] only gates the device.
     */
    val files: List<RemoteFile> = emptyList(),
    /**
     * ⭐⭐ A DiT package whose text encoder and VAE are its OWN, never the
     * family's shared ones in [CustomModels.DIT_SHARED] — Qwen Image 2.1,
     * Krea 2 Turbo, and FLUX.2 Klein 9B (whose Qwen3-8B encoder is not the
     * FLUX.2 family's Qwen3-4B).
     *
     * ⚠ Per model rather than per family, because of the 9B: a family flag
     * would either give Klein 4B's imports the 8B encoder, or give the 9B the
     * 4B one. It is also what keeps the 9B from being chosen as the donor an
     * import copies its parts from ([CustomModels.ditPartsPlan]).
     */
    val ownDitParts: Boolean = false,
    /**
     * ⭐ Another model whose copy of some of [files] this one may use instead of its own: Wan 2.2 Fun
     * Control takes Wan 2.2 Turbo's umT5 and TAEHV (3.6 GB not downloaded twice). The donor is the
     * SIBLING folder of this model's ([donorDir]), the path the backend gets as `--wan_parts`
     * (backend 033), so the two cannot disagree. A file counts as present when the donor holds it at its
     * exact size; the installer skips it ([toFetch]); this model's own copy wins when it has one.
     */
    val partsFrom: String? = null,
    /**
     * ⭐ A LoRA file in this model's own folder, applied at full strength on every render before the node's own
     * LoRAs: MOSS-Image's 8-step DMD distillation. ⚠⚠ At run time, never merged: its change to each weight is
     * about 1/1000 of a Q4_0 rounding step, so a merge into the shipped Q4_0 erases it (measured 2026-10-18).
     */
    val ownLora: String? = null,
    /**
     * ⭐ The size a node STARTS at when it takes this model — a new flow, or
     * "Use" on the Models tab — when that is not [native]. Null = [native].
     *
     * ⚠⚠ Never the context key: [native] stays what the key is pinned to, and
     * a DiT's size is a request field, so this moves no launch. It exists for
     * FLUX.2 Klein 9B, which completed 5 of 5 renders at 768² and 3 of 4 at
     * 1024² on a 12 GB phone (2026-09-28; the user's call to start it at 768).
     * The user can still slide it up; a node's own size is never overwritten.
     */
    val startRes: Res? = null,
    /**
     * ⭐⭐ A DiT that renders ONLY its [resolutions], not the 64-px grid. Iris 3B Turbo was distilled
     * at ~1 MP in seven shapes, and below that its 16-px patches decode apart into visible blocks
     * (512² blocky on the CPU and the NPU alike, 1024² clean; `docs/IRIS.md`). So the size panel
     * offers the list, and [ModelCatalog.keptSize] / `runDit` pick from it instead of snapping.
     */
    val fixedSizes: Boolean = false,
    /**
     * ⭐⭐ What this conversion takes per render — [ModelFeatures.LORA], `CONTROLNET`,
     * `IP_ADAPTER`, `INPAINT` — read from its `swap_features.json` at import
     * ([ModelFeatures.of]). Null = a built-in entry: its family's default.
     */
    val features: Set<String>? = null,
) {
    /** ⭐ [features], or the family's default when the spec does not say ([ModelFeatures]). */
    val featureSet: Set<String> get() = ModelFeatures.installed(id) ?: features ?: ModelFeatures.defaultFor(family)

    /** ⭐ Rendered by the DiT engine through `/generate` — see [Family.dit]. */
    val isDit: Boolean get() = family.dit

    /**
     * ⭐ A chip where this model runs with the NPU's matrix unit off ([DeviceProbe.DIT_NO_HMX_SOCS]): a note on the row.
     * ⚠⚠ Not Iris: with HMX off it ABORTS (`dsp-rsp NO-SUPPORT`, the S25 at v79, 2026-10-18): HVX matmuls take only
     * Q4_0 / Q4_1 / Q8_0 / IQ4_NL / MXFP4 weights (`matmul-ops.c`) and Iris keeps BF16 rows (`docs/IRIS.md` §4).
     * Wan is not offered there.
     */
    fun noHmxOn(caps: DeviceProbe.Caps): Boolean =
        isDit && family != Family.WAN && family != Family.IRIS && caps.ditNoHmx

    /**
     * ⭐ ⋮ Test the NPU on this chip: every [noHmxOn] model, and Iris, whose static there was the DiT's.
     * ⚠ Not below v73: the 8 Gen 1's skel has no HMX to test (`dit/034`), so the legs would only fail.
     */
    fun npuTestOn(caps: DeviceProbe.Caps): Boolean =
        isDit && family != Family.WAN && caps.ditNoHmx && caps.arch >= ModelCatalog.DIT_V73_MIN_ARCH

    /**
     * ⭐ A TRUE inpainting checkpoint — a 9-channel UNet whose `conv_in`
     * takes the mask, not a plain one masked by latent blending.
     *
     * ⚠ Read off [backendType] rather than a flag of its own, because that
     * string is what actually launches the backend (`--type sd15npu_inpaint`);
     * a separate boolean would be a second place for the same fact to live and
     * to disagree. `docs/MODELS.md`, `../LocalDream/docs/INPAINT.md`.
     */
    val isInpaint: Boolean get() = backendType.contains("inpaint")

    /** ⚠ [resolutions] is never empty; the constructor default is one entry. */
    val native: Res get() = resolutions.first()

    /** ⭐ Where a node starts — [startRes], else [native]. */
    val bornAt: Res get() = startRes ?: native

    /**
     * ⭐⭐ **What a new prompt node opens on for this checkpoint** — its own
     * text, or its family's general-purpose one when it has none.
     *
     * ⚠⚠ Every reader of [prompt] as a STARTING POINT wants this one.
     * [prompt] is raw catalogue data and is empty for an imported model, which
     * is how an import ended up opening a blank prompt box while every built-in
     * opened on upstream's text. ⚠ [modelPromptRetarget] still reads [prompt]
     * as well, because it has to recognise the OLD text to decide whether it may
     * overwrite it.
     */
    val starterPrompt: String get() = prompt.ifBlank { family.prompt }

    /** ⚠ See [starterPrompt]; both fields answer the same way or neither does. */
    val starterNegative: String get() = negative.ifBlank { family.negative }

    /**
     * ⭐⭐ The best build this phone can actually load, or **null** when it can
     * load none.
     *
     * ⚠ Null is a real answer and the UI must show it as one: SDXL publishes
     * `_8gen3` only, so on anything below a v75 HTP there is no build at all —
     * not a slower one, none. Falling back to "download the biggest and hope"
     * is how a user spends 3.5 GB on a file their chip rejects at load.
     */
    fun buildFor(caps: DeviceProbe.Caps): Build? {
        // ⭐ The GPU route ([DitGpu]): the GGUF Q4_0 build whatever the NPU's arch, the format the Adreno kernels run
        // (no FP8 there), and the one a v69 phone, which no DiT build here targets, can render at all.
        if (gpu && DitGpu.wanted(caps)) gpuBuild()?.takeIf { it.ramOk(caps) }?.let { return it }
        return builds.firstOrNull { it.runsOn(caps) }?.takeIf { !chipBlocked(caps) }
    }

    /** ⚠ The smallest build whose DiT is a GGUF (`dit.gguf`): Q4_0 for Klein 4B and Z-Image. */
    private fun gpuBuild(): Build? =
        builds.filter { b -> b.files.any { it.name == "dit.gguf" } }.minByOrNull { it.bytes }

    /**
     * ⭐ This chip cannot run this model in any way we have: Iris 3B Turbo on a [DeviceProbe.DIT_NO_HMX_SOCS] chip.
     * ⚠⚠ Why (an 8s Gen 4's ⋮ Test the NPU, 1.6.195, 2026-10-18, `docs/DEVICES.md` §3b): noise in every leg, and with
     * HMX off it aborts (its BF16 rows, [noHmxOn]); made to run, HVX alone would be ~an hour a picture. So [buildFor]
     * offers no build (dimmed, install refused), an installed copy is told so, and its launch is refused
     * ([BackendProcess]) except under Diagnose. Lift it if an engine makes HMX right there (the HMX self-test).
     */
    fun chipBlocked(caps: DeviceProbe.Caps): Boolean = (family == Family.IRIS && caps.ditNoHmx) || noEngineFor(caps)

    /**
     * ⭐ A DiT model on an NPU the engine has no skel for. ⚠ v85 (the 8 Elite Gen 6) was the case (Hexagon SDK 6.6
     * lacked its HVX intrinsics) until the v85 skel, built with SDK 6.7 ([BackendProcess.DIT_SKEL_ARCHES]); a pack
     * arch with no skel is still dimmed, with this reason.
     */
    fun noEngineFor(caps: DeviceProbe.Caps): Boolean =
        isDit && caps.arch in DeviceProbe.PACK_ARCHES && caps.arch !in BackendProcess.DIT_SKEL_ARCHES

    /**
     * ⭐ The files THIS phone installs and runs: the [buildFor] build's own list
     * ([Build.files]) when it has one, else [files]. ⚠ Every reader of a DiT
     * package's files reads this — `missing`, `fetchBytes`, the installer — or a
     * 16 GB phone would be told its Q4_0 Qwen is installed while FP8 is its build.
     */
    val active: List<RemoteFile>
        get() = buildFor(DeviceProbe.caps())?.files?.takeIf { it.isNotEmpty() } ?: files

    /** ⭐ [partsFrom]'s folder beside [modelDir], or null. */
    fun donorDir(modelDir: File): File? = partsFrom?.let { File(modelDir.parentFile, it) }

    /** ⭐ [ownLora] on disk: this model's own copy, else its donor's (a MOSS import's speed-up). Null when neither. */
    fun ownLoraFile(modelDir: File): File? = ownLora?.let { name ->
        File(modelDir, name).takeIf { it.isFile } ?: donorDir(modelDir)?.let { File(it, name) }?.takeIf { it.isFile }
    }

    /**
     * ⭐ Whether [f] is served by the donor ([partsFrom]) rather than [modelDir].
     *
     * ⚠⚠ Only a file the donor's catalogue entry lists from the SAME url: MOSS Base's DiT and LoRA have the same
     * names AND sizes as SFT's (3,217,359,072 and 1,189,176,800 bytes), so a name-and-size match called Base
     * installed with nothing to fetch on any phone with SFT, and its launch found no folder (Saved #6990, 1.6.208).
     */
    fun borrows(modelDir: File, f: RemoteFile): Boolean =
        File(modelDir, f.name).length() != f.bytes &&
            partsFrom?.let { ModelCatalog.byId(it) }?.active?.any { it.name == f.name && it.url == f.url } == true &&
            donorDir(modelDir)?.let { File(it, f.name).length() == f.bytes } == true

    /** ⭐ What an install must fetch: [active] less what the donor holds whole. */
    fun toFetch(context: Context): List<RemoteFile> = ownDir(context).let { d -> active.filterNot { borrows(d, it) } }

    /**
     * ⭐ The RAM this model needs when RAM is the ONLY reason [buildFor] is null
     * — so the row can say "needs a 16 GB phone" instead of blaming the chip.
     * 0 when the chip is the problem, or nothing is.
     */
    fun ramNeeded(caps: DeviceProbe.Caps): Long =
        if (buildFor(caps) != null) 0L
        else builds.filter { it.copy(minRamBytes = 0L).runsOn(caps) }
            .minOfOrNull { it.minRamBytes } ?: 0L

    /**
     * ⚠ The PREFERRED build, for anything that must name one without a device.
     *
     * ⚠⚠ **Nullable**, and it must stay that way: a [CustomModels] entry has no
     * builds at all, and this was `builds.first()` — which threw from the
     * delete confirmation the moment an imported model could be deleted. A
     * caller that wants a download size has to handle "there isn't one".
     */
    val best: Build? get() = builds.firstOrNull()

    fun url(b: Build): String = baseUrl + b.archive

    /**
     * ⭐⭐ Where this model's files are: [home] if it has one; else this app's own folder — unless
     * that copy is not complete and Local Dream's is ([LocalDreamModels], used in place).
     * ⚠ Our own copy wins whenever it is complete, and an incomplete one of ours with no complete
     * Local Dream copy stays ours, so Repair and Delete still act on our folder.
     */
    fun dir(context: Context): File {
        home?.let { return File(it) }
        val own = ownDir(context)
        if (missingIn(own).isEmpty()) return own
        val ld = LocalDreamModels.dirFor(id) ?: return own
        return if (missingIn(ld).isEmpty()) ld else own
    }

    /** ⚠ This app's folder for the model, whether or not the files are there. */
    fun ownDir(context: Context): File = File(ModelCatalog.root(context), id)

    /** ⭐ Its files are Local Dream's — shown as such, used in place, never deleted from here. */
    fun fromLocalDream(context: Context): Boolean = LocalDreamModels.owns(dir(context))

    /** Required files that are absent — i.e. why the backend cannot start. */
    fun missing(context: Context): List<String> = missingIn(dir(context))

    private fun missingIn(d: File): List<String> {
        // ⚠⚠ A plain-file package is downloaded IN PLACE, so an interrupted
        // download leaves a real file of the right name and the wrong size.
        // Existence alone would call a half-fetched 4 GB DiT installed.
        if (active.isNotEmpty()) {
            // ⭐⭐⭐ **…unless the user brought their own DiT weights.**
            //
            // ⭐⭐ A DiT family loads a plain `.safetensors` at run time — there
            // is no context binary and no conversion (`docs/ROADMAP.md` §2b) — so
            // swapping [DIT_WEIGHTS] for a community fine-tune of the same
            // architecture is a supportable thing to do. The exact-size check
            // above is what stops it: any other file reads as "not installed"
            // forever and the Run is refused before the engine sees it.
            //
            // ⚠⚠ The marker is the whole mechanism, and it is deliberately
            // NOT "skip the check when the size differs". That would quietly
            // accept a half-downloaded 6 GB file, which is the exact failure the
            // check exists for. A marker is written on purpose, by an import or
            // by hand, and says *this one file is mine now*.
            //
            // ⚠ The other files keep their exact sizes unless they are a GGUF its
            // header proves whole (below): the VAE and tokenizer are OURS, shared by
            // every model of the family; a text encoder does get swapped by users.
            // ⭐⭐ …and a GGUF of another size that its own header proves whole
            // ([GgufHeader.isComplete]): users do replace the text encoder after all
            // (Telegram #6290, #6310: a modified `llm.gguf` read "missing, repair 0 MB").
            // ⚠ The proof is the header, not the size: a half-download still ends short.
            val byo = File(d, BRING_YOUR_OWN).exists()
            return active.filter {
                val f = File(d, it.name)
                val len = f.length()
                when {
                    byo && it.name == DIT_WEIGHTS -> len <= 0L
                    len == it.bytes -> false
                    // ⭐ A part the donor model holds whole ([partsFrom]).
                    borrows(d, it) -> false
                    it.name.endsWith(".gguf", ignoreCase = true) && len > 0L -> !GgufHeader.isComplete(f)
                    else -> true
                }
            }.map { it.name }
        }
        // ⭐⭐⭐ A DiT package may keep its shared parts BESIDE the models.
        //
        // ⚠⚠⚠ This must agree with the backend, which resolves the same three
        // files the same way (`ditFile()` in `main.cpp`, `backend-patches/010`).
        // Two answers here is the worst outcome available: the app calls a
        // model installed and the backend cannot open it, or the reverse hides
        // a model that would have run.
        // ⚠ DiT only. An SD model whose own tokenizer went missing must read
        // as broken, not borrow one.
        val shared = File(d.parentFile, CustomModels.DIT_SHARED)
        // ⭐ …or the donor's folder ([partsFrom]): a MOSS import beside the built-in (`CustomModels.PARTS_FROM_FILE`).
        val donor = donorDir(d)
        return requiredFiles.filter {
            !File(d, it).exists() && !(usesDitShared && File(shared, it).exists()) &&
                !(donor != null && File(donor, it).isFile)
        }
    }

    /**
     * ⚠ Whether [CustomModels.DIT_SHARED] may stand in for a missing part. NOT
     * a package with [ownDitParts]: the shared parts are FLUX.2/Z-Image's
     * Qwen3-4B encoder and VAE, and the backend reads Qwen's and Krea 2's own
     * directory only (`backend-patches/013`, `014`) — the two answers must be
     * the same one.
     */
    private val usesDitShared: Boolean get() = isDit && !ownDitParts

    companion object {
        /** The DiT weights inside a DiT package — the one file a user may replace. */
        const val DIT_WEIGHTS = "dit.safetensors"

        /**
         * ⭐ Present in a model directory: [DIT_WEIGHTS] here is the user's, so
         * its size is not ours to check. Empty; only its existence means anything.
         */
        const val BRING_YOUR_OWN = "custom-dit"
    }

    /**
     * Whether the extractor keeps an archive entry of this basename.
     *
     * ⚠ Per-spec rather than one catalogue-wide rule, for the same reason
     * [requiredFiles] is. ⚠ `vae_encoder.bin` is kept although it is not in
     * every family's REQUIRED list upstream: img2img is a graph edge here.
     *
     * ⭐ Resolution patches are kept although v1 cannot use them (§5.2 pins one
     * context key, and `--patch` binds at backend launch). They are ~80 MB
     * against a ~1.2 GB install; the alternative when resolution support lands
     * is re-downloading a gigabyte to obtain them. ⚠ SDXL has none — its graphs
     * are compiled at a fixed 1024.
     *
     * ⚠⚠ [IpAdapter.TARGETS_FILE] too — the Swap v2 marker is OPTIONAL (a v1 model
     * installs without it), so it is not in [requiredFiles], and the first v2
     * download dropped it here: the model installed, rendered, and refused every
     * reference picture as "converted before IP-Adapter" (2026-09-30, the phone).
     */
    fun wanted(name: String): Boolean =
        name in requiredFiles || name == "vae_encoder.bin" || name.endsWith(".patch") ||
            name == IpAdapter.TARGETS_FILE || name == ModelFeatures.FILE

    fun installed(context: Context): Boolean = missing(context).isEmpty()

    /**
     * ⭐ Some of its files are here and some are not — a model damaged or
     * half-moved, not one never downloaded. ⚠ `.part` files do not count: a
     * download that never finished is still "not installed".
     */
    fun partial(context: Context): Boolean =
        !installed(context) &&
            dir(context).listFiles().orEmpty().any { it.isFile && !it.name.endsWith(".part") && it.length() > 0 }

    /**
     * ⭐⭐ What a Download or Repair will FETCH. A plain-file package (the DiT
     * families) fetches only what is absent or short — [ModelInstaller] skips
     * a file already at full size; an archive is fetched whole, because it is
     * one zip. The user's call, 2026-09-27: repair names what is missing and
     * keeps the whole-archive download.
     */
    fun fetchBytes(context: Context, build: Build?): Long =
        if (active.isNotEmpty()) {
            // ⚠⚠ The installer's own list and folder ([toFetch], [ownDir]): summing every [active]
            // file against [dir] counted the parts a donor lends ([partsFrom]), so MOSS Base beside SFT
            // promised ~5.6 GB more than it fetched, and the free-space story disagreed with the bar.
            val own = ownDir(context)
            toFetch(context).sumOf { f ->
                val have = File(own, f.name).takeIf { it.isFile }?.length() ?: 0L
                (f.bytes - have).coerceAtLeast(0L)
            }
        } else build?.bytes ?: 0L

    /**
     * Whether this model's `unet.bin` is a 512 base that resolution patches
     * apply to.
     *
     * ⚠ SDXL's graphs are compiled at a fixed 1024 and ship no patches at all;
     * ControlNet is a frozen 512 graph. Only the SD 1.5 NPU builds take one —
     * the 9-channel inpaint UNet included, which the backend patches exactly
     * as it does `sd15npu`.
     */
    val servesPatches: Boolean
        get() = (backendType == ModelCatalog.SD15_NPU || backendType == ModelCatalog.SD15_NPU_INPAINT) &&
            // ⚠ Swap launches as `sd15npu` too, and its template ships no patches.
            family != Family.SD15_SWAP

    /**
     * ⭐ Families whose graphs are frozen at one square size and which reach a
     * non-1:1 output through [ModelCatalog.aspectTarget] instead of a patch.
     *
     * ⚠ The backend's own condition is `sdxl || anima`, and it *forces* the
     * canvas for both — so this is the set for which a size widget is a lie and
     * an aspect chip is the truth.
     */
    val fixedCanvas: Boolean
        get() = family == Family.SDXL || family == Family.SDXL_SWAP || family == Family.ANIMA

    /**
     * ⭐⭐ The patch file [res] needs, or null when it needs none.
     *
     * ⚠⚠ **Null is ambiguous on its own and the caller must not treat it as
     * "fine, carry on".** It means either "this is the 512 base" or "this model
     * takes no patches" — both legitimate — but a patch that is *wanted and
     * absent* must NOT reach here as null. [missingPatch] is the question a
     * launch has to ask; upstream and DreamUI both answer it with a warning and
     * a silent fall back to 512, which is the same wrong-size-reported-as-success
     * shape `SDXL_NPU_RES` documents.
     */
    fun patchFor(context: Context, res: Res): File? {
        if (!servesPatches) return null
        val name = res.patchName ?: return null
        return File(dir(context), name).takeIf { it.exists() }
    }

    /** The patch [res] needs and does not have, or null when the launch can proceed. */
    fun missingPatch(context: Context, res: Res): String? {
        if (!servesPatches) return null
        val name = res.patchName ?: return null
        return name.takeIf { !File(dir(context), it).exists() }
    }

    /**
     * ⭐ The resolutions this model can actually serve **right now**.
     *
     * ⚠⚠ **Discovered from the files on disk, not declared.** Offering a size
     * whose patch is absent would launch the backend against a `unet.bin` that
     * cannot serve it, and upstream's answer to that is a silent 512. A
     * checkpoint whose archive shipped fewer patches therefore offers fewer
     * sizes instead of offering one that fails — the same reasoning
     * [DeviceProbe]-gated builds use, applied to graph shapes.
     *
     * ⚠ Before install this is just [native]: there are no files to scan yet.
     *
     * ⚠⚠⚠ **A LIST, and a DiT model's answer is not one** — ask [serves]
     * instead of this whenever the question is "can it render X". A DiT size is
     * a request field on a 64-px grid, so its legal set is 625 pairs; this
     * returns [native] alone for that family, which is the one size a caller
     * wanting a representative can use. Enumerating the grid here would put 625
     * chips in a dropdown and 625 lines in `res_list`.
     */
    fun availableResolutions(context: Context): List<Res> {
        // ⭐⭐ A DiT model serves every pair on its grid and needs no file for
        // any of them: the size is a request field, not a `--patch`
        // ([backendContextKey]). ⚠ [native] is untouched — it stays
        // [ModelCatalog.DIT_RES], because that is what the context key is
        // pinned to and what a new node is born at.
        // ⭐ ...except a [fixedSizes] DiT (Iris), which serves exactly its list.
        if (isDit) return if (fixedSizes) resolutions else listOf(native)
        if (!servesPatches) return resolutions
        val found = dir(context).listFiles().orEmpty()
            .filter { it.isFile }
            .mapNotNull { Res.fromPatch(it.name) }
        return (listOf(ModelCatalog.SD15_NPU_RES) + found)
            .distinct()
            .sortedWith(compareBy({ it.area }, { it.width }))
    }

    /**
     * ⭐⭐⭐ **Can this model render [res]? — the ONE authority**, asked by the
     * size control, the harness op, the workflow-adopt path and
     * [SelectedModel.setRes].
     *
     * ⚠⚠ It exists because the two families answer by different *kinds* of
     * rule: a patched family serves a LIST discovered on disk, a DiT family
     * serves a GRID computed by arithmetic. Five call sites each testing
     * `res in availableResolutions(ctx)` was fine while every family answered
     * with a list; the moment one of them stopped, that idiom refused every
     * legal DiT size but one. ⇒ Ask this, never the list.
     */
    fun serves(context: Context, res: Res): Boolean =
        if (isDit && !fixedSizes) ModelCatalog.ditSupported(res.width) && ModelCatalog.ditSupported(res.height)
        else res in availableResolutions(context)

    /**
     * ⭐ What to tell someone whose size was refused — the same split as
     * [serves], so a refusal never reads like a list the user could have
     * picked from when the real rule is a range.
     */
    fun sizesServedText(context: Context): String =
        if (isDit && !fixedSizes) "any size ${ModelCatalog.DIT_MIN}–${ModelCatalog.DIT_MAX} in ${ModelCatalog.DIT_STEP}-pixel steps"
        else availableResolutions(context).joinToString(", ")

    /** What this model occupies on disk, or 0 when it is not installed. */
    fun bytesOnDisk(context: Context): Long =
        dir(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * ⭐⭐⭐ **What a LAUNCH of this model brings into memory** — which is
     * not what its directory occupies.
     *
     * ⚠⚠⚠ The two differ by 2.6 GB for an IMPORTED DiT package, and that
     * gap is enough to flip a decision. Measured 2026-09-21: a bring-your-own
     * Z-Image holds only `dit.safetensors` (6.16 GB) in its own directory and
     * borrows the text encoder, VAE and tokenizer from [CustomModels.DIT_SHARED],
     * so [bytesOnDisk] reported 6.16 GB where the built-in reports 8.76 GB. The
     * residency gate releases a checkpoint over 65% of device RAM — 7.6 GB on
     * an 11.7 GB phone — so the built-in was released between runs and the
     * import, which loads exactly the same weights, was not. **Second run:
     * killed by lmkd while preparing the 5.87 GB of params.** The shared
     * directory saved disk and quietly lied about memory.
     *
     * ⚠ [bytesOnDisk] is still the right answer for "what does this occupy"
     * and "what would deleting it free" — the two questions are genuinely
     * different here, so they are two functions rather than one compromise.
     *
     * ⚠⚠ Counts a shared part only when this model does NOT have its own
     * copy, matching how the backend resolves each file (`ditFile()` in
     * `main.cpp`, `backend-patches/010`) and how [missing] decides it is there.
     */
    fun loadedBytes(context: Context): Long {
        val own = bytesOnDisk(context)
        val d = dir(context)
        // ⚠⚠ …and a part the DONOR lends ([partsFrom], the same [borrows] rule [missingIn] and
        // [toFetch] use): MOSS Base beside SFT reads SFT's 5.38 GB `llm.gguf`, and counting only
        // its own folder told the residency gate ~4.4 GB for a ~10 GB load (Wan Fun's umT5 alike).
        // ⚠ A custom spec has no [active] files to match by url: a MOSS import counts what its folder lacks and the donor holds.
        val lent = if (isCustom) requiredFiles.filter { !File(d, it).exists() }.sumOf { n -> donorDir(d)?.let { File(it, n).length() } ?: 0L }
            else active.filter { borrows(d, it) }.sumOf { f -> donorDir(d)?.let { File(it, f.name).length() } ?: 0L }
        if (!usesDitShared) return own + lent
        val shared = File(d.parentFile, CustomModels.DIT_SHARED)
        return own + lent + requiredFiles
            .filter { !File(d, it).exists() }
            .sumOf { File(shared, it).length() }
    }
}

object ModelCatalog {

    /**
     * The `--type` and native size of v1's only family, named once.
     *
     * ⚠ Also [ModelSpec]'s constructor default, so a new catalogue entry is
     * SD 1.5 on the NPU at 512² unless it says otherwise.
     */
    const val SD15_NPU = "sd15npu"
    val SD15_NPU_RES = Res(512, 512)

    /**
     * ⭐ An SD 1.5 checkpoint whose UNet takes 9 channels — [SD15_NPU]'s layout
     * with an inpainting `conv_in`. ⚠ A different `--type`, so a different
     * context key: a graph mixing it with a plain SD 1.5 model relaunches
     * between them like any two checkpoints.
     */
    const val SD15_NPU_INPAINT = "sd15npu_inpaint"

    /**
     * The same pair for SDXL, named once for the same reason.
     *
     * ⚠⚠ 1024 is **not** a default that can be overridden: the backend forces
     * `width = height = 1024` inside `parseGenerationRequest` for `--type sdxl`
     * whatever the client sends, so a graph asking for 512 renders 1024 and
     * reports success.
     *
     * ⚠ These were literals inside [sdxl] until custom models needed them too.
     * A second copy of `"sdxl"` is exactly the hazard [backendTypeOf] documents
     * — a node keyed one way against a process launched the other does not
     * fail, it renders at the wrong size in silence.
     */
    const val SDXL_NPU = "sdxl"
    val SDXL_NPU_RES = Res(1024, 1024)

    /**
     * ⭐ SDXL on the CPU (MNN): upstream's `--type sdxlmnn` (backend 036), an IMPORT only (GitHub #58: Fancy AI /
     * Local Dream's `_mnn` zips). The same family, nodes and fixed 1024² canvas as [SDXL_NPU]; runs on any chip.
     */
    const val SDXL_MNN = "sdxlmnn"

    /** ⭐ What an SDXL MNN package holds (upstream's layout; the `.weight` files are the weights). */
    val SDXL_MNN_REQUIRED = listOf(
        "tokenizer.json",
        "clip.mnn", "pos_emb.bin", "token_emb.bin",
        "clip_2.mnn", "clip_2.mnn.weight", "pos_emb_2.bin", "token_emb_2.bin",
        "unet.mnn", "unet.mnn.weight", "vae_decoder.mnn", "vae_encoder.mnn",
    )

    /**
     * ⭐ The same pair for Anima. ⚠ 1024 is forced by the backend exactly as it
     * is for SDXL — `RequestParser.hpp` guards on `sdxl || anima`.
     */
    const val ANIMA_NPU = "anima"
    val ANIMA_NPU_RES = Res(1024, 1024)

    /** ⭐ The DiT engine's `--type`s (backend-patches/007). */
    const val KLEIN = "klein"
    const val ZIMAGE = "zimage"
    /** ⭐ Qwen Image 2.1 (upstream local-dream 440899f, backend-patches/013). */
    const val QWEN21 = "qwen21"
    /** ⭐ Krea 2 Turbo (the fork's e70d89b, backend-patches/014). */
    const val KREA2 = "krea2"
    /** ⭐ Iris 3B Turbo (backend-patches/035): Krea 2's own-files launch with no VAE file. */
    const val IRIS = "iris"

    /** ⭐ MOSS-Image's own LoRA, in its folder ([ModelSpec.ownLora]). */
    const val MOSS_LORA = "dmd8.safetensors"

    /** ⭐ Anima 2.9B's own LoRA ([ModelSpec.ownLora]): Karely_ai's turbo, 8 steps at CFG 1 (GitHub #55). */
    const val ANIMA29_TURBO_LORA = "anima29_turbo.safetensors"

    /** ⭐ Ideogram 4's own LoRA, in its folder ([ModelSpec.ownLora]): ostris' TurboTime, its few-step distillation. */
    const val IDEOGRAM_LORA = "turbotime.safetensors"

    /**
     * ⭐⭐ Iris 3B Turbo's training buckets, square first (the model card): its ONLY sizes
     * ([ModelSpec.fixedSizes]). Multiples of 16, which is all the engine needs; most are off [DIT_STEP]'s grid.
     */
    val IRIS_SIZES = listOf(
        Res(1024, 1024), Res(896, 1200), Res(1200, 896), Res(896, 1344), Res(1344, 896),
        Res(752, 1344), Res(1344, 752),
    )
    /** ⚠ The size a NEW node starts at, and the context key's constant size. */
    val DIT_RES = Res(1024, 1024)
    /**
     * ⭐ Upstream's `DitResolution`: any size 512–2048, in **64**-px steps.
     *
     * ⚠⚠ **This said 256 until 2026-09-21, and it was simply wrong about
     * upstream.** `local-dream/.../data/DitResolution.kt` is `SIZE_STEP = 64`,
     * and its own comment says the models need only multiples of 16 — 64 is
     * upstream's slider-usability choice, not an engine limit. The 256 here was
     * never measured against anything; it was a guess that then had a whole
     * size vocabulary built on top of it.
     *
     * ⚠ 64 rather than 16, deliberately, and that is the one deviation from
     * "what the engine needs": it is upstream parity (`CLAUDE.md`, *follow
     * local-dream's implementation as much as possible*), and 16 makes a slider
     * a precision instrument for a choice nobody makes that finely.
     *
     * ⚠ [DIT_MIN] is itself a multiple of [DIT_STEP], which is what lets
     * [ditSnap] offset from it and still land on upstream's grid exactly.
     */
    const val DIT_MIN = 512
    const val DIT_MAX = 2048
    const val DIT_STEP = 64

    /**
     * ⭐ The grid, as ONE function — the sampler snaps a request to it and the
     * size control only ever writes values already on it.
     *
     * ⚠ It lived as a local `snap` inside `SdSampler.runDit`, which made the
     * control and the render two places that each decided what a legal DiT size
     * is. Two such places stop agreeing, and the symptom is a picture that comes
     * back a different size from the one the sheet says.
     */
    fun ditSnap(v: Int): Int =
        (((v - DIT_MIN) + DIT_STEP / 2) / DIT_STEP * DIT_STEP + DIT_MIN)
            .coerceIn(DIT_MIN, DIT_MAX)

    /**
     * ⭐⭐ The size a node KEEPS when its model changes with "keep my settings" (GitHub #33, the
     * user's call 2026-10-15): [w]x[h] held, pulled inside what [spec] renders. A DiT model takes any
     * size on its grid; a fixed-size model (SD 1.5, SDXL on the NPU) takes the listed size nearest
     * that shape, then nearest that area, because it renders its own size whatever is asked.
     */
    fun keptSize(spec: ModelSpec, w: Int, h: Int): Res {
        if (spec.isDit && !spec.fixedSizes) return Res(ditSnap(w), ditSnap(h))
        val aspect = w.toDouble() / h.coerceAtLeast(1)
        val area = w.toDouble() * h
        return spec.resolutions.minWithOrNull(
            compareBy<Res>({ Math.abs(Math.log(it.width.toDouble() / it.height / aspect)) }, { Math.abs(Math.log(it.width.toDouble() * it.height / area)) })
        ) ?: spec.native
    }

    /**
     * ⭐ Is [v] already on the grid? — upstream's `DitResolution.isSupported`.
     *
     * ⚠ [ditSnap] is the fixer and this is the test; a caller that wants to
     * REFUSE a stored size asks this, one that wants to repair it calls that.
     * Rewriting a saved workflow's size silently is the thing the 2026-09-19
     * "picture came back a different size" report was about.
     */
    fun ditSupported(v: Int): Boolean =
        v in DIT_MIN..DIT_MAX && (v - DIT_MIN) % DIT_STEP == 0

    /**
     * ⭐⭐⭐ **The size nearest [aspect] that a DiT model can render** — both
     * edges on the grid, area held near [around].
     *
     * ⚠⚠⚠ **This is NOT [aspectTarget] and must never be routed through it.**
     * That one means "render the square canvas the graph is frozen at, then crop
     * to this ratio", which is what SDXL and Anima do and what
     * [ModelSpec.fixedCanvas] gates. A DiT model has no fixed canvas — it
     * renders whatever width and height it is handed — so this *chooses* the
     * width and height and there is no crop at all.
     *
     * ⚠⚠ **It replaced a table of hand-picked pairs (`DIT_SHAPES`), and the
     * table is what the 2026-09-21 report was about**: on a 256-px grid `4:3`
     * could only offer 1024x768 or 2048x1536, so a person wanting a 4:3 picture
     * at an ordinary size had 1280x960 — legal upstream, legal on the engine —
     * nowhere to reach. A table cannot hold 625 pairs; arithmetic can.
     *
     * ⚠⚠ **The rule is RATIO first, area only as the tie-break** — and on
     * this grid the tie is usually real, because 4:3 is exact at 1024x768,
     * 1280x960, 1536x1152 and 2048x1536. Ranking by area first instead picks
     * 1216x896 for a 4:3 photo: 1.8% off the ratio, closer in pixels, and a
     * size no one recognises. ⚠ 16:9 is where that costs something: `w = 16k,
     * h = 9k` with both on a 64-px grid forces k to a multiple of 64, so the
     * only exact pairs are 1024x576 and 2048x1152 and a node at 1024² drops to
     * 576 tall. That is the honest answer — the long edge is held and the
     * picture really is 16:9 — and the sliders are right there for anyone who
     * wants 1344x768 (1.75) instead.
     */
    /** ⚠ The most [ditFit] may grow a node's area: a new picture or ratio changes the shape, not the cost. */
    const val DIT_FIT_MAX_GROW = 1.25

    fun ditFit(aspect: Float, around: Res): Res {
        if (!aspect.isFinite() || aspect <= 0f) return around
        val want = kotlin.math.ln(aspect.toDouble())
        val area = (around.width.toDouble() * around.height).coerceAtLeast(1.0)
        val grid = (DIT_MIN..DIT_MAX step DIT_STEP).toList()
        // ⚠⚠ Every pair on the grid, scored by RATIO first. 25 candidates —
        // one per width, each with the single height nearest the ratio, because
        // a second height on the same width is strictly further from it.
        // ⚠⚠⚠ …but never more than [DIT_FIT_MAX_GROW] times the area the node renders. Ratio-first with no cap
        // sent an odd-shaped photo to the ONE pair that hits its exact ratio: a 1600x1536 picture (25:24) moved a
        // FLUX.2 edit set to 640x1152 to 1600x1536, 3.3x the pixels, and lmkd reaped the app at sampling (the
        // user's report 2026-10-23, "i definitely used smaller resolutions"). A picture changes the SHAPE; the
        // size stays the person's. 1.25 keeps the aspect picker's 4:3 at 1280x960 and 3:2 at 1344x896 from 1024².
        val candidates = grid.map { w -> Res(w, ditSnap((w / aspect).toInt())) }
            .let { all -> all.filter { it.width.toDouble() * it.height <= area * DIT_FIT_MAX_GROW }.ifEmpty { all } }
        // ⚠ Log ratio, matching `HarnessViewModel.autoFraming`'s `far`: it makes
        // 2:3 and 3:2 equally far from 1:1, which a plain difference does not.
        fun err(r: Res) = kotlin.math.abs(kotlin.math.ln(r.width.toDouble() / r.height) - want)
        val best = candidates.minOf(::err)
        // ⭐⭐ **Area breaks the tie, and on this grid the tie is usually real.**
        // 4:3 is EXACT at 1024x768, 1280x960, 1536x1152 and 2048x1536, so
        // picking the first would hand back a quarter of the pixels the node was
        // rendering. ⚠ The epsilon admits pairs that are equally good rather
        // than exactly equal — float error alone would split 1280x960 from
        // 1536x1152 and make the choice depend on rounding.
        return candidates
            .filter { err(it) <= best + 1e-4 }
            .minByOrNull { kotlin.math.abs(it.width.toDouble() * it.height - area) }
            ?: around
    }

    /** ⭐ What the backend checks for in a DiT package dir (`main.cpp`) — plus the tokenizer it loads for every type. */
    val DIT_REQUIRED = listOf("dit.safetensors", "llm.gguf", "vae.safetensors", "tokenizer.json")
    /** ⭐ Qwen Image 2.1's two DiTs — the 16 GB class's FP8 (alpha.4) and everyone else's Q4_0. */
    val QWEN21_FP8 = RemoteFile(HF + "unsloth/Qwen-Image-2.1-FP8/resolve/main/Qwen-Image-2.1-FP8.safetensors", "dit.safetensors", 7_122_877_560L)
    val QWEN21_Q4 = RemoteFile(HF + "leejet/Qwen-Image-2.1-GGUF/resolve/main/qwen_image_2.1-Q4_0.gguf", "dit.gguf", 4_197_494_816L)
    /**
     * ⭐ Qwen Image 2.1 TURBO's DiT: the 8-step distillation (Qwen, 2026-10-09) as molbal's Q4_0: the
     * same 265 tensors, shapes and Q4_0 layers as [QWEN21_Q4] (header read 2026-10-14), so a drop-in.
     * ⚠ Not Abiray's Q4_0: it splits the MLP (`gate_layer`/`proj`), a different layout. `docs/DIT.md` §9b.
     */
    val QWEN21_TURBO_Q4 = RemoteFile(HF + "molbal/Qwen-Image-2.1-Turbo-GGUF/resolve/main/qwen_image_2.1_turbo_Q4_0.gguf", "dit.gguf", 4_197_519_520L)
    /** ⭐ Qwen Image 2.1's files beside the DiT, the same for every build and for Turbo. */
    val QWEN21_SHARED = listOf(
        RemoteFile(HF + "bartowski/Qwen_Qwen3-VL-8B-Instruct-GGUF/resolve/main/Qwen_Qwen3-VL-8B-Instruct-Q4_0.gguf", "llm.gguf", 4_787_333_600L),
        RemoteFile(HF + "bartowski/Qwen_Qwen3-VL-8B-Instruct-GGUF/resolve/main/mmproj-Qwen_Qwen3-VL-8B-Instruct-f16.gguf", "llm_vision.gguf", 1_159_029_920L),
        RemoteFile(HF + "Qwen/Qwen3-VL-8B-Instruct/resolve/main/tokenizer.json", "tokenizer.json", 7_032_403L),
        RemoteFile(HF + "Comfy-Org/Qwen-Image-2.1/resolve/main/vae/qwen_image_2.1_vae_bf16.safetensors", "vae.safetensors", 675_509_688L),
    )
    /** ⭐ Qwen Image 2.1's package — a DiT of either build and the VLM's vision tower (`main.cpp`, 013). */
    val QWEN21_REQUIRED = listOf("dit.gguf", "llm.gguf", "llm_vision.gguf", "vae.safetensors", "tokenizer.json")
    /** ⭐ Qwen's parts beside its DiT, for an import whose DiT may be either format. */
    val QWEN21_PARTS = QWEN21_REQUIRED.drop(1)

    /**
     * ⭐⭐ Qwen Image 2.1's two builds share a directory, and backend 014 loads a
     * `dit.gguf` in preference to `dit.safetensors` — so the build this phone
     * does NOT run has its DiT deleted, or a stray Q4_0 would shadow FP8 on a
     * 16 GB phone and a stray FP8 would cost a 12 GB one 7 GB for nothing.
     * ⚠ Only at the EXACT size this catalogue shipped: a user's own weights in
     * that dir are theirs. ⚠ Built-in dir only: an import's DiT is its weights.
     */
    fun reclaimObsolete(context: Context) {
        val spec = byId("qwen_image_2_1") ?: return
        val want = spec.active.firstOrNull { it.name.startsWith("dit.") } ?: return
        for (other in listOf(QWEN21_FP8, QWEN21_Q4)) {
            if (other.name == want.name) continue
            val f = File(spec.ownDir(context), other.name)
            if (f.isFile && f.length() == other.bytes && f.delete()) {
                android.util.Log.i("ModelCatalog", "reclaimed Qwen Image 2.1 ${other.name}. This phone's build is ${want.name}")
            }
        }
    }

    /** ⭐ Krea 2 Turbo's package — a GGUF DiT, no vision tower (`main.cpp`, 014). */
    val KREA2_REQUIRED = listOf("dit.gguf", "llm.gguf", "vae.safetensors", "tokenizer.json")

    /**
     * ⭐⭐ Non-square output on a family whose graphs are frozen at 1024.
     *
     * ⚠⚠ **`aspect_ratio` is a DIFFERENT FEATURE from resolution, wearing a
     * similar name** (`docs/ROADMAP.md` §Resolution). A resolution patch
     * changes what the UNet graph *is* and binds at launch; this changes
     * nothing about the graph. The backend renders the full 1024² canvas and
     * reaches a non-1:1 output by inpainting a centered rectangle into it
     * (`RequestParser.hpp`, `aspect_pad_inpaint`), then cropping that rectangle
     * back out. ⇒ It is a REQUEST field: no patch, no relaunch, and **it is not
     * part of the context key**.
     *
     * ⚠ `1:1` is the identity and is deliberately first: the backend ignores an
     * `aspect_ratio` whose terms are equal, so sending it is the same as
     * sending nothing.
     *
     * ⭐ Squarest first. 9:7 and 5:3 were a tester's ask (2026-10-16, the user: both).
     */
    val ASPECTS = listOf("1:1", "9:7", "7:9", "4:3", "3:4", "3:2", "2:3", "5:3", "3:5", "16:9", "9:16")

    const val DEFAULT_ASPECT = "1:1"

    /** ⚠ Pure: `w:h` as a number, or null when it is not one. */
    fun ratioValue(ratio: String): Float? {
        val (a, b) = ratio.split(':').takeIf { it.size == 2 } ?: return null
        val w = a.trim().toFloatOrNull() ?: return null
        val h = b.trim().toFloatOrNull() ?: return null
        return if (w > 0f && h > 0f) w / h else null
    }

    /** ⚠ Pure: the [ASPECTS] entry [size] is (within 1%), or null: the DiT Shape row's current choice. */
    fun ratioOf(size: Res): String? {
        if (size.width <= 0 || size.height <= 0) return null
        val have = kotlin.math.ln(size.width.toDouble() / size.height)
        return ASPECTS.firstOrNull { r ->
            ratioValue(r)?.let { kotlin.math.abs(kotlin.math.ln(it.toDouble()) - have) < 0.01 } == true
        }
    }

    /**
     * ⚠⚠ **A SECOND COPY of arithmetic that lives in C++, and it has to be
     * exact.** `RequestParser.hpp` computes the target rectangle and
     * `Pipeline.hpp` crops to it; on the decomposed op path the crop never runs
     * (see [VaeDecodeNode]), so the app repeats the sum to know what to cut.
     * The two drifting apart is an off-centre or wrongly-sized picture with
     * nothing reporting it.
     *
     * ⇒ Mirrors the backend line for line: long edge pinned to the canvas, the
     * short edge floored to a multiple of 8, a floor of 8, and **equal terms
     * meaning "no crop"** — the C++ guards on `!(rw == rh)`.
     *
     * Returns null when [ratio] is not `w:h`, has a non-positive term, or is
     * square. All three are "render the plain canvas", which is what the
     * backend does with them too.
     */
    fun aspectTarget(ratio: String, canvas: Res): Res? {
        val colon = ratio.indexOf(':').takeIf { it >= 0 } ?: return null
        val rw = ratio.substring(0, colon).trim().toIntOrNull() ?: return null
        val rh = ratio.substring(colon + 1).trim().toIntOrNull() ?: return null
        if (rw <= 0 || rh <= 0 || rw == rh) return null
        // ⚠ `canvas.width` on both branches, matching the C++ literal 1024 --
        // the backend forces a SQUARE canvas for these families, so there is
        // only one edge length in play.
        val edge = canvas.width
        return if (rw >= rh) {
            Res(edge, (((edge.toDouble() * rh) / rw).toInt() / 8 * 8).coerceAtLeast(8))
        } else {
            Res((((edge.toDouble() * rw) / rh).toInt() / 8 * 8).coerceAtLeast(8), edge)
        }
    }

    /**
     * ⭐⭐ The samplers the backend implements, and the ONLY legal values of a
     * `scheduler` param.
     *
     * ⚠⚠ **Mirrors `backend-src/src/Pipeline.hpp`'s chain of comparisons, and
     * an unknown string there falls through to `dpm` in silence** — it is not
     * rejected. So a free-text field would let a typo render with the wrong
     * sampler and report success, which is why the widget carries these as
     * [Widget.options] chips rather than as text.
     *
     * ⚠ Order is deliberate: [DEFAULT_SCHEDULER] first, then the ancestral and
     * Karras variants, then `lcm`. It is the order the chips are drawn in.
     */
    val SCHEDULERS: List<String> by lazy {
        SAMPLERS.flatMap { (id, _) -> SCHEDULES.map { (sfx, _) -> joinScheduler(id, sfx) } }.distinct()
    }

    /**
     * ⭐⭐ The samplers a FAMILY's backend actually distinguishes.
     *
     * ⚠⚠ Anima's `makeScheduler` reads exactly one bit: `euler` is the
     * deterministic flow-match sampler and EVERY other string is the ancestral
     * one. Offering the SD ids there would be many names for one sampler,
     * and Karras means nothing on flow-match. ⇒ Two, named for what they do.
     * ⭐ The DiT families take the engine's own samplers by name (backend 029,
     * GitHub #19): [DIT_SAMPLERS], and since engine ABI 108 (`dit/032`, a tester's wish
     * 2026-10-19) Karras and Exponential too ([DIT_SCHEDULERS]). ⚠ Not Wan (its video
     * request has no schedule) and not Iris (its own Turbo sigmas).
     */
    fun schedulersFor(family: Family): List<String> = when {
        family == Family.ANIMA -> listOf("euler", "euler_a")
        family == Family.WAN || family == Family.IRIS -> DIT_SAMPLER_IDS
        family.dit -> DIT_SCHEDULERS
        else -> SCHEDULERS
    }

    /** ⭐ The DiT samplers, each with the schedules ([DIT_SCHEDULES]) the engine takes since ABI 108. */
    val DIT_SCHEDULERS: List<String> by lazy {
        DIT_SAMPLER_IDS.flatMap { id -> DIT_SCHEDULES.map { (sfx, _) -> joinScheduler(id, sfx) } }.distinct()
    }

    /**
     * ⭐⭐ The SD samplers, named the way every other SD tool names them (`local-dream`'s
     * picker: names plus a schedule). ⭐ DPM++ 3M and 3M SDE since backend 029 (GitHub #19:
     * "Pony Realism's creator recommends DPM++ 3M"): the solver's third order was there and
     * never selected; the SDE branch at that order is diffusers'.
     *
     * ⚠ The stored param is still ONE wire value ([joinScheduler]), so a saved workflow, a
     * plugin manifest and a bug report all carry what the backend receives.
     */
    val SAMPLERS: List<Pair<String, String>> = listOf(
        "dpm" to "DPM++ 2M",
        "dpm_sde" to "DPM++ 2M SDE",
        "dpm3" to "DPM++ 3M",
        "dpm3_sde" to "DPM++ 3M SDE",
        "euler_a" to "Euler A",
        "euler" to "Euler",
        "lcm" to "LCM",
    )

    /**
     * ⭐⭐ The sigma SCHEDULES, as a suffix on the wire (`dpm3_sde_exponential`). "Exponential"
     * is what turbo / hyper SDXL checkpoints recommend (GitHub #19, 2026-10-14) — a schedule,
     * like Karras, not a sampler; backend 029 runs it on DPM, Euler and Euler A.
     */
    val SCHEDULES: List<Pair<String, String>> = listOf(
        "" to "Normal",
        "_karras" to "Karras",
        "_exponential" to "Exponential",
    )

    /**
     * ⭐ The DiT engine's schedules: [SCHEDULES] plus SGM Uniform and Bong Tangent (testers' wishes, 2026-10-20, the
     * user's call: every DiT model). stable-diffusion.cpp already knows both names (`str_to_scheduler`); backend 039
     * passes the suffixes through. ⚠ Not on the SD samplers: their backend has neither. ⭐ Simple and Beta too
     * (ComfyUI's names, a tester's wish for MOSS-Image, 2026-10-22): the ABI 108 engine knows both, backend 043.
     */
    val DIT_SCHEDULES: List<Pair<String, String>> = SCHEDULES + listOf(
        "_sgm_uniform" to "SGM Uniform",
        "_bong_tangent" to "Bong Tangent",
        "_simple" to "Simple",
        "_beta" to "Beta",
    )

    /**
     * ⭐ The DiT engine's samplers by its own names (stable-diffusion.cpp), the ones backend 029
     * passes through; anything else renders with Euler. Heun runs the model twice a step.
     */
    val DIT_SAMPLERS: List<Pair<String, String>> = listOf(
        "euler" to "Euler",
        "euler_a" to "Euler A",
        "heun" to "Heun (2x slower)",
        "dpm++2m" to "DPM++ 2M",
        "dpm++2m_sde" to "DPM++ 2M SDE",
        "er_sde" to "ER-SDE",
        "res_multistep" to "Res Multistep",
        "ipndm" to "iPNDM",
        "lcm" to "LCM",
    )
    val DIT_SAMPLER_IDS: List<String> = DIT_SAMPLERS.map { it.first }
    const val DIT_DEFAULT_SAMPLER = "euler"

    /**
     * ⚠ LCM has no schedule of its own to change — the backend's LCM scheduler ignores both
     * (upstream guards the same: `karrasSupported = baseId != "lcm"`), and DiT samplers take none.
     */
    fun schedulesSupported(base: String) = base != "lcm" && (SAMPLERS.any { it.first == base } || base in DIT_SAMPLER_IDS)

    /** ⚠ Kept for its callers: Karras is one of the schedules. */
    fun karrasSupported(base: String) = schedulesSupported(base)

    /** `"dpm_sde_karras"` -> `("dpm_sde", "_karras")`; a DiT id comes back whole with `""`. */
    fun splitScheduler(value: String): Pair<String, String> {
        if (value in DIT_SAMPLER_IDS && SAMPLERS.none { it.first == value }) return value to ""
        val sfx = DIT_SCHEDULES.map { it.first }.filter { it.isNotEmpty() }.firstOrNull { value.endsWith(it) } ?: ""
        val base = value.removeSuffix(sfx)
        // ⚠ An unrecognised base falls back to the default rather than being
        // passed through: the backend would silently treat it as `dpm`, and a
        // picker showing nothing selected is how that stays invisible.
        return if (SAMPLERS.any { it.first == base } || base in DIT_SAMPLER_IDS) base to sfx
        else DEFAULT_SCHEDULER to ""
    }

    /** The inverse. ⚠ Drops the schedule where the sampler has none. */
    fun joinScheduler(base: String, schedule: String): String =
        if (schedule.isNotEmpty() && schedulesSupported(base)) base + schedule else base

    /** ⚠ The old Karras-flag form, for its callers. */
    fun joinScheduler(base: String, karras: Boolean): String = joinScheduler(base, if (karras) "_karras" else "")

    /** How a wire value should read on screen, e.g. `"DPM++ 2M SDE Karras"`. */
    fun schedulerLabel(value: String): String {
        if (value in DIT_SAMPLER_IDS) DIT_SAMPLERS.firstOrNull { it.first == value }?.let {
            if (SAMPLERS.none { s -> s.first == value }) return it.second
        }
        val (base, sfx) = splitScheduler(value)
        val name = SAMPLERS.firstOrNull { it.first == base }?.second
            ?: DIT_SAMPLERS.firstOrNull { it.first == base }?.second ?: base
        val sched = DIT_SCHEDULES.firstOrNull { it.first == sfx && sfx.isNotEmpty() }?.second
        return if (sched != null) "$name $sched" else name
    }

    /**
     * ⚠⚠ **The backend's own default** — `RequestParser.hpp` reads
     * `json.value("scheduler", "dpm")`. Named here so the app's default and the
     * backend's cannot drift: before the `scheduler` field was sent at all,
     * every render in this app silently used this value, including checkpoints
     * whose author specified something else.
     */
    const val DEFAULT_SCHEDULER = "dpm"

    /**
     * ⚠ The sampler node's own defaults, named here because [ModelSpec] now
     * carries per-checkpoint overrides of them and the two must agree. 20/7.5
     * is DreamUI's default too.
     *
     * ⚠ The bounds are the widget's, and a `config.json` value outside them is
     * clamped rather than refused — an author who writes 60 steps meant "a lot",
     * and the slider cannot express it.
     */
    const val DEFAULT_STEPS = 20
    const val DEFAULT_CFG = 7.5
    val STEPS_RANGE = 1..50
    val CFG_RANGE = 1.0..20.0

    /**
     * xororz's QNN builds — the upstream local-dream publishes against.
     *
     * ⚠ **Two repositories, not one.** SDXL lives in `sdxl-qnn`; assuming one
     * base URL is how a catalogue with a second family produces 404s that look
     * like network trouble.
     */
    const val SD15_BASE_URL = "https://huggingface.co/xororz/sd-qnn/resolve/main/"
    const val SDXL_BASE_URL = "https://huggingface.co/xororz/sdxl-qnn/resolve/main/"
    const val ANIMA_BASE_URL = "https://huggingface.co/xororz/anima-qnn/resolve/main/"

    /**
     * ⚠ Every checkpoint is published as `_8gen1` / `_8gen2` / `_min`, and we
     * take `_8gen2` — the v73 context, which newer HTPs run forward.
     *
     * ⚠⚠ This is NOT yet a device decision, and it must become one. The APK
     * stages **V79 QNN libs only** (`tools/stage_backend.ps1`), so it already
     * runs on exactly one phone; shipping a `_min` archive to a chip whose
     * runtime libraries are absent would fail either way. ⇒ The archive tier and
     * the staged-library tier have to be lifted together —
     * `../LocalDream/docs/DEVICE-SUPPORT.md` has the real rule, and
     * The device-tier half is what is still open; `notes/PROGRESS.md` tracks it.
     */
    const val TIER = "_8gen2"

    /**
     * ⭐⭐ The three ways xororz publishes every SD 1.5 checkpoint, and what
     * each one demands of the HTP. Only the graph config differs — same
     * weights, same pictures.
     *
     * ⚠⚠ A QNN context runs **forward only**. An 8 Gen 1 has the full 8 MB of
     * VTCM but a **v69** HTP, so it cannot load the v73 `_8gen2` build at all;
     * before `_8gen1` was used such a chip fell all the way to `_min` and gave
     * up three quarters of its VTCM for want of an arch, at roughly 2.5× the
     * time per image.
     */
    const val TIER_8GEN1 = "_8gen1"
    const val TIER_MIN = "_min"
    private const val ARCH_8GEN2 = 73
    private const val ARCH_8GEN1 = 69
    private const val ARCH_MIN = 68

    /**
     * ⚠⚠ SDXL is published `_8gen3` ONLY — there is no `_8gen2` SDXL archive to
     * choose. That is a v75 context, so an 8 Gen 2 cannot load one at all, which
     * is why every SDXL entry carries `minHtpArch = 75` and why upstream
     * restricts SDXL to 8 Gen 3 and newer. The arch gate doubles as a rough
     * memory gate.
     */
    const val SDXL_TIER = "_8gen3"

    /**
     * What `--type sd15npu` needs before it will start.
     *
     * ⚠ SD1.5-shaped and hardcoded, which is correct exactly while every entry
     * in [all] is [Family.SD15]. It becomes per-family with the first SDXL
     * entry -- `docs/MODELS.md` §3 step 2 -- and deliberately NOT before, so
     * step 1 changes no behaviour it cannot be blamed for.
     *
     * ⚠ `vae_encoder.bin` is required HERE though DreamUI treats it as optional:
     * `vae_encode` and both img2img routes are graph edges in this app
     * (`docs/ARCHITECTURE.md` §3), so a model without it is one whose nodes fail
     * at run time rather than at install time.
     */
    val SD15_REQUIRED = listOf(
        "tokenizer.json", "clip_v2.mnn", "pos_emb.bin", "token_emb.bin",
        "unet.bin", "vae_decoder.bin", "vae_encoder.bin",
    )

    /** ⭐ SD 1.5 Swap: SD 1.5's files plus the template's LoRA target order. */
    val SD15_SWAP_REQUIRED = SD15_REQUIRED + TemplateLora.TARGETS_FILE

    /**
     * ⚠⚠ SDXL's CLIP side is **two encoders**, each with its own embedding
     * tables — not the single `clip_v2.mnn` SD 1.5 uses. And
     * `clip_2.mnn.weight` is CLIP-G's EXTERNAL weight file: MNN splits the
     * bigger encoder into graph plus weights, and `clip_2.mnn` will not load
     * without it. Dropping it leaves an install that passes every size check
     * and fails at the first prompt.
     *
     * ⚠ The list is the backend's own (`main.cpp`, `--type sdxl`), plus that
     * weight file, plus `vae_encoder.bin` — required HERE though DreamUI treats
     * it as optional, because img2img is a graph edge in this app.
     */
    val SDXL_REQUIRED = listOf(
        "tokenizer.json",
        "clip.mnn", "pos_emb.bin", "token_emb.bin",
        "clip_2.mnn", "clip_2.mnn.weight", "pos_emb_2.bin", "token_emb_2.bin",
        "unet.bin", "vae_decoder.bin", "vae_encoder.bin",
    )

    /** ⭐ SDXL Swap: SDXL's files plus the LoRA target list the packer cannot do without. */
    val SDXL_SWAP_REQUIRED = SDXL_REQUIRED + TemplateLora.TARGETS_FILE

    /**
     * ⭐⭐ A CATALOGUE SDXL Swap v2: also its 462-token marker and its feature list.
     * ⚠⚠ The installer extracts ONLY [ModelSpec.wanted] files, and without
     * `qnn_context.txt` the backend reads a 462-token UNet as xororz's 77 —
     * so a download would have been a model that cannot render. Imports keep
     * [SDXL_SWAP_REQUIRED]: they bring their own folder.
     */
    val SDXL_SWAP_V2_REQUIRED =
        SDXL_SWAP_REQUIRED + CustomModels.LONG_CONTEXT_FILE + "swap_features.json"

    /**
     * ⭐ What `--type anima` needs — the backend's own list (`main.cpp`,
     * `createPipeline`), plus `vae_encoder.bin` for the reason SDXL's has it.
     *
     * ⚠⚠ `tokenizer.json` here is QWEN's, not CLIP's; `tokenizer_t5.json` is what
     * the prompt is counted against. ⚠ No `pos_emb.bin` — the Qwen encoder uses
     * RoPE — and no patches: the DiT is compiled at 1024.
     */
    val ANIMA_REQUIRED = listOf(
        "tokenizer.json", "tokenizer_t5.json", "token_emb.bin",
        "clip.bin", "unet_part1.bin", "unet_part2.bin",
        "vae_decoder.bin", "vae_encoder.bin",
    )

    /** ⚠ Under [ModelStorage.root] — the person may have moved it to `Download/Nightmare`. */
    fun root(context: Context): File = File(ModelStorage.root(context), "models")

    /** Where a part-downloaded archive lives. ⚠ Not the model dir: a stray zip there reads as a model. */
    fun downloads(context: Context): File =
        // ⚠ Beside the models, so the finished file RENAMES into place rather
        // than copying across mounts.
        File(ModelStorage.root(context), "downloads")

    // ---- prompts ---------------------------------------------------------
    // ⭐⭐ Model parameters rather than UI copy, and **`local-dream`'s own, id
    // for id** (`../LocalDream/local-dream/.../data/Model.kt`, `codeDefaults`).
    //
    // ⚠⚠ These were DreamUI's until 2026-09-12, and DreamUI had collapsed
    // upstream's per-model text into two shared negatives and one anime prompt
    // — a simplification that costs exactly what CLAUDE.md warns DreamUI's
    // simplifications cost. It did not show while nothing READ them; now a new
    // graph opens on this text, so a checkpoint tuned for chibi opening on a
    // generic "detailed face" prompt is what the user sees first. The user's
    // call, 2026-09-12: copy upstream verbatim.
    //
    // ⚠ Verbatim also means a picture that differs from local-dream's on the
    // same checkpoint is a difference in the RUNTIME rather than in what was
    // asked for — the reason the old ones were copied from DreamUI unchanged.

    /** ⚠ Upstream's one anime negative, shared by its three SD 1.5 anime models and Illustrious. */
    /**
     * ⭐ A searchable style word for a catalogue model — the Models search box
     * finds "anime" checkpoints by the anime negative they all share, since no
     * tag list exists to search (2026-10-05).
     */
    fun styleWords(spec: ModelSpec): String = if (spec.negative == NEG_ANIME) "anime" else ""

    private const val NEG_ANIME =
        "lowres, bad anatomy, bad hands, missing fingers, extra fingers, " +
            "bad arms, missing legs, missing arms, poorly drawn face, bad face, " +
            "fused face, cloned face, three crus, fused feet, fused thigh, " +
            "extra crus, ugly fingers, horn, realistic photo, huge eyes, worst face, " +
            "2girl, long fingers, disconnected limbs,"

    /** ⚠ AnythingV5 and CuteYukiMix share it upstream; QteaMix does NOT (it is chibi). */
    private const val P_ANIME_GIRL =
        "masterpiece, best quality, 1girl, solo, cute, white hair,"
    private const val P_QTEA =
        "chibi, best quality, 1girl, solo, cute, pink hair,"

    // ⚠ A negative per photographic checkpoint, not one shared constant: upstream
    // gives AbsoluteReality 22 tags about photo realism and ChilloutMix a shorter
    // list about skin. Collapsing them was DreamUI's loss, not a tidy-up.
    /** ⚠ Shared by AbsoluteReality and its inpainting checkpoint — one text, two entries. */
    private const val P_ABSOLUTE = "masterpiece, best quality, ultra-detailed, realistic, 8k, a cat on grass,"

    /**
     * ⚠ The same tags with NO SUBJECT, for the inpaint checkpoint — the user's
     * call, 2026-09-23: *"for inpaint flows, remove cat on a grass from default
     * prompt"*. An inpaint prompt describes what goes IN the masked area, and a
     * cat that has to be deleted first is the [Family.prompt] rule's complaint.
     */
    private const val P_ABSOLUTE_INPAINT = "masterpiece, best quality, ultra-detailed, realistic, 8k,"

    private const val NEG_ABSOLUTE =
        "worst quality, low quality, normal quality, poorly drawn, lowres, " +
            "low resolution, signature, watermarks, ugly, out of focus, error, " +
            "blurry, unclear photo, bad photo, unrealistic, semi realistic, " +
            "pixelated, cartoon, anime, cgi, drawing, 2d, 3d, censored, duplicate,"
    private const val NEG_CHILLOUT =
        "paintings, cartoon, anime, lowres, bad anatomy, bad hands, text, error, " +
            "missing fingers, extra digit, cropped, worst quality, low quality, " +
            "normal quality, jpeg artifacts, signature, watermark, username, " +
            "skin spots, acnes, skin blemishes"

    /**
     * ⭐⭐ **Every checkpoint the app knows about** — ours plus the user's.
     *
     * ⚠⚠ This is what [byId] reads, and that is the whole reason
     * [CustomModels] merges in here rather than being a list the picker knows
     * about separately. `backendTypeOf` falls back to `sd15npu` for an
     * unknown id, so a custom SDXL model missing from THIS list would launch
     * the backend `--type sd15npu` and render 512 in silence.
     *
     * ⚠ Order: **SD 1.5 first, then SDXL**, with [V1_MODEL] at the head because
     * it is v1's default, and imported models last. SD 1.5 first is not
     * alphabetical tidiness — an SDXL entry is ~3.7 GB against ~1 GB and needs
     * a newer chip, so the cheap, universally-runnable ones belong where a
     * user's eye lands first. ⚠ The picker re-sorts anyway (installed first,
     * per family), so this order is the *curator's*, not the screen's.
     *
     * ⚠ Not stable across a [CustomModels.scan] — by design.
     */
    val all: List<ModelSpec>
        get() {
            // ⚠⚠ Memoised on the scan's list IDENTITY (`CustomModels.scan`
            // replaces it, never mutates it): [byId] is called during
            // composition, and rebuilding ~60 specs per call was 5% of the main
            // thread in the 2026-10-05 capture.
            val reg = CustomModels.registered
            allMemo?.let { (r, a) -> if (r === reg) return a }
            return (builtIn + reg).also { allMemo = reg to it }
        }

    @Volatile private var allMemo: Pair<List<ModelSpec>, List<ModelSpec>>? = null

    /**
     * ⭐⭐ The ids that are ON THE PHONE, cached so a caller with no
     * `Context` can still prefer an installed checkpoint.
     *
     * ⚠ [UpscalerCatalog.installedIds]'s shape exactly, for its reason: a
     * recipe is built from half a dozen call sites that have no context to
     * hand, and threading one through every builder to answer "is this
     * downloaded?" would be a lot of plumbing for one question.
     *
     * ⚠ Empty until [refreshInstalled] runs, so a caller must degrade to the
     * full catalogue rather than to "nothing is installed".
     */
    @Volatile
    var installedIds: List<String> = emptyList()
        // ⚠ `internal`, not `private`: `RecipeModelTest` sets it directly rather
        // than writing a gigabyte of fixture to disk, since this cache is the
        // only thing `Workflows.ctxKeyParams` reads. Nothing outside the module
        // can write it.
        internal set

    /** ⚠ Call after anything that installs, imports or deletes a checkpoint. */
    fun refreshInstalled(context: Context) {
        installedIds = all.filter { it.installed(context) }.map { it.id }
    }

    /**
     * ⭐⭐ The checkpoints WE publish — the catalogue proper.
     *
     * ⚠ Distinct from [all], which also carries whatever [CustomModels.scan]
     * last found on disk. Anything reasoning about **downloads or device
     * support in general** wants this one: a custom model has no archive and
     * makes no arch claim, so counting it among "models this phone can run"
     * answers a question about our catalogue with a fact about the user's
     * import.
     *
     * ⚠ It is also the collision set. A custom directory whose name matches an
     * id here is skipped by the scan, because [byId] returns the first match
     * and a shadowed built-in would point its downloads at the user's files.
     */
    // ⚠ `by lazy`, not a getter (it rebuilt the list per call) and not a plain
    // val (it would read the lists below before they are initialised).
    val builtIn: List<ModelSpec> by lazy {
        sd15Models + swapModels + sdxlModels +
            // ⚠ An entry whose archive size is not known yet (still uploading) is
            // not offered: `missing()` checks exact sizes, so it could not install.
            sdxlSwapModels.filter { m -> m.builds.all { it.bytes > 0 } } +
            communityModels + animaModels + ditModels
    }

    /** ⚠ Kept as its own list so a family can be counted, filtered and tested. */
    /**
     * One of xororz's SD 1.5 checkpoints, in all three published tiers.
     *
     * ⚠ Ordered best-first, which is [ModelSpec.buildFor]'s contract: `_8gen2`
     * is the fastest build any device that can take it should get, and `_min`
     * is the one that runs anywhere.
     */
    private fun sd15(
        id: String, label: String, stem: String,
        bytes8gen2: Long, bytes8gen1: Long, bytesMin: Long,
        prompt: String, negative: String,
    ) = ModelSpec(
        id = id,
        label = label,
        builds = listOf(
            Build(TIER, "${stem}_qnn2.28$TIER.zip", bytes8gen2, ARCH_8GEN2, 8),
            Build(TIER_8GEN1, "${stem}_qnn2.28$TIER_8GEN1.zip", bytes8gen1, ARCH_8GEN1, 8),
            Build(TIER_MIN, "${stem}_qnn2.28$TIER_MIN.zip", bytesMin, ARCH_MIN, 2),
        ),
        prompt = prompt,
        negative = negative,
    )

    /**
     * ⭐⭐ `Lykon/absolute-reality-1.6525-inpainting` — a 9-channel inpainting
     * UNet, listed among the SD 1.5 models as an ordinary checkpoint (the
     * user's call, 2026-09-19). An inpaint node naming it gets true inpainting,
     * where the model SEES the hole it fills; any other node gets the whole
     * frame repainted, i.e. text- and image-to-image. The backend decides from
     * its UNet (`backend-patches/006`), so nothing in the UI asks.
     *
     * ⚠⚠ The QAIRT **2.28** rebuild (`fix228c`), from DreamUI, and ONLY that
     * one: measured equal to the 2.49 build in quality (`extreme_frac` 0.0318
     * vs 0.0304 at 512², 0.0576 vs 0.0579 at 512×768) and it carries no fp16
     * stamp, so it is the build meant to load on the chips 2.49 is refused by
     * (`../LocalDream/docs/MODEL-SUPPORT.md` §4). ⚠ Proven on v79 only — no
     * chip that genuinely lacks fp16 has loaded it yet.
     *
     * ⚠ Sizes: `_8gen2` is 512 + 512×768 (the portrait patch is its own 5.8 MB
     * archive, cut against THIS `unet.bin`). `_min` is 512 alone — its patches
     * exist but measured 10.8 min per image, so they are not published. 768×512
     * is built and quality-equal but withheld at 21.7 s against 9.3 s on 2.49.
     *
     * ⚠ Self-contained: its CLIP and VAE are NOT xororz's AbsoluteReality's
     * (0/196 and 0/248 tensors bit-identical, measured in DreamUI), so nothing
     * is shared with that entry.
     */
    private val absrealityInpaint = ModelSpec(
        id = "absreality_inpaint",
        label = "AbsoluteReality Inpaint",
        builds = listOf(
            Build(
                TIER, "AbsoluteRealityInpaint_qnn2.28fix228c$TIER.zip", 1_232_146_314L, ARCH_8GEN2, 8,
                extras = listOf("AbsoluteRealityInpaint_qnn2.28fix228c_res512x768$TIER.zip" to 5_841_671L),
            ),
            // ⚠ No `_8gen1`: only xororz publishes that tier, so an 8 Gen 1
            // takes `_min` here, as it does in DreamUI.
            Build(TIER_MIN, "AbsoluteRealityInpaint_qnn2.28fix228c$TIER_MIN.zip", 1_298_100_138L, ARCH_MIN, 2),
        ),
        // ⚠ Its BASE's starter text, not DreamUI's negative-only entry. There it
        // was an inpaint-only model; here it also does text to image, and an
        // empty prompt cleared the box when a node was switched onto it.
        prompt = P_ABSOLUTE_INPAINT,
        negative = NEG_ABSOLUTE,
        backendType = SD15_NPU_INPAINT,
        baseUrl = "https://huggingface.co/AbrahamPJ/absolutereality-inpainting-qnn/resolve/main/",
    )

    /**
     * ⭐⭐ The SD 1.5 Swap tab's defaults — the user's pick, 2026-09-30 (Anything V5
     * added 2026-10-01): one realistic, two anime, converted in npuforge as *SD1.5 Swap* and hosted at
     * [SWAP_BASE_URL]. ⚠⚠ Converted ON the S25, so the UNet and VAE contexts are
     * v79 builds (npuforge compiles for the device's own arch): 8 Elite and
     * newer only, until v73 / v68 builds are made on the PC from the same
     * checkpoints (`notes/HANDOFF.md`).
     */
    // ⭐ **Swap v2** since 1.6.065 (IP-Adapter inputs, `docs/MODELS.md` §10): the same CLIP/VAE,
    // a v2 UNet converted on the S25 and `ip_targets.json`. ⚠ A v1 copy already installed stays
    // "installed" (the required files are all there) and renders as before; only a reference
    // picture asks for a re-download ([SwapInputs.resolve]).
    val swapModels: List<ModelSpec> = listOf(
        swapModel(
            "absolutereality_swap", "AbsoluteReality Swap",
            "absolutereality_v181_swap_v2_v79.zip", 1_309_413_181L,
            prompt = P_ABSOLUTE, negative = NEG_ABSOLUTE,
        ),
        swapModel(
            "cuteyukimix_swap", "CuteYukiMix Swap",
            "cuteyukimix_swap_v2_v79.zip", 1_309_413_181L,
            prompt = P_ANIME_GIRL, negative = NEG_ANIME,
        ),
        // ⭐ The user's pick, 2026-10-01: Anything V5 (Yuno779), npuforge's own export
        // zip hosted unchanged (it is what the user imported; same 9 files, v2).
        swapModel(
            "anything_v5_swap", "Anything V5 Swap",
            "anything_v5_swap_v2_v79.zip", 1_309_614_404L,
            prompt = P_ANIME_GIRL, negative = NEG_ANIME,
        ),
    )

    private fun swapModel(
        id: String, label: String, archive: String, bytes: Long,
        prompt: String, negative: String,
    ) = ModelSpec(
        id = id,
        label = label,
        builds = listOf(Build(TIER_DIT, archive, bytes, DIT_MIN_ARCH, 8)),
        family = Family.SD15_SWAP,
        requiredFiles = SD15_SWAP_REQUIRED,
        prompt = prompt,
        negative = negative,
        baseUrl = SWAP_BASE_URL,
    )

    const val SWAP_BASE_URL = "https://huggingface.co/AbrahamPJ/nightmare-sd15-swap-models/resolve/main/"

    /**
     * ⭐⭐ The SDXL Swap tab's defaults — the user's pick, 2026-10-05: Illustrious
     * XL v1.0 (anime) and Juggernaut XL Ragnarok (realism), converted ON the S25
     * by npuforge 1.0.11 as SDXL Swap v2 with LoRA (462 tokens, v79 contexts) and
     * hosted unchanged at [SDXL_SWAP_BASE_URL]. ⚠ Not the inpaint conversions:
     * a plain one does text and image to image, and inpaints by blend.
     * ⭐ Illustrious is npuforge 1.0.12's LoRA + ControlNet + IP-Adapter conversion since
     * 1.6.122 (`_v3_`; a user had downloaded the SDXL ControlNets and found its tiles dim).
     * A copy downloaded before keeps its own `swap_features.json` ([ModelFeatures.installed]).
     */
    val sdxlSwapModels: List<ModelSpec> = listOf(
        sdxlSwap(
            "illustrious_xl_swap", "Illustrious XL Swap",
            "illustrious_xl_v1_swap_v3_v79.zip", ILLUSTRIOUS_SWAP_BYTES,
            prompt = P_ILLUSTRIOUS, negative = NEG_ANIME,
            features = setOf(ModelFeatures.LORA, ModelFeatures.CONTROLNET, ModelFeatures.IP_ADAPTER),
        ),
        sdxlSwap(
            "juggernaut_xl_swap", "Juggernaut XL Swap",
            "juggernaut_xl_ragnarok_swap_v2_v79.zip", JUGGERNAUT_SWAP_BYTES,
            prompt = P_SDXL, negative = NEG_GENERAL,
        ),
    )

    private fun sdxlSwap(
        id: String, label: String, archive: String, bytes: Long,
        prompt: String, negative: String,
        features: Set<String>? = null,
    ) = ModelSpec(
        id = id,
        label = label,
        builds = listOf(Build(TIER_DIT, archive, bytes, DIT_MIN_ARCH, 8)),
        prompt = prompt,
        negative = negative,
        features = features,
        family = Family.SDXL_SWAP,
        backendType = SDXL_NPU,
        resolutions = listOf(SDXL_NPU_RES),
        baseUrl = SDXL_SWAP_BASE_URL,
        requiredFiles = SDXL_SWAP_V2_REQUIRED,
        tier = TIER_DIT,
        minHtpArch = DIT_MIN_ARCH,
        lowram = true,
        promptTokens = 462,
    )

    const val SDXL_SWAP_BASE_URL = "https://huggingface.co/AbrahamPJ/nightmare-sdxl-swap-models/resolve/main/"

    /**
     * ⭐⭐ COMMUNITY conversions (the user, 2026-10-18): npuforge 1.0.12 run ON an 8 Gen 3 (SM8650, `soc` in each
     * `swap_features.json`), so v75 contexts that load on the 8 Gen 3 and everything after (contexts run forward,
     * `docs/DEVICES.md` §2): the gap the S25's v79 conversions leave. Hosted UNCHANGED on the converter's own
     * Hugging Face, and marked [ModelSpec.community] so the app says whose they are. ⚠ The README and guide name
     * none of them (the user's call).
     * ⚠ Read by range request, not trusted from the names: the two `_swap` zips are SDXL Swap v2 with LoRA,
     * ControlNet and IP-Adapter (462 tokens); the two DMD ones are PLAIN SDXL with DMD2 merged (231 tokens, no
     * `config.json`, so the defaults are upstream's for its DMD2 builds: LCM, 8 steps, cfg 1).
     */
    val communityModels: List<ModelSpec> = listOf(
        communitySwap(
            "community_lustify_apex_v8_swap", "Lustify Apex V8 Swap", "JIONing/apexV8",
            "lustifyNSFWCheckpoint_apexV8_npuforge_swap_ControlNet_IP-Adapt.zip", 3_816_006_388L,
            prompt = P_SDXL, negative = NEG_GENERAL,
        ).copy(mature = true),
        communitySwap(
            "community_rinflanime_v50_swap", "rinFlanime Illustrious v5 Swap", "JIONing/rinFlanimeIllustrious_v50",
            "rinFlanimeIllustrious_v50_npuforge_swap.zip", 3_907_307_857L,
            prompt = P_ILLUSTRIOUS, negative = NEG_ANIME,
        ),
        communityDmd(
            "community_lustify_apex_v8_dmd2", "Lustify Apex V8 DMD2", "JIONing/apexV8",
            "lustifyNSFWCheckpoint_apexV8+dmd2_8gen3.zip", 3_859_554_287L,
            prompt = P_SDXL, negative = NEG_GENERAL,
        ).copy(mature = true),
        communityDmd(
            "community_rinflanime_v50_wicked_city_dmd2", "rinFlanime v5 Wicked City DMD2", "JIONing/rinFlanimeIllustrious_v50",
            "rinFlanimeIllustrious_v50+Wicked_City_Illustrious_0.1@0.8+dm.zip", 3_859_554_609L,
            prompt = P_ILLUSTRIOUS, negative = NEG_ANIME,
        ),
    )

    private fun hf(repo: String) = "https://huggingface.co/$repo/resolve/main/"

    private fun communitySwap(
        id: String, label: String, repo: String, archive: String, bytes: Long, prompt: String, negative: String,
    ) = sdxlSwap(
        id, label, archive, bytes, prompt, negative,
        features = setOf(ModelFeatures.LORA, ModelFeatures.CONTROLNET, ModelFeatures.IP_ADAPTER),
    ).copy(
        builds = listOf(Build(SDXL_TIER, archive, bytes, minArch = 75, minVtcmMb = 8)),
        baseUrl = hf(repo),
        tier = SDXL_TIER,
        minHtpArch = 75,
        community = true,
    )

    private fun communityDmd(
        id: String, label: String, repo: String, archive: String, bytes: Long, prompt: String, negative: String,
    ) = sdxl(id, label, archive, bytes, prompt, negative).copy(
        baseUrl = hf(repo),
        // ⚠ Without `qnn_context.txt` the backend reads a 231-token UNet as 77 ([SDXL_SWAP_V2_REQUIRED]'s trap).
        requiredFiles = SDXL_REQUIRED + CustomModels.LONG_CONTEXT_FILE,
        promptTokens = 231,
        scheduler = "lcm",
        steps = 8,
        cfg = 1.0,
        community = true,
    )
    private const val ILLUSTRIOUS_SWAP_BYTES = 3_899_749_590L
    private const val JUGGERNAUT_SWAP_BYTES = 3_886_131_553L

    val sd15Models: List<ModelSpec> = listOf(
        sd15(
            V1_MODEL, "AbsoluteReality", "AbsoluteReality",
            1_054_661_172L, 1_059_732_796L, 993_451_663L,
            prompt = P_ABSOLUTE,
            negative = NEG_ABSOLUTE,
        ),
        // ⭐ Directly under its base, where someone looking for AbsoluteReality
        // finds both (the user's call, 2026-09-19).
        absrealityInpaint,
        sd15(
            "anythingv5", "AnythingV5", "AnythingV5",
            1_057_820_237L, 1_061_290_117L, 995_100_213L,
            prompt = P_ANIME_GIRL, negative = NEG_ANIME,
        ),
        sd15(
            "chilloutmix", "ChilloutMix", "ChilloutMix",
            1_069_856_038L, 1_073_617_353L, 1_007_485_231L,
            prompt = "RAW photo, best quality, realistic, photo-realistic, masterpiece, " +
                "1girl, upper body, facing front, portrait, white shirt",
            negative = NEG_CHILLOUT,
        ),
        sd15(
            "cuteyukimix", "CuteYukiMix", "CuteYukiMix",
            1_057_299_173L, 1_059_582_346L, 993_526_703L,
            prompt = P_ANIME_GIRL, negative = NEG_ANIME,
        ),
        sd15(
            "qteamix", "QteaMix", "QteaMix",
            1_056_615_116L, 1_061_160_208L, 995_347_176L,
            prompt = P_QTEA, negative = NEG_ANIME,
        ),
    )

    // ---- SDXL ------------------------------------------------------------

    /**
     * ⚠ The fallback for the eight of the ten with **no upstream entry** —
     * DreamUI's own list, so this is DreamUI's prompt, unchanged.
     *
     * ⚠⚠ It is a fallback rather than the SDXL prompt: the two ids upstream
     * also publishes take upstream's text ([P_CYBERREALISTIC], [P_ILLUSTRIOUS]),
     * per the SD 1.5 rule above. Inventing one per checkpoint for the other
     * eight would be guessing at what their authors wanted, which is the thing
     * `config.json` exists to stop us doing.
     */
    // ⚠⚠ It is the FAMILY default now, not a prompt of its own. It used to
    // append "a majestic cat sitting on a windowsill at sunset," — a subject
    // this project invented for eight checkpoints whose authors said nothing,
    // which is the guessing the paragraph above says not to do, and a cat the
    // user had to delete before typing. The user's ask, 2026-09-15.
    private const val P_SDXL = GP_SDXL

    // ⭐ Two of the ten ARE upstream models, and upstream gives each its own
    // text. ⚠ CyberRealistic's negative is already [NEG_GENERAL] byte for byte,
    // so only the prompt differs; Illustrious is an anime checkpoint and takes
    // the anime negative, which is why the shared SDXL default cannot serve it.
    private const val P_CYBERREALISTIC =
        "masterpiece, best quality, " +
            "a majestic cat sitting on a windowsill at sunset,"
    private const val P_ILLUSTRIOUS =
        "1girl, solo, blue twintails, very long hair, bangs, blue eyes, jewelry, " +
            "necklace, hair bow, off-shoulder white frilled dress, bare shoulders, " +
            "collarbone, underwater, floating hair, reaching towards viewer, " +
            "air bubbles, blue theme, blurry foreground, masterpiece"
    // ⚠ ONE home: this is byte-for-byte [Family.SDXL]'s general negative, so it
    // is that constant rather than a second copy of it.
    private const val NEG_GENERAL = GP_SDXL_NEG

    /**
     * One of xororz's SDXL checkpoints.
     *
     * ⚠ Nothing is shared with SD 1.5 — different text encoders, different VAE,
     * different everything — so each is a self-contained ~3.7 GB install. ⚠⚠ And
     * ~3.7 GB is the DOWNLOAD; [ModelInstaller] needs roughly twice that free
     * because the archive and its unpacked copy are on disk at once.
     *
     * ⭐ It needs **no new node types**: `sd.clip_encode → COND → sd.sample`
     * works unchanged because COND is an opaque HANDLE, so SDXL's two encoders
     * are the backend's problem and never the graph's. That is the payoff of
     * the handle rule in `docs/ARCHITECTURE.md` §3.
     */
    private fun sdxl(
        id: String, label: String, archive: String, bytes: Long,
        // ⚠ Defaulted rather than required: eight of the ten have no upstream
        // entry to copy from, and inventing a prompt per checkpoint is guessing.
        prompt: String = P_SDXL,
        negative: String = NEG_GENERAL,
    ) = ModelSpec(
        id = id,
        label = label,
        // ⚠⚠ ONE build, and that is the whole SDXL device story: xororz
        // publishes `_8gen3` only -- 46 files, no `min`. So on anything below a
        // v75 HTP there is no SDXL build at all, not a slower one, and
        // `buildFor` correctly answers null rather than offering 3.5 GB the
        // chip would reject at load.
        builds = listOf(Build(SDXL_TIER, archive, bytes, minArch = 75, minVtcmMb = 8)),
        prompt = prompt,
        negative = negative,
        family = Family.SDXL,
        backendType = SDXL_NPU,
        // ⚠⚠ Forced by the backend, not chosen here -- see [SDXL_NPU_RES].
        // ⇒ The locked widgets read this.
        resolutions = listOf(SDXL_NPU_RES),
        baseUrl = SDXL_BASE_URL,
        requiredFiles = SDXL_REQUIRED,
        tier = SDXL_TIER,
        minHtpArch = 75,
        // ⚠ Every SDXL checkpoint, as in DreamUI: a ~3× UNet at 1024² does not
        // fit beside its VAE and two encoders.
        lowram = true,
    )

    /**
     * ⚠ A curated spread rather than all 46 in the repo — each is ~3.7 GB, and
     * the list is only useful if a user can tell the entries apart. These are
     * DreamUI's own ten, in its order. Adding another is one line: id, label,
     * filename, byte size from the HF listing.
     */
    val sdxlModels: List<ModelSpec> = listOf(
        sdxl("sdxl_base", "SDXL Base 1.0", "sdxl_base_qnn2.28$SDXL_TIER.zip", 3_753_226_114L),
        sdxl("sdxl_dreamshaper", "DreamShaper XL", "dreamshaper_qnn2.28$SDXL_TIER.zip", 3_753_755_544L),
        sdxl("sdxl_juggernaut", "Juggernaut XL", "juggernaut_qnn2.28$SDXL_TIER.zip", 3_747_687_306L),
        sdxl("sdxl_realvis", "RealVis XL v5", "realvis_xl_v5_qnn2.28$SDXL_TIER.zip", 3_499_694_289L),
        sdxl("sdxl_epicrealism", "epiCRealism XL", "epic_realism_qnn2.28$SDXL_TIER.zip", 3_502_991_005L),
        sdxl(
            "sdxl_cyberrealistic", "CyberRealistic v10",
            "cyber_realistic_v10_qnn2.28$SDXL_TIER.zip", 3_745_235_842L,
            prompt = P_CYBERREALISTIC,
        ),
        sdxl(
            "sdxl_illustrious", "Illustrious v16",
            "illustrious_v16_qnn2.28$SDXL_TIER.zip", 3_726_876_852L,
            prompt = P_ILLUSTRIOUS, negative = NEG_ANIME,
        ),
        sdxl("sdxl_animagine", "Animagine v4", "animagine_v4_qnn2.28$SDXL_TIER.zip", 3_752_469_362L),
        sdxl("sdxl_pony", "Pony Diffusion v6 XL", "ponydiffusion_v6xl_qnn2.28$SDXL_TIER.zip", 3_725_876_252L),
        sdxl("sdxl_novaanime", "NovaAnime v19", "novaanime_v19_qnn2.28$SDXL_TIER.zip", 3_732_162_768L),
    )

    /**
     * ⭐ One of xororz's Anima checkpoints — `xororz/anima-qnn`.
     *
     * ⚠⚠ The defaults are READ, not chosen: all nine archives' `config.json`
     * were fetched by range request on 2026-09-16 and every one says `euler`,
     * 10 steps, cfg 1 — they are TURBO (distilled) checkpoints, where 20 steps
     * at 7.5 does not fail but burns. Measured on the phone the same day:
     * 4 steps is visibly hazy, 8 matched 10 on the one prompt that finished
     * (`docs/MODELS.md` §8). ⇒ The author's 10 stands.
     *
     * ⚠ The PROMPT is not copied: all nine ship the same demo (a girl holding a
     * sign that reads "My phone is burning!"), so they take the family's.
     */
    private fun anima(id: String, label: String, archive: String, bytes: Long) = ModelSpec(
        id = id,
        label = label,
        // ⚠ `_8gen3` only, like SDXL — a v75 context, so nothing older can load it.
        builds = listOf(Build(SDXL_TIER, archive, bytes, minArch = 75, minVtcmMb = 8)),
        prompt = "",
        negative = "",
        family = Family.ANIMA,
        backendType = ANIMA_NPU,
        resolutions = listOf(ANIMA_NPU_RES),
        baseUrl = ANIMA_BASE_URL,
        requiredFiles = ANIMA_REQUIRED,
        tier = SDXL_TIER,
        minHtpArch = 75,
        // ⚠⚠ Two ~2 GB DiT halves, a 1.2 GB Qwen encoder and a 16-channel VAE:
        // upstream defaults `anima_lowram` ON, and on an 11.4 GB phone even that
        // was killed while the phone was in use (`docs/MODELS.md` §8).
        lowram = true,
        scheduler = "euler",
        steps = 10,
        cfg = 1.0,
    )

    /** ⚠ All nine xororz publishes, in the repo's order; sizes off the HF listing. */
    val animaModels: List<ModelSpec> = listOf(
        anima("anima_base", "Anima Base v1 Turbo", "anima_base_v1_turbo_qnn2.28$SDXL_TIER.zip", 4_290_828_128L),
        anima("anima_yume", "AnimaYume v1 Turbo", "animayume_v1_turbo_qnn2.28$SDXL_TIER.zip", 4_287_096_388L),
        anima("anima_cyberrealistic", "CyberRealistic v3 Turbo", "cyberrealistic_v3_turbo_qnn2.28$SDXL_TIER.zip", 4_853_683_064L),
        anima("anima_miaomiao", "MiaoMiao v1.4 Turbo", "miaomiao_v1.4_turbo_qnn2.28$SDXL_TIER.zip", 4_299_044_507L),
        anima("anima_novaanime25", "NovaAnime v2.5 Turbo", "novaanime_v2.5_turbo_qnn2.28$SDXL_TIER.zip", 4_290_744_025L),
        anima("anima_novaanime3", "NovaAnime v3 Turbo", "novaanime_v3_turbo_qnn2.28$SDXL_TIER.zip", 4_292_018_537L),
        anima("anima_rin_flanime", "Rin FlAnime v1 Turbo", "rin_flanime_v1_turbo_qnn2.28$SDXL_TIER.zip", 4_854_654_219L),
        anima("anima_sam_realistic", "SAM Anima Realistic v2.3 Turbo", "sam_anima_realistic_v2.3_turbo_qnn2.28$SDXL_TIER.zip", 4_290_163_015L),
        anima("anima_wai", "WAI Anima v1 Turbo", "wai_anima_v1_turbo_qnn2.28$SDXL_TIER.zip", 4_281_870_655L),
    )

    // ---- DiT (FLUX.2 Klein, Z-Image) -------------------------------------

    private const val HF = "https://huggingface.co/"

    /**
     * ⚠ The Qwen3 tokenizer both packages carry — the backend loads a
     * `tokenizer.json` from every model dir at startup, and `/tokenize` counts
     * the prompt with it. Byte-identical in both upstream sources.
     */
    private val DIT_TOKENIZER_BYTES = 11_422_654L

    /**
     * ⭐⭐ One DiT package: four plain files from the repositories that publish
     * them, never rehosted — upstream local-dream 3.0's `packageFiles`, verbatim
     * URLs, sizes read from the served Content-Length (2026-09-19).
     *
     * ⚠⚠ **v79 and newer only** (8 Elite, 8 Elite Gen 5). The engine ships
     * Hexagon skels for v79/v81 alone; upstream gates on SM8750+, which is the
     * same set of phones as this arch gate. ⚠ One pseudo-build carries the gate
     * and the total size — there is no archive to fetch.
     */
    private fun dit(
        id: String, label: String, family: Family, type: String, steps: Int,
        files: List<RemoteFile>,
        /** ⚠ Upstream's own starter prompt for the checkpoint. */
        prompt: String = "a lovely cat wearing black sunglasses, studio photo,",
        /**
         * ⭐⭐ On a DiT package `--lowram` does ONE thing, and it is not what it
         * does for SDXL: `main.cpp` turns it into the engine's
         * `params_backend = "te=disk"`, which streams the text encoder's
         * parameters from disk instead of holding them resident. It does not
         * load and release stages.
         *
         * ⚠ So it is a property of the MODEL's SIZE against this class of
         * phone, which is why it is a per-entry argument rather than a family
         * flag: FLUX.2 Klein fits without it and Z-Image does not.
         */
        lowram: Boolean = false,
        /** ⚠ See [ModelSpec.ownDitParts]. */
        ownDitParts: Boolean = false,
        /** ⚠ See [Build.minRamBytes]. */
        minRamBytes: Long = 0L,
        /** ⚠ See [ModelSpec.startRes]. */
        startRes: Res? = null,
        /** ⭐ Per-RAM builds, best first, each with its own [Build.files] (Qwen). Null = one build of [files]. */
        builds: List<Build>? = null,
        /** ⭐ The chip floor: [DIT_GGML_MIN_ARCH], [DIT_V73_MIN_ARCH] where measured, or [DIT_MIN_ARCH] for FP8. */
        minArch: Int = DIT_GGML_MIN_ARCH,
        /** ⚠ See [ModelSpec.partsFrom]. */
        partsFrom: String? = null,
        /** ⭐ Its ONLY sizes, first = native ([ModelSpec.fixedSizes]); null = the free 64-px grid. */
        sizes: List<Res>? = null,
        /** ⭐ 1 for every turbo here; a base model (Anima 2.9B) wants its own. */
        cfg: Double = 1.0,
        /** ⚠ See [ModelSpec.ownLora]. */
        ownLora: String? = null,
        /** ⚠ See [ModelSpec.gpu]. */
        gpu: Boolean = false,
    ) = ModelSpec(
        ownLora = ownLora,
        gpu = gpu,
        startRes = startRes,
        fixedSizes = sizes != null,
        lowram = lowram,
        ownDitParts = ownDitParts,
        partsFrom = partsFrom,
        id = id,
        label = label,
        builds = builds ?: listOf(Build(TIER_DIT, "", files.sumOf { it.bytes }, minArch, ditVtcmFloor(minArch), minRamBytes = minRamBytes)),
        prompt = prompt,
        negative = "",
        family = family,
        backendType = type,
        resolutions = sizes ?: listOf(DIT_RES),
        requiredFiles = files.map { it.name },
        scheduler = "euler",
        steps = steps,
        cfg = cfg,
        files = files,
    )

    /** ⚠ The tier label a DiT build reports — it is a device gate, not an archive. */
    const val TIER_DIT = "_v79"

    /**
     * ⭐⭐⭐ **The HTP arch a DiT model and the video models need** — v79,
     * which is the Snapdragon 8 Elite and everything after it.
     *
     * ⚠⚠ Named because THREE things gate on it and a literal 79 in each
     * would be three chances to drift: every `dit()` build, and the FLUX edit
     * and video FLOWS, which refuse to open below it
     * (`canvas/Workflows.kt`). The engine ships v79/v81 skels and the video
     * contexts are v79 binaries — `docs/NEODRAGON.md` §4, `docs/MODELS.md` §9.
     *
     * ⚠ A QNN context runs on the arch it was built for and every newer one,
     * so this is a floor and never an allowlist: gen 5 needed no entry.
     */
    const val DIT_MIN_ARCH = 79

    /**
     * ⭐⭐ **The floor for the ggml DiT engine's QUANTISED builds — v75, the 8 Gen 3 (alpha).**
     * Four models go lower, to [DIT_V73_MIN_ARCH].
     *
     * The engine's own source (happyyzy/ggml `f875862`, what `dit-engine-abi105-a4` and Local Dream
     * alpha.5 both build) has v73/v75 code paths and builds v73 and v75 skels; upstream ships only
     * v79/v81 to save APK size. Ours are built from that exact tree (`assets/ditlibs/libggml-htp-v73.so`,
     * `-v75.so`, `docs/DIT.md` §9e, `docs/DEVICES.md` §3b). ⚠⚠ FP8 weights stay at [DIT_MIN_ARCH]: below v79 the engine refuses
     * `F8_E4M3` matmuls on the NPU (`opt_arch < 79`) and runs them on the CPU — so Klein 4B and Qwen
     * each have an FP8 build at v79 and a GGUF build below it (Q8_0 / Q4_0), and so does Z-Image Turbo (leejet's
     * Q4_0) since engine `dit/025` stopped its `attention.out` overflowing fp16 on HTP (`docs/DIT.md` §2e).
     * ✅ FLUX.2 Klein and Qwen 2.1 render on an 8 Gen 3 since 1.6.133 (user, 2026-10-11; `docs/DIT.md` §9e).
     */
    const val DIT_GGML_MIN_ARCH = 75

    /**
     * ⭐ v73 (8 Gen 2, 8s Gen 4, and the 8s Gen 3 under [DIT_V73_MIN_VTCM_MB]), ALPHA, for the
     * models that rendered right on the S25 forced to the v73 skel (`GGML_HEXAGON_ARCH=73`, the app's
     * below-v79 env plus `SWIGLU` on the CPU, `docs/DEVICES.md` §3b): Z-Image Turbo's Q4_0 build,
     * FLUX.2 Klein 9B, Anima Turbo 1.1, Iris 3B Turbo (and Klein 4B's Q4_0, below). ⚠ NOT Krea 2 (the v73 skel faults in a matmul whose
     * activation rows pass 64 KB), Qwen Image 2.1 (its 1024² decode came back in bands), Wan, imports or the
     * edit flow (not run). Klein 4B's Q4_0 since 1.6.257: a tester's import of one rendered on a real 8s Gen 4.
     */
    const val DIT_V73_MIN_ARCH = 73

    /**
     * ⭐ v69 (8 Gen 1, 8+ Gen 1), ALPHA: our skel (engine `dit/034`), HVX only, decode on the CPU
     * ([DeviceProbe.DIT_NO_HMX_SOCS]). Only the models that rendered right on the S25 forced to it
     * (`docs/DEVICES.md` §3c). Its VTCM floor is [ditVtcmFloor]'s, as v73's. No real 8 Gen 1 has run it.
     */
    const val DIT_V69_MIN_ARCH = 69

    /**
     * ⭐ The v73 builds' VTCM floor: the chip table's own floor, so the 8s Gen 3 (SM8635, under 8 MB) gets them
     * as alpha (the user's call, 2026-10-17). ⚠ The 8 MB every other build asks is the QNN context binaries'
     * rule, fixed at build time. ggml-hexagon sizes its work to the session's VTCM at run time: a fusion that
     * does not fit is skipped, a kernel that cannot run reports `VTCM-TOO-SMALL` ([HexFallback] then runs
     * unfused). Unmeasured on a real 8s Gen 3.
     */
    const val DIT_V73_MIN_VTCM_MB = DeviceProbe.FLOOR_VTCM_MB

    /** ⭐ A DiT build's VTCM floor: [DIT_V73_MIN_VTCM_MB] for the v73 builds, 8 MB for every other. */
    fun ditVtcmFloor(minArch: Int): Int = if (minArch <= DIT_V73_MIN_ARCH) DIT_V73_MIN_VTCM_MB else 8

    /** ⚠ Klein 4B's shared parts — the text encoder, VAE and tokenizer both its builds load. */
    private val KLEIN4B_PARTS = listOf(
        RemoteFile(HF + "zhiyuanasad/flux2_klein_adreno/resolve/main/llm.gguf", "llm.gguf", 2_262_670_048L),
        RemoteFile(HF + "zhiyuanasad/flux2_klein_adreno/resolve/main/vae.safetensors", "vae.safetensors", 336_213_556L),
        RemoteFile(HF + "Qwen/Qwen3-4B/resolve/main/tokenizer.json", "tokenizer.json", DIT_TOKENIZER_BYTES),
    )
    private val KLEIN4B_FP8 = listOf(
        RemoteFile(HF + "black-forest-labs/FLUX.2-klein-4b-fp8/resolve/main/flux-2-klein-4b-fp8.safetensors", "dit.safetensors", 4_070_624_520L),
    ) + KLEIN4B_PARTS
    /** ⭐ The 8 Gen 3 build: leejet's Q8_0 (the 9B's author), `dit.gguf` — backend 014 loads it first. */
    private val KLEIN4B_Q8 = listOf(
        RemoteFile(HF + "leejet/FLUX.2-klein-4B-GGUF/resolve/main/flux-2-klein-4b-Q8_0.gguf", "dit.gguf", 4_300_629_440L),
    ) + KLEIN4B_PARTS

    /** ⭐ v73 (8 Gen 2, 8s Gen 4): a Q4_0 Klein 4B import ran on a real 8s Gen 4 (a tester, 1.6.251, Saved #7439). */
    private val KLEIN4B_Q4 = listOf(
        RemoteFile(HF + "leejet/FLUX.2-klein-4B-GGUF/resolve/main/flux-2-klein-4b-Q4_0.gguf", "dit.gguf", 2_460_378_560L),
    ) + KLEIN4B_PARTS

    /** ⚠ Z-Image's shared parts — the text encoder, VAE and tokenizer both its builds load. */
    private val ZIMAGE_PARTS = listOf(
        RemoteFile(HF + "zhiyuanasad/z_image_turbo_adreno/resolve/main/llm.gguf", "llm.gguf", 2_262_670_048L),
        RemoteFile(HF + "zhiyuanasad/z_image_turbo_adreno/resolve/main/vae.safetensors", "vae.safetensors", 335_304_388L),
        RemoteFile(HF + "Tongyi-MAI/Z-Image-Turbo/resolve/main/tokenizer/tokenizer.json", "tokenizer.json", DIT_TOKENIZER_BYTES),
    )
    private val ZIMAGE_FP8 = listOf(
        RemoteFile(HF + "Kijai/Z-Image_comfy_fp8_scaled/resolve/main/z-image-turbo_fp8_scaled_e4m3fn_KJ.safetensors", "dit.safetensors", 6_158_115_074L),
    ) + ZIMAGE_PARTS
    /** ⭐ The 8 Gen 3 build: leejet's Q4_0, `dit.gguf`. Renders right only on an engine with `dit/025` (`docs/DIT.md` §2e). */
    private val ZIMAGE_Q4 = listOf(
        RemoteFile(HF + "leejet/Z-Image-Turbo-GGUF/resolve/main/z_image_turbo-Q4_0.gguf", "dit.gguf", 3_683_370_944L),
    ) + ZIMAGE_PARTS

    val ditModels: List<ModelSpec> = listOf(
        dit(
            "flux2_klein_4b", "FLUX.2 Klein 4B", Family.FLUX2, KLEIN, steps = 4,
            files = KLEIN4B_FP8,
            gpu = true,
            // ⭐⭐⭐ **NO `lowram`, and the reason it was switched on was
            // a misreading.** It was set in 1.5.514 and reverted 2026-09-20.
            //
            // The reasoning was: a 1024x1024 edit with a reference dies at
            // `conditioner.hpp ... parse`, which is the TEXT ENCODER, so stream
            // the text encoder off disk (`te=disk`) the way Z-Image does one
            // entry below. It did not help, and measurement later said why: the
            // text encoder is not what is expensive. Its compute buffer is
            // **65 MB in every leg** — identical for zero, one and two
            // references — and its 1.67 GB of weights are explicitly released
            // before the DiT loads. The kill lands while it is resident, not
            // because of it: what is underneath is VAE scratch, 1536 MB of VRAM
            // plus 524 MB of RAM **per reference** at 1024x1024
            // ([SdSampler.REF_ENCODED_AT_CANVAS]).
            //
            // ⚠⚠⚠ Which makes `lowram` here a pure loss: it slowed prompt
            // encoding on EVERY FLUX render, including plain text-to-image that
            // was never near the limit, and bought nothing on the one case it
            // was added for — the 1024x1024 edit is still reaped with it on.
            //
            // ⚠⚠ The general lesson, and it is the third time this file has
            // paid for it: **the stage a process dies AT is not the stage that
            // cost the memory.** Reaching for the lever that matches the last
            // log line is how 1.5.511 tuned the VAE tile threshold, how 1.5.513
            // and 1.5.516 bounded reference pixels, and how this line got set.
            // All four were reasoned, all four were wrong, and one measurement
            // with a control (`dit_edit`, and `img2img_0.65` as the leg that
            // survives the same work) ended it. `notes/PROGRESS.md`.
            //
            // ⇒ Klein is 57% of RAM and stays resident; it needs no staging.
            // Z-Image below is a different case and keeps its own `lowram`.
            // ⭐ Two builds, Qwen's way: Black Forest Labs' FP8 on v79+, leejet's Q8_0 GGUF on an 8 Gen 3
            // — below v79 the engine runs FP8 matmuls on the CPU ([DIT_GGML_MIN_ARCH], `docs/DIT.md` §9e).
            builds = listOf(
                Build(TIER_DIT, "", KLEIN4B_FP8.sumOf { it.bytes }, DIT_MIN_ARCH, 8, files = KLEIN4B_FP8),
                Build(TIER_DIT, "", KLEIN4B_Q8.sumOf { it.bytes }, DIT_GGML_MIN_ARCH, 8, files = KLEIN4B_Q8),
                // ⭐ The user's call, 2026-10-29: offered at v73 with the slow note ([noHmxOn]), as Z-Image is.
                Build(TIER_DIT, "", KLEIN4B_Q4.sumOf { it.bytes }, DIT_V73_MIN_ARCH, ditVtcmFloor(DIT_V73_MIN_ARCH), files = KLEIN4B_Q4),
            ),
        ),
        // ⭐⭐⭐ **`lowram` because it does not FIT, measured 2026-09-19.** This
        // entry used to carry a note saying Z-Image "crashes on the dev phone
        // (8 Elite) in upstream LocalDream too … not broken, not yet mature",
        // which was inference from upstream's reports rather than a reading of
        // ours. The log says something narrower and actionable: it is not a
        // crash at all. The engine loads, prints
        //
        //   total params memory size = 8193.44MB
        //   text_encoders 2158.46MB | diffusion_model 5874.97MB | vae 160.00MB
        //   weights will be prepared lazily
        //
        // takes the request, and ~4 s into preparing those weights **lmkd**
        // reclaims the app — `oom_score_adj 0`, foreground, and it takes
        // whatever else is running down with it. No tombstone, no `F DEBUG`
        // block. 8.2 GB of weights on an 11.7 GB phone.
        //
        // ⚠⚠ **The RESOLUTION is not the lever**, which is the trap here: the
        // report was "512x512 crashed", and 512 cannot help because the model
        // size is fixed and only activations scale. `te=disk` drops the text
        // encoder to ~6.0 GB resident — about FLUX.2 Klein's footprint, and
        // that one renders on this phone.
        dit(
            "z_image_turbo", "Z-Image Turbo", Family.ZIMAGE, ZIMAGE, steps = 8,
            lowram = true,
            gpu = true,
            files = ZIMAGE_FP8,
            // ⭐ Two builds, Klein's way: Kijai's FP8 on v79+, leejet's Q4_0 GGUF on an 8 Gen 3 (below v79 FP8
            // runs on the CPU). The GGUF went NaN on HTP until engine `dit/025` (`docs/DIT.md` §2e).
            builds = listOf(
                Build(TIER_DIT, "", ZIMAGE_FP8.sumOf { it.bytes }, DIT_MIN_ARCH, 8, files = ZIMAGE_FP8),
                Build(TIER_DIT, "", ZIMAGE_Q4.sumOf { it.bytes }, DIT_V73_MIN_ARCH, ditVtcmFloor(DIT_V73_MIN_ARCH), files = ZIMAGE_Q4),
            ),
        ),
        // ⭐ Krea 2 Turbo — `happyyzy/stable-diffusion.cpp`'s README, file for
        // file (e70d89b; sizes HEAD-checked 2026-09-28). A 6.8 GB MXFP4 MoE DiT,
        // Qwen3-VL-4B as the text encoder, the Wan 2.1 VAE; 8 steps at cfg 1.
        //
        // ⚠ Text to image only ([Family.KREA2]). The backend runs it
        // `all=disk` like Qwen, and reads its own directory only
        // (backend-patches/014) — none of it is FLUX.2's.
        //
        // ⚠⚠ **12 GB phones and up, under a budget** (the user's call, 2026-10-14). Unbudgeted,
        // the 12 GB S25 Ultra's lmkd reaped the app while its 6,520 MB DiT loaded (2026-09-28),
        // so it was 16 GB only. With `DIT_MAX_VRAM` 4 (set below the 16 GB class by
        // [BackendProcess]) it runs in 29 segments: 1024² in 211–235 s from a cool phone, 2.8 GB
        // left free, all pictures clean (budgets 3/4/5 measured, `notes/PROGRESS.md`).
        // ⚠ 8 GB phones stay out: never run there.
        dit(
            "krea2_turbo", "Krea 2 Turbo", Family.KREA2, KREA2, steps = 8,
            ownDitParts = true,
            minRamBytes = Prefs.RAM_12GB_CLASS_BYTES,
            files = listOf(
                RemoteFile(HF + "gguf-org/krea-2-gguf/resolve/main/krea2_turbo-mxfp4_moe.gguf", "dit.gguf", 6_837_146_720L),
                RemoteFile(HF + "bartowski/Qwen_Qwen3-VL-4B-Instruct-GGUF/resolve/main/Qwen_Qwen3-VL-4B-Instruct-Q4_0.gguf", "llm.gguf", 2_375_774_112L),
                RemoteFile(HF + "Comfy-Org/Wan_2.1_ComfyUI_repackaged/resolve/main/split_files/vae/wan_2.1_vae.safetensors", "vae.safetensors", 253_815_318L),
                RemoteFile(HF + "Qwen/Qwen3-VL-4B-Instruct/resolve/main/tokenizer.json", "tokenizer.json", 7_032_403L),
            ),
        ),
        // ⭐⭐ Anima Turbo 1.1 on the DiT engine (`docs/ANIMA-GGML.md`): circlestone's
        // OFFICIAL turbo (CFG 1, 8–12 steps), the one users asked for over the DMD
        // merges the QNN packages are. 56 s at 1024², 10 steps, against 82 s on QNN.
        //
        // ⚠ The DiT is downloaded as the official BF16 file and converted on the
        // phone to Q8_0 ([AnimaGguf]): nothing re-hosted, and Q8_0 renders the same
        // picture 16% faster in 1.8 GB less NPU memory. The text encoder is
        // Qwen3-0.6B-Base, the VAE Anima's own; sizes HEAD-checked 2026-10-13.
        //
        // ⚠ Launched as `--type krea2`, the backend's shape for "own files, no
        // edit" (`main.cpp`): the engine detects Anima from the weights and reads
        // `kind` only for reference presets — the Krea 2 precedent itself.
        // ⚠ Needs `GGML_HEXAGON_OPFILTER` to include ADD ([BackendProcess]).
        dit(
            "anima_turbo_1_1", "Anima Turbo 1.1", Family.ANIMA_DIT, KREA2, steps = 10,
            prompt = "",
            // ⭐ v69 too: the S25 forced to v69, HVX only, 512² 4 steps, a clean picture (`docs/DEVICES.md` §3c).
            minArch = DIT_V69_MIN_ARCH,
            ownDitParts = true,
            files = listOf(
                RemoteFile(
                    HF + "circlestone-labs/Anima/resolve/main/split_files/diffusion_models/anima-turbo-v1.1.safetensors",
                    "dit.gguf", AnimaGguf.TURBO_11_BYTES,
                    sourceBytes = 4_182_230_656L, convert = AnimaGguf.converter,
                ),
                RemoteFile(HF + "mradermacher/Qwen3-0.6B-Base-GGUF/resolve/main/Qwen3-0.6B-Base.Q8_0.gguf", "llm.gguf", 639_447_232L),
                RemoteFile(HF + "circlestone-labs/Anima/resolve/main/split_files/vae/qwen_image_vae.safetensors", "vae.safetensors", 253_806_246L),
                RemoteFile(HF + "Qwen/Qwen3-0.6B-Base/resolve/main/tokenizer.json", "tokenizer.json", 7_031_645L),
            ),
        ),
        // ⭐ Anima 2.9B preview (Gazingstars123, CircleStone non-commercial; `docs/ANIMA-GGML.md` §4): Anima's 28 blocks
        // deepened to 40, the same engine path unchanged (it reads the depth from the weights). A BASE model: 28 steps
        // at CFG 4, its card's floor (28-50, 3.5-5). Converted on the phone like Turbo ([AnimaGguf], its RoPE tables
        // dropped), to [AnimaGguf.ANIMA_29_PREVIEW_BYTES]. Measured on the S25 (1.6.186): 1024², 20 steps, CFG 4, 300 s.
        // ⚠ Its own copy of Turbo's encoder, VAE and tokenizer (0.9 GB): [ModelSpec.partsFrom] reaches the backend
        // for Wan only. ⚠ Below v79 never run (as Turbo was before its 8 Gen 2 report).
        // ⭐⭐ With Karely_ai's turbo LoRA on every render ([ModelSpec.ownLora], GitHub #55, the user's call 2026-10-19:
        // the easiest path, on by default): 8 steps at CFG 1, 95 s at 1024² on the S25 against ~7 min for the base's
        // 28 at CFG 4. Re-hosted unchanged on our HF (CivitAI serves it only with a key; its permissions allow it).
        dit(
            "anima_29b_preview", "Anima 2.9B (preview)", Family.ANIMA_DIT, KREA2, steps = 8, cfg = 1.0,
            prompt = "",
            // ⭐ v69 too: the S25 forced to v69 through the app, 512² 4 steps, turbo LoRA on, 357 s, clean (`DEVICES.md` §3c).
            minArch = DIT_V69_MIN_ARCH,
            ownDitParts = true,
            ownLora = ANIMA29_TURBO_LORA,
            files = listOf(
                RemoteFile(
                    HF + "Gazingstars123/Anima-2.9B/resolve/main/Anima-2.9B-preview-v1.safetensors",
                    "dit.gguf", AnimaGguf.ANIMA_29_PREVIEW_BYTES,
                    sourceBytes = 5_843_204_206L, convert = AnimaGguf.converter,
                ),
                RemoteFile(HF + "mradermacher/Qwen3-0.6B-Base-GGUF/resolve/main/Qwen3-0.6B-Base.Q8_0.gguf", "llm.gguf", 639_447_232L),
                RemoteFile(HF + "circlestone-labs/Anima/resolve/main/split_files/vae/qwen_image_vae.safetensors", "vae.safetensors", 253_806_246L),
                RemoteFile(HF + "Qwen/Qwen3-0.6B-Base/resolve/main/tokenizer.json", "tokenizer.json", 7_031_645L),
                RemoteFile(HF + "AbrahamPJ/nightmare-anima-turbo-lora/resolve/main/anima29_turbo_remapped.safetensors", ANIMA29_TURBO_LORA, 148_911_854L),
            ),
        ),
        // ⭐⭐ Iris 3B Turbo (speridlabs, Apache 2.0; `docs/IRIS.md`): a pixel-space DiT, so no
        // VAE, and Krea 2's Qwen3-VL-4B text encoder. 4 steps on Turbo's own schedule, which the
        // engine's Iris default reproduces exactly (`dit/022`). The official BF16 is converted on the
        // phone ([IrisGguf]), nothing re-hosted; sizes HEAD-checked 2026-10-16.
        //
        // ⚠ Only its trained sizes ([IRIS_SIZES]): at 512² its patches decode apart into blocks.
        // ⚠ Needs `GGML_HEXAGON_OPFUSION` without MUL_MAT_ADD, as Anima does ([BackendProcess]).
        // ⚠ Offered from v73 ([DIT_V73_MIN_ARCH]) like every DiT here, with the same below-v79 env; never run
        // below v79 yet.
        dit(
            "iris_3b_turbo", "Iris 3B Turbo", Family.IRIS, IRIS, steps = 4,
            ownDitParts = true,
            minArch = DIT_V73_MIN_ARCH,
            sizes = IRIS_SIZES,
            files = listOf(
                RemoteFile(
                    HF + "speridlabs/iris-3b/resolve/main/turbo/model.safetensors",
                    "dit.gguf", IrisGguf.TURBO_BYTES,
                    sourceBytes = 5_975_089_200L, convert = IrisGguf.converter,
                ),
                RemoteFile(HF + "bartowski/Qwen_Qwen3-VL-4B-Instruct-GGUF/resolve/main/Qwen_Qwen3-VL-4B-Instruct-Q4_0.gguf", "llm.gguf", 2_375_774_112L),
                RemoteFile(HF + "Qwen/Qwen3-VL-4B-Instruct/resolve/main/tokenizer.json", "tokenizer.json", 7_032_403L),
            ),
        ),
        // ⭐⭐ MOSS-Image (OpenMOSS, Apache 2.0; `docs/MOSS.md`): the SFT DiT as Q4_0 (ours, made from realrebelai's
        // Q8_0; the NPU runs no K-quant), Qwen3.5 9B Q4_0 and its own tokenizer.json (a vocabulary Qwen2's is not:
        // the engine reads it via `SD_QWEN35_TOKENIZER`, [BackendProcess]), the FLUX.2 VAE, and lodestones' 8-step
        // LoRA on every render ([ModelSpec.ownLora]). Measured on the S25 (v79, `all=disk`, engine `dit/035`): 512² 8
        // steps 50 s, 1024² 163 s. ⭐ v75 (8 Gen 3) ALPHA since 1.6.214: right on the S25 forced to v75, 512² 133 s; a real
        // 8 Gen 3 is owed (`docs/MOSS.md` §3b). Sizes read off the files 2026-10-17.
        dit(
            "moss_image", "MOSS-Image SFT (8 steps)", Family.MOSS, KREA2, steps = 8,
            ownDitParts = true,
            minArch = DIT_GGML_MIN_ARCH,
            prompt = "a red fox sitting in a snowy pine forest at dawn, soft light, photograph",
            ownLora = MOSS_LORA,
            files = listOf(
                RemoteFile(HF + "AbrahamPJ/nightmare-moss-image/resolve/main/moss-image-sft-q4_0.gguf", "dit.gguf", 3_217_359_072L),
                RemoteFile(HF + "unsloth/Qwen3.5-9B-GGUF/resolve/main/Qwen3.5-9B-Q4_0.gguf", "llm.gguf", 5_379_417_312L),
                RemoteFile(HF + "realrebelai/MOSS-Image_GGUFs/resolve/main/MOSS-VAE.safetensors", "vae.safetensors", 249_521_372L),
                RemoteFile(HF + "OpenMOSS-Team/MOSS-Image-Base/resolve/main/tokenizer/tokenizer.json", "tokenizer.json", 19_989_325L),
                RemoteFile(HF + "lodestones/MOSS-Image-SFT-8-steps-dmd/resolve/main/lora.safetensors", MOSS_LORA, 1_189_176_800L),
            ),
        ),
        // ⭐ MOSS-Image BASE (#6960, a tester's wish): the pre-SFT model, as Q4_0 (ours, from realrebelai's Base Q8_0,
        // 2026-10-21; the same 3,217,359,072 bytes as SFT's, the same layout), with lodestones' Base 8-step DMD LoRA
        // (CFG 1, 4-8 steps, its card). ⭐ SFT's encoder, VAE and tokenizer are BORROWED when SFT is installed
        // ([ModelSpec.partsFrom], backend 040): 5.6 GB not fetched twice; this list still names them for a phone
        // without SFT. ⚠ Never run on a phone until measured (PROGRESS).
        dit(
            "moss_image_base", "MOSS-Image Base (8 steps)", Family.MOSS, KREA2, steps = 8,
            ownDitParts = true,
            minArch = DIT_GGML_MIN_ARCH,
            partsFrom = "moss_image",
            prompt = "a red fox sitting in a snowy pine forest at dawn, soft light, photograph",
            ownLora = MOSS_LORA,
            files = listOf(
                RemoteFile(HF + "AbrahamPJ/nightmare-moss-image/resolve/main/moss-image-base-q4_0.gguf", "dit.gguf", 3_217_359_072L),
                RemoteFile(HF + "unsloth/Qwen3.5-9B-GGUF/resolve/main/Qwen3.5-9B-Q4_0.gguf", "llm.gguf", 5_379_417_312L),
                RemoteFile(HF + "realrebelai/MOSS-Image_GGUFs/resolve/main/MOSS-VAE.safetensors", "vae.safetensors", 249_521_372L),
                RemoteFile(HF + "OpenMOSS-Team/MOSS-Image-Base/resolve/main/tokenizer/tokenizer.json", "tokenizer.json", 19_989_325L),
                RemoteFile(HF + "lodestones/MOSS-Image-Base-8-steps-dmd/resolve/main/lora.safetensors", MOSS_LORA, 1_189_176_800L),
            ),
        ),
        // ⭐ MOSS-Image TURBO (1.6.215, the user's call): molbal's "MOSS Image Turbo GGUF (8 step)" Q4_0, SFT with
        // lodestones' 8-step LoRA merged before quantizing (CivitAI 3415613, every permission open; re-hosted on ours).
        // No run-time LoRA, so the fastest MOSS: S25 1024² 8 steps 145 s against SFT's 163 s (`docs/MOSS.md` §3).
        // Fused `proj.qkv` (Comfy-Org's layout). SFT's parts BORROWED as Base does ([ModelSpec.partsFrom]).
        dit(
            "moss_image_turbo", "MOSS-Image Turbo (8 steps)", Family.MOSS, KREA2, steps = 8,
            ownDitParts = true,
            minArch = DIT_GGML_MIN_ARCH,
            partsFrom = "moss_image",
            prompt = "a red fox sitting in a snowy pine forest at dawn, soft light, photograph",
            files = listOf(
                RemoteFile(HF + "AbrahamPJ/nightmare-moss-image/resolve/main/moss-image-turbo-q4_0-molbal.gguf", "dit.gguf", 3_328_170_880L),
                RemoteFile(HF + "unsloth/Qwen3.5-9B-GGUF/resolve/main/Qwen3.5-9B-Q4_0.gguf", "llm.gguf", 5_379_417_312L),
                RemoteFile(HF + "realrebelai/MOSS-Image_GGUFs/resolve/main/MOSS-VAE.safetensors", "vae.safetensors", 249_521_372L),
                RemoteFile(HF + "OpenMOSS-Team/MOSS-Image-Base/resolve/main/tokenizer/tokenizer.json", "tokenizer.json", 19_989_325L),
            ),
        ),
        // ⭐⭐ FLUX.3 Image Klein (Black Forest Labs, 5.6B, FLUX Kommunity License; `NOTICE`): leejet's Q4_0 DiT (every
        // matmul Q4_0, the NPU's), Krea 2's and Iris' Qwen3-VL-4B Q4_0 encoder and tokenizer.json (the engine reads
        // layers 4 to 32 through Qwen3-VL's template, `dit/042`), and Klein's FLUX.2 VAE from the same url as Ideogram's
        // (BFL's own comfy repo is gated). The distilled model: 16 steps at CFG 1, euler (upstream's docs/flux3.md).
        // Launched as `--type krea2`, the engine reads FLUX.3 off the weights. Sizes read off the files 2026-10-25.
        // ⭐ Krea 2's encoder and tokenizer are BORROWED when Krea 2 is installed ([ModelSpec.partsFrom], backend 040):
        // 2.4 GB not fetched twice. The VAE is never Krea 2's (Wan's), so it is always this model's own.
        dit(
            "flux3_klein", "FLUX.3 Image Klein", Family.FLUX3, KREA2, steps = 16,
            ownDitParts = true,
            partsFrom = "krea2_turbo",
            // ⭐ v75 (8 Gen 3, alpha) since 1.6.249: engine `dit-engine-abi108-fibo` carries `dit/055`'s block cut
            // points, so the 2.75 GiB budget streams it (S25 forced to v75: 512² 129 s, 58 dB from v79; DEVICES §3e).
            minArch = DIT_GGML_MIN_ARCH,
            prompt = "a red fox sitting in a snowy pine forest at dawn, soft light, photograph",
            files = listOf(
                RemoteFile(HF + "leejet/flux-3-image-klein-GGUF/resolve/main/flux3-image-klein-Q4_0.gguf", "dit.gguf", 3_514_023_104L),
                RemoteFile(HF + "bartowski/Qwen_Qwen3-VL-4B-Instruct-GGUF/resolve/main/Qwen_Qwen3-VL-4B-Instruct-Q4_0.gguf", "llm.gguf", 2_375_774_112L),
                RemoteFile(HF + "zhiyuanasad/flux2_klein_adreno/resolve/main/vae.safetensors", "vae.safetensors", 336_213_556L),
                RemoteFile(HF + "Qwen/Qwen3-VL-4B-Instruct/resolve/main/tokenizer.json", "tokenizer.json", 7_032_403L),
            ),
        ),
        // ⭐⭐ Fibo 2.0 Edit Turbo (Bria, 8B, non-commercial, GH #69; `NOTICE`): realrebelai's Q4_0 GGUF, FLUX.3's Qwen3-VL-4B
        // Q4_0 encoder and tokenizer with bartowski's f16 vision tower (`llm_vision.gguf`: Fibo reads the picture through
        // it, backend 049 passes it), the FLUX.2 VAE. 4 steps at CFG 1, euler (upstream's fibo2-edit-turbo recipe).
        // Launched as `--type krea2`; the engine reads Fibo off the weights (`dit/056`). An editor: the picture is the
        // reference (backend 049 makes a launch with a vision tower a native editor). ⭐ S25 (sd-cli, engine 056+057,
        // `all=disk` 2.5 GiB, 512² 4 steps): 72 s, 15 s a step, the CPU's edit within 32 dB; without 057's fused rope
        // 53 s a step. ⭐ v75 (the S25 forced to the 8 Gen 3 skel, the app's below-v79 op filter, 2.75 GiB): 82 s, the
        // v79 picture within 56.6 dB (`docs/FIBO2.md` §2). ⭐ Krea 2's encoder and tokenizer are borrowed when Krea 2
        // is installed ([ModelSpec.partsFrom]), as FLUX.3's are. Sizes read off the files 2026-10-27.
        dit(
            "fibo2_edit_turbo", "Fibo 2.0 Edit Turbo", Family.FIBO2, KREA2, steps = 4,
            ownDitParts = true,
            partsFrom = "krea2_turbo",
            minRamBytes = Prefs.RAM_12GB_CLASS_BYTES,
            minArch = DIT_GGML_MIN_ARCH,
            prompt = "Make the sky a warm sunset and keep everything else the same",
            files = listOf(
                // ⭐ Ours: realrebelai's Q4_0 with its BF16 cross-attention, perceiver and noise refiner also Q4_0 (4.87 GB, not
                // 6.59): the same edit within 29 dB, and 11.4 s a step on the S25 against 15 (`docs/FIBO2.md` §2).
                RemoteFile(HF + "AbrahamPJ/fibo2-edit-turbo-q4_0-gguf/resolve/main/fibo2_edit_turbo-q4_0.gguf", "dit.gguf", 4_870_390_432L),
                RemoteFile(HF + "bartowski/Qwen_Qwen3-VL-4B-Instruct-GGUF/resolve/main/Qwen_Qwen3-VL-4B-Instruct-Q4_0.gguf", "llm.gguf", 2_375_774_112L),
                RemoteFile(HF + "bartowski/Qwen_Qwen3-VL-4B-Instruct-GGUF/resolve/main/mmproj-Qwen_Qwen3-VL-4B-Instruct-f16.gguf", "llm_vision.gguf", 836_180_256L),
                RemoteFile(HF + "zhiyuanasad/flux2_klein_adreno/resolve/main/vae.safetensors", "vae.safetensors", 336_213_556L),
                RemoteFile(HF + "Qwen/Qwen3-VL-4B-Instruct/resolve/main/tokenizer.json", "tokenizer.json", 7_032_403L),
            ),
        ),
        // ⭐⭐ Ideogram 4 (Ideogram Non-Commercial Model Agreement; `NOTICE`): leejet's Q4_0 DiT, unsloth's Qwen3-VL-8B
        // Q4_0 (the engine reads 13 of its hidden layers) with Qwen3-VL's tokenizer.json (the backend loads one for a
        // `krea2` launch), Klein's FLUX.2 VAE, and ostris' TurboTime LoRA on every render ([ModelSpec.ownLora]): the
        // base needs a second 9.3B unconditional model and 20+ steps. ⚠ `dit/029` is what makes it usable: the stock
        // engine runs its rope as composed ops, 73-85 s a step at 768². Measured on the S25 (v79, sd-cli, `all=disk`
        // 2.5 GiB): 768² 8 steps 324 s, 36-42 s a step, the sign's text spelled right. ⚠ v79 only. Sizes read off the
        // files 2026-10-18.
        dit(
            "ideogram_4_turbo", "Ideogram 4 (8 steps)", Family.IDEOGRAM, KREA2, steps = 8,
            ownDitParts = true,
            // ⚠ Krea 2's gate and budget ([BackendProcess.kreaBudget]): 6.2 GB on the NPU, streamed below 16 GB.
            minRamBytes = Prefs.RAM_12GB_CLASS_BYTES,
            minArch = DIT_MIN_ARCH,
            startRes = Res(768, 768),
            prompt = "a cozy bookshop storefront at dusk, a wide wooden sign above the window reads OPEN LATE, warm light, realistic photograph",
            ownLora = IDEOGRAM_LORA,
            files = listOf(
                RemoteFile(HF + "leejet/ideogram-4-GGUF/resolve/main/ideogram4-Q4_0.gguf", "dit.gguf", 5_643_820_832L),
                RemoteFile(HF + "unsloth/Qwen3-VL-8B-Instruct-GGUF/resolve/main/Qwen3-VL-8B-Instruct-Q4_0.gguf", "llm.gguf", 4_787_333_984L),
                RemoteFile(HF + "zhiyuanasad/flux2_klein_adreno/resolve/main/vae.safetensors", "vae.safetensors", 336_213_556L),
                RemoteFile(HF + "Qwen/Qwen3-VL-8B-Instruct/resolve/main/tokenizer.json", "tokenizer.json", 7_032_403L),
                RemoteFile(HF + "ostris/ideogram_4_turbotime_lora/resolve/main/ideogram_4_turbotime_v1.safetensors", IDEOGRAM_LORA, 846_778_576L),
            ),
        ),
        // ⭐ FLUX.2 Klein 9B — the same architecture as 4B, so the same family,
        // sampler and edit; only the files differ. `leejet`'s Q4_0 DiT and
        // Qwen3-8B as the text encoder, as the fork's README runs it (7841da2;
        // sizes HEAD-checked 2026-09-28). The VAE and tokenizer are 4B's.
        //
        // ⚠ [ModelSpec.ownDitParts]: the 8B encoder is not the family's 4B one,
        // so nothing here may come from, or go to, `_dit_shared`.
        // ⚠ `lowram` (te=disk) is the fork's own setting for the 9B: 10.7 GB on
        // disk, a 5.6 GB DiT — Z-Image's footprint with its encoder streamed.
        dit(
            "flux2_klein_9b", "FLUX.2 Klein 9B", Family.FLUX2, KLEIN, steps = 4,
            lowram = true,
            minArch = DIT_V73_MIN_ARCH,
            // ⚠ Measured 2026-09-28 on the 12 GB S25 Ultra, five renders in a
            // row: 768² 5 of 5 (64–94 s); 1024² 3 of 4 (84–97 s), the fourth
            // reaped by lmkd mid-sampling. ⇒ Nodes start at 768.
            startRes = Res(768, 768),
            ownDitParts = true,
            files = listOf(
                RemoteFile(HF + "leejet/FLUX.2-klein-9B-GGUF/resolve/main/flux-2-klein-9b-Q4_0.gguf", "dit.gguf", 5_616_208_032L),
                RemoteFile(HF + "bartowski/Qwen_Qwen3-8B-GGUF/resolve/main/Qwen_Qwen3-8B-Q4_0.gguf", "llm.gguf", 4_787_332_640L),
                RemoteFile(HF + "zhiyuanasad/flux2_klein_adreno/resolve/main/vae.safetensors", "vae.safetensors", 336_213_556L),
                RemoteFile(HF + "Qwen/Qwen3-8B/resolve/main/tokenizer.json", "tokenizer.json", DIT_TOKENIZER_BYTES),
            ),
        ),
        // ⭐ Qwen Image 2.1 — upstream local-dream v3.0.0-alpha.3 (440899f) and
        // alpha.4 (18be9aa), sizes HEAD-checked. A DiT plus Qwen3-VL-8B as the
        // text encoder; `llm_vision.gguf` is its vision tower, which native
        // EDITING needs. 20 steps at cfg 1, upstream's defaults.
        //
        // ⭐⭐ TWO BUILDS BY RAM (the user's call, 2026-10-06): alpha.4 moved the
        // DiT to unsloth's FP8 (7.1 GB, 6.8 GB of params) — and on the 12 GB S25
        // Ultra, with the app IN FRONT, the text encoder ran (3.74 GB) and lmkd
        // killed the app 2 s into the FP8 DiT load (`docs/DIT.md` §9b). So FP8 is
        // the 16 GB class's build and Q4_0 (4.2 GB, 4.0 GB of params; ran here on
        // 2026-09-28) is everyone else's. The parts beside the DiT are shared.
        //
        // ⚠ Whatever the build, the backend runs it `all=disk` — each of TE, DiT
        // and VAE loaded for its stage and dropped (backend-patches/013, 022).
        // ⚠ Every file lives in the model's OWN directory: `_dit_shared` holds
        // FLUX.2/Z-Image's Qwen3-4B encoder and VAE, which are not these.
        // ⚠ [reclaimObsolete] deletes the OTHER build's DiT: backend 014 loads a
        // `dit.gguf` in preference to `dit.safetensors`.
        run {
            val fp8 = listOf(QWEN21_FP8) + QWEN21_SHARED
            val q4 = listOf(QWEN21_Q4) + QWEN21_SHARED
            dit(
                "qwen_image_2_1", "Qwen Image 2.1", Family.QWEN21, QWEN21, steps = 20,
                prompt = "a lovely cat holding a sign that says 'Qwen Image 2.1',",
                ownDitParts = true,
                files = q4,
                builds = listOf(
                    Build(TIER_DIT, "", fp8.sumOf { it.bytes }, DIT_MIN_ARCH, 8, minRamBytes = Prefs.LOWRAM_BELOW_BYTES, files = fp8),
                    // ⭐ Q4_0: also the build a v75 phone takes, whatever its RAM ([DIT_GGML_MIN_ARCH]).
                    Build(TIER_DIT, "", q4.sumOf { it.bytes }, DIT_GGML_MIN_ARCH, 8, files = q4),
                ),
            )
        },
        // ⭐ Qwen Image 2.1 TURBO (the user's call, 2026-10-14: a second entry, the base stays): the same
        // 7B, distilled to 8 steps at cfg 1. S25, 1024²: 104 s against the base's 259 s; edit 469 s against
        // 14.5 min (`docs/DIT.md` §9b). One Q4_0 build for every phone (no Turbo FP8 is published).
        // ⚠ Its own directory, like the base: the backend reads Qwen's parts from the model's dir only, so a
        // phone with both holds the 6.6 GB of parts twice (FUSE has no links; [CustomModels.ditPartsPlan]).
        // ⚠ Turbo was distilled on its own 8 sigmas; the engine sends sd.cpp's shift 3, which looked the same
        // on the S25 (one prompt). The exact schedule needs an ABI 107 engine.
        dit(
            "qwen_image_2_1_turbo", "Qwen Image 2.1 Turbo", Family.QWEN21, QWEN21, steps = 8,
            prompt = "a lovely cat holding a sign that says 'Qwen Image 2.1',",
            ownDitParts = true,
            files = listOf(QWEN21_TURBO_Q4) + QWEN21_SHARED,
        ),
    )

    /**
     * ⭐ Every catalogue model, the video ones ([video]) included. ⚠ Only the launch path and
     * the video row need [video]; a checkpoint list must keep reading [all].
     */
    fun byId(id: String): ModelSpec? = all.firstOrNull { it.id == id } ?: video.firstOrNull { it.id == id }

    /** ⭐ The backend `--type` of [WAN22] (backend 028). */
    const val WAN = "wan"

    /**
     * ⭐⭐ **Wan 2.2 TI2V 5B Turbo** — quanhaol's Self-Forcing DMD distillation, 4 steps at
     * cfg 1, as hum-ma's Q4_0 GGUF; umT5-XXL as city96's Q4_K_M (Q8_0's picture quality,
     * 2.3 GB less RAM); TAEHV as the decoder (madebyollin, GitHub only, pinned to a commit). Sizes HEAD-checked 2026-10-14.
     * Measured on the S25 Ultra: `docs/VIDEO-GGML.md` §1.
     * ⭐ Alpha on the 8 Gen 3 (v75, the user's call 2026-10-14): the S25 forced to v75 renders the v79
     * clip (50.5 dB, T2V). ⚠ Unproven on a real 8 Gen 3, whose ~3.1 GB map cap the forced S25 lacks
     * (`docs/VIDEO-GGML.md` §6).
     */
    val WAN22: ModelSpec = dit(
        "wan22_ti2v_turbo", "Wan 2.2 Turbo", Family.WAN, WAN, steps = 4,
        prompt = "",
        ownDitParts = true,
        minArch = DIT_GGML_MIN_ARCH,
        files = listOf(
            RemoteFile(HF + "hum-ma/Wan2.2-TI2V-5B-Turbo-GGUF/resolve/main/Wan2_2-TI2V-5B-Turbo-Q4_0.gguf", "dit.gguf", 3_033_897_696L),
            RemoteFile(HF + "city96/umt5-xxl-encoder-gguf/resolve/main/umt5-xxl-encoder-Q4_K_M.gguf", "umt5.gguf", 3_655_145_312L),
            RemoteFile("https://raw.githubusercontent.com/madebyollin/taehv/fa579a9a726b0a55951998d73e309bfdf0abd342/safetensors/taew2_2.safetensors", "tae.safetensors", 22_848_048L),
        ),
    )

    /**
     * ⭐⭐ **Wan 2.2 Fun 5B Control** (alibaba-pai, QuantStack's Q4_0 GGUF): a picture moving like a driving
     * video (its pose or depth), the "Motion transfer" flow (`npu.WanFunNode`, `docs/VIDEO-GGML.md` §7).
     * A fine-tune of the BASE TI2V 5B: Kijai's FastWan 5B LoRA ([FUN_LORA]) gives it 3 steps at cfg 1.
     * ⭐ Its umT5 and TAEHV are Wan 2.2 Turbo's when Turbo is installed ([ModelSpec.partsFrom]): 3.7 GB
     * then, 7.3 GB alone. Measured on the S25: 480x832 x49 in 84 s, 704x1280 x49 in 259 s (2026-10-15).
     */
    val WAN22_FUN: ModelSpec = dit(
        "wan22_fun5b_control", "Wan 2.2 Fun Control", Family.WAN, WAN, steps = 3,
        prompt = "",
        ownDitParts = true,
        partsFrom = "wan22_ti2v_turbo",
        minArch = DIT_GGML_MIN_ARCH,
        files = listOf(
            RemoteFile(HF + "QuantStack/Wan2.2-Fun-5B-Control-GGUF/resolve/main/Wan2.2-Fun-5B-Control-Q4_0.gguf", "dit.gguf", 3_040_004_960L),
            RemoteFile(HF + "Kijai/WanVideo_comfy/resolve/main/FastWan/Wan2_2_5B_FastWanFullAttn_lora_rank_128_bf16.safetensors", FUN_LORA, 660_874_456L),
            RemoteFile(HF + "city96/umt5-xxl-encoder-gguf/resolve/main/umt5-xxl-encoder-Q4_K_M.gguf", "umt5.gguf", 3_655_145_312L),
            RemoteFile("https://raw.githubusercontent.com/madebyollin/taehv/fa579a9a726b0a55951998d73e309bfdf0abd342/safetensors/taew2_2.safetensors", "tae.safetensors", 22_848_048L),
        ),
    )

    /** ⭐ Fun Control's speed LoRA, in its own folder (applied by `npu.WanFunNode`). */
    const val FUN_LORA = "fastwan_lora.safetensors"

    /** ⭐ Wan 2.2 A14B's high-noise expert, beside its low-noise `dit.gguf` ([WAN22_A14B], engine `dit/044`). */
    const val WAN_HIGH_DIT = "dit_high.gguf"
    /** ⭐ Lightning's two halves, one per expert, applied on every clip by the engine (`dit/044`). */
    const val WAN_HIGH_LORA = "lightning_high.safetensors"
    const val WAN_LOW_LORA = "lightning_low.safetensors"

    /**
     * ⭐⭐ **Wan 2.2 T2V A14B** (Wan-AI, Apache 2.0): the big Wan, two 14B experts (high noise for the first steps,
     * low noise for the rest), QuantStack's Q4_0 GGUFs, 4 steps through lightx2v's Lightning LoRAs (Seko V1.1,
     * rank 64, one per expert: euler, shift 5, CFG 1, two steps each, their ComfyUI workflow). ⚠ The LoRAs are OUR
     * FP16 copies (AbrahamPJ/nightmare-wan22-a14b-lightning): the originals store `alpha` as I64, which the engine
     * aborts on ("type i32 unsupported"), and their F32 weights are twice the bytes to stream. The Wan 2.1 latent
     * space, so TAEHV's `taew2_1` decodes, and 16 fps. Its umT5 is Wan 2.2 Turbo's when Turbo is installed
     * ([ModelSpec.partsFrom]). ⚠ 8.6 GB a expert: it always STREAMS (`all=disk` + a budget, `npu.WanA14Node`), one
     * block at a time. Text to video; a wired picture runs [WAN22_A14B_I2V]. Measured on the S25: `docs/VIDEO-GGML.md` §8.
     */
    val WAN22_A14B: ModelSpec = dit(
        "wan22_t2v_a14b", "Wan 2.2 14B", Family.WAN, WAN, steps = 4,
        prompt = "",
        ownDitParts = true,
        partsFrom = "wan22_ti2v_turbo",
        minArch = DIT_MIN_ARCH,
        minRamBytes = Prefs.RAM_12GB_CLASS_BYTES,
        files = listOf(
            RemoteFile(HF + "QuantStack/Wan2.2-T2V-A14B-GGUF/resolve/main/LowNoise/Wan2.2-T2V-A14B-LowNoise-Q4_0.gguf", "dit.gguf", 8_556_458_496L),
            RemoteFile(HF + "QuantStack/Wan2.2-T2V-A14B-GGUF/resolve/main/HighNoise/Wan2.2-T2V-A14B-HighNoise-Q4_0.gguf", WAN_HIGH_DIT, 8_556_458_496L),
            RemoteFile(HF + "AbrahamPJ/nightmare-wan22-a14b-lightning/resolve/main/wan22_t2v_a14b_lightning_high_fp16.safetensors", WAN_HIGH_LORA, 613_558_320L),
            RemoteFile(HF + "AbrahamPJ/nightmare-wan22-a14b-lightning/resolve/main/wan22_t2v_a14b_lightning_low_fp16.safetensors", WAN_LOW_LORA, 613_558_320L),
            RemoteFile(HF + "city96/umt5-xxl-encoder-gguf/resolve/main/umt5-xxl-encoder-Q4_K_M.gguf", "umt5.gguf", 3_655_145_312L),
            RemoteFile("https://raw.githubusercontent.com/madebyollin/taehv/fa579a9a726b0a55951998d73e309bfdf0abd342/safetensors/taew2_1.safetensors", "tae.safetensors", 22_642_902L),
        ),
    )

    /**
     * ⭐⭐ **Wan 2.2 I2V A14B, 4-step distilled** (Wan-AI, lightx2v's Wan2.2-Distill-Models; jayn7's Q4_0 GGUFs): a
     * picture into a clip on the big Wan. lightx2v trained the 4 steps INTO both experts (no Lightning LoRA, so no
     * run-time LoRA cost); `high_noise_1030` (2025-10-30) and `low_noise`, the newest with a Q4_0 (the 2026-04 pair
     * is Q4_K only, which the NPU does not run). Same schedule, fps and decoder as [WAN22_A14B], MoE boundary 0.9.
     * ⚠ The start picture is encoded through TAEHV in time chunks (`BackendProcess.wanA14Env`): whole, 480x832 x33
     * asked 6 GB. Measured on the S25 (sd-cli): 480x832 2 s in 538 s. `docs/VIDEO-GGML.md` §8.
     */
    val WAN22_A14B_I2V: ModelSpec = dit(
        "wan22_i2v_a14b", "Wan 2.2 14B", Family.WAN, WAN, steps = 4,
        prompt = "",
        ownDitParts = true,
        partsFrom = "wan22_ti2v_turbo",
        minArch = DIT_GGML_MIN_ARCH,  // ⭐ 8 Gen 3 (v75) since 2026-10-26, up to 832x480 there (`WanVideoNode.A14B_LOW_ARCH`)
        minRamBytes = Prefs.RAM_12GB_CLASS_BYTES,
        files = listOf(
            RemoteFile("https://huggingface.co/jayn7/WAN2.2-I2V_A14B-DISTILL-LIGHTX2V-4STEP-GGUF/resolve/main/low_noise/wan2.2_i2v_A14b_low_noise_lightx2v_4step-Q4_0.gguf", "dit.gguf", 8_567_937_664L),
            RemoteFile("https://huggingface.co/jayn7/WAN2.2-I2V_A14B-DISTILL-LIGHTX2V-4STEP-GGUF/resolve/main/high_noise_1030/wan2.2_i2v_A14b_high_noise_lightx2v_4step_1030-Q4_0.gguf", WAN_HIGH_DIT, 8_567_937_664L),
            RemoteFile(HF + "city96/umt5-xxl-encoder-gguf/resolve/main/umt5-xxl-encoder-Q4_K_M.gguf", "umt5.gguf", 3_655_145_312L),
            RemoteFile("https://raw.githubusercontent.com/madebyollin/taehv/fa579a9a726b0a55951998d73e309bfdf0abd342/safetensors/taew2_1.safetensors", "tae.safetensors", 22_642_902L),
        ),
    )

    /** ⭐ The NSFW 1.3B's speed LoRA, in its own folder (applied on every clip by `npu.WanVideoNode`). */
    const val CAUSVID_LORA = "causvid_lora.safetensors"

    /**
     * ⭐⭐ **NSFW Wan 1.3B** (NSFW-API's NSFW_Wan_1.3b, `exp_e14`, their recommended checkpoint): a Wan 2.1 T2V 1.3B
     * fine-tune, text to video only, trained by a community member, so [ModelSpec.community] (the user, 2026-10-29).
     * Nothing re-hosted: their BF16 file is converted to Q8_0 on the phone ([WanGguf]). Kijai's CausVid 1.3B LoRA
     * ([CAUSVID_LORA]) gives it 6 steps at cfg 1; Wan 2.1's latent space, so TAEHV's `taew2_1` and 16 fps, as the 14B.
     * Its umT5 is Wan 2.2 Turbo's when Turbo is installed ([ModelSpec.partsFrom]). ⚠ Wan 2.1's 8x VAE: four times the
     * 5B's tokens a frame, so slower than Turbo a second of clip (`docs/ROADMAP.md`, the stock 1.3B measured).
     */
    val WAN21_NSFW13: ModelSpec = dit(
        "wan21_nsfw_1_3b", "NSFW Wan 1.3B", Family.WAN, WAN, steps = 6,
        prompt = "",
        ownDitParts = true,
        partsFrom = "wan22_ti2v_turbo",
        minArch = DIT_MIN_ARCH,
        files = listOf(
            RemoteFile(
                HF + "NSFW-API/NSFW_Wan_1.3b/resolve/main/wan_1.3B_exp_e14.safetensors",
                "dit.gguf", WanGguf.NSFW13_BYTES,
                sourceBytes = 2_838_077_328L, convert = WanGguf.converter,
            ),
            RemoteFile(HF + "Kijai/WanVideo_comfy/resolve/main/Wan21_CausVid_bidirect2_T2V_1_3B_lora_rank32.safetensors", CAUSVID_LORA, 91_233_416L),
            RemoteFile(HF + "city96/umt5-xxl-encoder-gguf/resolve/main/umt5-xxl-encoder-Q4_K_M.gguf", "umt5.gguf", 3_655_145_312L),
            RemoteFile("https://raw.githubusercontent.com/madebyollin/taehv/fa579a9a726b0a55951998d73e309bfdf0abd342/safetensors/taew2_1.safetensors", "tae.safetensors", 22_642_902L),
        ),
    ).copy(community = true, mature = true)

    /** ⭐ MiniMax-H3's backend `--type` (backend 050): `/video` with a soundtrack beside the frames. */
    const val H3 = "h3"

    /**
     * ⭐⭐ **MiniMax H3** (MiniMax, MiniMax H3 Community License; `NOTICE`), as FastVideo's FastH3 4-step Preview v1 Dense:
     * a video WITH SOUND (speech, effects, music) from text, one DiT over video and stereo audio tokens
     * (`docs/MINIMAX-H3.md`). Our Q4_0 of Gestura's FastH3 GGUF (the NPU runs Q4_0, not their Q4_K), Gestura's Q8_0 video VAE,
     * Comfy's fp32 audio VAE, and Qwen3-VL-4B as the text encoder through NicoLab28's ClipProj v3.1 (MIT) in place of
     * H3's own 32B (the user's call, 2026-10-29: 2.4 GB against 13 GB, the same scene). The 4B is Krea 2's when Krea 2
     * is installed ([ModelSpec.partsFrom]). 4 steps, euler, CFG 1, 24 fps on a 17k+5 frame grid ([npu.H3Node]).
     * ⚠ [ModelSpec.licence]: the person accepts its territory and output terms before the download.
     * ⭐ v79 and v75 (8 Gen 3: the S25 forced to v75 at 2.75 GiB, `docs/MINIMAX-H3.md` §5d). 12 GB class: one block
     * streams at 4.5 GiB and the 1152x640 clip left 1.69 GB free on the S25.
     */
    val MINIMAX_H3: ModelSpec = dit(
        "minimax_h3_fast", "MiniMax H3", Family.WAN, H3, steps = 4,
        prompt = "",
        ownDitParts = true,
        partsFrom = "krea2_turbo",
        minArch = DIT_GGML_MIN_ARCH,
        minRamBytes = Prefs.RAM_12GB_CLASS_BYTES,
        files = listOf(
            RemoteFile(HF + "AbrahamPJ/fasth3-dense-v1-q4_0-gguf/resolve/main/fasth3_dense_v1_euler4_q4_0.gguf", "dit.gguf", 11_583_540_608L),
            RemoteFile(HF + "ZawShiShawn/Gestura-MiniMax-H3-VAE-GGUF/resolve/main/split/vae/minimax_h3_video_vae_Q8_0.gguf", "vae.gguf", 2_936_962_688L),
            RemoteFile(HF + "Comfy-Org/MiniMax-H3/resolve/main/vae/minimax_h3_audio_vae_fp32.safetensors", "audio_vae.safetensors", 605_254_808L),
            RemoteFile(HF + "NicoLab28/ClipProj-MiniMax-H3/resolve/main/mmh3-4b-ClipProj-v3.1.safetensors", "clipproj.safetensors", 26_256_128L),
            RemoteFile(HF + "bartowski/Qwen_Qwen3-VL-4B-Instruct-GGUF/resolve/main/Qwen_Qwen3-VL-4B-Instruct-Q4_0.gguf", "llm.gguf", 2_375_774_112L),
        ),
    ).copy(
        licence = ModelLicence(R.string.licence_h3_title, R.string.licence_h3_terms, "https://huggingface.co/MiniMaxAI/MiniMax-H3/blob/main/LICENSE"),
    )

    /** ⚠ The video models: found by [byId], never listed as checkpoints. */
    // ⚠ Not [WAN22_A14B]: one 14B download, its image to video experts; text to video on the 14B makes the first
    // frame with a picture model (`canvas.T2V14`, the user's call 2026-10-25: two 17 GB downloads was too much).
    val video: List<ModelSpec> = listOf(WAN22, WAN22_FUN, WAN22_A14B_I2V, WAN21_NSFW13, MINIMAX_H3)

    fun installed(context: Context): List<ModelSpec> = all.filter { it.installed(context) }

    /**
     * ⭐⭐ The backend `--type` a model needs -- **the one function both the
     * launch and every `contextKey()` read**.
     *
     * ⚠⚠ That is the whole point of it existing. `--type` was a literal in two
     * places (`BackendProcess` and three node types), and two literals that must
     * agree are two literals that eventually will not: a node keyed `sd15npu`
     * against a process launched `sdxl` does not fail, it renders at the wrong
     * size in silence.
     *
     * ⚠ An id that is not in the catalogue falls back to the SD 1.5 default
     * rather than throwing. A model directory pushed by hand over adb is a real
     * developer path and has no catalogue entry, and that is exactly what this
     * returned before the field existed -- so the fallback is today's behaviour,
     * not a new guess. It stops being harmless when a second family ships, at
     * which point an unknown id has no honest answer; the `model` param is
     * locked, so by then only a hand-edited workflow file can produce one.
     */
    fun backendTypeOf(modelId: String): String =
        byId(modelId)?.backendType ?: SD15_NPU

    /** The size a new node on [modelId] is built at. ⚠ Same fallback as [backendTypeOf]. */
    fun resolutionOf(modelId: String): Res = byId(modelId)?.native ?: SD15_NPU_RES
}

/**
 * Which model the app is currently set to use.
 *
 * ⚠⚠ ONE source of truth, read by BOTH the backend launch and the `model`
 * widget default. They cannot be allowed to disagree: the backend is launched
 * with `--model_dir <id>` while a node's `model` param only feeds the context
 * key, so a graph naming a different checkpoint from the running server does
 * not fail — it renders, silently, with the server's model. A picture that
 * looks almost right, from a mismatch nothing reports.
 *
 * ⚠ A process-global rather than a parameter because [NODE_TYPES] is a static
 * registry built without a `Context`. It is loaded once in [MainActivity].
 */
object SelectedModel {

    private const val PREFS = "nightmare"
    private const val KEY = "model"
    private const val KEY_RES = "resolution"

    @Volatile
    var id: String = V1_MODEL
        private set

    /**
     * ⭐⭐ The resolution every backend node on a new graph is sized to — the
     * `width`/`height` thirds of the [ContextKey], chosen graph-wide rather
     * than per node.
     *
     * ⚠⚠ **Graph-wide on purpose.** §5.2 pins ONE context key per graph, and
     * `--patch` binds at backend launch exactly as `--type` and `--model_dir`
     * do. A per-node size widget would make the executor's "needs 2 backend
     * contexts" refusal a normal-path experience for something the user chose
     * deliberately; picking it beside the model instead means [contextKeyRetarget]
     * rewrites every node at once and the graph still names one key. The pin is
     * untouched.
     *
     * ⚠ Stored per MODEL, not globally: 768x512 is meaningful on SD 1.5 and
     * impossible on SDXL, so one shared value would follow the user across a
     * family switch into a size that model cannot serve.
     */
    @Volatile
    var res: Res = ModelCatalog.SD15_NPU_RES
        private set

    /**
     * ⭐ The sizes the selected model can serve, **cached**.
     *
     * ⚠⚠ Cached because [ModelSpec.availableResolutions] reads the disk and the
     * places that need this list have no [Context]: the size chips are built
     * from a `NodeType.widgets` getter and drawn by a composable that must stay
     * a function of its arguments so the goldens can render it. Reading
     * `listFiles()` from either would be a disk hit inside a recomposition.
     *
     * ⚠ Refreshed wherever the answer can change — [load], [set], and after an
     * install or a delete ([refresh]). A stale list offers a size whose patch is
     * gone, and `BackendProcess.start` then refuses the launch by name, which is
     * the right failure but a late one.
     */
    @Volatile
    var resolutions: List<Res> = listOf(ModelCatalog.SD15_NPU_RES)
        private set

    /** ⚠ Call after anything that adds or removes files in a model directory. */
    fun refresh(context: Context) {
        perModel.clear()
        resolutions = spec.availableResolutions(context)
        ModelFeatures.refreshInstalled(context)
    }

    private val perModel = java.util.concurrent.ConcurrentHashMap<String, List<Res>>()

    /**
     * ⭐⭐ The sizes ANY model can serve, cached the same way as [resolutions].
     *
     * ⚠⚠ A sampler's size chips must come from ITS model, not from this
     * selection. Since the model became a per-node choice (`ARCHITECTURE.md`
     * §4) the two differ routinely, and reading [resolutions] showed an SD 1.5
     * node the one size SDXL has — so no size chooser at all (2026-09-17).
     * ⚠ Cleared by [refresh], which every install and delete already calls.
     */
    fun resolutionsOf(context: Context, spec: ModelSpec): List<Res> =
        perModel.getOrPut(spec.id) { spec.availableResolutions(context) }

    /**
     * The catalogue entry in use -- family, native resolution and `--type`.
     *
     * ⚠ Never null: [load] and [set] both refuse an id [ModelCatalog] does not
     * know, so the fallback here is unreachable and exists only so callers do
     * not each invent their own.
     */
    val spec: ModelSpec
        get() = ModelCatalog.byId(id) ?: ModelCatalog.byId(V1_MODEL)!!

    /**
     * ⚠ Falls back to [V1_MODEL] when the stored id names a model that is gone.
     *
     * ⚠⚠ **[CustomModels.scan] must have run first.** This drops a stored id
     * the catalogue cannot see, and an imported model is only in the catalogue
     * after a scan — so loading first would silently reset a user whose
     * selected checkpoint is a custom one back to [V1_MODEL] on every launch,
     * and the graph-authoritative restore would then relaunch the backend to
     * "correct" it. The scan is therefore unconditional and comes first in
     * [MainActivity] and [OpService]; it is cheap (one stat per directory).
     */
    fun load(context: Context) {
        CustomModels.scan(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY, null)
        id = stored?.takeIf { ModelCatalog.byId(it) != null } ?: V1_MODEL
        res = readRes(context, prefs.getString(resKey(id), null))
        refresh(context)
    }

    fun set(context: Context, newId: String) {
        require(ModelCatalog.byId(newId) != null) { "unknown model \"$newId\"" }
        id = newId
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY, newId).apply()
        // ⚠⚠ The resolution MUST be re-resolved here and not carried over. The
        // previous model's size may be one this one cannot serve at all -- every
        // SDXL size but 1024² , or a patch this checkpoint's archive did not
        // ship -- and a stale value would reach `--patch` as a file that is not
        // there. Re-reading per model is what makes [setRes] safe to be dumb.
        res = readRes(context, prefs.getString(resKey(newId), null))
        refresh(context)
    }

    /**
     * ⚠ Refuses a size the model cannot serve, rather than storing it and
     * failing at launch. The caller has [ModelSpec.availableResolutions] and
     * this is the backstop for a stored value that has gone stale -- a model
     * re-downloaded from a build with fewer patches, say.
     */
    fun setRes(context: Context, newRes: Res) {
        require(spec.serves(context, newRes)) {
            "\"${spec.label}\" cannot render $newRes. It serves ${spec.sizesServedText(context)}"
        }
        res = newRes
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(resKey(id), "${newRes.width}x${newRes.height}").apply()
    }

    private fun resKey(modelId: String) = "$KEY_RES.$modelId"

    /**
     * ⚠ Falls back to the model's native size whenever the stored string is
     * absent, malformed, or names a resolution this install can no longer
     * serve. All three are the same answer and none is an error.
     */
    private fun readRes(context: Context, stored: String?): Res {
        // ⚠ [ModelSpec.bornAt], not `native`: a model with no stored size
        // starts where its entry says (Klein 9B at 768²).
        val native = spec.bornAt
        val parts = stored?.split("x") ?: return native
        val w = parts.getOrNull(0)?.toIntOrNull() ?: return native
        val h = parts.getOrNull(1)?.toIntOrNull() ?: return native
        val want = Res(w, h)
        return if (spec.serves(context, want)) want else native
    }
}
