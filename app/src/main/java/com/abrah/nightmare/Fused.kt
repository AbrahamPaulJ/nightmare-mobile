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
 * ⚠ **Latent-blend vs a 9-channel inpaint model is NOT a fifth type.** Same
 * ports, same editor; the difference is which checkpoint is loaded and one
 * branch inside [run]. It becomes a chip on the inpaint types when such a
 * checkpoint exists in the catalogue — today none does, and a chip with one
 * legal value is a knob that cannot matter (§5.7).
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
        // ⚠⚠ Absent entirely on a `sample` type — not present-and-ignored. A
        // port that accepts a wire and then does nothing with it is the shape
        // that makes a graph render confident nonsense.
        if (inpaint) Port("mask", "IMAGE") else null,
    )
    override val outputs = listOf(Port("image", "IMAGE"))
    override val category = "generate"

    /** ⚠ The FAMILY is the whole reason there are four of these. */
    override val paletteName =
        (if (inpaint) "inpaint" else "sample") + " · " + family.label

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
        const val MASK_ON = "mask_on"

        /**
         * ⚠⚠ The VAE encode's noise, FIXED. It is what makes one picture encode
         * to one latent, so every render downstream of it is reproducible only
         * while this does not move — see the widget list for why it is not one.
         */
        const val ENCODE_SEED = 42

        /** ⭐ The four registrations. One class; two arguments of difference. */
        val SD15 = SdSampler("sd15.sample", Family.SD15, inpaint = false)
        val SDXL = SdSampler("sdxl.sample", Family.SDXL, inpaint = false)
        val SD15_INPAINT = SdSampler("sd15.inpaint", Family.SD15, inpaint = true)
        val SDXL_INPAINT = SdSampler("sdxl.inpaint", Family.SDXL, inpaint = true)
        val ALL = listOf(SD15, SDXL, SD15_INPAINT, SDXL_INPAINT)

        /**
         * ⭐⭐ The type a graph should use for [family] and [inpaint] — the one
         * place that maps the pair to a name.
         *
         * ⚠ A recipe, the migration and the palette all need this answer, and
         * three string literals spelling "sdxl.inpaint" would be three places to
         * get it wrong once.
         */
        fun typeFor(family: Family, inpaint: Boolean): String =
            (ALL.firstOrNull { it.family == family && it.inpaint == inpaint } ?: SD15).name
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

    private fun defaultRes(): Res =
        if (SelectedModel.spec.family == family) SelectedModel.res
        else ModelCatalog.all.firstOrNull { it.family == family }?.native ?: SelectedModel.res

    override val widgets get() = listOf(
        // ⚠⚠ Defaults from the MODEL, not from a literal. A distilled checkpoint
        // publishes something like 10 steps at cfg 1.5, and this app's 20/7.5
        // renders it burnt rather than failing — measured on device 2026-09-10.
        Widget("steps", "int", SelectedModel.spec.steps.toString(), 1.0, 50.0),
        Widget("cfg", "float", SelectedModel.spec.cfg.toString(), 1.0, 20.0, fine = true),
        // ⭐ 0 rolls a new seed on every Run, so Run means "give me another one"
        // rather than returning the cached picture unchanged.
        Widget(
            "seed", "int", "0",
            hint = "0 = a new picture every Run. Type the seed shown on the node to get that one back.",
        ),
        // ⚠ Read only when a picture is wired AND `start_from` is `image`.
        Widget("denoise", "float", "0.6", 0.0, 1.0),
        Widget(
            "scheduler", "string", SelectedModel.spec.scheduler,
            options = ModelCatalog.SCHEDULERS,
            hint = "the sampler; a distilled model usually needs the one its author published",
        ),
        // ⚠⚠ **No `start from` knob.** The user's call, 2026-09-15: *"start from
        // is decided by whether an image is connected, simple as that."* It
        // shipped as a switch so a flow could flip without rewiring — which is
        // right for the VIDEO node, where the alternative to a photo is four
        // seconds of SSD1B generating a first frame, and wrong here, where the
        // alternative is nothing at all. A knob whose value the wire already
        // states is a second place for the same fact to live.
        // ⚠ The same switch for the painting, so inpaint ↔ full re-render costs
        // no rewiring AND does not erase what was painted.
        *(if (inpaint) arrayOf(Widget(MASK_ON, "bool", "true", hint = "use the area you painted")) else emptyArray()),
        // ⭐⭐ The framing, normalised 0..1 of the source — and under
        // `image.crop`'s OWN param names, so [cropRectOf], [CropRect.asParams]
        // and [CropEditor] work on this node with no second spelling to keep in
        // step. ⚠ Normalised so a saved flow re-pointed at a photo of a
        // different size still means the same framing.
        Widget("x", "float", "0.0", 0.0, 1.0, hint = "drag the frame on the picture"),
        Widget("y", "float", "0.0", 0.0, 1.0),
        Widget("w", "float", "1.0", 0.0, 1.0),
        Widget("h", "float", "1.0", 0.0, 1.0),
        // ⚠ Drawn as the tick/pencil in the Crop title row, never as a checkbox
        // in the knob list ([hiddenKnob]).
        Widget(CropNode.LOCKED, "bool", "false"),
        // ⚠ No `pad` chip either: it only does anything when the photo has
        // fewer pixels than the render, which is rare, and it was a two-option
        // row sitting in front of everyone for the case that is not theirs.
        // `image.crop` still offers it — that node IS the framing.
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
        *aspectWidget(),
        Widget("width", "int", defaultRes().width.toString(), contextKey = true),
        Widget("height", "int", defaultRes().height.toString(), contextKey = true),
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
        if (inpaint && p[MaskNode.OPS].orEmpty().isNotBlank() && p[MASK_ON].equals("true", true)) return null
        return (p["width"]?.toIntOrNull() ?: 0) to (p["height"]?.toIntOrNull() ?: 0)
    }

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
        val aspect = nodeAspect(node)
            ?.takeIf { ModelCatalog.aspectTarget(it, Res(w, h)) != null }

        // ⚠ First, because it is the cheapest thing that can fail: a backend
        // that is not up says so here rather than after a 200 ms VAE encode.
        ctx.say("reading the prompt")
        val cond = when (val r = ctx.host.encodeText(prompt.positive, prompt.negative)) {
            is Ops.Result.Ok -> r.value.handle
            is Ops.Result.Err -> throw OpFailure("encode_text", r.code, r.body)
        }

        // ⭐ The switch, not the wire, decides. A photo wired with `start_from`
        // at `noise` is ignored ON PURPOSE and the canvas draws it dimmed.
        // ⚠ The WIRE decides, and nothing else: an image is connected or it is
        // not (2026-09-15).
        val photo = inputs["image"] as? Value.Image

        if (photo == null) {
            ctx.say("rendering")
            val latent = sample(ctx, p, cond, null, w, h, aspect)
            return VaeDecodeNode.decode(ctx, latent, w, h, aspect)
        }

        val src = ctx.images.get(photo.id)
            ?: throw IllegalStateException(
                "node \"${node.id}\": image ${photo.id} is no longer in the store"
            )

        // ⭐⭐ Is there a mask? Either painted here or wired in — the wire wins.
        val painted = MaskState.decode(p[MaskNode.OPS]).copy(
            growFrac = num("grow").toFloat(),
            featherFrac = num("feather").toFloat(),
        )
        val wired = inputs["mask"] as? Value.Image
        // ⚠ `inpaint` first: a `sample` type has no mask port and no painting
        // params, so there is nothing here to be true.
        val masking = inpaint && flag(MASK_ON) && (wired != null || !painted.isEmpty)

        // ⚠⚠ The framed photo. With a mask it keeps its OWN pixels, because the
        // patch is pasted back into it at the end and a frame already reduced to
        // 512² would paste a 512² picture back over a photo. Without one the
        // frame IS the render, so it goes straight to the model's size.
        val fx = num("x").toFloat()
        val fy = num("y").toFloat()
        val fw = num("w").toFloat()
        val fh = num("h").toFloat()
        val (frame, _) = CropNode.render(
            src, fx, fy, fw, fh,
            if (masking) 0 else w, if (masking) 0 else h,
            // ⚠ Black bars when a photo is too small for the frame. The blurred
            // fill is `image.crop`'s to offer; this node does not ask.
            CropNode.PAD_BLACK,
        )

        if (!masking) {
            ctx.say("re-imagining the picture")
            val base = encode(ctx, frame, ENCODE_SEED, w, h)
            val latent = sample(ctx, p, cond, base, w, h, aspect)
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
        val maskSrc = wired?.let { ctx.images.get(it.id) }
            ?: MaskRaster.rasterise(
                painted,
                (src.width * cap).toInt().coerceAtLeast(1),
                (src.height * cap).toInt().coerceAtLeast(1),
            )
        // ⚠⚠ PAD_BLACK, never the blurred fill: black is "not masked". A
        // mirrored edge here would invent painting outside the photo.
        val maskBmp = CropNode.render(
            maskSrc, fx, fy, fw, fh, frame.width, frame.height, CropNode.PAD_BLACK,
        ).first

        // ⭐⭐ "Only masked" — the render window is a crop around the painting,
        // so the detail lands where the finger was.
        val cut = MaskCropNode.cut(frame, maskBmp, w, h, flag(MaskCropNode.ONLY_MASKED))
        ctx.say("repainting the area you marked")
        val base = encode(ctx, cut.image, ENCODE_SEED, w, h)
        val repainted = sample(ctx, p, cond, base, w, h, aspect)

        // ⚠⚠ `base` then `repainted`: the mask's WHITE area is where the new
        // pixels show through. The other way round replaces everything EXCEPT
        // what was painted — a plausible picture and a silent mistake.
        val blended = when (
            val r = ctx.host.latentBlend(base, repainted, ImageStore.encodePng(cut.mask))
        ) {
            is Ops.Result.Ok -> r.value.handle
            is Ops.Result.Err -> throw OpFailure("latent_blend", r.code, r.body)
        }
        val patch = VaeDecodeNode.decode(ctx, blended, w, h, aspect)
        val patchBmp = ctx.images.get(patch.id)
            ?: throw IllegalStateException("node \"${node.id}\": the render vanished from the store")

        // ⭐⭐ The patch goes back where it was cut from, blended along the mask
        // rather than pasted as a rectangle — the seam is the whole reason the
        // inpaint output "did not join up with the original" (2026-09-15).
        val dst = android.graphics.RectF(
            cut.rect[0].toFloat(), cut.rect[1].toFloat(),
            (cut.rect[0] + cut.rect[2]).toFloat(), (cut.rect[1] + cut.rect[3]).toFloat(),
        )
        val out = InpaintPixels.composite(frame, patchBmp, dst, cut.mask)
        return Value.Image(ctx.images.put(out), out.width, out.height)
    }

    /** ⚠ Never put in the store: [ImageStore.encodePng] says why. */
    private suspend fun encode(
        ctx: NodeCtx,
        bmp: android.graphics.Bitmap,
        seed: Int,
        w: Int,
        h: Int,
    ): String = when (
        val r = ctx.host.vaeEncode(ImageStore.encodePng(bmp), seed, w, h)
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
    ): String {
        val r = ctx.host.sample(
            steps = p["steps"]?.toIntOrNull() ?: 20,
            cfg = p["cfg"]?.toDoubleOrNull() ?: 7.5,
            seed = p["seed"]?.toIntOrNull() ?: 0,
            width = w,
            height = h,
            latentHandle = latent,
            denoise = p["denoise"]?.toDoubleOrNull() ?: 0.6,
            scheduler = p["scheduler"].orEmpty(),
            condHandle = cond,
            aspect = aspect,
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
