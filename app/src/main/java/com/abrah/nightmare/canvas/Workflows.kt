package com.abrah.nightmare.canvas

import com.abrah.nightmare.Graph
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
private fun ctxKeyParams(): Map<String, String> {
    // ⚠ The SELECTED size, not the model's native one. A recipe builds NEW
    // nodes, and a new node is born at the size the user last chose for this
    // checkpoint (`SelectedModel.res`) -- opening a recipe at 512 while the
    // picker said 768x512 would silently retarget their whole graph back.
    val res = SelectedModel.res
    return mapOf(
        "model" to SelectedModel.id,
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
 * ⚠ An imported model's is empty unless its `config.json` says otherwise --
 * see [com.abrah.nightmare.TextEncodeNode]'s widgets, which default the same
 * way for a node dragged from the palette.
 */
private fun promptParams(): Map<String, String> = mapOf(
    "prompt" to SelectedModel.spec.prompt,
    "negative" to SelectedModel.spec.negative,
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
            // ⭐ THE prompt. The sampler has none (docs/ARCHITECTURE.md §3), so
            // this node is where a user types, and it is first in the chain
            // because it is the first thing they will want to change.
            Node("prompt", "sd.clip_encode", params = promptParams()),
            Node(
                "sample", "sd.sample",
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
                inputs = sources("cond" to "prompt"),
            ),
            Node(
                "decode", "sd.vae_decode",
                params = ctxKeyParams(),
                inputs = sources("latent" to "sample"),
            ),
        )
    ),
    // One column, because this graph really is one chain. Spaced so a node and
    // the port beneath it never collide at 1x on a 411dp-wide phone.
    // ⚠ y starts below the canvas TOP BAR rather than at the very top: the bar
    // floats over the canvas, so a node at y=40 had its title drawn underneath
    // the model name on the app's first screen.
    // ⚠⚠ 120, not 96. The bar became TWO rows when the model name moved onto a
    // line of its own, and 96 then cleared it by 3dp at the default zoom --
    // which is not clearance, it is a coincidence. Chrome height and node
    // positions are independent numbers that have to be re-checked together.
    // ⚠⚠ The gap under `prompt` is 240, not 180. A prompt node carries its two
    // prompt boxes in its BODY now, so it stands ~205 tall where it used to be
    // ~128 -- and at the old spacing the sampler was drawn straight through it.
    // Caught by the golden, 2026-09-11.
    // ⚠ These are the only stacked positions that matter: every other recipe
    // puts the prompt node in a SECOND COLUMN, where its height cannot collide
    // with the pixel chain beside it.
    diagonal("prompt", "sample", "decode"),
)

/**
 * ⭐⭐ **Every recipe on a DIAGONAL, so a wire only ever goes forward.**
 *
 * ⚠⚠ Asked for from the phone, 2026-09-13: *"go in a diagonal top to bottom
 * so that wires only go forward"*. Two arrangements were tried first and both
 * were wrong the same way — a single column made a node that feeds two others
 * send a wire the length of the graph across everything between, and moving
 * the feeder into a second column merely turned those wires BACKWARDS, leftward
 * into the column they came from.
 *
 * ⚠⚠⚠ **[STEP_X] is at least [Sizes.NODE_WIDTH], and that is the whole
 * trick.** An output port sits on a node's right edge and an input on the next
 * one's left edge, so a step NARROWER than a node puts the destination's left
 * edge to the left of the source's right edge — the wire runs backwards even
 * though the node is further right. At 210 against a 190-wide node, every wire
 * leaves an edge and arrives at one 20 units further right.
 *
 * ⚠ It also means two nodes can never overlap, whatever their heights: their
 * x-ranges are disjoint. So [STEP_Y] is chosen for readability alone, and a
 * node that grows a picture or a prose box cannot collide with a neighbour —
 * which is what the hand-tuned column spacings kept having to be re-tuned for.
 *
 * ⚠⚠ [ids] must be in TOPOLOGICAL order, feeders first. That is what makes
 * the wires forward; the diagonal only makes it visible. A recipe that lists
 * them wrongly draws a backward wire and says so immediately, which is the
 * point of laying them out this way rather than by hand.
 */
private fun diagonal(vararg ids: String): Map<String, Pt> =
    ids.mapIndexed { i, id ->
        id to Pt(24f + i * STEP_X, TOP + i * STEP_Y)
    }.toMap()

/** ⚠ The canvas top bar FLOATS over the graph and is two rows tall. */
private const val TOP = 120f

/** ⚠ ≥ [Sizes.NODE_WIDTH] (190), or the wires run backwards. See [diagonal]. */
private const val STEP_X = 210f

private const val STEP_Y = 220f

/**
 * A graph a user can start from.
 *
 * ⚠ Built-ins only, for the same reason [defaultWorkflow] is: a recommended
 * workflow that needed a plugin pack would fail to open on any device that has
 * not been handed one, which is every device but the developer's.
 */
data class Recipe(val id: String, val label: String, val about: String, val build: () -> Workflow)

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
        "A photo from the gallery: frame it, then re-imagine it at the strength you choose.",
        ::img2imgWorkflow,
    ),
    Recipe(
        "upscale", "Upscale a photo",
        "A picture from the gallery, enlarged 4x. No checkpoint involved — " +
            "the upscaler is its own small model, installed under Models.",
        ::upscaleWorkflow,
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
    ),
    Recipe(
        "inpaint", "Inpaint — paint an area to redo",
        "Paint over part of a photo and only that part is re-imagined. " +
            "Tap the Mask node to paint.",
        ::inpaintWorkflow,
    ),
)

/**
 * ⭐⭐ Inpainting, from parts that already existed.
 *
 * ⚠⚠ **No 9-channel UNet and no second checkpoint.** This is RePaint-style
 * latent blending (`../LocalDream/docs/INPAINT.md` §1): the photo is encoded
 * once, sampled once, and the two latents are blended under a painted mask. It
 * is the clearest demonstration in the app that decomposing the pipeline buys
 * something a preset picker cannot — every node here already existed for
 * another reason.
 *
 * ```
 *   photo ─ frame ─┬─ encode ──────────────┐
 *                  │                       ├─ blend ─ decode
 *                  ├─ encode ─ sample ─────┘    │
 *                  └─ mask ────────────────────-┘
 *   text ──────────────────────┘
 * ```
 *
 * ⚠ `frame` feeds THREE consumers and they must all want the same size — they
 * do, because `vae_encode` and `latent_blend` both demand the render size and
 * `mask` passes that demand through. That is the whole reason the mask node is
 * `sizedByConsumer`.
 *
 * ⚠ ONE `encode`, not two: `base` and the sampler's starting latent are the
 * same picture, and encoding it twice would cost a second VAE pass for an
 * identical tensor the cache would have to notice anyway.
 *
 * ⚠ `denoise` at 0.85 rather than img2img's 0.6 — inside the mask the point is
 * to make something new, and the untouched surroundings come from `base`
 * regardless. A low denoise here reads as "the mask did nothing".
 */
fun inpaintWorkflow(): Workflow = Workflow(
    Graph(
        listOf(
            Node("photo", "image.load", params = mapOf("uri" to "")),
            Node("prompt", "sd.clip_encode", params = promptParams()),
            Node(
                "frame", "image.crop",
                params = mapOf("x" to "0.0", "y" to "0.0", "w" to "1.0", "h" to "1.0"),
                inputs = sources("image" to "photo"),
            ),
            // ⭐ Tap this node on the canvas to paint. It is interactive, so the
            // tap opens the editor rather than a fullscreen copy of the picture.
            Node(
                "mask", "image.mask",
                params = mapOf("grow" to "0.0", "feather" to "0.02"),
                inputs = sources("image" to "frame"),
            ),
            Node(
                "encode", "sd.vae_encode",
                params = ctxKeyParams() + mapOf("seed" to "42"),
                inputs = sources("image" to "frame"),
            ),
            Node(
                "sample", "sd.sample",
                params = ctxKeyParams() + mapOf("seed" to "0", "denoise" to "0.85"),
                inputs = sources("cond" to "prompt", "latent" to "encode"),
            ),
            // ⚠⚠ `base` is the ORIGINAL and `repaint` is the sampled one. The
            // mask's white area is where `repaint` shows through; the other way
            // round replaces everything except what you painted, which is a
            // plausible picture and a silent mistake.
            Node(
                "blend", "sd.latent_blend",
                params = ctxKeyParams(),
                inputs = sources("base" to "encode", "repaint" to "sample", "mask" to "mask"),
            ),
            Node(
                "decode", "sd.vae_decode",
                params = ctxKeyParams(),
                inputs = sources("latent" to "blend"),
            ),
        )
    ),
    // ⚠ 120 for the top row, for the reason [defaultWorkflow] gives.
    // ⚠ Topological, feeders first: `mask` before `blend`, `prompt` before
    // `sample`. A recipe that lists them wrongly draws a backward wire.
    diagonal("prompt", "photo", "frame", "encode", "mask", "sample", "blend", "decode"),
)

/**
 * Photo -> latent -> re-sample -> picture.
 *
 * ⚠ `denoise` is what makes this useful rather than a noisy copy: at 1.0 the
 * source is entirely renoised, which is txt2img with extra steps.
 */
fun img2imgWorkflow(): Workflow = Workflow(
    Graph(
        listOf(
            // ⚠ No size on `load_image` any more: it hands the photo on whole,
            // and `frame` below is the only node that decides a framing. Two
            // nodes cropping in sequence threw the first decision away before
            // the user ever saw it.
            Node("photo", "image.load", params = mapOf("uri" to "")),
            // ⚠ A branch of its own, not a link in the chain: the text reaches
            // the sampler directly and never touches the photo. Placed in a
            // second column for that reason -- stacked into the middle of the
            // pixel chain it would read as a step the picture passes through.
            Node("prompt", "sd.clip_encode", params = promptParams()),
            Node(
                "frame", "image.crop",
                params = mapOf("x" to "0.0", "y" to "0.0", "w" to "1.0", "h" to "1.0", "out" to "512"),
                inputs = sources("image" to "photo"),
            ),
            Node(
                "encode", "sd.vae_encode",
                params = ctxKeyParams() + mapOf("seed" to "42"),
                inputs = sources("image" to "frame"),
            ),
            Node(
                "sample", "sd.sample",
                params = ctxKeyParams() + mapOf(
                    // ⚠ No `steps`/`cfg`: the model supplies both (see the
                    // txt2img recipe above). `denoise` stays -- it is a
                    // property of THIS recipe, not of the checkpoint.
                    "seed" to "0", "denoise" to "0.6",
                ),
                inputs = sources("cond" to "prompt", "latent" to "encode"),
            ),
            Node(
                "decode", "sd.vae_decode",
                params = ctxKeyParams(),
                inputs = sources("latent" to "sample"),
            ),
        )
    ),
    // ⚠ 120 for the top row, for the reason [defaultWorkflow] gives.
    diagonal("prompt", "photo", "frame", "encode", "sample", "decode"),
)

/**
 * ⭐⭐ Enlarge a picture, and nothing else.
 *
 * ⚠⚠ **No sampler, no checkpoint, no [ContextKey] at all.** An upscaler binds
 * nothing at backend launch — `/upscale` builds its own QNN context from the
 * weight file per request and frees it after — so this graph pins the process
 * to nothing and runs beside any model. That is also why an upscaler never
 * appears as a "resident" model in the load readout: there is nothing resident
 * to report.
 *
 * ⚠ It exists because wiring a photo straight into an upscale node by hand was
 * the obvious thing to try and gave no clue what was missing: the node needs an
 * upscaler INSTALLED (Models → Upscalers) and an `image.output` to land in.
 * Asked for from the phone, 2026-09-11.
 */
fun upscaleWorkflow(): Workflow = Workflow(
    Graph(
        listOf(
            // ⚠ Whole, uncropped. There is no size to match here -- unlike the
            // sampler recipes, an upscaler takes whatever it is given.
            Node("photo", "image.load", params = mapOf("uri" to "")),
            // ⚠ **No `image.output` after it.** The upscale node shows its own
            // result and carries save/share/keep like any node with a picture,
            // so a terminal node would be a third box doing nothing the second
            // one does not. `image.output` earns its place only where a graph
            // needs an explicit save toggle.
            Node(
                "upscale", "image.upscale",
                // ⚠ No `upscaler` param written: the node's own default is the
                // first INSTALLED one, read at call time, and pinning a literal
                // here would name a file a fresh install does not have.
                inputs = sources("image" to "photo"),
            ),
        )
    ),
    // ⚠⚠ 120, not 40. The canvas top bar FLOATS over the graph and is two
    // rows tall, so a node at 40 opens half-hidden behind it — every other
    // recipe starts at 120 and this one did not.
    positions = diagonal("photo", "upscale"),
)

/**
 * ⭐⭐ Prompt → a 2 second clip. `docs/NEODRAGON.md`.
 *
 * ⚠⚠ **No [ctxKeyParams], and that is the point.** Every other recipe pins the
 * graph's one `(type, model, resolution)` key; this one names no checkpoint at
 * all, because the video path loads its own QNN context binaries in-process and
 * binds nothing at backend launch (`docs/ARCHITECTURE.md` §5.2). ⇒ It runs with
 * no backend server up, and the model chip in the top bar is irrelevant to it —
 * which is worth knowing before someone reports that it "ignored" their model.
 *
 * ⚠ Two nodes, not five. The sampler is FUSED for the same reason `sd.sample`
 * is; what earns a node here is the edge — the clip is a value, so saving it is
 * a separate, optional act.
 *
 * ⭐⭐ `save` starts TRUE, unlike every picture recipe — the clip lives in
 * `cacheDir` until it is written out, and Android clears that without asking.
 * See [com.abrah.nightmare.npu.VideoOutputNode].
 *
 * ⭐ The seed is rolled by `HarnessOps.runRolled` like every other sampler's
 * ([com.abrah.nightmare.SAMPLER_TYPES]), so Run gives a new clip and the run
 * bar's lock pins the one you liked.
 */
/**
 * ⭐⭐ **Image to video** — the decomposed path, fed a picture instead of
 * making one.
 *
 * ⚠⚠ The ONLY difference from [textToVideoWorkflow] is where the picture
 * comes from: `image.crop` instead of `nd.first_frame`. That is the same
 * substitution img2img already makes against `sd.vae_encode`, and it is why
 * i2v needs no flag, no optional port and no special node.
 *
 * ⭐ It is also the CHEAPER path, measurably: SSD1B never runs (~4 s and
 * 614 MB), and because nothing wires `frame_cond` the prompt node never loads
 * `clipl` either. Three of the thirteen models are not needed at all.
 *
 * ⚠⚠ The crop is not optional and not decoration. `image.load` promises no
 * size, `nd.vae_encode` needs exactly 512x320, and
 * [com.abrah.nightmare.sizeRefusal] refuses that wire at the drop — the same
 * rule that already stands between a photo and `sd.vae_encode`. The crop's
 * `out_w`/`out_h` are DERIVED and locked, so nobody types a size anywhere.
 */
fun imageToVideoWorkflow(): Workflow = Workflow(
    Graph(
        listOf(
            Node(
                "prompt", "nd.clip_encode",
                // ⚠ A MOTION prompt, not a subject one: the subject is the
                // photo. "a cat walking" against a picture of a harbour is the
                // instruction fighting the image it was given.
                params = mapOf("prompt" to "gentle camera push in, subtle motion"),
            ),
            Node("photo", "image.load"),
            // ⚠ No out_w/out_h: derived from `nd.vae_encode` and drawn locked.
            Node("frame", "image.crop", inputs = sources("image" to "photo")),
            Node("encode", "nd.vae_encode", inputs = sources("image" to "frame")),
            Node(
                "sample", "nd.sample",
                params = mapOf("seed" to "0"),
                // ⚠ `cond` by NAME: the prompt node makes two, and a bare wire
                // would mean its first.
                inputs = sources("cond" to "prompt:cond", "latent" to "encode"),
            ),
            Node(
                "decode", "nd.vae_decode",
                params = mapOf("upscale" to "true"),
                inputs = sources("latent" to "sample"),
            ),
        )
    ),
    // ⚠⚠ **The chain runs down column one; the prompt sits in column two**,
    // which is what [img2imgWorkflow] and [inpaintWorkflow] already do with
    // their own `prompt` and `mask`. A single column made every wire from the
    // prompt travel the length of the graph, across every node in between.
    // ⚠ The prompt is level with the node it feeds, so its wire is one short
    // horizontal hop rather than a diagonal down the whole canvas.
    diagonal("prompt", "photo", "frame", "encode", "sample", "decode"),
)

/**
 * ⭐⭐ **Text to video** — prompt in, a clip out, in the shape of every other
 * recipe here.
 *
 * ⚠⚠ The prompt is a **wire**, not a widget on the sampler, exactly as
 * `sd.sample` has no prompt and takes `sd.clip_encode` → `cond`. The prompt
 * node makes TWO conditionings because the first frame and the MMDiT genuinely
 * need different tensors (`docs/NEODRAGON.md` §8); both are wired here, which
 * is what makes this the more expensive of the two video recipes.
 *
 * ⚠ `nd.vae_decode` is terminal and draws its own clip, so there is no output
 * node — the same reason the upscale recipe has none.
 */
fun textToVideoWorkflow(): Workflow = Workflow(
    Graph(
        listOf(
            Node(
                "prompt", "nd.clip_encode",
                params = mapOf("prompt" to "a cat walking through tall grass, cinematic"),
            ),
            Node(
                "frame", "nd.first_frame",
                params = mapOf("seed" to "0"),
                inputs = sources("cond" to "prompt:frame_cond"),
            ),
            Node("encode", "nd.vae_encode", inputs = sources("image" to "frame")),
            Node(
                "sample", "nd.sample",
                params = mapOf("seed" to "0"),
                inputs = sources("cond" to "prompt:cond", "latent" to "encode"),
            ),
            Node(
                "decode", "nd.vae_decode",
                params = mapOf("upscale" to "true"),
                inputs = sources("latent" to "sample"),
            ),
        )
    ),
    // ⚠ 120 for the top row, for the reason [defaultWorkflow] gives: the canvas
    // top bar floats over the graph and is two rows tall.
    //
    // ⚠⚠ **The prompt is in column two, level with `sample`** — the house
    // layout, the same as [img2imgWorkflow]'s. It is the only node here that
    // feeds TWO others, and putting it at the top of a single column made both
    // of its wires run the full height of the graph across everything between.
    // Sitting between its consumers, each wire is one hop: up-left to `frame`,
    // down-left to `sample`, and neither crosses a node because the left
    // column is empty at that row.
    diagonal("prompt", "frame", "encode", "sample", "decode"),
)
