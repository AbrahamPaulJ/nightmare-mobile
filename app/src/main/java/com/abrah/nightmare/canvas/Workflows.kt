package com.abrah.nightmare.canvas

import com.abrah.nightmare.Graph
import com.abrah.nightmare.ModelCatalog
import com.abrah.nightmare.Node
import com.abrah.nightmare.SelectedModel
import com.abrah.nightmare.sources

/**
 * The context-key params every backend node in a recipe carries.
 *
 * ⚠⚠ A function, evaluated per build, for the same reason the recipes are
 * ([RECIPES]): it reads [SelectedModel]. ⚠ And the SIZE comes from the model
 * too, not from a `"512"` beside each node -- `sdxl` and `anima` force 1024
 * inside the backend's request parser whatever the client sends, so a recipe
 * carrying a hardcoded 512 would render 1024 in silence on the first non-SD1.5
 * family. `docs/MODELS.md` §3 step 3.
 */
/**
 * ⭐⭐ The sampler type a recipe should build, for the checkpoint in use.
 *
 * ⚠⚠ A recipe cannot name `"sd15.sample"` as a literal since the fork of
 * 2026-09-15: opening "Text to image" with an SDXL checkpoint selected would
 * build a node of the wrong family, which refuses to run rather than rendering.
 * ⇒ The family comes from [SelectedModel], exactly as `model` and the size do.
 */
private fun samplerType(inpaint: Boolean = false): String =
    com.abrah.nightmare.SdSampler.typeFor(SelectedModel.spec.family, inpaint)

/**
 * ⚠⚠⚠ **The model must match the TYPE this recipe is building, not the
 * selection.** [samplerType] already falls back to SD 1.5 inpaint when the
 * selected family has no inpaint type (the DiT ones), but this function used
 * to hand back `SelectedModel.id` regardless — so opening Inpaint with FLUX.2
 * selected built an `sd15.inpaint` node carrying `flux2_klein_4b`. It rendered
 * nothing useful, and the picker then (correctly) refused to offer FLUX back,
 * which is how it was found from the phone on 2026-09-20.
 *
 * ⚠⚠ The rule already existed — [com.abrah.nightmare.SdSampler.defaultModel]
 * has honoured it since the node rework: *the selected checkpoint when it
 * belongs to this family, that family's entry otherwise.* A dragged-out node
 * obeyed it and a recipe did not, which is the N−1-of-N failure
 * `CLAUDE.md` warns about. This is the same rule, with one addition:
 *
 * ⭐ **Prefer one that is INSTALLED** ([ModelCatalog.installedIds]). The
 * user's ask, 2026-09-20: opening Inpaint should land on AbsoluteReality
 * Inpaint when it is downloaded rather than on the catalogue's first SD 1.5
 * entry, which may be a 1 GB download away. ⚠ A true inpaint checkpoint wins
 * over a plain one of the same family, since the node is an inpaint node.
 */
private fun ctxKeyParams(inpaint: Boolean = false): Map<String, String> {
    val type = samplerType(inpaint)
    val sampler = com.abrah.nightmare.SdSampler.ALL.firstOrNull { it.name == type }
    val family = sampler?.family ?: SelectedModel.spec.family

    // ⚠ The SELECTED size, not the model's native one, WHEN the family
    // matches. A recipe builds NEW nodes, and a new node is born at the size
    // the user last chose for this checkpoint (`SelectedModel.res`) -- opening
    // a recipe at 512 while the picker said 768x512 would silently retarget
    // their whole graph back. ⚠⚠ When the family does NOT match, that size
    // belongs to another family entirely (a DiT 1024 on an SD 1.5 node), so
    // the chosen model's own native size is the only sane answer.
    if (SelectedModel.spec.family == family) {
        val res = SelectedModel.res
        return mapOf(
            "model" to SelectedModel.id,
            "width" to res.width.toString(),
            "height" to res.height.toString(),
        )
    }

    val ofFamily = ModelCatalog.all.filter { it.family == family }
    val installed = ofFamily.filter { it.id in ModelCatalog.installedIds }
    // ⚠ `installed` is empty before `refreshInstalled` has run, and also when
    // the user genuinely has none of this family; both degrade to the
    // catalogue order rather than to no model at all.
    val pool = installed.ifEmpty { ofFamily }
    val pick = (if (inpaint) pool.firstOrNull { it.isInpaint } else null)
        ?: pool.firstOrNull()
        ?: return mapOf("model" to SelectedModel.id)
    val res = pick.native
    return mapOf(
        "model" to pick.id,
        "width" to res.width.toString(),
        "height" to res.height.toString(),
    )
}

/**
 * ⭐⭐ The prompt node's text: the SELECTED checkpoint's own, never a literal.
 *
 * ⚠⚠ A recipe carried `"a cat on grass"` for txt2img and a bare quality tag
 * for the other two, which is a prompt tuned for whatever model happened to be
 * selected the day it was typed. The catalogue has carried a per-model prompt
 * and negative since it was written (`ModelCatalog.ModelSpec.prompt`, copied
 * from upstream) and nothing read them: an anime checkpoint opened on a
 * photographic prompt with a photographic negative. The user's ask, 2026-09-12.
 *
 * ⚠ A function, per build, for the reason [ctxKeyParams] is one: it reads
 * [SelectedModel].
 *
 * ⚠⚠ [com.abrah.nightmare.ModelSpec.starterPrompt], not `prompt`: an
 * IMPORTED model carries no text of its own, and a recipe reading the raw field
 * opened it on a blank box. The fallback is the FAMILY's general-purpose pair
 * ([com.abrah.nightmare.Family.prompt]) -- quality tags with no subject, so
 * nothing has to be deleted before typing. The user's ask, 2026-09-15.
 *
 * ⚠ [com.abrah.nightmare.PromptNode]'s widgets default the same way, for a
 * node dragged from the palette.
 */
private fun promptParams(): Map<String, String> = mapOf(
    "prompt" to SelectedModel.spec.starterPrompt,
    "negative" to SelectedModel.spec.starterNegative,
)

/**
 * The workflow the app opens with.
 *
 * ⚠⚠ ONE definition, two callers: the canvas screen's Run button and the
 * headless `canvas_run` op both use this. A screen that ran a graph the
 * headless check could not reproduce would make every device report
 * unattributable — the two would drift the first time either was edited.
 *
 * ⚠ Built-ins only. A default that used plugin nodes would fail to open on a
 * device with no packs pushed, which is every device except this one.
 */
fun defaultWorkflow(): Workflow = Workflow(
    Graph(
        listOf(
            // ⭐ THE prompt, and it carries TEXT (docs/ARCHITECTURE.md §5.7).
            // First in the chain because it is the first thing anyone changes.
            Node("prompt", "core.prompt", params = promptParams()),
            Node(
                "generate", samplerType(),
                params = ctxKeyParams() + mapOf(
                    // ⚠⚠ **No `steps` here, deliberately** -- the node's own
                    // default (20) applies, which is DreamUI's default too.
                    //
                    // This recipe carried `steps = 8`, a fast-first-render
                    // choice made when the only family was SD 1.5 at 512. On
                    // SDXL at 1024 it is a badly undercooked denoise, and it
                    // does not look soft -- it looks BROKEN: rainbow speckle
                    // over the whole frame. Reported from the phone as
                    // "blurry, bad quality" on epiCRealism XL, and measured
                    // there at one seed, 2026-09-09: 8 steps 12 s and unusable,
                    // 20 steps 24 s and clean, 30 steps 38 s and cleaner still.
                    //
                    // ⇒ A default tuned for one family's cost is a trap for the
                    // next one. 20 is the value both families are known good at.
                    //
                    // ⚠⚠ And **no `cfg` either, for exactly the same reason** --
                    // it sat here as a literal 7.5 one line below that warning
                    // until 2026-09-10. A DISTILLED checkpoint publishes cfg
                    // 1.5, and 7.5 does not fail on one: it renders burnt and
                    // oversaturated, which reads as a bad conversion. The
                    // sampler node now defaults both from the MODEL
                    // (`ModelSpec.steps`/`cfg`), so a recipe that named either
                    // would override the checkpoint that knows better.
                    // ⭐ 0, so Run gives a NEW picture each time rather than
                    // the cached one. A fixed seed is opt-in, for when a
                    // render is worth reproducing.
                    "seed" to "0",
                ),
                inputs = sources("prompt" to "prompt"),
            ),
            // ⭐ The end of the flow: it shows the picture and keeps it. The
            // sampler draws its own render too -- this is what says "THIS one is
            // the deliverable", which is what it is for in a chain.
            Node("output", "core.output", inputs = sources("media" to "generate")),
        )
    ),
    // ⚠ One feeder, so the prompt sits alone in the left column and the
    // sampler hangs just under it. [flowLayout] owns every number.
    flowLayout("prompt", "generate", "output"),
)

/**
 * ⭐⭐⭐ **The shape every recipe is laid out in** — the feeders down the LEFT,
 * the renderer in the middle, the output on the right.
 *
 * ⚠⚠ Arranged by hand on the phone and screenshotted, 2026-09-15: *"prompt and
 * image nodes at same side so that the wires dont cross, the wires look clean,
 * and all nodes fit on the screen — just do like this one for all default
 * workflows"*. This function is that arrangement generalised, and it
 * generalises because every recipe here is the SAME shape: N feeders into one
 * renderer into one output.
 *
 * ⚠⚠ It replaces the diagonal of 2026-09-13, and the reason is the one thing
 * a diagonal cannot do: it put `prompt` and `photo` in different COLUMNS, so
 * the prompt's wire had to cross the photo's to reach a sampler whose `prompt`
 * port sits above its `image` port. Stacking the feeders in one column in PORT
 * ORDER makes that crossing impossible rather than merely unlikely.
 *
 * ⚠⚠⚠ What the diagonal got right is kept: a wire must point FORWARD, and
 * an output port sits on a node's right edge while an input sits on the next
 * one's left edge. So each column starts clear of the WIDEST node in the column
 * before it — [WORKER_X] past a 380-wide prose node, [OUTPUT_X] past a 190-wide
 * renderer. A column narrower than the node feeding it draws a backward wire
 * even though the node is further right.
 *
 * ⚠ Nothing here knows a node's HEIGHT (a prompt node is ~300 units, a photo
 * ~140), so the vertical numbers are clearances chosen against the tallest of
 * them rather than a stack. That is also why the columns must not overlap in x:
 * a node that grows a picture or a third prose line then cannot collide with a
 * neighbour.
 *
 * @param ids feeders first, then the renderer, then the output. ⚠ The feeders
 *   must be in the renderer's own PORT order — prompt before image — or the
 *   wires cross again.
 */
private fun flowLayout(vararg ids: String): Map<String, Pt> {
    val feeders = ids.dropLast(2)
    val worker = ids[ids.size - 2]
    val output = ids.last()
    // ⭐ The renderer sits BETWEEN its feeders, which is what keeps both wires
    // short and stops either of them travelling past a node.
    val middle = TOP + (feeders.size - 1) * FEED_STEP_Y / 2f
    return buildMap {
        feeders.forEachIndexed { i, id -> put(id, Pt(LEFT, TOP + i * FEED_STEP_Y)) }
        put(worker, Pt(WORKER_X, middle + WORKER_DROP))
        put(output, Pt(OUTPUT_X, middle + WORKER_DROP + OUTPUT_DROP))
    }
}

/**
 * ⚠⚠⚠ **The clearance under the floating top bar, and it is a WORLD
 * number on purpose.**
 *
 * The bar is drawn over the canvas and is up to three rows — tabs, flow name,
 * load line — which is ~125dp. A recipe opens at the scale `CanvasState.fitted`
 * picks (~0.32 for the shape below), so the clearance on screen is `TOP * scale`
 * dp on every device: the density cancels, which is why this is not an offset
 * applied to the viewport. `Viewport.offset` is in device PIXELS and nothing
 * that can see the density is in a position to set it.
 *
 * ⚠⚠ It was 120 while recipes opened at ~1x. At 0.32 that is 38dp, and the
 * prompt node's title was drawn underneath the model name — which is the exact
 * bug the 120 was chosen to fix, reappearing because the ZOOM changed and the
 * two numbers were never re-checked together.
 */
private const val TOP = 500f

/**
 * ⭐ [positions] moved so the graph's top-left node sits where a recipe's first
 * node does — so the fit [CanvasState.withView] gives a recipe frames it too.
 * ⚠ Layout only; wires and params are untouched.
 */
fun layoutAtRecipeOrigin(positions: Map<String, com.abrah.nightmare.canvas.Pt>): Map<String, com.abrah.nightmare.canvas.Pt> {
    if (positions.isEmpty()) return positions
    val dx = LEFT - positions.values.minOf { it.x }
    val dy = TOP - positions.values.minOf { it.y }
    return positions.mapValues { (_, p) -> com.abrah.nightmare.canvas.Pt(p.x + dx, p.y + dy) }
}

private const val LEFT = 24f

/**
 * ⚠⚠ Clear of a PROMPT node, not of a photo one. A prompt is born with two
 * prose boxes and their captions, ~300 units tall, and it is always the first
 * feeder — so the second feeder is placed below that, not below the short node
 * it happens to be.
 */
private const val FEED_STEP_Y = 380f

/**
 * ⚠ Past the 380-wide prose column, with a gap a wire can be seen in.
 *
 * ⚠⚠ The gaps were trimmed (140 → 110, 130 → 100) on 2026-09-15 for one
 * reason: this layout's total WIDTH is what sets the opening zoom, and every
 * unit of gap is a unit the whole graph has to shrink by to fit a phone. Air
 * between columns that costs legibility in the nodes is a bad trade.
 */
private const val WORKER_X = LEFT + Sizes.PROSE_NODE_WIDTH + 110f

/** ⚠ Past the 190-wide renderer. See [WORKER_X] on why the gap is this tight. */
private const val OUTPUT_X = WORKER_X + Sizes.NODE_WIDTH + 100f

/**
 * ⚠ The renderer hangs slightly BELOW the midpoint of its feeders rather than
 * on it: its `prompt` port is near its top, so a centred node would send the
 * first wire faintly upwards.
 */
private const val WORKER_DROP = 40f

/** ⚠ And the output below the renderer again, so its wire reads as forward. */
private const val OUTPUT_DROP = 240f

/**
 * A graph a user can start from.
 *
 * ⚠ Built-ins only, for the same reason [defaultWorkflow] is: a recommended
 * workflow that needed a plugin pack would fail to open on any device that has
 * not been handed one, which is every device but the developer's.
 */
data class Recipe(
    val id: String,
    val label: String,
    val about: String,
    val build: () -> Workflow,
    /**
     * ⭐⭐ Whether this flow runs on a CHECKPOINT.
     *
     * ⚠⚠ The video flows and the upscaler do not: they load their own weights
     * and bind no [com.abrah.nightmare.ContextKey] at all. Offering them when a
     * user has just picked a checkpoint is offering flows that will ignore the
     * choice they made — reported 2026-09-15 as the Use dialog showing every
     * flow.
     */
    val usesCheckpoint: Boolean = true,
)

/**
 * ⚠ The text node is called **`prompt`**, not `text`.
 *
 * A node's id is its title on the canvas, and "text" named the DATA TYPE where
 * every other node in these recipes is named for its job (`sample`, `decode`,
 * `frame`, `mask`). The thing a person is looking for when they open one of
 * these is where to type the prompt. The user's call, 2026-09-11.
 *
 * ⚠ A saved workflow keeps whatever ids it was written with. ✅ But a node
 * dragged from the palette is born `prompt` too since 2026-09-12 — its id comes
 * from the type's LABEL, and `sd.clip_encode` is labelled `prompt`
 * (`LABEL_OVERRIDES`).
 */

/**
 * ⭐ The recommended workflows.
 *
 * ⚠ Functions, not values: they read [SelectedModel], so a recipe evaluated once
 * at class-init would pin whichever model happened to be selected at app start.
 */
val RECIPES: List<Recipe> = listOf(
    Recipe(
        "txt2img", "Text to image",
        "A prompt in, a picture out. The one to start with.",
        ::defaultWorkflow,
    ),
    Recipe(
        "img2img", "Image to image",
        "A photo from the gallery, re-imagined at the strength you choose.",
        ::img2imgWorkflow,
    ),
    Recipe(
        "inpaint", "Inpaint — paint an area to redo",
        "Paint over part of a photo and only that part is re-imagined. Open the " +
            "sampler and tap Mask to paint.",
        ::inpaintWorkflow,
    ),
    // ⚠⚠ **Upscale sits with the picture flows, before the video ones.** The
    // user's call, 2026-09-15. It is a PICTURE flow — a photo in, a bigger
    // photo out — and it was only after the video pair because that is the
    // order the two products shipped in. Ordering a list by the git log is the
    // one ordering no user can predict.
    Recipe(
        "upscale", "Upscale a photo",
        "A picture from the gallery, enlarged 4x. No checkpoint involved — " +
            "the upscaler is its own small model, installed under Models.",
        ::upscaleWorkflow,
        usesCheckpoint = false,
    ),
    Recipe(
        "t2v", "Text to video",
        // ⚠ 1024x640, the way round it actually COMES OUT. `../Neodragon`'s docs
        // say "320x512 -> 640x1024" in (height, width) order, and repeating that
        // here would have told the user a portrait clip and handed them a
        // landscape one. Measured on device 2026-09-12: 49 frames, 1024x640.
        "A prompt in, a 2 second clip out — 49 frames at 1024x640, on the NPU. " +
            "Needs the video models installed; it does not use your checkpoint.",
        ::textToVideoWorkflow,
        usesCheckpoint = false,
    ),
    Recipe(
        "i2v", "Image to video",
        // ⚠ The speed is the SELLING point and it is measured, not guessed:
        // 20.6 s against t2v's 24.6 s on device 2026-09-13, because SSD1B never
        // runs. ⚠⚠ It also needs 1.68 GB fewer models, which matters to
        // someone deciding what to download.
        "A photo from the gallery, brought to life — 49 frames at 1024x640. " +
            "Faster than text to video, and it needs three fewer models.",
        ::imageToVideoWorkflow,
        usesCheckpoint = false,
    ),
)

/**
 * ⭐⭐⭐ Inpainting — the SAME four nodes as image to image, with a mask painted
 * on the sampler.
 *
 * `docs/ARCHITECTURE.md` §5.7. It was TEN nodes until 2026-09-15:
 * `photo → frame → mask → cut → encode → sample → blend → decode → paste`, six
 * of which existed only because a latent was visible on a wire. Every one of
 * those steps still happens — inside [com.abrah.nightmare.RenderNode], calling
 * the same functions the nodes called.
 *
 * ⭐ **A recipe is a starting point, not a machine.** This one and
 * [img2imgWorkflow] build the same graph; what makes it an inpaint is the mask
 * and the denoise, and a user can turn one into the other by painting or by
 * clearing the mask — without rewiring anything.
 *
 * ⚠ `denoise` 0.65, the same as image to image — the user's call, 2026-09-17,
 * replacing an inpaint-only 0.85 ("inside the mask the point is to make
 * something new"). ⚠ If a painted area reads as "the mask did nothing", this is
 * the knob to raise.
 */
fun inpaintWorkflow(): Workflow = Workflow(
    Graph(
        listOf(
            Node("prompt", "core.prompt", params = promptParams()),
            Node("photo", "core.image", params = mapOf("uri" to "")),
            // ⭐ Wired by default (the user's call, 2026-09-17, reversing "no new
            // recipe" of the day before): the Tap tool is the easy way to mask, and
            // a person should not have to know a node exists to find it. Without
            // the model installed the tool says where to get it.
            Node(
                "segment_model", "mask.segment_model",
                params = mapOf(com.abrah.nightmare.SelectObjectNode.MODEL to com.abrah.nightmare.segment.Segmenter.LABEL),
            ),
            // ⭐ Tap the node, then Mask, to paint. The framing lives here too —
            // there is no crop node in the chain any more, because the sampler
            // fits whatever it is given.
            Node(
                "inpaint", samplerType(inpaint = true),
                params = ctxKeyParams(inpaint = true) + mapOf("seed" to "0", "denoise" to "0.65"),
                inputs = sources("prompt" to "prompt", "image" to "photo", "segmenter" to "segment_model"),
            ),
            Node("output", "core.output", inputs = sources("media" to "inpaint")),
        )
    ),
    flowLayout("prompt", "photo", "segment_model", "inpaint", "output"),
)

/**
 * Photo in, re-imagined picture out.
 *
 * ⚠ `denoise` is what makes this useful rather than a noisy copy: at 1.0 the
 * source is entirely renoised, which is txt2img with extra steps.
 *
 * ⚠⚠ **No crop node**, and that is the change of 2026-09-15: `image.crop` used
 * to stand here because `sd.vae_encode` demanded an exact 512². The sampler
 * fits the photo itself now, so the node earns its place only when a framing is
 * worth choosing once and feeding to two branches.
 */
fun img2imgWorkflow(): Workflow = Workflow(
    Graph(
        listOf(
            Node("prompt", "core.prompt", params = promptParams()),
            Node("photo", "core.image", params = mapOf("uri" to "")),
            Node(
                "generate", samplerType(),
                // ⚠ No `steps`/`cfg`: the model supplies both (see
                // [defaultWorkflow]). `denoise` stays — it is a property of THIS
                // recipe, not of the checkpoint.
                params = ctxKeyParams() + mapOf("seed" to "0", "denoise" to "0.65"),
                inputs = sources("prompt" to "prompt", "image" to "photo"),
            ),
            Node("output", "core.output", inputs = sources("media" to "generate")),
        )
    ),
    flowLayout("prompt", "photo", "generate", "output"),
)


/**
 * ⭐⭐ Enlarge a picture, and nothing else.
 *
 * ⚠⚠ **No sampler, no checkpoint, no [ContextKey] at all.** An upscaler binds
 * nothing at backend launch — `/upscale` builds its own QNN context per request
 * and frees it after — so this graph pins the process to nothing and runs beside
 * any model.
 *
 * ⚠ It exists because wiring a photo straight into an upscale node by hand gave
 * no clue what was missing: the node needs an upscaler INSTALLED (Models →
 * Upscalers). Asked for from the phone, 2026-09-11.
 */
fun upscaleWorkflow(): Workflow = Workflow(
    Graph(
        listOf(
            Node("photo", "core.image", params = mapOf("uri" to "")),
            Node(
                "upscale", "image.upscale",
                // ⚠ No `upscaler` param written: the node's own default is the
                // first INSTALLED one, read at call time, and pinning a literal
                // here would name a file a fresh install does not have.
                inputs = sources("image" to "photo"),
            ),
            Node("output", "core.output", inputs = sources("media" to "upscale")),
        )
    ),
    positions = flowLayout("photo", "upscale", "output"),
)

/**
 * ⭐⭐ **Image to video** — a photo brought to life, in the same four nodes as
 * image to image.
 *
 * ⚠⚠ The ONLY difference from [textToVideoWorkflow] is the photo. Since
 * 2026-09-15 there is no first-frame node and no crop node: the sampler makes
 * its own first frame when nothing is wired, and frames the photo itself when
 * one is. The user's call — one video sampler for both.
 *
 * ⭐ It is also the CHEAPER path, measurably: SSD1B never runs (~4 s and
 * 614 MB), and three of the thirteen models are not needed at all.
 */
fun imageToVideoWorkflow(): Workflow = Workflow(
    Graph(
        listOf(
            Node(
                "prompt", "core.prompt",
                // ⚠ A MOTION prompt, not a subject one: the subject is the
                // photo. "a cat walking" against a picture of a harbour is the
                // instruction fighting the image it was given.
                params = mapOf(
                    "prompt" to "gentle camera push in, subtle motion",
                    "negative" to "",
                ),
            ),
            Node("photo", "core.image", params = mapOf("uri" to "")),
            Node(
                "video", "nd.sample",
                params = mapOf("seed" to "0", "upscale" to "true"),
                inputs = sources("prompt" to "prompt", "image" to "photo"),
            ),
            Node("output", "core.output", inputs = sources("media" to "video")),
        )
    ),
    flowLayout("prompt", "photo", "video", "output"),
)

/**
 * ⭐⭐ **Text to video** — a prompt in, a clip out, in the shape of every other
 * recipe here.
 *
 * ⚠⚠ **No [ctxKeyParams].** The video path loads its own QNN context binaries
 * in-process and binds nothing at backend launch, so the checkpoint in the top
 * bar is irrelevant to it — and a video node never forces a model load.
 *
 * ⚠ The first frame is made INSIDE the sampler. It used to be its own node you
 * could look at and re-roll before paying 20 s for the clip; that went with the
 * fusion, and it is the one thing this shape costs.
 */
fun textToVideoWorkflow(): Workflow = Workflow(
    Graph(
        listOf(
            Node(
                "prompt", "core.prompt",
                params = mapOf(
                    "prompt" to "a cat walking through tall grass, cinematic",
                    "negative" to "",
                ),
            ),
            Node(
                "video", "nd.sample",
                params = mapOf("seed" to "0", "upscale" to "true"),
                inputs = sources("prompt" to "prompt"),
            ),
            Node("output", "core.output", inputs = sources("media" to "video")),
        )
    ),
    flowLayout("prompt", "video", "output"),
)
