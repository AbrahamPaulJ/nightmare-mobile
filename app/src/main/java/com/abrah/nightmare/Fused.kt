package com.abrah.nightmare

/**
 * ⭐⭐⭐ The reworked node set — `docs/ARCHITECTURE.md` §5.7.
 *
 * **The frozen binary is the unit.** There is no runtime compiler on the NPU, so
 * nobody can recombine the inside of a pipeline — ⇒ the whole process is ONE
 * node. Ten types that existed only because a latent was visible on a wire fuse
 * into [RenderNode]; what is left is a set a person can hold in their head.
 *
 * ⚠⚠ This does NOT undo the decomposition. `/encode_text`, `/vae_encode`,
 * `/latent_blend` and `/vae_decode` are what this file is MADE OF, and they stay
 * reachable from a plugin and from the harness. The op surface and the node set
 * simply stopped being the same list.
 *
 * ⚠ Everything here calls the SAME function the node it replaces called —
 * [CropNode.render], [MaskCropNode.cut], [VaeDecodeNode.decode],
 * [MaskRaster.rasterise], [InpaintPixels.composite]. §5.6 step 4: two surfaces
 * that must agree call one function, and this file would otherwise be the
 * second hand-rolled copy of five of them.
 */

/**
 * ⭐⭐ The prompt, as TEXT — one node for every family.
 *
 * ⚠⚠ `sd.clip_encode` emitted a COND, which is encoded BY a checkpoint and
 * belongs to it: one prompt node could never feed two samplers on two models,
 * and that is exactly what chaining across checkpoints needs ([Value.Prompt]).
 * It also meant two prompt nodes in the palette, since the video path had to
 * have its own.
 *
 * ⚠ App-side, so it costs nothing and runs in a preview — the old one reached
 * the backend just to hold some text.
 */
object PromptNode : NodeType {
    override val name = "core.prompt"
    override val version = "1"
    override val inputs = emptyList<Port>()
    override val outputs = listOf(Port("prompt", "PROMPT"))
    override val category = "source"

    /** ⚠ Twice the ordinary width: this node is two boxes of prose. */
    override val defaultWidth = com.abrah.nightmare.canvas.Sizes.PROSE_NODE_WIDTH

    /** ⚠ Both, in the order they are read; the negative is the one people check. */
    override val prose = listOf("prompt", "negative")

    /**
     * ⚠⚠ The defaults are the CHECKPOINT's, not literals — an anime model and a
     * photographic one want opposite negatives, and the catalogue has carried
     * both since it was written ([ModelCatalog.ModelSpec.prompt]).
     *
     * ⚠⚠ An imported model still carries no text of its OWN — we know nothing
     * about a checkpoint someone brought, and handing it a built-in's prompt
     * would bias it toward a model it is not. ⭐ Since 2026-09-15 it falls back
     * to the FAMILY's general-purpose pair instead of to a blank box
     * ([ModelSpec.starterPrompt]): quality tags carry no style claim, so they
     * bias nothing, and an import no longer opens on nothing at all.
     */
    override val widgets get() = listOf(
        Widget(
            "prompt", "string", SelectedModel.spec.starterPrompt,
            hint = "what to draw",
        ),
        Widget(
            "negative", "string", SelectedModel.spec.starterNegative,
            hint = "what to keep out of the picture",
        ),
    )

    override fun contextKey(node: Node): ContextKey? = null

    override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value {
        val p = effectiveParams(node)
        return Value.Prompt(p["prompt"].orEmpty(), p["negative"].orEmpty())
    }
}

/**
 * ⭐⭐⭐ **The SD sampler — four types, one implementation.**
 *
 * `docs/ARCHITECTURE.md` §5.7. It absorbs `sd.vae_encode`, `sd.vae_decode`,
 * `sd.latent_blend`, `image.mask`, `image.mask_crop` and `image.paste`, none of
 * which passed the test that earns a node.
 *
 * ⚠⚠ **It forks on CAPABILITY and on FAMILY** — the user's call, 2026-09-15,
 * against a recommendation to fork on capability alone:
 *
 * | | text/image | inpaint |
 * |---|---|---|
 * | **SD 1.5** | `sd15.sample` | `sd15.inpaint` |
 * | **SDXL** | `sdxl.sample` | `sdxl.inpaint` |
 *
 * ⭐ The capability fork is what was actually asked for and it is plainly right:
 * an image-to-image flow was being handed a mask editor it has no use for, and
 * a node's ports cannot depend on a checkpoint. ⇒ The `sample` types have no
 * mask port, no painting params and no mask editor **at all**, so the surface a
 * user meets is the surface they need.
 *
 * ⚠⚠ The family fork was the user's choice over the alternative of one pair
 * with a model dropdown. What it buys is a palette that states the family and a
 * node that cannot be pointed at a checkpoint of the wrong kind. What it costs
 * is written down here so it is not rediscovered: **a graph chaining SD 1.5 into
 * SDXL needs two different node types for the same job**, and every rule keyed
 * on a sampler's type name must ask a SET (`SAMPLER_TYPES`, `BatchParams`,
 * `FRAMES`/`PAINTS`, `hiddenKnob`) rather than compare one string.
 *
 * ⇒ **One class, four registrations.** Four copies of this logic would be four
 * places for the blend argument order to drift, and that mistake is invisible
 * (`FusedSamplerTest`). The differences are exactly two constructor arguments.
 *
 * ⚠ **Latent-blend vs a 9-channel inpaint model is NOT a fifth type — and not a
 * chip either.** Same ports, same editor; the MODEL the node names is the whole
 * choice. An inpaint render always sends its picture and mask with `sample`, and
 * the backend uses them only when its UNet takes 9 channels
 * (`backend-patches/006`). The user's call, 2026-09-19, replacing the chip this
 * note used to promise: a knob that only restates the model is not a knob.
 *
 * ⚠⚠ **It does not draw its own picture.** The user's call, 2026-09-15: a
 * render appears on `core.output` and nowhere else, so a graph says where its
 * result goes. The executor refuses a run whose render has nowhere to land.
 */
class SdSampler(
    override val name: String,
    /** ⚠ Which checkpoints this node may name, and the sizes it may render. */
    val family: Family,
    /** Whether this type carries a mask, its editor and the paste back. */
    val inpaint: Boolean,
) : NodeType {
    /** ⚠ Reaches the backend, so never run for a preview. [NodeType.appSide]. */
    override val appSide = false
    override val version = "1"

    /**
     * ⚠ `prompt` is the only one that is NOT optional: unwired, there is
     * nothing to render and [run] says so by name.
     *
     * ⚠⚠ `mask` is a PORT as well as a painting. Unwired, the mask painted on
     * this node is used; wired, an upstream mask wins. That is the door a Tier 1
     * segmenter (CLIPSeg, SAM 2.1) walks through later without re-adding a mask
     * node — and it exists NOW because a node's shape cannot be widened once
     * saved workflows depend on it (§7).
     */
    override val inputs = listOfNotNull(
        Port("prompt", "PROMPT"),
        Port("image", "IMAGE"),
        // ⚠⚠ **No `mask` port** — removed 2026-09-17 at the user's call. The
        // mask is painted or tapped in the node's own editor; a wired mask was a
        // second way to supply it that nobody used and every inpaint node paid
        // a port row for. `WorkflowIo.migrateType` drops an old saved wire.
        // ⭐ Tap to select (`docs/SEGMENTER.md`): wiring `mask.segment_model`
        // here is what shows the Tap tool. ⚠ Added while no flow depends on the
        // port list — a port cannot be added after one does (§7).
        if (inpaint) Port("segmenter", SelectObjectNode.PORT_TYPE) else null,
        // ⭐⭐ FLUX.2 Klein's edit REFERENCE — a second picture the model reads
        // but does not redraw. Unlike `image` it is never cropped to the
        // canvas: the engine VAE-encodes each reference at its OWN aspect
        // ratio and positions it with FLUX.2's reference-token RoPE scheme, so
        // fitting it to the output would throw away the thing that makes it a
        // reference.
        //
        // ⚠ Wiring it WITHOUT `image` is allowed and means "generate fresh,
        // guided by this" — `native_edit` fires on references alone
        // (`PipelineDit::generate`). The user's call, 2026-09-20.
        //
        // ⚠⚠ FLUX.2 ONLY, and the port is absent elsewhere rather than
        // present-and-failing: the backend throws "native reference editing is
        // only supported by FLUX.2 Klein" for Z-Image, and a port that always
        // errors is worse than no port. Same reasoning that keeps Z-Image out
        // of the inpaint picker.
        if (family == Family.FLUX2) Port("reference", "IMAGE") else null,
    )
    override val outputs = listOf(Port("image", "IMAGE"))
    /** ⚠ Inpaint is its OWN palette section (the user's call, 2026-09-16). */
    override val category = if (inpaint) "inpaint" else "generate"

    /**
     * ⭐ One palette card per JOB, a chip per family — [NodeType.paletteGroup].
     * ⚠ No "sample" anywhere a person reads it (the user's call, 2026-09-16),
     * and no "Text / image to" either (2026-09-17): the tab already says
     * Generate, and in a half-width card "Text / image to video" cut off to
     * read exactly like this one — the video card looked missing.
     */
    override val paletteName = if (inpaint) "Inpaint" else "Image"
    override val paletteGroup = if (inpaint) "sd.inpaint" else "sd.generate"
    override val paletteVariant = family.label

    /**
     * ⭐ `SDXL Inpaint`, or `SD 1.5 Text to image` until a photo is wired in.
     *
     * ⭐⭐ **FLUX.2 says "Image edit", not "Image to image"** — the user's
     * call, 2026-09-20. It is not the same job: Klein takes the base as a
     * clean reference and runs its full distilled schedule, which is why its
     * denoise is born at 1.0 ([defaultDenoise]) and why upstream labels the
     * whole path "edit" rather than img2img.
     *
     * ⚠ The condition is upstream's own, to the letter: an edit is a base
     * image **or** a reference. A FLUX.2 node with only a reference wired is
     * editing too — "generate fresh, guided by this" (`docs/MODELS.md` §9).
     */
    override fun titleFor(node: Node): String = family.label + " " + when {
        inpaint -> "Inpaint"
        family == Family.FLUX2 &&
            (node.inputs["image"] != null || node.inputs["reference"] != null) -> EDIT_LABEL
        node.inputs["image"] != null -> "Image to image"
        else -> "Text to image"
    }

    override val defaultId = family.slug + if (inpaint) "_inpaint" else "_generate"

    override val about = if (inpaint) {
        "paint an area of a photo and re-imagine only that"
    } else {
        "a prompt, or a prompt and a photo, into a picture"
    }

    /** ⚠ Its picture is its OUTPUT, so a tap on it opens the viewer (§5.7). */
    override val interactive = false

    /** ⚠⚠ The render belongs to `core.output`. [NodeType.showsResult]. */
    override val showsResult = false

    companion object {
        const val START_FROM = "start_from"
        const val FROM_NOISE = "noise"
        const val FROM_IMAGE = "image"

        /**
         * ⚠⚠ The VAE encode's noise, FIXED. It is what makes one picture encode
         * to one latent, so every render downstream of it is reproducible only
         * while this does not move — see the widget list for why it is not one.
         */
        const val ENCODE_SEED = 42

        /** ⚠ The stitched picture's longest edge — a 4096 px photo plus outpaint stays bounded. */
        const val STITCH_MAX_EDGE = 4096f

        /**
         * ⭐ The reference region's params. Named apart from `x`/`y`/`w`/`h`
         * so a node can frame its base and crop its reference independently.
         */
        const val REF_X = "ref_x"
        const val REF_Y = "ref_y"
        const val REF_W = "ref_w"
        const val REF_H = "ref_h"

        /**
         * ⭐⭐⭐ **A reference is VAE-encoded at the OUTPUT CANVAS's size, never
         * at its own** — so nothing this app does to a reference's pixels costs
         * or saves a single byte.
         *
         * Measured on device 2026-09-20, the same two references through the
         * same graph, with only the canvas changed:
         *
         * | reference sent | canvas 512x512 | canvas 1024x1024 |
         * |---|---|---|
         * | 512x512 | 384.00 MB | 1536.00 MB |
         * | 341x512 | 380.25 MB | 1521.00 MB |
         * | flux compute buffer | 491.50 MB | 1599.50 MB |
         * | outcome | ✅ ok, 53.9 s | ❌ reaped by lmkd |
         *
         * Exactly 4x, which is the canvas AREA ratio; the 1% between the two
         * rows is their differing aspect, not their pixel count. The engine
         * announces it — `diffusion_engine.cpp: Using 'flux2' preset for
         * reference images` — and resizes every reference before encoding it.
         *
         * ⚠⚠⚠ **This is why [REF_MAX] is not a memory control**, and why
         * 1.5.513 (bound the reference to 512 px) and 1.5.516 (bound the base
         * too) were both reverted: shrinking a reference does not make it
         * cheaper, it only throws detail away before the engine scales it back
         * up. 1.5.513 was written down as "right on its own terms,
         * insufficient" — it was never right on its own terms either.
         *
         * ⭐ **Rule changed 2026-09-20, later the same day, at the user's ask.**
         * This block first said "there is no reference-size knob" and deleted
         * [REF_MAX] outright. That went one step too far: the knob is useless
         * for MEMORY and that is what the measurement above proves, but it is
         * not useless — it changes **what the model reads**, and a reference
         * sent at 512 under a 1024 canvas is upscaled context rather than
         * sharp context. Whether that helps or hurts a composition is a
         * question about pictures, which no measurement here answers and the
         * person holding the phone has to try. ⇒ [REF_MAX] is back, defaulting
         * to sending the reference untouched, and its hint says plainly that
         * it costs nothing and buys nothing but detail.
         *
         * ⇒ The only lever on what a reference COSTS is the output resolution,
         * which lives in the Crop tab and is stated in the Reference tab's
         * warning (`docs/UI.md` §8.12). `docs/MODELS.md` §9 has the rest.
         */
        const val REF_ENCODED_AT_CANVAS = true

        /**
         * ⭐⭐ **The size a reference is SENT at — independent of the canvas.**
         *
         * ⚠⚠ Its own param, and that is the point: asked for 2026-09-20 after
         * the Reference tab briefly drew the OUTPUT resolution (1.5.518), which
         * meant changing it in one tab moved the other — *"dont share same
         * resolution dropdown. have base image res. and reference img res so i
         * can test, for example, 1024 base img and 512 reference"*.
         *
         * ⚠⚠⚠ **It does NOT change memory** — [REF_ENCODED_AT_CANVAS]. It is
         * here so a composition can be tried with a coarser reference, not as
         * a way out of an OOM, and its hint has to keep saying so or it will be
         * reached for during the next crash the way it was during the last one.
         */
        const val REF_MAX = "ref_max"

        /**
         * ⚠ Longest edge, aspect always preserved. [REF_ORIGINAL] is the
         * default: untouched pixels cost nothing extra, so anything else is a
         * deliberate experiment.
         */
        const val REF_ORIGINAL = "Original"
        val REF_SIZES = listOf(REF_ORIGINAL, "1536", "1024", "768", "512", "384")

        /** ⭐ The four registrations. One class; two arguments of difference. */
        val SD15 = SdSampler("sd15.sample", Family.SD15, inpaint = false)
        val SDXL = SdSampler("sdxl.sample", Family.SDXL, inpaint = false)
        val SD15_INPAINT = SdSampler("sd15.inpaint", Family.SD15, inpaint = true)
        val SDXL_INPAINT = SdSampler("sdxl.inpaint", Family.SDXL, inpaint = true)
        val ANIMA = SdSampler("anima.sample", Family.ANIMA, inpaint = false)
        val ANIMA_INPAINT = SdSampler("anima.inpaint", Family.ANIMA, inpaint = true)
        /**
         * ⭐⭐ The DiT families. ⚠ **FLUX.2 has an inpaint type since 1.5.507
         * and Z-Image does not**, and that asymmetry is the engine's, not a
         * preference: ABI 3 gave `PipelineDit` a `mask_image`, but the clean
         * reference latent that makes a masked redraw understand the picture
         * around the hole is gated on `DIT_MODEL_FLUX2_KLEIN`. Z-Image's mask
         * is NOT gated, so it would render a masked img2img with no reference
         * — the mechanism without the quality, looking identical in the
         * picker. It is left out rather than offered as a lesser thing.
         * `docs/MODELS.md` §9.
         */
        val FLUX2 = SdSampler("flux2.sample", Family.FLUX2, inpaint = false)
        val ZIMAGE = SdSampler("zimage.sample", Family.ZIMAGE, inpaint = false)
        /**
         * ⚠⚠⚠ **`flux2.inpaint` is BUILT and NOT REGISTERED, on purpose.**
         * Everything behind it works — [runDitMasked] sends `mask` on
         * `/generate`, the backend reports `Mask:1`, `PipelineDit` binds
         * `mask_image` beside the clean reference, and [finishInpaint]
         * composites the result. Measured on device 2026-09-20 against the
         * SAME mask the SD path uses:
         *
         *     SD 1.5, denoise 0.65   6303 pixels changed, 1500 strongly
         *     Klein,  denoise 0.65    821 pixels changed,    1 strongly
         *     Klein,  denoise 1.0     821 pixels changed,    1 strongly
         *
         * ⚠⚠ Identical at both denoise values, and confined to the mask's own
         * bounding box — so the engine HONOURS the mask (nothing outside it
         * moves) and then regenerates almost nothing inside it. Denoise having
         * no effect rules out "the reference out-weighs the redraw". The SD
         * path proves the mask content and the composite are right, so the
         * difference is inside `libdit_engine.so`, which we cannot read.
         *
         * ⇒ Registering it would put a checkpoint in the inpaint picker that
         * renders confidently and ignores what the user painted — the same
         * "it still RENDERED" class as the stale-skel noise bug. It stays out
         * until the engine's behaviour is understood. `notes/PROGRESS.md`.
         */
        val ALL = listOf(SD15, SDXL, ANIMA, FLUX2, ZIMAGE, SD15_INPAINT, SDXL_INPAINT, ANIMA_INPAINT)

        /**
         * ⭐⭐ The type a graph should use for [family] and [inpaint] — the one
         * place that maps the pair to a name.
         *
         * ⚠ A recipe, the migration and the palette all need this answer, and
         * three string literals spelling "sdxl.inpaint" would be three places to
         * get it wrong once.
         */
        /**
         * ⭐⭐⭐ **Can a checkpoint of this family be inpainted with?**
         *
         * ⚠⚠ THE one answer, because there are now two surfaces asking and
         * they must never disagree: the checkpoint picker on an inpaint node
         * (`canvas/NodeInspector.kt`) and the Use dialog on the Models screen
         * (`ui/ModelsScreen.kt`). The picker had it inline and the dialog did
         * not ask at all, so pressing Use on Z-Image offered "Inpaint" and
         * built a flow that cannot run — reported 2026-09-20.
         *
         * ⚠ It is a fact about the REGISTRY, not about the engine: a family
         * can inpaint exactly when an inpaint type is registered for it. That
         * is why FLUX.2 answers false — `flux2.inpaint` is written and
         * deliberately not in [ALL] (the engine honours the mask and then
         * regenerates nothing inside it, `notes/PROGRESS.md`) — and it is why
         * registering it would light this up everywhere at once.
         */
        /**
         * ⭐⭐⭐ **The denoise a node of this family is born with.**
         *
         * ⚠⚠ FLUX.2 is **1.0** and the rest are 0.65, asked for 2026-09-20.
         * For Klein, denoise is not a strength dial at all — it is the mode
         * selector (`docs/MODELS.md` §9): at 1.0 the base is a clean REFERENCE
         * and the full distilled schedule runs, which is what upstream calls
         * "edit" and labels its button with. 0.65 was inherited from the SD
         * samplers, where it means something else entirely.
         *
         * ⚠⚠⚠ **And with a reference wired it is the setting that does
         * nothing.** Below 1.0 the base is BOTH the init latent and reference
         * #1, so the picture is told to stay where it is while the noised init
         * has little room to move, and the result comes back as the input.
         * Reported from the phone twice.
         *
         * ⚠ Z-Image is a DiT and is NOT included: it is not an edit model,
         * `native_edit` never fires for it, and its denoise is an ordinary
         * img2img strength where 0.65 is the right default.
         *
         * ⚠⚠ It is a DEFAULT, not a lock. Klein img2img at 0.65 with no
         * reference works and looks good — measured 2026-09-20, the same photo
         * and prompt through both, and both returned a convincing picture. A
         * lock would delete a working mode to prevent a footgun that exists
         * only in one combination; the combination warns instead
         * (`canvas/NodeInspector.kt`).
         */
        /**
         * ⭐⭐ What FLUX.2 calls the job a photo turns into. One constant,
         * because the node title, the recipe and the node's own id all say it
         * and three spellings would drift.
         */
        const val EDIT_LABEL = "Image edit"

        fun defaultDenoise(family: Family): String =
            if (family == Family.FLUX2) "1.0" else "0.65"

        fun canInpaint(family: Family): Boolean =
            ALL.any { it.family == family && it.inpaint }

        fun typeFor(family: Family, inpaint: Boolean): String =
            (ALL.firstOrNull { it.family == family && it.inpaint == inpaint }
                // ⚠ A family with no inpaint type (the DiT ones) asked for one:
                // an SD 1.5 inpaint, not that family's text-to-image — which
                // would silently drop the mask the caller asked for.
                ?: if (inpaint) SD15_INPAINT else SD15).name
    }

    /**
     * ⚠ The checkpoint a NEW node of this type is born with: the selected one
     * when it belongs to this family, and this family's first catalogue entry
     * otherwise. A node that defaulted to a model of the wrong family would
     * refuse to run the moment it was dragged out.
     */
    private fun defaultModel(): String =
        if (SelectedModel.spec.family == family) SelectedModel.id
        else ModelCatalog.all.firstOrNull { it.family == family }?.id ?: SelectedModel.id

    private fun defaultSpec(): ModelSpec = ModelCatalog.byId(defaultModel()) ?: SelectedModel.spec

    private fun defaultRes(): Res =
        if (SelectedModel.spec.family == family) SelectedModel.res
        else ModelCatalog.all.firstOrNull { it.family == family }?.native ?: SelectedModel.res

    /**
     * ⭐ On an INPAINT node, denoise, only-masked and stitch come FIRST — they
     * are what the inpaint is, and the user asked for them above Steps
     * (2026-09-17). ⚠ Order only; every widget is the same declaration.
     */
    override val widgets get() = (if (family.dit) ditWidgets() else baseWidgets()).let { ws ->
        if (!inpaint) ws else {
            val front = listOf("denoise", MaskCropNode.ONLY_MASKED, PasteNode.STITCH)
            front.mapNotNull { n -> ws.firstOrNull { it.name == n } } + ws.filterNot { it.name in front }
        }
    }

    private fun baseWidgets() = listOf(
        // ⚠⚠ Defaults from the MODEL, not from a literal. A distilled checkpoint
        // publishes something like 10 steps at cfg 1.5, and this app's 20/7.5
        // renders it burnt rather than failing — measured on device 2026-09-10.
        // ⚠⚠ …from THIS FAMILY's model, not the global selection. A new Anima
        // node dragged out while SD 1.5 is selected took SD's 20 steps / cfg 7.5
        // and `dpm`, and a turbo checkpoint does not fail on those, it burns.
        Widget("steps", "int", defaultSpec().steps.toString(), 1.0, 50.0),
        Widget("cfg", "float", defaultSpec().cfg.toString(), 1.0, 20.0, fine = true),
        // ⭐ 0 rolls a new seed on every Run, so Run means "give me another one"
        // rather than returning the cached picture unchanged.
        Widget(
            "seed", "int", "0",
            hint = "0 = a new picture every Run. Type the seed shown on the node to get that one back.",
        ),
        // ⚠ Read only when a picture is wired AND `start_from` is `image`.
        Widget("denoise", "float", defaultDenoise(family), 0.0, 1.0),
        Widget(
            "scheduler", "string", defaultSpec().scheduler,
            options = ModelCatalog.schedulersFor(family),
            hint = "the sampler; a distilled model usually needs the one its author published",
        ),
        // ⚠⚠ **No `start from` knob.** The user's call, 2026-09-15: *"start from
        // is decided by whether an image is connected, simple as that."* It
        // shipped as a switch so a flow could flip without rewiring — which is
        // right for the VIDEO node, where the alternative to a photo is four
        // seconds of SSD1B generating a first frame, and wrong here, where the
        // alternative is nothing at all. A knob whose value the wire already
        // states is a second place for the same fact to live.
        // ⚠⚠ **No `mask on` switch either** — removed 2026-09-16 at the user's
        // ask (*"whats even the point of it?"*). It predates the capability fork:
        // while one node served both jobs it turned inpaint into a full render.
        // Now an inpaint node with its mask off IS the `sample` node, so the
        // switch was a second way to reach a type that already exists.
        // ⭐⭐ The framing, normalised 0..1 of the source — and under
        // `image.crop`'s OWN param names, so [cropRectOf], [CropRect.asParams]
        // and [CropEditor] work on this node with no second spelling to keep in
        // step. ⚠ Normalised so a saved flow re-pointed at a photo of a
        // different size still means the same framing.
        Widget("x", "float", "0.0", 0.0, 1.0, hint = "drag the frame on the picture"),
        Widget("y", "float", "0.0", 0.0, 1.0),
        Widget("w", "float", "1.0", 0.0, 1.0),
        Widget("h", "float", "1.0", 0.0, 1.0),
        // ⭐⭐ The REFERENCE's own region, and a different job to the four
        // above. Those FRAME the picture into the output canvas; these choose
        // WHICH PART of a reference to send, and the region goes over the wire
        // at its own aspect ratio — never fitted to the canvas, because that
        // is the whole point of a reference (`docs/MODELS.md` §9).
        //
        // ⚠ Separate names, not a second use of x/y/w/h: one node can carry a
        // framed base AND a cropped reference at once, and sharing the params
        // would make moving one move the other.
        //
        // ⚠⚠ Hidden from the knob list like x/y/w/h are ([hiddenKnob]) — they
        // are dragged on the picture, and four more loose sliders under the
        // size control is the duplicate the 2026-09-18 report named.
        Widget(REF_X, "float", "0.0", 0.0, 1.0, hint = "drag the region on the reference"),
        Widget(REF_Y, "float", "0.0", 0.0, 1.0),
        Widget(REF_W, "float", "1.0", 0.0, 1.0),
        Widget(REF_H, "float", "1.0", 0.0, 1.0),
        // ⭐⭐ The reference's OWN size, drawn in the Reference editor where the
        // base's Shape + Resolution sit in the Crop one — and a separate param
        // from `width`/`height` so the two tabs cannot move each other, which
        // is the whole reason it exists ([REF_MAX]).
        // ⚠⚠ The hint must never promise memory: a reference is encoded at the
        // CANVAS ([REF_ENCODED_AT_CANVAS]), so this buys detail and nothing else.
        Widget(
            REF_MAX, "string", REF_ORIGINAL,
            options = REF_SIZES,
            hint = "the longest edge the reference is sent at, its own aspect kept. " +
                "This does NOT change memory — a reference is encoded at your output " +
                "size whatever you send — it only changes how much detail the model reads",
        ),
        // ⚠ Drawn as the tick/pencil in the Crop title row, never as a checkbox
        // in the knob list ([hiddenKnob]).
        Widget(CropNode.LOCKED, "bool", "false"),
        // ⭐ The padding choice `image.crop` has — black bars or the picture's
        // own edges blurred — for when the frame runs off the photo. ⚠ It was
        // left off as "rare"; the user asked for it back in the inpaint Crop tab
        // (2026-09-17). ⚠ The FRAME only: the mask is always padded black,
        // because black is "not masked".
        // ⭐⭐ **BLUR by default on an inpaint node, black elsewhere** — the
        // user's call, 2026-09-20.
        //
        // ⚠⚠ On an inpaint node the frame running off the photo is
        // OUTPAINTING ([PadRule.OUTPAINT]), and what fills it is what the
        // model continues from: blurred edges carry the picture's own colour
        // and light into the new area, where a black bar asks it to invent a
        // scene that begins at a hard black edge. ⚠ Elsewhere the bars mean
        // "this photo is too small" and black says that honestly.
        Widget(
            CropNode.PAD, "string",
            if (inpaint) CropNode.PAD_BLUR else CropNode.PAD_BLACK,
            options = listOf(CropNode.PAD_BLACK, CropNode.PAD_BLUR),
            hint = "what fills the frame where it runs off the photo",
        ),
        // ⭐ The painting itself — `image.mask`'s params, moved. ⚠ A real param
        // rather than editor state, because it is what a saved workflow stores.
        // ⚠⚠ Declared ONLY on an inpaint type: an undeclared param is still
        // hashed into the cache key, so leaving these on a `sample` node would
        // put a mask nobody can edit into the key of every render it makes.
        *(if (!inpaint) emptyArray() else arrayOf(
            Widget(MaskNode.OPS, "string", "", hint = "paint the area to redo"),
            Widget("grow", "float", "0.0", 0.0, 0.2, hint = "spread the mask outward"),
            Widget("feather", "float", "0.02", 0.0, 0.2, hint = "soften the mask edge"),
            // ⭐⭐ DreamUI's two inpaint toggles, unchanged in meaning.
            Widget(
                MaskCropNode.ONLY_MASKED, "bool", "true",
                hint = "render a crop around the mask: more detail where you painted",
            ),
            Widget(
                PasteNode.STITCH, "bool", "false",
                hint = "off: the result is the frame you chose. on: it is pasted back into the whole photo",
            ),
        )),
        // ⚠⚠ **No `encode seed`.** A VAE latent is mean + std * noise, so this
        // number is what makes the same picture encode to the same latent —
        // which means it must never move, and a knob that must never move is a
        // knob that can only do harm. Fixed at [ENCODE_SEED]; the hint used to
        // say "leave it fixed", which is the app asking the user to enforce its
        // own invariant.
        // ⚠ model / width / height are the CONTEXT KEY (§4): changing one costs
        // a backend relaunch. They are the only three knobs here that are not
        // free.
        Widget("model", "string", defaultModel(), locked = CONTEXT_KEY_LOCK, contextKey = true),
        *aspectWidget(defaultSpec()),
        Widget("width", "int", defaultRes().width.toString(), contextKey = true),
        Widget("height", "int", defaultRes().height.toString(), contextKey = true),
    )

    /**
     * ⭐⭐ A DiT family's knobs: the same names as every sampler's, minus what
     * its engine does not have — no scheduler (it hardcodes euler), no aspect
     * chip, no mask — and a SIZE that is a request field, not a launch one.
     * ⚠ Not [Widget.contextKey]: moving them never relaunches the backend
     * ([backendContextKey] keys a DiT model on its native size).
     * ⚠⚠ They are also not drawn as sliders any more. The inspector's size
     * panel owns them for every family now (`NodeInspector`'s `ditPanel`,
     * [ModelCatalog.DIT_SHAPES]) and `hiddenKnob` keeps them out of the knob
     * list, so these declarations exist to carry the DEFAULT and the legal
     * range — which is what a saved workflow and `applyDefaults` read.
     */
    private fun ditWidgets(): List<Widget> = baseWidgets()
        .filterNot { it.name in setOf("scheduler", "aspect", "width", "height", "cfg") } + listOf(
        // ⭐ The one knob whose meaning DIFFERS here, so the one that gets a
        // hint the SD nodes have no need of. A guidance-distilled checkpoint
        // opens at 1.0, and at exactly 1.0 the engine skips the unconditional
        // pass — which is what makes the negative prompt beside it inert until
        // this moves. Nothing else in the sheet could tell a person that.
        Widget(
            "cfg", "float", defaultSpec().cfg.toString(), 1.0, 20.0, fine = true,
            hint = "1 is what these models are distilled for. Above 1 the negative prompt " +
                "starts being read, and each step costs about twice as long",
        ),
        Widget(
            "width", "int", ModelCatalog.DIT_RES.width.toString(),
            ModelCatalog.DIT_MIN.toDouble(), ModelCatalog.DIT_MAX.toDouble(), step = ModelCatalog.DIT_STEP,
        ),
        Widget(
            "height", "int", ModelCatalog.DIT_RES.height.toString(),
            ModelCatalog.DIT_MIN.toDouble(), ModelCatalog.DIT_MAX.toDouble(), step = ModelCatalog.DIT_STEP,
        ),
    )

    override fun contextKey(node: Node) = backendContextKey(node)

    /**
     * ⚠⚠ **Null — this node demands NO size of anything.** It fits whatever it
     * is handed (§5.7), which is what retires `sizeRefusal` for the image path:
     * a wire that used to be refused at the drop now renders the obvious thing.
     */
    override fun requiredInputSize(node: Node, port: String): Pair<Int, Int>? = null

    /**
     * ⚠ The render size, and only when no paste is coming. With a mask painted
     * the output is the FRAME at its own resolution — the patch goes back where
     * it was cut from — and that size is not knowable from the params alone.
     */
    override fun outputSize(node: Node): Pair<Int, Int>? {
        val p = effectiveParams(node)
        if (inpaint && (p[MaskNode.OPS].orEmpty().isNotBlank() || paddingOf(p) != null)) return null
        return (p["width"]?.toIntOrNull() ?: 0) to (p["height"]?.toIntOrNull() ?: 0)
    }

    /**
     * ⭐⭐ The size the framing is cut to: the ASPECT's rectangle on a
     * fixed-canvas family, the render size otherwise.
     *
     * ⚠⚠ Reported 2026-09-17: switching an inpaint node to SDXL left the crop
     * and mask square while the output came back 16:9 — the editors framed the
     * 1024² canvas and the backend kept only its middle band. The editors read
     * this through `framingOutSize`, and [run] cuts to the same size, so what is
     * framed is what is rendered.
     */
    override fun framesTo(node: Node): Pair<Int, Int> {
        val w = node.params["width"]?.toIntOrNull() ?: 0
        val h = node.params["height"]?.toIntOrNull() ?: 0
        val t = nodeAspect(node)?.let { ModelCatalog.aspectTarget(it, Res(w, h)) }
        return if (t != null) t.width to t.height else w to h
    }

    /** ⭐ The photo's extent inside an OUTPAINT frame, or null — [CropGeometry.photoInFrame]. */
    private fun paddingOf(p: Map<String, String>): Frame? =
        if (!inpaint) null else CropGeometry.photoInFrame(
            p["x"]?.toFloatOrNull() ?: 0f, p["y"]?.toFloatOrNull() ?: 0f,
            p["w"]?.toFloatOrNull() ?: 1f, p["h"]?.toFloatOrNull() ?: 1f,
        )

    override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value {
        val p = effectiveParams(node)
        fun str(k: String) = p[k].orEmpty()
        fun num(k: String) = p[k]?.toDoubleOrNull() ?: 0.0
        fun int(k: String) = p[k]?.toIntOrNull() ?: 0
        fun flag(k: String) = p[k].equals("true", ignoreCase = true)

        val prompt = inputs["prompt"] as? Value.Prompt
            ?: throw IllegalArgumentException(
                "node \"${node.id}\": nothing is wired into \"prompt\" — " +
                    "drag a Prompt node out of the palette and connect it"
            )
        val w = int("width")
        val h = int("height")
        // ⭐⭐ A DiT model renders whole, so it leaves here — EXCEPT an inpaint
        // one, which first needs the mask this function builds below. That
        // path rejoins at [runDitMasked] and returns before any op endpoint is
        // touched. `docs/MODELS.md` §9.
        if (family.dit && !inpaint) {
            return runDit(
                ctx, node, p, prompt,
                inputs["image"] as? Value.Image,
                inputs["reference"] as? Value.Image,
                w, h,
            )
        }
        val aspect = nodeAspect(node)
            ?.takeIf { ModelCatalog.aspectTarget(it, Res(w, h)) != null }

        // ⚠ First, because it is the cheapest thing that can fail: a backend
        // that is not up says so here rather than after a 200 ms VAE encode.
        //
        // ⚠⚠ Computed ON DEMAND since DiT inpaint joined this function: the DiT
        // backend answers `/encode_text` with "DiT engine owns text encoding",
        // so a Klein inpaint that paid this cost up front would fail before it
        // reached the masking it is here for. Cached, so the three callers
        // below still encode exactly once between them.
        var condCache: String? = null
        suspend fun cond(): String = condCache ?: run {
            ctx.say("reading the prompt")
            when (val r = ctx.host.encodeText(prompt.positive, prompt.negative)) {
                is Ops.Result.Ok -> r.value.handle
                is Ops.Result.Err -> throw OpFailure("encode_text", r.code, r.body)
            }.also { condCache = it }
        }

        // ⭐ The switch, not the wire, decides. A photo wired with `start_from`
        // at `noise` is ignored ON PURPOSE and the canvas draws it dimmed.
        // ⚠ The WIRE decides, and nothing else: an image is connected or it is
        // not (2026-09-15).
        val photo = inputs["image"] as? Value.Image

        if (photo == null) {
            ctx.say("rendering")
            val latent = sample(ctx, p, cond(), null, w, h, aspect)
            return VaeDecodeNode.decode(ctx, latent, w, h, aspect)
        }

        val src = ctx.images.get(photo.id)
            ?: throw IllegalStateException(
                "node \"${node.id}\": image ${photo.id} is no longer in the store"
            )

        // ⭐⭐ Is there a mask? Painted (or tapped) here — there is no mask wire.
        val stored = MaskState.decode(p[MaskNode.OPS]).copy(
            growFrac = num("grow").toFloat(),
            featherFrac = num("feather").toFloat(),
        )

        // ⭐⭐⭐ **An inpaint ALWAYS needs a mask — no exceptions, no silent
        // fallback.** Rule changed 2026-09-19, at the user's ask, twice in one
        // day: a first attempt made an empty mask mean "everything" and ran
        // unattended; that was reverted because the person still wanted to be
        // STOPPED and shown the editor. This is the second correction —
        // *"lets not do the full masking thing for inpaint. instead if user
        // doesnt mask just show error saying nothing masked, this should be
        // always true for inpaint nodes"* — dropping the OLD rule too, which
        // only refused when the picture came from a chain
        // (`ctx.ancestorTypes.any { isSampler(it) }`) and silently ran a
        // PHOTO-sourced inpaint with nothing painted as a plain re-render.
        // ⚠ Padding is still the one exception: an outpaint frame hanging off
        // the photo IS the mask, and needs no painting.
        val padded = paddingOf(p) != null
        if (inpaint && stored.isEmpty && !padded) {
            throw NeedsInput("nothing masked — paint an area, then Run again")
        }
        // ⚠⚠ Still gated to a CHAINED picture, unlike the check above: a plain
        // photo's mask is cleared the moment the photo changes
        // ([Graph.withNewPicture]), so this state is only reachable when the
        // picture is a GENERATED one that re-rendered under a painted mask.
        if (inpaint && ctx.ancestorTypes.any { isSampler(it) }) {
            val on = p[MaskNode.PAINTED_ON].orEmpty()
            if (!stored.isEmpty && on.isNotBlank() && on != photo.id) {
                throw NeedsInput(
                    "the picture you painted on has changed — repaint it, or undo the change upstream"
                )
            }
        }
        // ⭐⭐ Tapped regions become geometry here, against the SAME photo the
        // taps were made on. ⚠⚠ Refused by name when they cannot be — the
        // missing-model case, read the way a missing checkpoint reads
        // (`docs/SEGMENTER.md` §3, the user's call 2026-09-16). Rendering
        // without the mask would repaint the wrong area confidently.
        val painted = if (!MaskTaps.hasTaps(stored)) stored else {
            val android = ctx.android
            if (android == null || !com.abrah.nightmare.segment.Segmenter.isInstalled(android)) {
                throw IllegalStateException(
                    "node \"${node.id}\": this mask was tapped — download " +
                        "${com.abrah.nightmare.segment.Segmenter.LABEL} in Models, Tools"
                )
            }
            ctx.say("finding the objects you tapped")
            MaskTaps.resolve(stored) { x, y ->
                com.abrah.nightmare.segment.Segmenter.segment(android, src, x, y)?.candidates
            }
        }
        // ⭐⭐ OUTPAINT: a frame hanging off the photo is masked there whether or
        // not anything was painted — DreamUI's `isEmpty` counts the padding too.
        val padding = paddingOf(p)
        // ⚠ `inpaint` first: a `sample` type has no painting params, so there is
        // nothing here to be true.
        val masking = inpaint && (!painted.isEmpty || padding != null)
        // ⭐⭐ The cut is the ASPECT's rectangle; the encode is the whole canvas
        // with that rectangle centred in it ([framesTo], [padToCanvas]).
        val (tw, th) = framesTo(node)

        // ⚠⚠ The framed photo. With a mask it keeps its OWN pixels, because the
        // patch is pasted back into it at the end and a frame already reduced to
        // 512² would paste a 512² picture back over a photo. Without one the
        // frame IS the render, so it goes straight to the model's size.
        val fx = num("x").toFloat()
        val fy = num("y").toFloat()
        val fw = num("w").toFloat()
        val fh = num("h").toFloat()
        val (frame, frameRect) = CropNode.render(
            src, fx, fy, fw, fh,
            if (masking) 0 else tw, if (masking) 0 else th,
            p[CropNode.PAD] ?: CropNode.PAD_BLACK,
        )

        if (!masking) {
            ctx.say("re-imagining the picture")
            val base = encode(ctx, ImageStore.encodePng(padToCanvas(frame, w, h)), ENCODE_SEED, w, h)
            val latent = sample(ctx, p, cond(), base, w, h, aspect)
            return VaeDecodeNode.decode(ctx, latent, w, h, aspect)
        }

        // ⭐⭐⭐ **The mask is painted in the PHOTO's coordinates, not the
        // frame's**, and then put through the SAME framing the picture was.
        //
        // ⚠⚠ Rasterising it at the frame's size instead would tie a painting to
        // whatever the frame happened to be when it was made: re-frame the shot
        // afterwards and every stroke slides across the subject, silently. The
        // user paints on a picture, so the picture is the coordinate space.
        //
        // ⚠ Capped for the reason `image.mask` capped: a photo may be 4096 px
        // and a mask is read in normalised terms anyway, so a smaller raster
        // loses nothing but memory.
        val cap = (MaskNode.NO_DEMAND_MAX_EDGE.toFloat() / maxOf(src.width, src.height))
            .coerceAtMost(1f)
        val maskSrc = MaskRaster.rasterise(
                painted,
                (src.width * cap).toInt().coerceAtLeast(1),
                (src.height * cap).toInt().coerceAtLeast(1),
            )
        // ⚠⚠ PAD_BLACK, never the blurred fill: black is "not masked". A
        // mirrored edge here would invent painting outside the photo.
        val maskBmp = CropNode.render(
            maskSrc, fx, fy, fw, fh, frame.width, frame.height, CropNode.PAD_BLACK,
        ).first
        // ⭐⭐ …and the padding forced white on top, so no eraser reaches it.
        padding?.let { MaskRaster.forcePadding(maskBmp, it) }

        // ⭐⭐ "Only masked" — the render window is a crop around the painting,
        // so the detail lands where the finger was.
        val cut = MaskCropNode.cut(frame, maskBmp, tw, th, flag(MaskCropNode.ONLY_MASKED))
        ctx.say(if (painted.isEmpty) "filling the padding" else "repainting the area you marked")
        val imagePng = ImageStore.encodePng(padToCanvas(cut.image, w, h))
        // ⚠ On the CANVAS, black outside the aspect rectangle: that is the
        // part the decode cuts away, so it keeps the base.
        val maskPng = ImageStore.encodePng(padToCanvas(cut.mask, w, h))
        // ⭐⭐⭐ **Klein's masked redraw — TRUE inpainting, and neither blend is
        // reachable from here.** The engine takes the mask itself (ABI 3's
        // `mask_image`: white is regenerated, black keeps the init image) with
        // the base ALSO bound as a clean reference latent, so it understands
        // both the hole and the picture around it.
        //
        // ⚠⚠ `PipelineDit` names `mask_latent_blend` zero times and DiT refuses
        // every decomposed op, so the per-step blend and the `/latent_blend`
        // below are not choices we are declining — they do not exist on this
        // path. What DOES stay is the pixel composite at the end of this
        // function: a VAE round trip is not pixel-exact, so without it the
        // unpainted area drifts in colour and the patch stops joining up
        // (the seam bug of 2026-09-15).
        //
        // ⚠ Klein only. Z-Image reaches `PipelineDit` too and its mask is NOT
        // gated there, but `native_edit` is Klein-only, so Z-Image would get a
        // masked img2img with no reference — the mechanism without the quality.
        // It is kept out of the picker instead of being offered as a lesser
        // thing that looks the same. `docs/MODELS.md` §9.
        if (family.dit) {
            val patchBmp = runDitMasked(ctx, node, p, prompt, imagePng, maskPng, w, h)
            return finishInpaint(ctx, node, p, src, frame, frameRect, cut, patchBmp)
        }

        val base = encode(ctx, imagePng, ENCODE_SEED, w, h)
        // ⭐⭐ The picture and mask go to `sample` as well. A 9-channel inpaint
        // checkpoint conditions on them and SEES the hole it fills; every other
        // model's backend drops them (`Ops.sample`), so nothing here branches
        // on which kind of checkpoint is loaded — the model the node names is
        // the whole choice (the user's call, 2026-09-19). The blend below then
        // runs either way: over a 9-channel render it only re-asserts the
        // unmasked area, which that model already kept.
        val repainted = sample(ctx, p, cond(), base, w, h, aspect, imagePng, maskPng)

        // ⚠⚠ `base` then `repainted`: the mask's WHITE area is where the new
        // pixels show through. The other way round replaces everything EXCEPT
        // what was painted — a plausible picture and a silent mistake.
        val blended = when (
            val r = ctx.host.latentBlend(base, repainted, maskPng)
        ) {
            is Ops.Result.Ok -> r.value.handle
            is Ops.Result.Err -> throw OpFailure("latent_blend", r.code, r.body)
        }
        val patch = VaeDecodeNode.decode(ctx, blended, w, h, aspect)
        val patchBmp = ctx.images.get(patch.id)
            ?: throw IllegalStateException("node \"${node.id}\": the render vanished from the store")
        return finishInpaint(ctx, node, p, src, frame, frameRect, cut, patchBmp)
    }

    /**
     * ⭐⭐ The patch goes back where it was cut from, blended along the mask
     * rather than pasted as a rectangle — the seam is the whole reason the
     * inpaint output "did not join up with the original" (2026-09-15).
     *
     * ⚠⚠ **ONE function, two callers**: the SD path above and Klein's masked
     * redraw. They must agree, and two hand-rolled composites would stop
     * agreeing — the rule `CLAUDE.md` states and `clipNodes` broke. It is the
     * only stage of the three that a DiT render still needs, because a VAE
     * round trip is not pixel-exact and the unpainted area would otherwise
     * drift.
     */
    private fun finishInpaint(
        ctx: NodeCtx,
        node: Node,
        p: Map<String, String>,
        src: android.graphics.Bitmap,
        frame: android.graphics.Bitmap,
        frameRect: Frame,
        cut: MaskCropNode.Cut,
        patchBmp: android.graphics.Bitmap,
    ): Value {
        val dst = android.graphics.RectF(
            cut.rect[0].toFloat(), cut.rect[1].toFloat(),
            (cut.rect[0] + cut.rect[2]).toFloat(), (cut.rect[1] + cut.rect[3]).toFloat(),
        )
        val out = if (!p[PasteNode.STITCH].equals("true", ignoreCase = true)) {
            InpaintPixels.composite(frame, patchBmp, dst, cut.mask)
        } else {
            stitch(src, frame, frameRect, patchBmp, dst, cut.mask)
        }
        return Value.Image(ctx.images.put(out), out.width, out.height)
    }

    /**
     * ⭐⭐ **"Stitch to original"** — the patch pasted back into the whole PHOTO,
     * not into the frame.
     *
     * ⚠⚠ Lost when ten nodes were fused (§5.7): `image.paste` read the toggle,
     * and the sampler kept the widget but always pasted into the frame, so the
     * switch did nothing (reported 2026-09-17).
     *
     * ⚠ The canvas is the UNION of photo and frame, so an OUTPAINT frame
     * extends the photo rather than having its new edges clipped away. The frame
     * is drawn first (its padding fill under the region about to be repainted),
     * the photo on top at its own quality, then the patch — [dst] carried from
     * frame pixels into photo pixels through [frameRect].
     * ⚠ Capped at [STITCH_MAX_EDGE], scaling everything alike.
     */
    private fun stitch(
        photo: android.graphics.Bitmap,
        frame: android.graphics.Bitmap,
        frameRect: Frame,
        patch: android.graphics.Bitmap,
        dst: android.graphics.RectF,
        mask: android.graphics.Bitmap,
    ): android.graphics.Bitmap {
        val ux0 = minOf(0f, frameRect.left)
        val uy0 = minOf(0f, frameRect.top)
        val ux1 = maxOf(photo.width.toFloat(), frameRect.right)
        val uy1 = maxOf(photo.height.toFloat(), frameRect.bottom)
        val k = minOf(1f, STITCH_MAX_EDGE / maxOf(ux1 - ux0, uy1 - uy0))
        val base = android.graphics.Bitmap.createBitmap(
            ((ux1 - ux0) * k).toInt().coerceAtLeast(1),
            ((uy1 - uy0) * k).toInt().coerceAtLeast(1),
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        val canvas = android.graphics.Canvas(base)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        fun place(l: Float, t: Float, r: Float, b: Float) =
            android.graphics.RectF((l - ux0) * k, (t - uy0) * k, (r - ux0) * k, (b - uy0) * k)
        canvas.drawBitmap(frame, null, place(frameRect.left, frameRect.top, frameRect.right, frameRect.bottom), paint)
        canvas.drawBitmap(photo, null, place(0f, 0f, photo.width.toFloat(), photo.height.toFloat()), paint)
        // Frame pixels -> photo pixels.
        val sx = frameRect.width() / frame.width
        val sy = frameRect.height() / frame.height
        val into = place(
            frameRect.left + dst.left * sx, frameRect.top + dst.top * sy,
            frameRect.left + dst.right * sx, frameRect.top + dst.bottom * sy,
        )
        return InpaintPixels.composite(base, patch, into, mask)
    }

    /**
     * ⭐⭐ A DiT render: ONE `/generate`, the text and (for image to image) the
     * framed picture in, the picture out. Their engine owns the text encoder,
     * the loop and the VAE, so there is no conditioning or latent to hand
     * between ops ([Family.dit]).
     *
     * ⚠ The size snaps to the engine's grid here too: a saved flow or a typed
     * param off the 256-px grid would otherwise reach the engine as a size it
     * was never verified at.
     */
    /**
     * ⭐⭐⭐ Klein's masked redraw. Returns the repainted CANVAS, which the
     * caller composites back exactly as it does an SD one — same
     * [finishInpaint], same seam.
     *
     * ⚠⚠ No `/encode_text`, no `/sample`, no `/vae_decode`, no
     * `/latent_blend`: the DiT backend answers all four with "DiT engine owns
     * text encoding", so this is one `/generate` carrying the picture and the
     * mask. That is not a shortcut — it is the only path the engine offers,
     * and it is the better one (`docs/MODELS.md` §9).
     *
     * ⚠ The image and mask are already the CANVAS-padded cut the SD path
     * built, so the engine sees exactly the frame the user painted on.
     */
    private suspend fun runDitMasked(
        ctx: NodeCtx,
        node: Node,
        p: Map<String, String>,
        prompt: Value.Prompt,
        imagePng: ByteArray,
        maskPng: ByteArray,
        w: Int,
        h: Int,
    ): android.graphics.Bitmap {
        ctx.say("repainting the area you marked")
        val r = ctx.host.generate(
            prompt = prompt.positive,
            negative = prompt.negative,
            steps = p["steps"]?.toIntOrNull() ?: 4,
            cfg = p["cfg"]?.toDoubleOrNull() ?: 1.0,
            seed = p["seed"]?.toIntOrNull() ?: 0,
            width = w,
            height = h,
            imagePng = imagePng,
            maskPng = maskPng,
            denoise = p["denoise"]?.toDoubleOrNull() ?: defaultDenoise(family).toDouble(),
            onProgress = ctx.onProgress,
        )
        val out = when (r) {
            is Ops.Result.Ok -> r.value
            is Ops.Result.Err -> throw OpFailure("generate", r.code, r.body)
        }
        return android.graphics.BitmapFactory.decodeByteArray(out.png, 0, out.png.size)
            ?: throw IllegalStateException("node \"${node.id}\": the engine's picture would not decode")
    }

    private suspend fun runDit(
        ctx: NodeCtx,
        node: Node,
        p: Map<String, String>,
        prompt: Value.Prompt,
        photo: Value.Image?,
        reference: Value.Image?,
        w0: Int,
        h0: Int,
    ): Value {
        // ⚠ [ModelCatalog.ditSnap], not a local copy: the size control offers
        // only grid values and this must agree with it (see DIT_SHAPES).
        val w = ModelCatalog.ditSnap(w0)
        val h = ModelCatalog.ditSnap(h0)
        val png = photo?.let {
            val src = ctx.images.get(it.id)
                ?: throw IllegalStateException("node \"${node.id}\": image ${it.id} is no longer in the store")
            val (frame, _) = CropNode.render(
                src,
                p["x"]?.toFloatOrNull() ?: 0f, p["y"]?.toFloatOrNull() ?: 0f,
                p["w"]?.toFloatOrNull() ?: 1f, p["h"]?.toFloatOrNull() ?: 1f,
                w, h, p[CropNode.PAD] ?: CropNode.PAD_BLACK,
            )
            ImageStore.encodePng(frame)
        }
        // ⭐⭐ The reference goes over the wire at its OWN size — no
        // [CropNode.render], deliberately. `image` above is fitted to the
        // canvas because it becomes the init latent; a reference is VAE-encoded
        // separately and positioned by FLUX.2's reference-token RoPE, so
        // cropping it to the output would discard its framing for nothing.
        val referencePng = reference?.let {
            val bmp = ctx.images.get(it.id)
                ?: throw IllegalStateException(
                    "node \"${node.id}\": reference image ${it.id} is no longer in the store"
                )
            // ⚠⚠ Target size **0, 0** — the region at its OWN pixels, the same
            // way an inpaint keeps the photo's own resolution. Passing `w, h`
            // here would fit the reference to the output canvas, which is
            // exactly what a reference must not be.
            val (region, _) = CropNode.render(
                bmp,
                p[REF_X]?.toFloatOrNull() ?: 0f, p[REF_Y]?.toFloatOrNull() ?: 0f,
                p[REF_W]?.toFloatOrNull() ?: 1f, p[REF_H]?.toFloatOrNull() ?: 1f,
                0, 0, CropNode.PAD_BLACK,
            )
            // ⭐ …then bounded, if the node asks for it. Default [REF_ORIGINAL]
            // sends the region exactly as it is.
            ImageStore.encodePng(boundReference(region, p[REF_MAX]))
        }
        // ⭐⭐⭐ Says what actually goes over the wire, in PIXELS.
        //
        // ⚠⚠⚠ This line exists because two reasoned fixes were shipped against
        // a cost that does not work the way the code believed. 1.5.513 bounded
        // the user's reference to 512 px and 1.5.516 did the same to the base;
        // both were reverted on 2026-09-20 when the engine logged a **1536 MB
        // encode for a 512x512 reference** and 384 MB for the same picture
        // under a 512x512 canvas. Nothing in the backend's own log states the
        // size a reference was sent AT, so there was no way to see it.
        // Same rule as `NmPreview` (CLAUDE.md): when three readings of the code
        // disagree with the phone, instrument the phone.
        //
        // ⚠ Meant to STAY — [REF_ENCODED_AT_CANVAS] is the kind of claim that
        // needs a line of evidence next to it every run.
        fun dims(b: ByteArray?): String {
            if (b == null) return "none"
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, o)
            return "${o.outWidth}x${o.outHeight}"
        }
        ctx.say(
            "[ref] canvas=${w}x$h image=${dims(png)} reference=${dims(referencePng)} " +
                "(a reference is encoded at the CANVAS, not at its own size)"
        )
        ctx.say(
            when {
                referencePng != null && png == null -> "rendering from your reference"
                referencePng != null -> "re-imagining the picture with your reference"
                png == null -> "rendering"
                else -> "re-imagining the picture"
            }
        )
        val r = ctx.host.generate(
            prompt = prompt.positive,
            negative = prompt.negative,
            steps = p["steps"]?.toIntOrNull() ?: 4,
            cfg = p["cfg"]?.toDoubleOrNull() ?: 1.0,
            seed = p["seed"]?.toIntOrNull() ?: 0,
            width = w,
            height = h,
            imagePng = png,
            denoise = p["denoise"]?.toDoubleOrNull() ?: defaultDenoise(family).toDouble(),
            referencePngs = listOfNotNull(referencePng),
            onProgress = ctx.onProgress,
        )
        val out = when (r) {
            is Ops.Result.Ok -> r.value
            is Ops.Result.Err -> throw OpFailure("generate", r.code, r.body)
        }
        val bmp = android.graphics.BitmapFactory.decodeByteArray(out.png, 0, out.png.size)
            ?: throw IllegalStateException("node \"${node.id}\": the engine's picture would not decode")
        return Value.Image(ctx.images.put(bmp), bmp.width, bmp.height)
    }

    /**
     * ⭐ The reference at [REF_MAX]'s longest edge, **aspect preserved exactly**.
     *
     * ⚠⚠⚠ This is a DETAIL control, not a memory one, and the distinction is
     * the most expensive thing learned on 2026-09-20. A reference is
     * VAE-encoded at the output canvas's size whatever it is sent at
     * ([REF_ENCODED_AT_CANVAS]), so shrinking one here saves nothing — it only
     * decides whether the model reads sharp context or upscaled context. Two
     * shipped fixes assumed the opposite and were reverted.
     *
     * ⚠ [REF_ORIGINAL], anything unparseable, and a picture already smaller
     * than the bound all return the bitmap untouched.
     */
    private fun boundReference(
        src: android.graphics.Bitmap,
        setting: String?,
    ): android.graphics.Bitmap {
        val maxEdge = setting?.toIntOrNull() ?: return src
        val longest = maxOf(src.width, src.height)
        if (maxEdge <= 0 || longest <= maxEdge) return src
        val scale = maxEdge.toFloat() / longest
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return android.graphics.Bitmap.createScaledBitmap(src, w, h, true)
    }

    /** ⚠ Never put in the store: [ImageStore.encodePng] says why. */
    private suspend fun encode(
        ctx: NodeCtx,
        png: ByteArray,
        seed: Int,
        w: Int,
        h: Int,
    ): String = when (
        val r = ctx.host.vaeEncode(png, seed, w, h)
    ) {
        is Ops.Result.Ok -> r.value.handle
        is Ops.Result.Err -> throw OpFailure("vae_encode", r.code, r.body)
    }

    private suspend fun sample(
        ctx: NodeCtx,
        p: Map<String, String>,
        cond: String,
        latent: String?,
        w: Int,
        h: Int,
        aspect: String?,
        inpaintImage: ByteArray? = null,
        inpaintMask: ByteArray? = null,
    ): String {
        val r = ctx.host.sample(
            steps = p["steps"]?.toIntOrNull() ?: 20,
            cfg = p["cfg"]?.toDoubleOrNull() ?: 7.5,
            seed = p["seed"]?.toIntOrNull() ?: 0,
            width = w,
            height = h,
            latentHandle = latent,
            denoise = p["denoise"]?.toDoubleOrNull() ?: defaultDenoise(family).toDouble(),
            scheduler = p["scheduler"].orEmpty(),
            condHandle = cond,
            aspect = aspect,
            inpaintImage = inpaintImage,
            inpaintMask = inpaintMask,
            onProgress = ctx.onProgress,
        )
        return when (r) {
            is Ops.Result.Ok -> r.value.handle
            is Ops.Result.Err -> throw OpFailure("sample", r.code, r.body)
        }
    }
}

/**
 * ⭐⭐ The end of a flow: show what came out, and keep it.
 *
 * ⚠⚠ **A display, and nothing else** — the user's call, 2026-09-15: every
 * post-process belongs to the sampler. It takes a picture OR a clip, because
 * "which of these is the deliverable" is the same question either way.
 *
 * ⚠ Deleted on 2026-09-13 as a node whose only widget had become a no-op, and
 * un-deleted here because chaining gave it back a job: in
 * `sampler → sampler → output` it is what marks the picture worth keeping, so
 * the intermediates do not fill the gallery.
 *
 * ⚠⚠ NOT cacheable — saving is a side effect, and a cached Output would skip the
 * write on the second Run, leaving the user having pressed the button twice for
 * one file.
 */
object MediaOutputNode : NodeType {
    override val name = "core.output"
    override val version = "1"

    /**
     * ⚠ `MEDIA`, not `IMAGE` — the one port type that accepts either. A second
     * output node for clips is the shape §5.7 deleted: two nodes that differ by
     * what they happen to be handed is one node too many.
     */
    override val inputs = listOf(Port("media", "MEDIA"))

    /** ⚠ No outputs: this is where a graph ends. [run] still returns its input
     * so the canvas and the harness can draw what was produced. */
    override val outputs = emptyList<Port>()
    override val category = "output"
    override val cacheable = false

    /**
     * ⚠⚠ Twice the ordinary width — the user's call, 2026-09-15. This node
     * holds the picture the whole flow was for, and the height follows the
     * width because a preview is laid out at the image's own aspect: doubling
     * one doubles both.
     */
    override val defaultWidth = com.abrah.nightmare.canvas.Sizes.PROSE_NODE_WIDTH


    /**
     * ⭐⭐ **Autosave — every Run is KEPT in Results, automatically.**
     *
     * ⚠⚠ Not a gallery write. The user's call, 2026-09-15: the gallery is an
     * EXPORT and stays a deliberate tap on ⬇, or twenty runs while tweaking a
     * prompt become twenty files in the camera roll. Results is the app's own
     * shelf, and it keeps the FLOW beside the picture, which is what an
     * automatic keep should fill.
     */
    const val AUTOSAVE = "autosave"

    override val widgets = listOf(
        // ⭐ ON by default: placing this node IS the statement that this is the
        // result you want back, and Results is private to the app.
        Widget(AUTOSAVE, "bool", "true", hint = "keep every Run in Results"),
        // ⚠⚠ **No `name` box.** It was the filename prefix for the gallery
        // write this node used to do, and that write is gone — autosave keeps
        // into Results, which names things by seed and prompt. A text box whose
        // value nothing reads is worse than no box: it invites typing that has
        // no effect. Removed 2026-09-15 on the user's question, *"what does it
        // even do?"* — which was the right question, and the answer was nothing.
    )

    override fun contextKey(node: Node): ContextKey? = null

    override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value {
        val media = inputs["media"]
            ?: throw IllegalArgumentException(
                "node \"${node.id}\": nothing is wired into \"media\""
            )
        if (media !is Value.Image && media !is Value.Video) {
            throw IllegalArgumentException(
                "node \"${node.id}\": \"media\" carries ${media.describe()}, " +
                    "which is not a picture or a clip"
            )
        }
        // ⚠⚠ **It writes nothing.** Autosave KEEPS into Results, and Results is
        // a ViewModel concern — it needs the store, the flow that made the
        // picture, the seed and the batch label, none of which a node can
        // reach. `HarnessViewModel` reads [AUTOSAVE] off this node after the
        // run. ⇒ This node's whole job is to be the thing a graph points at, so
        // the result has a named destination and something to draw it.
        //
        // ⚠ It used to write a PNG to the gallery when `save` was ticked. That
        // was the same confusion the disk icon had — an export wearing the word
        // "save" — and the gallery is a deliberate tap on ⬇ now.
        return media
    }

    /**
     * ⭐ Whether [graph] keeps every Run on its own.
     *
     * ⚠ Asked by the picture actions, which dim the keep button when it is true
     * — keeping by hand would make a second copy of something already there.
     */
    fun autosaves(graph: Graph): Boolean = graph.nodes
        .filter { it.type == name }
        .any { effectiveParams(it)[AUTOSAVE].equals("true", ignoreCase = true) }
}
