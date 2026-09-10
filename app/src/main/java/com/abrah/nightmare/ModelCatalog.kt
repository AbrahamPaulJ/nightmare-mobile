package com.abrah.nightmare

import android.content.Context
import java.io.File

/**
 * ⭐ What a checkpoint IS, as opposed to which files it ships.
 *
 * ⚠ The family decides the **node vocabulary** and the native resolution, and
 * nothing else (`docs/MODELS.md` §2). Two checkpoints of the same family are
 * interchangeable in a graph; two families are not, because `sd.sample` on an
 * SDXL model is a different `--type` and therefore a different [ContextKey].
 */
enum class Family(val label: String) {
    SD15("SD 1.5"),
    SDXL("SDXL"),
    ANIMA("Anima"),
}

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
) {
    fun runsOn(caps: DeviceProbe.Caps): Boolean =
        caps.arch >= minArch && caps.vtcmMb >= minVtcmMb
}

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
     * SDXL is compiled `_8gen3`, so 75. **Nothing enforces this yet** — the app
     * ships V79 libraries only and therefore runs on one phone, and lifting
     * both together is `notes/PROGRESS.md` ①. It is carried as data now so the
     * gate has something true to read when it is built.
     */
    val minHtpArch: Int = 68,
    /**
     * `--lowram`: load and release each stage rather than holding the whole
     * pipeline resident. ⚠ Not a preference — SDXL's UNet is ~3× SD 1.5's at
     * 1024², and DreamUI sets it on every SDXL checkpoint.
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
) {
    /** ⚠ [resolutions] is never empty; the constructor default is one entry. */
    val native: Res get() = resolutions.first()

    /**
     * ⭐⭐ The best build this phone can actually load, or **null** when it can
     * load none.
     *
     * ⚠ Null is a real answer and the UI must show it as one: SDXL publishes
     * `_8gen3` only, so on anything below a v75 HTP there is no build at all —
     * not a slower one, none. Falling back to "download the biggest and hope"
     * is how a user spends 3.5 GB on a file their chip rejects at load.
     */
    fun buildFor(caps: DeviceProbe.Caps): Build? = builds.firstOrNull { it.runsOn(caps) }

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

    fun dir(context: Context): File = File(ModelCatalog.root(context), id)

    /** Required files that are absent — i.e. why the backend cannot start. */
    fun missing(context: Context): List<String> {
        val d = dir(context)
        return requiredFiles.filter { !File(d, it).exists() }
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
     */
    fun wanted(name: String): Boolean =
        name in requiredFiles || name == "vae_encoder.bin" || name.endsWith(".patch")

    fun installed(context: Context): Boolean = missing(context).isEmpty()

    /** What this model occupies on disk, or 0 when it is not installed. */
    fun bytesOnDisk(context: Context): Long =
        dir(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }
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
    val SCHEDULERS = listOf(
        "dpm", "dpm_karras", "dpm_sde", "dpm_sde_karras",
        "euler", "euler_karras", "euler_a", "euler_a_karras",
        "lcm",
    )

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

    fun root(context: Context): File = File(context.getExternalFilesDir(null), "models")

    /** Where a part-downloaded archive lives. ⚠ Not the model dir: a stray zip there reads as a model. */
    fun downloads(context: Context): File =
        File(context.getExternalFilesDir(null), "downloads")

    // ---- prompts ---------------------------------------------------------
    // Model parameters rather than UI copy, and deliberately the same values
    // DreamUI uses, so a picture that differs between the two apps is a
    // difference in the RUNTIME rather than in what was asked for.

    private const val NEG_PHOTO =
        "cartoon, anime, illustration, painting, drawing, lowres, bad anatomy, worst quality, low quality"
    private const val NEG_ANIME =
        "lowres, bad anatomy, bad hands, text, error, missing fingers, worst quality, low quality, jpeg artifacts"
    private const val P_ANIME_GIRL =
        "masterpiece, best quality, 1girl, solo, detailed face, soft shading,"

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
    val all: List<ModelSpec> get() = builtIn + CustomModels.registered

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
    val builtIn: List<ModelSpec> get() = sd15Models + sdxlModels

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

    val sd15Models: List<ModelSpec> = listOf(
        sd15(
            V1_MODEL, "AbsoluteReality", "AbsoluteReality",
            1_054_661_172L, 1_059_732_796L, 993_451_663L,
            prompt = "masterpiece, best quality, ultra-detailed, realistic, 8k, a cat on grass,",
            negative = NEG_PHOTO,
        ),
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
            negative = NEG_PHOTO,
        ),
        sd15(
            "cuteyukimix", "CuteYukiMix", "CuteYukiMix",
            1_057_299_173L, 1_059_582_346L, 993_526_703L,
            prompt = P_ANIME_GIRL, negative = NEG_ANIME,
        ),
        sd15(
            "qteamix", "QteaMix", "QteaMix",
            1_056_615_116L, 1_061_160_208L, 995_347_176L,
            prompt = P_ANIME_GIRL, negative = NEG_ANIME,
        ),
    )

    // ---- SDXL ------------------------------------------------------------

    /**
     * ⚠ Deliberately the same prompt DreamUI ships for every SDXL checkpoint.
     * A picture that differs between the two apps is then a difference in the
     * RUNTIME rather than in what was asked for, which is the whole reason the
     * SD 1.5 prompts above were copied verbatim too.
     */
    private const val P_SDXL =
        "masterpiece, best quality, highly detailed, " +
            "a majestic cat sitting on a windowsill at sunset,"
    private const val NEG_GENERAL =
        "lowres, bad anatomy, bad hands, text, error, missing fingers, extra digit, " +
            "fewer digits, cropped, worst quality, low quality, normal quality, " +
            "jpeg artifacts, signature, watermark, username, blurry,"

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
    private fun sdxl(id: String, label: String, archive: String, bytes: Long) = ModelSpec(
        id = id,
        label = label,
        // ⚠⚠ ONE build, and that is the whole SDXL device story: xororz
        // publishes `_8gen3` only -- 46 files, no `min`. So on anything below a
        // v75 HTP there is no SDXL build at all, not a slower one, and
        // `buildFor` correctly answers null rather than offering 3.5 GB the
        // chip would reject at load.
        builds = listOf(Build(SDXL_TIER, archive, bytes, minArch = 75, minVtcmMb = 8)),
        prompt = P_SDXL,
        negative = NEG_GENERAL,
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
        sdxl("sdxl_cyberrealistic", "CyberRealistic v10", "cyber_realistic_v10_qnn2.28$SDXL_TIER.zip", 3_745_235_842L),
        sdxl("sdxl_illustrious", "Illustrious v16", "illustrious_v16_qnn2.28$SDXL_TIER.zip", 3_726_876_852L),
        sdxl("sdxl_animagine", "Animagine v4", "animagine_v4_qnn2.28$SDXL_TIER.zip", 3_752_469_362L),
        sdxl("sdxl_pony", "Pony Diffusion v6 XL", "ponydiffusion_v6xl_qnn2.28$SDXL_TIER.zip", 3_725_876_252L),
        sdxl("sdxl_novaanime", "NovaAnime v19", "novaanime_v19_qnn2.28$SDXL_TIER.zip", 3_732_162_768L),
    )

    fun byId(id: String): ModelSpec? = all.firstOrNull { it.id == id }

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

    @Volatile
    var id: String = V1_MODEL
        private set

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
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        id = stored?.takeIf { ModelCatalog.byId(it) != null } ?: V1_MODEL
    }

    fun set(context: Context, newId: String) {
        require(ModelCatalog.byId(newId) != null) { "unknown model \"$newId\"" }
        id = newId
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, newId).apply()
    }
}
