package com.abrah.nightmare.canvas

import com.abrah.nightmare.NODE_TYPES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Named saves — the half of the Workflows tab that is not UI.
 *
 * ⚠ The round trip through the FILE, not through `toJson` alone: the point of
 * the tab is that a graph kept today opens tomorrow, and the file is where that
 * can go wrong.
 */
@RunWith(RobolectricTestRunner::class)   // ⚠ org.json is Android's, not the JVM's
class WorkflowStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val types = NODE_TYPES
    private fun store() = WorkflowStore(tmp.newFolder("workflows-${counter++}"))
    private var counter = 0

    private val graph = img2imgWorkflow()

    @Test
    fun aSavedWorkflowComesBackWithEverythingOnIt() {
        val s = store()
        val resized = graph.resized("sample", 420f).moved("decode", Pt(77f, 88f))
        s.save("my flow", resized, types)

        val back = s.load("my flow")!!.workflow
        assertEquals(resized.graph.nodes.map { it.id }, back.graph.nodes.map { it.id })
        // ⭐ Params, wiring, position AND width -- the whole point of the button.
        assertEquals("0.6", back.graph.byId["sample"]!!.params["denoise"])
        assertEquals(
            resized.graph.byId["sample"]!!.inputs,
            back.graph.byId["sample"]!!.inputs,
        )
        assertEquals(Pt(77f, 88f), back.positions["decode"])
        assertEquals(420f, back.widthOf("sample"), 0.5f)
    }

    @Test
    fun savedListsWhatWasSavedNewestFirstAndExcludesTheAutosave() {
        val s = store()
        s.save("current", graph, types)   // the canvas autosave
        s.save("alpha", graph, types)
        s.save("beta", graph, types)

        val names = s.saved().map { it.name }
        assertTrue("the autosave must not be offered as a saved flow", "current" !in names)
        assertEquals(setOf("alpha", "beta"), names.toSet())
    }

    @Test
    fun deleteRemovesIt() {
        val s = store()
        s.save("gone", graph, types)
        assertNotNull(s.load("gone"))
        s.delete("gone")
        assertNull(s.load("gone"))
    }

    /**
     * ⚠⚠ The name becomes a FILENAME and the user types it. A name carrying a
     * path separator would write outside the workflows directory.
     */
    @Test
    fun aNameThatWouldEscapeTheDirectoryIsRefused() {
        val s = store()
        assertNotNull(s.validName("../../etc/passwd"))
        assertNotNull(s.validName("a/b"))
        assertNotNull(s.validName(""))
        assertNotNull(s.validName("current"))
        assertNull(s.validName("my flow 2"))
    }

    /** ⭐ Every recommended workflow must actually open. */
    @Test
    fun everyRecipeBuildsAndRoundTrips() {
        val s = store()
        for (r in RECIPES) {
            val w = r.build()
            assertTrue("${r.id} has no nodes", w.graph.nodes.isNotEmpty())
            // ⚠ Built-ins only: a recipe needing a plugin pack cannot open on a
            // device that has never been handed one, which is every device.
            for (n in w.graph.nodes) {
                assertTrue("${r.id} uses non-built-in ${n.type}", types.containsKey(n.type))
            }
            s.save(r.id, w, types)
            assertEquals(w.graph.nodes.size, s.load(r.id)!!.workflow.graph.nodes.size)
        }
    }
}
