package com.abrah.nightmare.canvas

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.abrah.nightmare.Graph
import com.abrah.nightmare.MaskNode
import com.abrah.nightmare.MaskOp
import com.abrah.nightmare.MaskState
import com.abrah.nightmare.MaskStrokeData
import com.abrah.nightmare.NODE_TYPES
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

    private fun sampler(id: String, seed: Int) = Node(
        id, "sd.sample",
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
     * (notes/PROGRESS.md ⑦4), so this is what a graph with two renders in it
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
     * ⭐ The whole screen, mid-render: the run bar, the output thumbnail, and
     * the per-node state the executor reports. This is the state docs/UI.md §1
     * says decides whether the app feels modern, so it is the one most worth
     * pinning.
     */
    @Test
    fun theScreenMidRender() = shoot("screen-running") {
        CanvasScreen(
            state = CanvasState(defaultWorkflow(), Viewport(Pt(0f, 0f), 0.85f)),
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
            state = CanvasState(
                defaultWorkflow(),
                Viewport(Pt(0f, 0f), 0.85f),
                zoomLocked = true,
                panLocked = true,
            ),
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
            state = CanvasState(
                defaultWorkflow(),
                Viewport(Pt(0f, 0f), 0.85f),
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
            state = CanvasState(defaultWorkflow(), Viewport(Pt(0f, 0f), 0.85f)),
            types = NODE_TYPES,
            status = emptyMap(),
            busy = false,
            image = null,
            onGesture = {}, onRun = {}, onBack = {},
            modelLabel = "AbsoluteReality",
            backendUp = false,
        )
    }

    /** ⚠ A run that failed says so ON the canvas — it used to reach only the log. */
    @Test
    fun theScreenShowingWhyARunFailed() = shoot("screen-run-error") {
        CanvasScreen(
            state = CanvasState(defaultWorkflow(), Viewport(Pt(0f, 0f), 0.85f)),
            types = NODE_TYPES,
            status = emptyMap(),
            busy = false,
            image = null,
            onGesture = {}, onRun = {}, onBack = {},
            runError = "no model installed -- open Models and download one",
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
                    "src", "image.load",
                    params = mapOf("width" to "512", "height" to "512"),
                ),
                type = NODE_TYPES["image.load"],
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
                    "src", "image.load",
                    params = mapOf(
                        "uri" to "content://media/external/images/media/258620",
                        "width" to "512", "height" to "512",
                    ),
                ),
                type = NODE_TYPES["image.load"],
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
                    "sample", "sd.sample",
                    params = mapOf("steps" to "10", "cfg" to "1.5", "scheduler" to "euler_a"),
                ),
                type = NODE_TYPES["sd.sample"],
                onSetParam = { _, _, _ -> },
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

    /**
     * ⭐⭐ The cropper's sheet when the graph has decided its size: `out_w`
     * and `out_h` locked and SAYING WHO decided, the shape chooser gone because
     * there is nothing left to choose, and the padding mode as chips rather than
     * a text box that would accept "Black" and fail at Run.
     */
    @Test
    fun aCropSizedByItsConsumer() = shoot("inspector-crop-sized") {
        Surface(Modifier.fillMaxSize()) {
            NodeInspectorBody(
                nodeId = "frame",
                node = Node(
                    "frame", "image.crop",
                    params = mapOf(
                        "x" to "0.1", "y" to "0.1", "w" to "0.6", "h" to "0.6",
                        "out_w" to "512", "out_h" to "512", "pad" to "black",
                    ),
                    inputs = sources("image" to "photo"),
                ),
                type = NODE_TYPES["image.crop"],
                onSetParam = { _, _, _ -> },
                onDelete = {},
                cropSource = stripes(300, 220),
                demand = SizeDemand.Exactly(512, 512, listOf("encode")),
            )
        }
    }
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
