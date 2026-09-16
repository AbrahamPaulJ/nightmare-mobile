package com.abrah.nightmare

import androidx.test.core.app.ApplicationProvider
import com.abrah.nightmare.canvas.Pt
import com.abrah.nightmare.canvas.Workflow
import com.abrah.nightmare.canvas.WorkflowStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * ⚠⚠ **Does the canvas a user left behind actually come back?**
 *
 * The autosave is restored by a `LaunchedEffect` in `HarnessScreen`, beside two
 * others that fire on the same first composition. One of them —
 * `checkBackend()` — takes the view model's `busy` latch **synchronously** and
 * then suspends on an HTTP call, so anything routed through [HarnessViewModel]'s
 * `run` afterwards is refused with "already running".
 *
 * ⇒ The restore was routed through exactly that, so on a cold start it lost
 * every time and the user's graph was silently replaced by `defaultWorkflow()`.
 * ⚠⚠ And the canvas AUTOSAVES on the next gesture, so panning the default graph
 * overwrote the file the restore had just failed to read. The visible symptom is
 * "my workflow is gone", one step removed from the cause.
 *
 * ⚠ This is the SECOND time these effects have raced over that latch — the
 * first cost a scripted op that vanished in silence (`notes/HANDOFF.md` §5).
 * The lesson recorded then covered `runOp`; the restore was never considered.
 *
 * ⚠ Robolectric because the view model is an `AndroidViewModel` and the store is
 * a real file in `filesDir` — the file is the thing under test.
 */
@RunWith(RobolectricTestRunner::class)
class WorkflowRestoreTest {

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    /** The same directory [HarnessViewModel] keeps its own store in. */
    private fun store() = WorkflowStore(File(app.filesDir, "workflows"))

    /**
     * A graph nothing else in the app would produce, so "it came back" cannot be
     * confused with "the default happens to look like this".
     */
    private fun mine() = Workflow(
        Graph(listOf(Node("only_mine", "sd.clip_encode", mapOf("prompt" to "kept", "negative" to "")))),
        mapOf("only_mine" to Pt(12f, 34f)),
    )

    private fun seed() = store().save("current", mine(), NODE_TYPES)

    /**
     * ⚠⚠ Run the queued coroutines, and keep running them.
     *
     * `viewModelScope` dispatches on Main, which Robolectric leaves PAUSED, so
     * SOMETHING has to drain it or nothing here happens at all. ⚠ And one
     * `idle()` is not enough: the restore hops to `Dispatchers.IO` for the file
     * read and posts its continuation BACK to Main, which a single drain has
     * already passed. Draining repeatedly covers both halves.
     *
     * ⚠⚠ Both mistakes were made here in turn, and each made every test in this
     * file pass or fail for the wrong reason — "the restore did not clobber my
     * edit" reads green when no restore ever ran. The control
     * [theSavedCanvasComesBackOnItsOwn] is what caught them, twice; a suite
     * without it would have shipped a fix for a bug it never reproduced.
     */
    private fun settle() {
        repeat(50) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    /**
     * ⭐ The cold start, in the order `HarnessScreen`'s effects actually run it:
     * the backend probe first, then the restore.
     *
     * ⚠ `checkBackend()` is not awaited on purpose. That is the production
     * shape — it sets `busy` and suspends — and awaiting it here would test a
     * sequence the app never performs.
     */
    @Test
    fun theSavedCanvasComesBackEvenThoughTheBackendProbeWentFirst() {
        seed()
        val vm = HarnessViewModel(app)
        vm.checkBackend()
        vm.restoreWorkflow()
        settle()
        assertTrue(
            "the probe held the latch and the user's graph was dropped: " +
                vm.canvas.workflow.graph.nodes.map { it.id },
            "only_mine" in vm.canvas.workflow.graph.byId,
        )
        assertEquals(Pt(12f, 34f), vm.canvas.workflow.positions["only_mine"])
    }

    /** …and with nothing racing it, which is the control. */
    @Test
    fun theSavedCanvasComesBackOnItsOwn() {
        seed()
        val vm = HarnessViewModel(app)
        vm.restoreWorkflow()
        settle()
        assertTrue("only_mine" in vm.canvas.workflow.graph.byId)
    }

    /**
     * ⚠⚠ Restoring is a ONE-TIME event, not something that repeats every time
     * the canvas is shown again.
     *
     * The effect is keyed on `showCanvas`, so it re-fires on every return from
     * the Workflows or Models tab. Re-reading the file there can only revert
     * edits the autosave has not caught up with yet — the canvas already holds
     * the truth by then.
     */
    @Test
    fun comingBackToTheCanvasDoesNotRereadTheFileOverLiveEdits() {
        seed()
        val vm = HarnessViewModel(app)
        vm.restoreWorkflow()
        settle()
        assertTrue("the restore never ran, so this proves nothing",
            "only_mine" in vm.canvas.workflow.graph.byId)

        // The user drags a node. The autosave is asynchronous; the canvas is
        // authoritative from this moment on.
        vm.updateCanvas(vm.canvas.copy(workflow = vm.canvas.workflow.moved("only_mine", Pt(99f, 99f))))

        // Off to Models and back.
        vm.setCanvasVisible(false)
        vm.setCanvasVisible(true)
        vm.restoreWorkflow()
        settle()

        assertEquals(
            "returning to the canvas reverted a live edit",
            Pt(99f, 99f), vm.canvas.workflow.positions["only_mine"],
        )
    }

    /**
     * ⚠ A workflow opened from the Workflows tab must not be replaced by the
     * restore either — `openWorkflow` has just made the canvas authoritative.
     */
    @Test
    fun aWorkflowOpenedByHandSurvivesTheRestore() {
        seed()
        val vm = HarnessViewModel(app)
        val chosen = Workflow(
            Graph(listOf(Node("chosen", "sd.clip_encode", mapOf("prompt" to "x", "negative" to "")))),
            mapOf("chosen" to Pt(0f, 0f)),
        )
        vm.openWorkflow(chosen)
        vm.setCanvasVisible(true)
        vm.restoreWorkflow()
        settle()
        assertTrue("chosen" in vm.canvas.workflow.graph.byId)
    }

    /** ⚠ Nothing saved is not an error, and must leave the default in place. */
    @Test
    fun aFirstEverRunKeepsTheDefaultWorkflow() {
        val vm = HarnessViewModel(app)
        vm.restoreWorkflow()
        settle()
        assertTrue("generate" in vm.canvas.workflow.graph.byId)
    }

    // ---- the two latches ---------------------------------------------------

    /**
     * ⭐⭐⭐ **A model download must NOT make the canvas's Run button a
     * Cancel.**
     *
     * ⚠⚠⚠ `busy` used to mean "a run OR an install", because the harness
     * screen gates the same buttons on either. The canvas reads the same flag to
     * decide whether Run becomes a red **Cancel** — so an 8.6 GB download put
     * one there, and pressing it called `cancelRun`, whose `runJob` is null when
     * nothing is rendering: it aborted whatever HTTP the backend had open, said
     * "cancelling…", and the download carried on. Reported from the phone with
     * a screenshot, 2026-09-15.
     *
     * ⚠ [HarnessViewModel.working] is the OR, and only the harness reads it.
     */
    @Test
    fun aDownloadDoesNotPutACancelOnTheCanvas() {
        val vm = HarnessViewModel(app)
        assertTrue("nothing is happening yet", !vm.busy && !vm.working)

        // ⚠ An import is the cheapest install to start: it needs no network,
        // and it sets the same latch by the same lines as every other one.
        vm.importModel(android.net.Uri.parse("content://nothing/here"), "made_up")

        assertTrue("an install is a run to the harness", vm.working)
        assertTrue(
            "…but NOT to the canvas, or Run turns into a Cancel that cancels nothing",
            !vm.busy,
        )
        settle()
    }

    /**
     * ⚠⚠ And Run is still refused during one — with a sentence that names the
     * download. "already running" was true and useless: nothing was running that
     * the user had started or could see from the canvas.
     */
    @Test
    fun runDuringADownloadSaysWhatIsDownloading() {
        val vm = HarnessViewModel(app)
        vm.importModel(android.net.Uri.parse("content://nothing/here"), "made_up")
        vm.runCanvas()
        assertTrue(
            "the refusal must name the download: ${vm.runError}",
            vm.runError.orEmpty().contains("downloading"),
        )
        settle()
    }

    // ---- what may replace the canvas ---------------------------------------

    /**
     * ⭐⭐⭐ **Every route that replaces the canvas asks first** — and the
     * recommended flows did not.
     *
     * ⚠⚠⚠ Three routes replace the graph on the canvas: a RECIPE from the
     * Flows tab, a SAVED flow from the row below it, and the open-flow button in
     * Results. Two went through `guardedOpen` and the recipe did not, so tapping
     * "Text to image" threw away unsaved work with nothing said. Reported from
     * the phone, 2026-09-15: *"in Flows tab when i click flow it needs same
     * confirm popup as open flow icon from results tab"*.
     *
     * ⚠⚠ The N−1-of-N rule again, and the missed one is the row tapped most
     * often.
     */
    @Test
    fun openingARecipeOverUnsavedWorkAsksFirst() {
        val vm = HarnessViewModel(app)
        settle()
        // ⚠ An edit the user would mind losing, made the way the canvas makes
        // one — so `activeFlow.dirty` is set by the production path.
        vm.updateCanvas(vm.canvas.setParam("prompt", "prompt", "mine, typed by hand"))
        settle()
        assertTrue("the canvas must be dirty for this to mean anything", vm.activeFlow.dirty)

        vm.openRecipe(com.abrah.nightmare.canvas.RECIPES.first { it.id == "img2img" })
        assertNotNull("it replaced the canvas without asking", vm.pendingOpen)
        assertTrue(
            "the graph must NOT have changed yet: " + vm.canvas.workflow.graph.nodes.map { it.id },
            vm.canvas.workflow.graph.nodes.none { it.id == "photo" },
        )

        // ⚠ …and confirming is what opens it, which is the control: without
        // this the test passes for a recipe that is simply broken.
        vm.confirmPendingOpen()
        settle()
        assertTrue(
            "confirming must open it",
            vm.canvas.workflow.graph.nodes.any { it.id == "photo" },
        )
    }

    /**
     * ⚠ A CLEAN canvas is replaced straight away — nothing to lose, nothing to
     * ask. This is the control: without it the test above passes for a guard
     * that asks about everything, which would be its own bug.
     *
     * ⚠⚠ `savedBaseline` is null on a bare view model, which reads as dirty —
     * so the canvas is made clean the way the app makes it clean, by OPENING
     * something. In the app that is `restoreWorkflow` at startup.
     */
    @Test
    fun openingARecipeOverACleanCanvasDoesNotAsk() {
        val vm = HarnessViewModel(app)
        vm.openRecipe(com.abrah.nightmare.canvas.RECIPES.first { it.id == "txt2img" })
        vm.pendingOpen?.let { vm.confirmPendingOpen() }
        settle()
        assertTrue("an opened flow is its own baseline", !vm.activeFlow.dirty)

        vm.openRecipe(com.abrah.nightmare.canvas.RECIPES.first { it.id == "img2img" })
        assertTrue("an unedited flow is not worth a dialog", vm.pendingOpen == null)
        assertTrue(vm.canvas.workflow.graph.nodes.any { it.id == "photo" })
    }

    /**
     * ⭐⭐⭐ **The model list exists before any screen is opened.**
     *
     * ⚠⚠⚠ `modelRows` was filled only by opening the Models tab. The
     * sampler's checkpoint picker is drawn from it and was hidden when it was
     * empty, so on a COLD START a sampler's inspector had no Checkpoint field at
     * all — reported the minute a fresh APK was installed, 2026-09-16.
     *
     * ⚠⚠ **A sheet must not depend on a screen having been opened.** Anything
     * filled by navigation is empty on the path that skips it, and that is the
     * path a new user takes. This test constructs the view model and asks
     * NOTHING of it, which is the whole point.
     */
    @Test
    fun aFreshViewModelAlreadyKnowsTheModelCatalogue() {
        val vm = HarnessViewModel(app)
        assertTrue(
            "nothing was opened, so nothing filled it: " + vm.modelRows.size,
            vm.modelRows.isNotEmpty(),
        )
        assertEquals(ModelCatalog.all.map { it.id }, vm.modelRows.map { it.spec.id })
    }
}
