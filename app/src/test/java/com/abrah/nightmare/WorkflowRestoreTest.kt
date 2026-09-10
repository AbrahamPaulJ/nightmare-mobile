package com.abrah.nightmare

import androidx.test.core.app.ApplicationProvider
import com.abrah.nightmare.canvas.Pt
import com.abrah.nightmare.canvas.Workflow
import com.abrah.nightmare.canvas.WorkflowStore
import org.junit.Assert.assertEquals
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
        assertTrue("sample" in vm.canvas.workflow.graph.byId)
    }
}
