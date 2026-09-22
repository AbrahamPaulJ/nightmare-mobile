package com.abrah.nightmare

import androidx.test.core.app.ApplicationProvider
import com.abrah.nightmare.canvas.Pt
import com.abrah.nightmare.canvas.Workflow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ⭐⭐⭐ **What the top bar says is holding the machine.**
 *
 * ⚠⚠ Two bugs, reported together from the phone on 2026-09-15 — *"the 'holding'
 * at top bar of canvas isnt updated for model changes and sometimes even says
 * idle on runs"* — and both are the same shape: the readout answered a question
 * about the MACHINE with a fact about something else.
 *
 * | it said | the fact it used | the question |
 * |---|---|---|
 * | the old checkpoint after a node swap | `SelectedModel`, the global picker | what will THIS GRAPH load |
 * | "(idle)" during a render | a 2 s poll of `launchedKey` | is anything happening |
 *
 * ⚠ Robolectric because [HarnessViewModel] is an `AndroidViewModel` and
 * `refreshLoad` reads `ActivityManager`.
 */
@RunWith(RobolectricTestRunner::class)
class LoadReadoutTest {

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun graphOn(model: String) = Workflow(
        Graph(
            listOf(
                Node("prompt", "core.prompt", mapOf("prompt" to "a", "negative" to "")),
                Node(
                    "sample", "sd15.sample",
                    mapOf("model" to model, "width" to "512", "height" to "512"),
                    sources("prompt" to "prompt"),
                ),
                Node("output", "core.output", inputs = sources("media" to "sample")),
            )
        ),
        mapOf("prompt" to Pt(0f, 0f), "sample" to Pt(400f, 0f), "output" to Pt(800f, 0f)),
    )

    /**
     * ⭐⭐ It names the checkpoint the GRAPH carries, not the one the picker is
     * set to — which is the whole bug, because a node has carried its own
     * checkpoint since the swap landed.
     */
    @Test
    fun theReadoutNamesWhatTheGraphWillLoad() {
        val vm = HarnessViewModel(app)
        vm.openWorkflow(graphOn("qteamix"))
        vm.refreshLoad()
        assertEquals(listOf("QteaMix"), vm.load?.graphModels)

        // ⚠ …and it FOLLOWS a change rather than lagging it.
        vm.openWorkflow(graphOn("absolutereality"))
        vm.refreshLoad()
        assertEquals(listOf("AbsoluteReality"), vm.load?.graphModels)
    }

    /**
     * ⭐⭐ Two checkpoints in one graph is legal since the scheduler landed, and
     * the bar has one line — so it says the first and how many more, rather than
     * picking one and implying it is the only one.
     */
    @Test
    fun aGraphNamingTwoCheckpointsReportsBoth() {
        val vm = HarnessViewModel(app)
        val two = Workflow(
            Graph(
                graphOn("qteamix").graph.nodes + Node(
                    "second", "sd15.sample",
                    mapOf("model" to "absolutereality", "width" to "512", "height" to "512"),
                    sources("prompt" to "prompt"),
                ),
            ),
            graphOn("qteamix").positions + ("second" to Pt(400f, 400f)),
        )
        vm.openWorkflow(two)
        vm.refreshLoad()
        assertEquals(listOf("QteaMix", "AbsoluteReality"), vm.load?.graphModels)
    }

    /**
     * ⚠⚠ A graph with no sampler names no checkpoint, and the readout falls back
     * to the selected one rather than to an empty string — which is what it said
     * before any of this, so an upscale-only flow reads unchanged.
     */
    @Test
    fun aGraphWithNoSamplerFallsBackToTheSelectedModel() {
        val vm = HarnessViewModel(app)
        vm.openWorkflow(
            Workflow(
                Graph(listOf(Node("photo", "core.image", mapOf("uri" to "")))),
                mapOf("photo" to Pt(0f, 0f)),
            )
        )
        vm.refreshLoad()
        assertEquals(listOf(SelectedModel.spec.label), vm.load?.graphModels)
    }

    /**
     * ⭐⭐⭐ **"Idle" is a claim about the machine, and a run in flight
     * contradicts it.**
     *
     * ⚠⚠ The poll is every 2 s and a backend launch is 2.3-5 s, so the whole
     * launch window was described as idle while the user watched the run bar
     * count up. [CanvasLoad.running] is the one fact that settles it, and it is
     * refreshed at both ends of a run rather than waiting for the next tick.
     */
    @Test
    fun aRunIsNeverIdle() {
        val vm = HarnessViewModel(app)
        // ⚠ A flow that needs NO checkpoint: since 2026-09-17 a Run naming one
        // that is not installed stops at the download popup before it starts
        // (`HarnessViewModel.modelsPresentOrAsk`), and nothing is installed here.
        // ⚠ …and no UPSCALER either — the popup asks for those too.
        vm.openWorkflow(
            Workflow(
                Graph(listOf(Node("photo", "core.image", mapOf("uri" to "")))),
                mapOf("photo" to Pt(0f, 0f)),
            )
        )
        vm.refreshLoad()
        assertNotNull(vm.load)
        assertTrue("nothing has started yet", vm.load?.running == false)

        vm.runCanvas()
        assertTrue(
            "the readout must know a run started, without waiting for the 2s poll",
            vm.load?.running == true,
        )
    }

    /**
     * ⭐ A Run on a checkpoint that is not on the phone ASKS — the popup on the
     * canvas (2026-09-17) — instead of launching a backend against a missing
     * directory. A catalogue model offers itself; nothing runs.
     */
    @Test
    fun aMissingCheckpointAsksToDownloadIt() {
        val vm = HarnessViewModel(app)
        vm.openWorkflow(graphOn("qteamix"))
        vm.runCanvas()
        val m = vm.missingModel as? HarnessViewModel.MissingModel.Checkpoint
        assertNotNull("the run must stop at the download question", m)
        assertEquals("qteamix", m!!.offer.id)
        assertTrue(!m.substitute)
        assertTrue(vm.load?.running != true)
    }

    /**
     * ⚠ Every checkpoint a graph names, as empty files of the right names —
     * enough for `ModelSpec.missing()`, which checks EXISTENCE for a QNN family.
     */
    private fun installCheckpointsFor(w: com.abrah.nightmare.canvas.Workflow) {
        for (n in w.graph.nodes) {
            val spec = ModelCatalog.byId(n.params["model"].orEmpty()) ?: continue
            val dir = spec.dir(app).apply { mkdirs() }
            for (f in spec.requiredFiles) java.io.File(dir, f).writeText("")
        }
    }

    /**
     * ⭐⭐ A mask that was actually TAPPED asks for the segmenter when it is
     * not installed — those taps are re-resolved at render, so the model is
     * genuinely needed.
     *
     * ⚠⚠⚠ **It used to fire on a Segment model NODE being present**, and
     * the Inpaint recipe wired one in by default — so every inpaint Run demanded
     * an 80 MB download for a tool nobody had touched. Reported 2026-09-22:
     * *"even if i dont use tap to segment and click run, i got window saying to
     * download segment model"*. The node is deleted and the checkbox that
     * replaced it must NOT inherit the demand: ticking a tool is not using it.
     * ⇒ This asserts the TAP, which is using it.
     */
    @Test
    fun aTappedMaskAsksForTheSegmenter() {
        val vm = HarnessViewModel(app)
        val inpaint = com.abrah.nightmare.SdSampler.ALL.first { it.inpaint }
        // ⚠⚠ The CHECKPOINT check runs before the segmenter one, so without a
        // model on disk this stops at "which model" and never reaches the
        // assertion. The old fixture dodged it by using a graph with no sampler
        // at all, which an inpaint test cannot do.
        installCheckpointsFor(com.abrah.nightmare.canvas.inpaintWorkflow())
        // ⚠ A COMPLETE inpaint graph: the checkpoint check runs before the
        // segmenter one, so a bare node stops at "which model" and never
        // reaches the assertion.
        val w = com.abrah.nightmare.canvas.inpaintWorkflow().let { wf ->
            wf.copy(
                graph = Graph(
                    wf.graph.nodes.map { n ->
                        if (n.type != inpaint.name) n else n.copy(
                            params = n.params + (
                                com.abrah.nightmare.MaskNode.OPS to
                                    com.abrah.nightmare.MaskState(
                                        listOf(com.abrah.nightmare.MaskOp.Tap(0.5f, 0.5f, 1)),
                                    ).encode()
                                ),
                        )
                    },
                ),
            )
        }
        vm.openWorkflow(w)
        vm.runCanvas()
        val m = vm.missingModel
        assertTrue("got $m", m is HarnessViewModel.MissingModel.Segment)
    }

    /** ⚠ …and an inpaint node with the checkbox ON but no taps does NOT. */
    @Test
    fun tickingTapToSelectDoesNotDemandTheModel() {
        val vm = HarnessViewModel(app)
        installCheckpointsFor(com.abrah.nightmare.canvas.inpaintWorkflow())
        val w = com.abrah.nightmare.canvas.inpaintWorkflow()
        vm.openWorkflow(w)
        vm.runCanvas()
        assertTrue(
            "a ticked checkbox must not demand a download: got ${vm.missingModel}",
            vm.missingModel !is HarnessViewModel.MissingModel.Segment,
        )
    }
}
