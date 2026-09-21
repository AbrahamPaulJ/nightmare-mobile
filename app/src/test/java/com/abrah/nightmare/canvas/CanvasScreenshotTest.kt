package com.abrah.nightmare.canvas

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abrah.nightmare.Graph
import com.abrah.nightmare.MaskNode
import com.abrah.nightmare.MaskOp
import com.abrah.nightmare.MaskState
import com.abrah.nightmare.MaskStrokeData
import com.abrah.nightmare.NODE_TYPES
import com.abrah.nightmare.Res
import com.abrah.nightmare.SdSampler
import com.abrah.nightmare.V1_MODEL
import com.abrah.nightmare.SizeDemand
import com.abrah.nightmare.Node
import com.abrah.nightmare.sources
import com.abrah.nightmare.Outcome
import com.abrah.nightmare.Port
import com.abrah.nightmare.ui.NightmareTheme
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.graphics.asImageBitmap
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens for the canvas.
 *
 * ⚠ These pin the LOOK; `CanvasGeometryTest` pins the arithmetic. Neither
 * substitutes for the other — a screenshot cannot see that a hit-box is 8 units
 * off, and a geometry test cannot see that two node colours became the same.
 *
 * ⭐ docs/UI.md §3 asks for exactly these states: an empty canvas, a real graph,
 * mid-render, and an error.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class CanvasScreenshotTest {

    private fun shoot(name: String, body: @Composable () -> Unit) {
        captureRoboImage(filePath = "src/test/screenshots/$name.png") {
            NightmareTheme(darkTheme = true) { body() }
        }
    }

    /** The plugin nodes, as the canvas sees them once a pack is loaded. */
    private val pluginTypes = NODE_TYPES + mapOf(
        "com.example.latent-mix:HalfMask" to FakeType(
            "com.example.latent-mix:HalfMask", "mask",
            emptyList(), listOf(Port("image", "IMAGE")),
        ),
        "com.example.latent-mix:LatentMix" to FakeType(
            "com.example.latent-mix:LatentMix", "latent",
            listOf(Port("a", "LATENT"), Port("b", "LATENT"), Port("mask", "IMAGE")),
            listOf(Port("latent", "LATENT")),
        ),
    )

    /**
     * ⚠⚠ The LEGACY sampler, and it has to be: this fixture is the
     * plugin-LATENT story (`LatentMix` takes two latents), and the fused sampler
     * emits an IMAGE. Pointed at the new type the wires below would be drawn
     * between ports that cannot connect — a golden of a graph nobody can build.
     * ⇒ It goes when the legacy types do, and this fixture becomes an
     * IMAGE-level plugin, which is where every real Tier 0 pack lives anyway
     * (docs/ARCHITECTURE.md §5.7).
     */
    private fun sampler(id: String, seed: Int) = Node(
        id, "sd.sample_legacy",
        params = mapOf(
            "model" to "dreamshaper",
            "steps" to "8", "cfg" to "7.5", "seed" to seed.toString(),
            "width" to "512", "height" to "512",
        ),
        inputs = sources("cond" to "text"),
    )

    /**
     * The graph the device actually ran: a prompt, two samplers, a plugin mask,
     * a plugin blend, a decode.
     *
     * ⚠ The text node is not decoration. The sampler has no prompt of its own
     * (docs/ARCHITECTURE.md §3), so this is what a graph with two renders in it
     * now LOOKS like -- one conditioning fanning out to both -- and a golden of
     * the old shape would be pinning a canvas nobody can draw any more.
     */
    private val realGraph = Workflow(
        Graph(
            listOf(
                Node("text", "sd.clip_encode",
                    params = mapOf("prompt" to "a cat on grass", "negative" to "blurry")),
                sampler("a", 42),
                sampler("b", 7),
                Node("mask", "com.example.latent-mix:HalfMask"),
                Node("mix", "com.example.latent-mix:LatentMix",
                    inputs = sources("a" to "a", "b" to "b", "mask" to "mask")),
                Node("decode", "sd.vae_decode",
                    params = mapOf("model" to "dreamshaper", "width" to "512", "height" to "512"),
                    inputs = sources("latent" to "mix")),
            )
        ),
        // ⚠ The text node gets its own column on the LEFT, and everything else
        // moved right to make room. Placed beside the samplers it fed, its two
        // wires ran backwards across the whole graph -- which is the phone-shaped
        // layout problem in `docs/UI.md` §5, and not something to pin a golden of.
        mapOf(
            "text" to Pt(40f, 180f),
            "a" to Pt(250f, 60f),
            "b" to Pt(250f, 300f),
            "mask" to Pt(250f, 540f),
            "mix" to Pt(490f, 260f),
            "decode" to Pt(720f, 300f),
        ),
    )

    @Test
    fun emptyCanvas() = shoot("canvas-empty") {
        GraphCanvas(
            workflow = Workflow(Graph(emptyList()), emptyMap()),
            types = NODE_TYPES,
            viewport = Viewport(Pt(0f, 0f), 1f),
            modifier = Modifier.fillMaxSize(),
        )
    }

    /** ⚠ Zoomed out, because that is how a six-node graph fits on a phone at all. */
    @Test
    fun aRealGraph() = shoot("canvas-graph") {
        GraphCanvas(
            workflow = realGraph,
            types = pluginTypes,
            viewport = Viewport(Pt(10f, 10f), 0.55f),
            modifier = Modifier.fillMaxSize(),
        )
    }

    /**
     * ⭐⭐ **The whole graph at once, and every node still says what it is.**
     *
     * ⚠⚠ 0.28x is below the floor at which labels used to be DROPPED, and
     * this golden exists because that behaviour shipped and was reported from
     * the phone: the one view that shows a six-node workflow in a single screen
     * was the one view that could not tell you which node was which. The type
     * stops shrinking with the node here, so what this pins is that the labels
     * are present AND that they stay inside their boxes -- an ellipsised id, a
     * subtitle dropped when the header has no room, and no port name written
     * over its neighbour.
     */
    @Test
    fun aGraphZoomedRightOut() = shoot("canvas-far") {
        GraphCanvas(
            workflow = realGraph,
            types = pluginTypes,
            viewport = Viewport(Pt(20f, 40f), 0.28f),
            modifier = Modifier.fillMaxSize(),
        )
    }

    /**
     * ⭐ The state that decides whether this feels modern (docs/UI.md §1): the
     * sampler mid-render with per-node progress, the nodes that were skipped
     * marked cached, and everything downstream still waiting.
     */
    @Test
    fun midRender() = shoot("canvas-running") {
        GraphCanvas(
            workflow = realGraph,
            types = pluginTypes,
            viewport = Viewport(Pt(10f, 10f), 0.55f),
            selected = setOf("b"),
            status = mapOf(
                "a" to NodeStatus(Outcome.CACHED),
                "b" to NodeStatus(progress = 5 to 8),
                "mask" to NodeStatus(Outcome.CACHED),
            ),
            modifier = Modifier.fillMaxSize(),
        )
    }

    /**
     * ⭐⭐ A multi-selection, started by a long press. Every chosen node is drawn
     * RAISED — shadow, halo, lighter body — and that, not the 3px stroke, is
     * what makes the mode legible at 0.55x zoom. The run bar has become a
     * contextual one, but the bar is `CanvasScreen`'s, so what this pins is that
     * the CANVAS can show more than one node selected at a time.
     */
    @Test
    fun severalNodesSelectedAtOnce() = shoot("canvas-multi-select") {
        GraphCanvas(
            workflow = realGraph,
            types = pluginTypes,
            viewport = Viewport(Pt(10f, 10f), 0.55f),
            selected = setOf("a", "b", "mask"),
            modifier = Modifier.fillMaxSize(),
        )
    }

    /**
     * ⭐⭐ A wire picked for deletion: it is drawn hot, with a bin at its middle.
     *
     * ⚠ The mark is on the CURVE's midpoint, not on the straight line between
     * the ports — `wirePath` is shared with the hit-testing precisely so the
     * thing you tap is the thing you see.
     */
    @Test
    fun aWirePickedForDeletion() = shoot("canvas-wire-picked") {
        GraphCanvas(
            workflow = realGraph,
            types = pluginTypes,
            viewport = Viewport(Pt(10f, 10f), 0.55f),
            wire = WireRef("mix", "a", com.abrah.nightmare.Source("a")),
            modifier = Modifier.fillMaxSize(),
        )
    }

    /**
     * ⭐⭐ …and the confirm: a tick in the bin's EXACT place, with a cancel
     * beside it. That is what makes "double tap the middle of a wire" delete it
     * while a single tap never can.
     */
    @Test
    fun aWireAskingToBeConfirmed() = shoot("canvas-wire-confirm") {
        GraphCanvas(
            workflow = realGraph,
            types = pluginTypes,
            viewport = Viewport(Pt(10f, 10f), 0.55f),
            wire = WireRef("mix", "a", com.abrah.nightmare.Source("a")),
            wireConfirming = true,
            modifier = Modifier.fillMaxSize(),
        )
    }

    /**
     * ⭐ A wire in flight that WILL be allowed to land — the other branch of the
     * state the refused golden covers. Without both, a change that made every
     * pending wire red would pass the suite.
     */
    @Test
    fun aWireBeingDragged() = shoot("canvas-wiring") {
        GraphCanvas(
            workflow = realGraph,
            types = pluginTypes,
            viewport = Viewport(Pt(10f, 10f), 0.55f),
            selected = setOf("mask"),
            pending = PendingWire(
                from = PortRef("mask", Port("image", "IMAGE"), isInput = false, at = Pt(440f, 614f)),
                to = Pt(490f, 470f),
            ),
            modifier = Modifier.fillMaxSize(),
        )
    }

    /**
     * ⭐⭐⭐ **A recipe opened the way the APP opens one** — [CanvasState.withView]
     * with no saved view, which is what `HarnessViewModel` calls.
     *
     * ⚠⚠ Every screen golden below used to hardcode `Viewport(Pt(0, 0), 0.85f)`,
     * and that is precisely the blind spot `docs/UI.md` §5 names: a test that
     * does not drive the production wiring cannot test it. The fit was broken
     * for weeks — `REFERENCE_WIDTH` was 1100 against a 411dp screen, so a flow
     * opened with its last two nodes off the right edge — and these goldens
     * showed a tidy canvas throughout, because they never asked for the fit.
     */
    private fun opened(w: Workflow = defaultWorkflow()) = CanvasState(w).withView(null)

    /**
     * ⭐ The whole screen, mid-render: the run bar, the output thumbnail, and
     * the per-node state the executor reports. This is the state docs/UI.md §1
     * says decides whether the app feels modern, so it is the one most worth
     * pinning.
     */
    @Test
    fun theScreenMidRender() = shoot("screen-running") {
        CanvasScreen(
            state = opened(),
            types = NODE_TYPES,
            status = mapOf("sample" to NodeStatus(progress = 7 to 20)),
            busy = true,
            image = null,
            // ⭐⭐ The run log, which is the whole point of this state: what is
            // executing, its step, the elapsed time and a bar wide enough to
            // notice. ⚠ `startedAtMs = 0` deliberately -- a live clock would
            // make the golden change every time it was recorded, so this pins
            // the FINISHED shape with a total. The running shape differs only
            // in which number the right-hand column shows.
            runLog = RunLogState(
                lines = listOf(
                    RunLine("text", "ran 76ms"),
                    RunLine("sample", "ran 24.3s"),
                ),
                now = "decode",
                totalMs = 26_532,
            ),
            onGesture = {}, onRun = {}, onBack = {},
        )
    }

    /**
     * ⭐⭐ Both locks on, so the two padlocks are legible as locked without
     * having to try a pinch to find out.
     *
     * ⚠⚠ The glyphs are DRAWN (`LockButton`), because `material-icons-core`
     * has exactly one `Lock` and no `LockOpen` -- the open one lives in
     * `material-icons-extended`, which cost ~55 MB of dex when it was last on
     * the classpath. Two locks side by side would have been the same glyph
     * twice, with the state carried entirely by tint. This golden is the only
     * check that the shackle really opens and that the two marks differ.
     */
    @Test
    fun theScreenWithBothLocks() = shoot("screen-locked") {
        CanvasScreen(
            state = opened().copy(zoomLocked = true, panLocked = true),
            types = NODE_TYPES,
            status = emptyMap(),
            busy = false,
            image = null,
            onGesture = {}, onRun = {}, onBack = {},
        )
    }

    /** ⚠ And the refusal, which stays on screen rather than flashing past. */
    @Test
    fun theScreenShowingARefusal() = shoot("screen-refused") {
        CanvasScreen(
            state = opened().copy(
                // ⚠ No selection: `selection.isNotEmpty()` implies multi-select
                // now, and this golden is about the refusal strip over an
                // ORDINARY canvas -- the run bar, not the contextual one.
                message = "that would make a loop",
            ),
            types = NODE_TYPES,
            status = emptyMap(),
            busy = false,
            image = null,
            onGesture = {}, onRun = {}, onBack = {},
        )
    }

    /**
     * ⭐ THE FIRST SCREEN, as a new user meets it: the canvas, with the model in
     * use named in the top bar and the backend not yet running.
     *
     * ⚠ The harness used to be the launch screen, so this state had never been
     * drawn at all. ⚠⚠ It also cannot prove the inset fix: Robolectric reports
     * zero window insets, so `navigationBarsPadding()` is a no-op here and the
     * run bar sits at the very bottom in this image while on a real phone it was
     * UNDER the navigation bar. That was reported from a device, and only a
     * device can confirm it. This golden pins the layout, not the insets.
     */
    @Test
    fun theFirstScreen() = shoot("screen-first-run") {
        CanvasScreen(
            state = opened(),
            types = NODE_TYPES,
            status = emptyMap(),
            busy = false,
            image = null,
            onGesture = {}, onRun = {}, onBack = {},
            modelLabel = "AbsoluteReality",
            backendUp = false,
        )
    }

    /**
     * ⭐⭐⭐ **The TWO-FEEDER shape** — the one the user arranged by hand on the
     * phone and asked every recipe to copy, 2026-09-15.
     *
     * ⚠⚠ It is the shape with something to get wrong: `prompt` and `photo`
     * both feed the sampler, and the sampler's `prompt` port sits ABOVE its
     * `image` port. Stacked in one column in that order the wires cannot cross;
     * in two columns — which is what the diagonal did — they always do. That is
     * a thing only a picture shows, so this is where it is pinned.
     */
    @Test
    fun aFlowWithAPhotoAndAPrompt() = shoot("screen-img2img") {
        CanvasScreen(
            state = opened(img2imgWorkflow()),
            types = NODE_TYPES,
            status = emptyMap(),
            busy = false,
            image = null,
            onGesture = {}, onRun = {}, onBack = {},
            modelLabel = "AbsoluteReality",
            backendUp = true,
            flowName = "Image to image",
        )
    }

    /**
     * ⭐⭐ The top bar's SECOND and third rows — the flow and the load line.
     *
     * ⚠⚠ No golden drew them until the design review, 2026-09-15: every other
     * screen golden passes `flowName` and `loadLine` as null, which hides both.
     * `docs/UI.md` §6 rule 3 asked for this after a literal `$it` reached the
     * phone through exactly that gap. ⚠ A video flow, with an SDXL-length
     * label, because that is the case the readout got wrong twice.
     */
    @Test
    fun theTopBarNamesTheFlowAndTheLoad() = shoot("screen-top-bar") {
        CanvasScreen(
            state = opened(textToVideoWorkflow()),
            types = NODE_TYPES,
            status = emptyMap(),
            busy = false,
            image = null,
            onGesture = {}, onRun = {}, onBack = {},
            backendUp = true,
            flowName = "Text to video",
            flowDirty = true,
            loadLine = "holding Neodragon (video) · 6.1/11.4 GB free",
        )
    }

    /** ⚠ A run that failed says so ON the canvas — it used to reach only the log. */
    @Test
    fun theScreenShowingWhyARunFailed() = shoot("screen-run-error") {
        CanvasScreen(
            state = opened(),
            types = NODE_TYPES,
            status = emptyMap(),
            busy = false,
            image = null,
            onGesture = {}, onRun = {}, onBack = {},
            runError = "no model installed — open Models and download one",
            modelLabel = "AbsoluteReality (not installed)",
            backendUp = false,
        )
    }

    /** A failed node, and a wire being dragged that will not be allowed to land. */
    // ------------------------------------------------------------- inspector

    /**
     * ⭐ The picker, which is the one control that cannot be typed.
     *
     * ⚠ This golden is what stands in for a device check of the picker. Tapping
     * through the system document chooser over adb means driving another app's
     * UI on somebody's phone, and it would still not prove the part that
     * matters (`takePersistableUriPermission`) — which only shows up as a
     * failure days later. What a screenshot CAN prove is that the row is there
     * and laid out, so that is what it proves, and no more.
     */
    @Test
    fun theLoaderOffersAPicker() = shoot("inspector-load-image") {
        Surface(Modifier.fillMaxSize()) {
            NodeInspectorBody(
                nodeId = "src",
                node = Node(
                    "src", "core.image",
                    params = mapOf("width" to "512", "height" to "512"),
                ),
                type = NODE_TYPES["core.image"],
                onSetParam = { _, _, _ -> },
                onDelete = {},
            )
        }
    }

    /**
     * …and once something is chosen, it says what -- next to the two icons that
     * are the whole point of the row: swap this picture, or drop it.
     *
     * ⚠ The word "choose another" used to be a full-width button here. Asked
     * for as icons from the phone, 2026-09-09, and the golden is what proves the
     * uri still has room to wrap beside them.
     */
    @Test
    fun theLoaderShowsWhatWasChosen() = shoot("inspector-load-image-chosen") {
        Surface(Modifier.fillMaxSize()) {
            NodeInspectorBody(
                nodeId = "src",
                node = Node(
                    "src", "core.image",
                    params = mapOf(
                        "uri" to "content://media/external/images/media/258620",
                        "width" to "512", "height" to "512",
                    ),
                ),
                type = NODE_TYPES["core.image"],
                onSetParam = { _, _, _ -> },
                onDelete = {},
            )
        }
    }

    /**
     ⭐ The seed that made the picture, on the node that DECODED it.
     *
     * ⚠⚠ The number comes from the sampler upstream, not from this node -- a
     * `vae_decode` has no seed of its own, and asking it for one is what a user
     * looking at a render they like actually means. [seedFor].
     */
    @Test
    fun theDecoderShowsTheSeedThatMadeIt() = shoot("inspector-seed") {
        Surface(Modifier.fillMaxSize()) {
            NodeInspectorBody(
                nodeId = "decode",
                node = Node("decode", "sd.vae_decode"),
                type = NODE_TYPES["sd.vae_decode"],
                onSetParam = { _, _, _ -> },
                onDelete = {},
                preview = stripes(96, 96),
                seed = "1284471903",
            )
        }
    }

    /**
     * ⭐⭐ The sampler's own knobs: sliders for the bounded numbers, a dropdown
     * for the nine schedulers, a text field only for the seed.
     *
     * ⚠⚠ This golden exists because all three controls were text fields until
     * 2026-09-10, and the two that changed are the ones that decide what the
     * picture looks like. `steps` and `cfg` declare a range and were typed into
     * — a number pad over the canvas with no sense of where 7.5 sits between 1
     * and 20. `scheduler` has NINE values, which as chips became a scrolling
     * strip that can hide the current selection.
     *
     * ⚠ `seed` must stay a TEXT field in this shot: it is an identifier rather
     * than a magnitude, declares no range on purpose, and a slider over
     * 0..2^31 would be useless. `model`/`width`/`height` stay locked.
     */
    @Test
    fun theSamplerDrawsSlidersAndADropdown() = shoot("inspector-sampler") {
        Surface(Modifier.fillMaxSize()) {
            NodeInspectorBody(
                nodeId = "sample",
                node = Node(
                    "sample", "sd15.sample",
                    params = mapOf(
                        "steps" to "10", "cfg" to "1.5", "scheduler" to "euler_a",
                        "model" to "absolutereality",
                    ),
                ),
                type = NODE_TYPES["sd15.sample"],
                onSetParam = { _, _, _ -> },
                onDelete = {},
                // ⭐⭐⭐ **The checkpoint picker**, which had no golden at all
                // while it was the most-reported control in the sheet — a chip
                // strip of fifteen models, then a dropdown, then a family
                // filter, all shipped without a picture of any of them.
                //
                // ⚠ Two families, because the chips only exist with two: one
                // installed family filters nothing and draws none.
                installedModels = listOf(
                    CheckpointChoice("absolutereality", "AbsoluteReality", com.abrah.nightmare.Family.SD15),
                    CheckpointChoice("qteamix", "QteaMix", com.abrah.nightmare.Family.SD15),
                    CheckpointChoice("intorealism", "intorealism", com.abrah.nightmare.Family.SDXL),
                ),
            )
        }
    }

    /**
     * ⭐⭐⭐ **A `bool` knob is a CHECKBOX**, and this golden is the only
     * thing that sees it.
     *
     * ⚠⚠ Every `bool` in the app rendered as a TEXT FIELD until
     * 2026-09-12 — the inspector's widget loop had no branch for the type, so
     * `upscale` and `save` fell through to the box at the bottom of it and
     * asked people to type the word `true` on a phone keyboard. `True` and a
     * typo both read as false, and nothing said so until a Run that quietly
     * wrote nothing. Reported from the phone in exactly those terms.
     *
     * ⚠ `nd.vae_decode`, the end of the video chain, because its `upscale` is
     * the `bool` every clip passes through. ⚠ Unset on purpose — the box must
     * draw CHECKED from the widget's own default, or it lies about what the
     * node will run with.
     * ⚠⚠ This golden and the one below pinned `nd.video_sample` and
     * `video.output` until the design review, 2026-09-15 — both DELETED types,
     * so both images showed "unknown node type" and checked nothing.
     */
    @Test
    fun aBoolKnobIsACheckbox() = shoot("inspector-video-decode") {
        Surface(Modifier.fillMaxSize()) {
            NodeInspectorBody(
                nodeId = "decode",
                node = Node("decode", "nd.vae_decode"),
                type = NODE_TYPES["nd.vae_decode"],
                onSetParam = { _, _, _ -> },
                onDelete = {},
            )
        }
    }

    /**
     * ⭐ The video sampler: its seed hint must read like `sd.sample`'s, and its
     * knobs carry readable labels — both settled by the design review.
     */
    @Test
    fun theVideoSamplerReadsLikeTheSdOne() = shoot("inspector-video-sample") {
        Surface(Modifier.fillMaxSize()) {
            NodeInspectorBody(
                nodeId = "sample",
                node = Node("sample", "nd.sample", params = mapOf("seed" to "0")),
                type = NODE_TYPES["nd.sample"],
                onSetParam = { _, _, _ -> },
                onDelete = {},
            )
        }
    }

    /**
     * ⭐⭐ The render size, on the node — the control this whole feature is.
     *
     * ⚠⚠ **A golden is the only thing that sees this.** It lives in a
     * `ModalBottomSheet`, so it is invisible to a screenshot of the canvas
     * behind it, and it is driven headlessly by `res_use` — which exercises
     * every part of the feature EXCEPT the composable. That is the exact shape
     * `docs/UI.md` §5 collects: fully verified on device, never once drawn.
     *
     * ⚠ Seven resolutions is past `CHIP_LIMIT`, so this pins the DROPDOWN
     * form. Chips would become a scrolling strip that can hide the current
     * value, which is a control that conceals its own state.
     *
     * ⚠ `width`/`height` must NOT also appear as two number fields below it.
     */
    @Test
    fun theSamplerOffersTheRenderSize() = shoot("inspector-resolution") {
        Surface(Modifier.fillMaxSize()) {
            NodeInspectorBody(
                nodeId = "sample",
                node = Node(
                    "sample", "sd15.sample",
                    params = mapOf(
                        "steps" to "20", "cfg" to "7.5", "scheduler" to "dpm",
                        "model" to V1_MODEL, "width" to "768", "height" to "512",
                    ),
                ),
                type = NODE_TYPES["sd15.sample"],
                onSetParam = { _, _, _ -> },
                // ⚠ Passed in, never read from SelectedModel: on the JVM there is
                // no model directory to scan, so the real cache holds one entry
                // and this golden would pin an empty control.
                resolutions = listOf(
                    Res(512, 512), Res(512, 768), Res(768, 512), Res(768, 768),
                    Res(768, 1024), Res(1024, 768), Res(1024, 1024),
                ),
                onDelete = {},
            )
        }
    }

    /**
     * ⭐⭐⭐ **The FLUX.2 node's sheet, which had none of this.**
     *
     * Reported 2026-09-19: the Flux node "doesn't maintain an ounce of
     * consistency" with the SD ones. It had no checkpoint picker and no size
     * control, because both are gated on things a DiT node is not — the picker
     * on `IMAGE_SAMPLER_TYPES`, which the DiT registration never joined, and the
     * size control on two [Widget.contextKey] size widgets, which a DiT node
     * deliberately does not have. Its width and height turned up instead as two
     * raw sliders at the bottom of the knob list.
     *
     * ⚠⚠ This golden pins all three halves of the fix at once: the checkpoint
     * field is present, the size is drawn at the TOP in the slot every other
     * family's size control uses, and `width`/`height` do NOT also appear
     * below — the same duplicate the SD nodes were reported for on 2026-09-18.
     *
     * ⭐⭐ **The size is two free SLIDERS since 2026-09-21** — upstream's
     * control, on upstream's 64-px grid. It was a Shape dropdown over a table
     * of hand-picked pairs, and the table could not reach 1280x960: see
     * [ModelCatalog.ditFit] and `docs/MODELS.md`. ⚠ What the golden is still
     * for is unchanged — the size control lives in the SAME slot as every
     * other family's, whatever widget it is made of.
     */
    /**
     * ⭐⭐ The LoRA picker, with all three states it has: one applied at a
     * strength, one installed and off, and one a workflow NAMES that this phone
     * does not have.
     *
     * ⚠⚠ The third row is the one worth a golden. A missing LoRA is easy to
     * drop silently, and a graph that then refuses at Run would name a file the
     * picker swore was not in it — so it is drawn, in the error colour, with
     * what to do about it.
     *
     * ⚠ [LoraPickerContent] rather than [LoraPicker]: an `AlertDialog` is a
     * window of its own and Roborazzi cannot reach inside one. Same split, for
     * the same reason, as `NodePaletteContent`.
     */
    @Test
    fun theLoraPickerShowsWhatIsThereAndWhatIsNot() = shoot("lora-picker") {
        Surface {
            LoraPickerContent(
                installed = listOf(
                    "anime-lines.safetensors" to 88L * 1024 * 1024,
                    "film-grain.safetensors" to 42L * 1024 * 1024,
                ),
                spec = "anime-lines.safetensors@0.8, gone.safetensors@1.2",
                onSet = {},
            )
        }
    }

    /** ⚠ A fresh phone. The empty state has to say where LoRAs come from. */
    @Test
    fun theLoraPickerSaysWhereToGetThemWhenThereAreNone() = shoot("lora-picker-empty") {
        Surface { LoraPickerContent(installed = emptyList(), spec = "", onSet = {}) }
    }

    @Test
    fun theFluxNodeReadsLikeAnSdOne() = shoot("inspector-dit-size") {
        Surface(Modifier.fillMaxSize()) {
            NodeInspectorBody(
                nodeId = "generate",
                node = Node(
                    "generate", "flux2.sample",
                    params = mapOf(
                        "steps" to "4", "cfg" to "1.0", "seed" to "0",
                        "model" to "flux2_klein_4b", "width" to "1792", "height" to "1024",
                    ),
                ),
                type = NODE_TYPES["flux2.sample"],
                onSetParam = { _, _, _ -> },
                onDelete = {},
            )
        }
    }

    /**
     * ⚠ A model that serves ONE size draws no size control at all — a lone
     * option that cannot be unselected is furniture, and it would sit on every
     * backend node of every graph.
     */
    @Test
    fun oneSizeMeansNoSizeControl() = shoot("inspector-resolution-single") {
        Surface(Modifier.fillMaxSize()) {
            NodeInspectorBody(
                nodeId = "sample",
                node = Node(
                    "sample", "sd15.sample",
                    params = mapOf(
                        "steps" to "20", "cfg" to "7.5", "scheduler" to "dpm",
                        "model" to V1_MODEL, "width" to "512", "height" to "512",
                    ),
                ),
                type = NODE_TYPES["sd15.sample"],
                onSetParam = { _, _, _ -> },
                resolutions = listOf(Res(512, 512)),
                onDelete = {},
            )
        }
    }

    /**
     * ⭐⭐ The mask editor, with paint on it.
     *
     * ⚠ The photo shows through a translucent RED overlay rather than white:
     * the mask is judged against what is under it, and an opaque overlay hides
     * exactly the thing you are aiming at. ⚠⚠ What leaves the node is
     * black/white — this colour is a display convention only.
     */
    @Test
    fun theMaskEditorShowsPaintOverThePhoto() = shoot("inspector-mask") {
        Surface(Modifier.fillMaxSize()) {
            NodeInspectorBody(
                nodeId = "mask",
                node = Node(
                    "mask", "image.mask",
                    params = mapOf(
                        "out_w" to "512", "out_h" to "512",
                        "grow" to "0.0", "feather" to "0.02",
                        MaskNode.OPS to MaskState(
                            listOf(
                                MaskOp.Stroke(
                                    MaskStrokeData(
                                        listOf(0.3f to 0.35f, 0.5f to 0.4f, 0.65f to 0.6f),
                                        0.09f,
                                    )
                                ),
                            )
                        ).encode(),
                    ),
                ),
                type = NODE_TYPES["image.mask"],
                onSetParam = { _, _, _ -> },
                onDelete = {},
                maskSource = stripes(256, 256),
            )
        }
    }

    /**
     * ⭐ The blend node, which is inpainting without a 9-channel UNet.
     *
     * ⚠ Three inputs and the mask is an IMAGE — the shot is here to show that
     * the port list reads clearly, because "which latent is which" is the thing
     * a user gets wrong and white-takes-B is not guessable from the node.
     */
    @Test
    fun theBlendNodeShowsItsThreePorts() = shoot("inspector-latent-blend") {
        Surface(Modifier.fillMaxSize()) {
            NodeInspectorBody(
                nodeId = "blend",
                node = Node("blend", "sd.latent_blend"),
                type = NODE_TYPES["sd.latent_blend"],
                onSetParam = { _, _, _ -> },
                onDelete = {},
            )
        }
    }

    /**
     * ⚠ A type the canvas does not know must SAY so. An unloaded plugin and a
     * node with no knobs render identically otherwise, and one is a bug.
     */
    @Test
    fun anUnknownTypeSaysSo() = shoot("inspector-unknown") {
        Surface(Modifier.fillMaxSize()) {
            NodeInspectorBody(
                nodeId = "mystery",
                node = Node("mystery", "com.example.gone:Thing"),
                type = null,
                onSetParam = { _, _, _ -> },
                onDelete = {},
            )
        }
    }

    @Test
    fun failureAndARefusedWire() = shoot("canvas-refused") {
        GraphCanvas(
            workflow = realGraph,
            types = pluginTypes,
            viewport = Viewport(Pt(10f, 10f), 0.55f),
            status = mapOf(
                "a" to NodeStatus(Outcome.RAN),
                "mix" to NodeStatus(Outcome.FAILED, detail = "input \"mask\" wants IMAGE"),
            ),
            pending = PendingWire(
                from = PortRef("a", Port("latent", "LATENT"), isInput = false, at = Pt(230f, 128f)),
                to = Pt(330f, 420f),
                error = "LATENT does not fit a IMAGE port",
            ),
            modifier = Modifier.fillMaxSize(),
        )
    }

    /**
     * ⭐⭐ A plugin's own line number, on the node that failed.
     *
     * ⚠ This is the state a Tier 0 contributor is actually in: no debugger, no
     * console, and a node that either worked or did not. The golden exists to
     * make sure the number survives all the way to the pixels — `failureNote`
     * being right in a unit test proves nothing about whether the canvas draws
     * it, or draws it somewhere legible.
     */
    @Test
    fun aPluginFailureNamesItsLine() = shoot("canvas-plugin-error") {
        GraphCanvas(
            workflow = realGraph,
            types = pluginTypes,
            viewport = Viewport(Pt(10f, 10f), 0.55f),
            status = mapOf(
                "a" to NodeStatus(Outcome.RAN),
                "mix" to NodeStatus(
                    Outcome.FAILED,
                    detail = "TypeError: cannot read property 'width' of undefined\n" +
                        "    at LatentMix (index.js:27)\n" +
                        "    at <eval> (host.js:3)",
                ),
            ),
            modifier = Modifier.fillMaxSize(),
        )
    }

    // ------------------------------------------------------------- cropper

    /**
     * ⭐ The drag-to-frame view, which is the whole point of the crop node.
     *
     * ⚠ A synthetic source: the golden is about the OVERLAY -- the dimmed
     * surround, the thirds, the corner handle -- and a real photo would make
     * every re-record depend on a fixture image.
     */
    private fun stripes(w: Int, h: Int): androidx.compose.ui.graphics.ImageBitmap {
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint()
        for (i in 0 until 8) {
            p.color = if (i % 2 == 0) 0xFF3A6EA5.toInt() else 0xFF7FB2E5.toInt()
            c.drawRect(0f, h * i / 8f, w.toFloat(), h * (i + 1) / 8f, p)
        }
        return bmp.asImageBitmap()
    }

    @Test
    fun theCropFrame() = shoot("crop-editor") {
        Surface(Modifier.fillMaxSize()) {
            CropEditor(
                source = stripes(640, 480),
                rect = CropRect(0.18f, 0.15f, 0.55f, 0.6f),
                onChange = {},
            )
        }
    }

    /**
     * ⭐⭐ A picture too small for what is being asked of it, padded rather
     * than enlarged.
     *
     * ⚠ A 300x220 photo cannot fill a 512² output at 1:1, so the frame reaches
     * past it and the remainder is output — black here. This is the one state in
     * which bars are correct, and pinning it is what stops a later "fix" from
     * quietly restoring the upscale that makes a small photo soft instead.
     */
    @Test
    fun aPictureTooSmallIsPaddedNotEnlarged() = shoot("crop-padded") {
        Surface(Modifier.fillMaxSize()) {
            CropEditor(
                source = stripes(300, 220),
                rect = CropRect(-0.35f, -0.5f, 1.7f, 2.3f),
                onChange = {},
                outW = 512,
                aspect = 1f,
            )
        }
    }

    /**
     * ⭐ …and the same frame with the picture's own edges reflected into the
     * bars instead, and BLURRED.
     *
     * ⚠⚠ A reflected TILING of a downscaled copy, which is exactly what
     * `CropNode` does with a `MIRROR` shader over [blurSource]. The editor and
     * the node have to agree or this preview is a lie about the thing it is
     * previewing — that is the whole reason to pin it, and the blur made the
     * agreement harder to reach, not easier: the only blur BOTH can perform is
     * the one that comes free with drawing a small bitmap large.
     */
    @Test
    fun paddingCanBlurTheEdgesInstead() = shoot("crop-blurred") {
        Surface(Modifier.fillMaxSize()) {
            CropEditor(
                source = stripes(300, 220),
                rect = CropRect(-0.35f, -0.5f, 1.7f, 2.3f),
                onChange = {},
                outW = 512,
                aspect = 1f,
                padBlur = true,
            )
        }
    }

    /**
     * ⭐⭐ **The framing view has to fit the WINDOW**, and this is the case that
     * proves it: a landscape phone is ~360dp tall, and a frame sized from the
     * width alone is taller than the whole screen.
     *
     * ⚠⚠ It cannot be fixed by layout. The editor lives inside the inspector's
     * `verticalScroll`, which offers an infinite height constraint by
     * definition — there is nothing to fit against, so the bound comes from the
     * window. Reported from the phone, 2026-09-09: "the crop box should fit the
     * screen, it's even worse for landscape".
     */
    @Test
    @Config(qualifiers = "w891dp-h411dp-xxhdpi")
    fun theFrameFitsALandscapeWindow() = shoot("crop-landscape") {
        Surface(Modifier.fillMaxSize()) {
            CropEditor(
                source = stripes(640, 480),
                rect = CropRect(0.1f, 0.1f, 0.7f, 0.7f),
                onChange = {},
                outW = 512,
                aspect = 1f,
            )
        }
    }

    /**
     * ⚠ …and a TALL frame in portrait, which the old sizing also broke: a 3:4
     * output is 1.33x as tall as it is wide, so deriving the height from the
     * full width ran it off the bottom.
     */
    @Test
    fun aTallFrameFitsAPortraitWindow() = shoot("crop-tall") {
        Surface(Modifier.fillMaxSize()) {
            CropEditor(
                source = stripes(640, 480),
                rect = CropRect(0.1f, 0.05f, 0.5f, 0.9f),
                onChange = {},
                outW = 384,
                aspect = 384f / 512f,
            )
        }
    }

}

/**
 * ⭐ The Add node sheet, each tab — a fixed height and one full-width card a row
 * (2026-09-17). Its own class so the palette can be shot without the canvas.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class PaletteScreenshotTest {
    private fun shootTab(tab: Int, name: String) =
        captureRoboImage(filePath = "src/test/screenshots/$name.png") {
            NightmareTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize()) {
                    NodePaletteContent(NODE_TYPES, onPick = {}, height = 660.dp, initialTab = tab)
                }
            }
        }

    @Test fun common() = shootTab(0, "palette-common")
    @Test fun generate() = shootTab(1, "palette-generate")
    @Test fun inpaint() = shootTab(2, "palette-inpaint")
}

/**
 * ⭐ An inpaint node's sheet: Crop and Mask as two previews (2026-09-17), and
 * the segment model node whose model is a locked fact.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class InpaintInspectorScreenshotTest {
    @Test fun cropAndMaskArePreviews() = shoot("inspector-inpaint", null)
    /** ⭐ The popup's two tabs — the picture must sit in the same place in both. */
    @Test fun popupCropTab() = shoot("inpaint-popup-crop", 0)
    @Test fun popupMaskTab() = shoot("inpaint-popup-mask", 1)

    private fun shoot(name: String, tab: Int?) =
        captureRoboImage(filePath = "src/test/screenshots/$name.png") {
            NightmareTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize()) {
                    NodeInspectorBody(
                        nodeId = "inpaint",
                        node = Node(
                            "inpaint", "sd15.inpaint",
                            params = mapOf(
                                "x" to "0.1", "y" to "0.1", "w" to "0.8", "h" to "0.8",
                                "width" to "512", "height" to "512", "model" to "absolutereality",
                                MaskNode.OPS to MaskState(
                                    listOf(MaskOp.Stroke(MaskStrokeData(listOf(0.3f to 0.35f, 0.6f to 0.6f), 0.09f)))
                                ).encode(),
                            ),
                            inputs = sources("image" to "photo", "segmenter" to "segment_model"),
                        ),
                        type = NODE_TYPES["sd15.inpaint"],
                        onSetParam = { _, _, _ -> },
                        onDelete = {},
                        cropSource = stripeSource(300, 220),
                        maskSource = stripeSource(300, 220),
                        inlinePopupTab = tab,
                    )
                }
            }
        }
}

/**
 * ⭐⭐ The IMAGE-TO-IMAGE sampler's sheet and crop popup — the same shape as
 * inpaint's, one tab. ⚠ Added 2026-09-17 after the popup was reported missing
 * on i2i: no golden drew an i2i sheet with a picture, so nothing showed it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class Img2ImgInspectorScreenshotTest {
    @Test fun cropIsAPreview() = shoot("inspector-i2i", null)
    @Test fun popupCropTab() = shoot("i2i-popup-crop", 0)

    private fun shoot(name: String, tab: Int?) =
        captureRoboImage(filePath = "src/test/screenshots/$name.png") {
            NightmareTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize()) {
                    NodeInspectorBody(
                        nodeId = "sample",
                        node = Node(
                            "sample", "sd15.sample",
                            params = mapOf(
                                "x" to "0.1", "y" to "0.1", "w" to "0.8", "h" to "0.8",
                                "width" to "512", "height" to "512", "model" to "absolutereality",
                            ),
                            inputs = sources("image" to "photo", "prompt" to "prompt"),
                        ),
                        type = NODE_TYPES["sd15.sample"],
                        onSetParam = { _, _, _ -> },
                        onDelete = {},
                        cropSource = stripeSource(300, 220),
                        inlinePopupTab = tab,
                    )
                }
            }
        }
}

/**
 * ⭐⭐⭐ **The FLUX.2 node with BOTH its pictures wired** — a base and a
 * reference.
 *
 * Reported 2026-09-20: *"why doesnt inpaint node have consistency for base and
 * reference img, both should have consistent cropper windows with their
 * respective names and knobs for size"*. It was true, and no golden showed it:
 * every crop/mask golden drew a node with ONE upstream picture, so the only
 * sheet that draws two was the one nothing pinned. The base went behind a
 * titled thumbnail into a tabbed popup; the reference — the same gesture on
 * the same [CropEditor] — was drawn loose in the sheet below it, in the empty
 * half-row the missing Mask tile left.
 *
 * ⚠⚠ These three pin the fix: the row has a **Reference** tile beside
 * **Crop**, the popup has a Reference tab, and that tab carries a size control
 * of its own where the Crop tab carries Shape + Resolution.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ReferenceInspectorScreenshotTest {
    @Test fun bothPicturesAreTiles() = shoot("inspector-flux-reference", null)
    /** ⚠ The two tabs, and the picture must sit in the same place in both. */
    @Test fun popupCropTab() = shoot("flux-popup-crop", 0)
    @Test fun popupReferenceTab() = shoot("flux-popup-reference", 1)

    private fun shoot(name: String, tab: Int?) =
        captureRoboImage(filePath = "src/test/screenshots/$name.png") {
            NightmareTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize()) {
                    NodeInspectorBody(
                        nodeId = "generate",
                        node = Node(
                            "generate", "flux2.sample",
                            params = mapOf(
                                "steps" to "4", "cfg" to "1.0", "seed" to "0",
                                "model" to "flux2_klein_4b",
                                "width" to "1024", "height" to "1024",
                                "x" to "0.1", "y" to "0.1", "w" to "0.8", "h" to "0.8",
                                // ⚠ A region that is NOT the whole reference, so the
                                // tile proves it draws the chosen part.
                                SdSampler.REF_X to "0.2", SdSampler.REF_Y to "0.05",
                                SdSampler.REF_W to "0.6", SdSampler.REF_H to "0.7",
                            ),
                            inputs = sources("image" to "photo", "reference" to "ref", "prompt" to "prompt"),
                        ),
                        type = NODE_TYPES["flux2.sample"],
                        onSetParam = { _, _, _ -> },
                        onDelete = {},
                        cropSource = stripeSource(300, 220),
                        // ⚠ A PORTRAIT reference against a square canvas: the tile
                        // must keep its own shape, because the wire never fits it
                        // to the output (`docs/MODELS.md` §9).
                        refSource = stripeSource(200, 320),
                        inlinePopupTab = tab,
                    )
                }
            }
        }
}

/** [CanvasScreenshotTest]'s synthetic source, for the classes beside it. */
private fun stripeSource(w: Int, h: Int): androidx.compose.ui.graphics.ImageBitmap {
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = android.graphics.Paint()
    for (i in 0 until 8) {
        p.color = if (i % 2 == 0) 0xFF3A6EA5.toInt() else 0xFF7FB2E5.toInt()
        c.drawRect(0f, h * i / 8f, w.toFloat(), h * (i + 1) / 8f, p)
    }
    return bmp.asImageBitmap()
}

/** A stand-in for a loaded plugin's node type — the real one needs QuickJS. */
private class FakeType(
    override val name: String,
    override val category: String,
    override val inputs: List<Port>,
    override val outputs: List<Port>,
) : com.abrah.nightmare.NodeType {
    override val version = "fake@1"
    override fun contextKey(node: Node) = null
    override suspend fun run(
        ctx: com.abrah.nightmare.NodeCtx,
        node: Node,
        inputs: Map<String, com.abrah.nightmare.Value>,
    ) = throw UnsupportedOperationException("drawing only")

}
