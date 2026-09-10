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
    val res = SelectedModel.spec.native
    return mapOf(
        "model" to SelectedModel.id,
        "width" to res.width.toString(),
        "height" to res.height.toString(),
    )
}

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
            Node(
                "text", "sd.clip_encode",
                params = mapOf(
                    "prompt" to "a cat on grass",
                    "negative" to "blurry, lowres",
                ),
            ),
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
                inputs = sources("cond" to "text"),
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
    mapOf("text" to Pt(24f, 120f), "sample" to Pt(24f, 300f), "decode" to Pt(24f, 560f)),
)

/**
 * A graph a user can start from.
 *
 * ⚠ Built-ins only, for the same reason [defaultWorkflow] is: a recommended
 * workflow that needed a plugin pack would fail to open on any device that has
 * not been handed one, which is every device but the developer's.
 */
data class Recipe(val id: String, val label: String, val about: String, val build: () -> Workflow)

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
            Node(
                "text", "sd.clip_encode",
                params = mapOf(
                    "prompt" to "masterpiece, best quality, highly detailed,",
                    "negative" to "blurry, lowres",
                ),
            ),
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
                inputs = sources("cond" to "text", "latent" to "encode"),
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
    mapOf(
        "photo" to Pt(24f, 120f), "frame" to Pt(24f, 330f),
        "encode" to Pt(24f, 560f), "sample" to Pt(24f, 790f),
        "blend" to Pt(24f, 1060f), "decode" to Pt(24f, 1330f),
        // Second column: the prompt and the mask, the two things a user
        // actually touches, level with the chain they join.
        "text" to Pt(250f, 120f), "mask" to Pt(250f, 560f),
    ),
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
            Node(
                "text", "sd.clip_encode",
                params = mapOf(
                    "prompt" to "masterpiece, best quality, highly detailed,",
                    "negative" to "blurry, lowres",
                ),
            ),
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
                inputs = sources("cond" to "text", "latent" to "encode"),
            ),
            Node(
                "decode", "sd.vae_decode",
                params = ctxKeyParams(),
                inputs = sources("latent" to "sample"),
            ),
        )
    ),
    // ⚠ 120 for the top row, for the reason [defaultWorkflow] gives.
    mapOf(
        "photo" to Pt(24f, 120f), "frame" to Pt(24f, 330f), "encode" to Pt(24f, 560f),
        "sample" to Pt(24f, 790f), "decode" to Pt(24f, 1130f),
        // Second column, level with the photo: the two branches start side by
        // side and meet at the sampler.
        "text" to Pt(250f, 120f),
    ),
)
