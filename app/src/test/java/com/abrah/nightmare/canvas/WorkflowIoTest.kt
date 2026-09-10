package com.abrah.nightmare.canvas

import com.abrah.nightmare.Graph
import com.abrah.nightmare.NODE_TYPES
import com.abrah.nightmare.Node
import com.abrah.nightmare.Source
import com.abrah.nightmare.sources
import com.abrah.nightmare.NodeCtx
import com.abrah.nightmare.NodeType
import com.abrah.nightmare.Port
import com.abrah.nightmare.Value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Saving and loading a graph.
 *
 * ⭐ The cases that matter are not "does it round-trip" — they are what happens
 * when the file outlives the app that wrote it: a plugin that is missing, a
 * plugin at the wrong version, a node type this build has never heard of, and a
 * file written by a future format.
 */
@RunWith(RobolectricTestRunner::class)
class WorkflowIoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pluginType = fake("com.example.pack:Thing", "com.example.pack@0.1.0")

    private val types = NODE_TYPES + ("com.example.pack:Thing" to pluginType)

    private val workflow = Workflow(
        Graph(
            listOf(
                // ⚠ The prompt is a NODE. A sampler carrying one is a graph
                // from before ⑦4 and is REWRITTEN on load, so a round-trip
                // fixture that used the old shape would be testing the
                // migration and calling it a round trip.
                Node("t", "sd.clip_encode", params = mapOf("prompt" to "a cat", "negative" to "")),
                Node(
                    "s", "sd.sample",
                    params = mapOf("seed" to "42", "model" to "dreamshaper"),
                    inputs = sources("cond" to "t"),
                ),
                Node("d", "sd.vae_decode", mapOf("model" to "dreamshaper"), sources("latent" to "s")),
                Node("p", "com.example.pack:Thing"),
            )
        ),
        mapOf(
            "t" to Pt(10f, 0f), "s" to Pt(10f, 20f), "d" to Pt(300f, 40.5f),
            "p" to Pt(-5f, 0f),
        ),
    )

    private fun reload(w: Workflow = workflow) = workflowFromJson(w.toJson(types))

    // --- round trip ---------------------------------------------------------

    @Test
    fun nodesParamsAndWiringSurvive() {
        val back = reload().workflow.graph
        assertEquals(listOf("t", "s", "d", "p"), back.nodes.map { it.id })
        assertEquals("a cat", back.byId["t"]!!.params["prompt"])
        assertEquals(Source("t"), back.byId["s"]!!.inputs["cond"])
        assertEquals(Source("s"), back.byId["d"]!!.inputs["latent"])
    }

    /**
     * ⭐ The format guarantee. A wire that names WHICH output it left has to
     * survive the file, or a two-output node would come back wired to whatever
     * the reader guessed — and a workflow already on disk cannot be migrated
     * once that has happened.
     *
     * ⚠ Both spellings in one graph on purpose: `"s"` and `"s:latent"` must
     * round-trip as the DIFFERENT things they are. Collapsing them would look
     * harmless here and lose the distinction the executor refuses on.
     */
    @Test
    fun aNamedOutputPortSurvivesTheFile() {
        val w = Workflow(
            Graph(
                listOf(
                    Node("s", "sd.sample", mapOf("seed" to "1", "model" to "m")),
                    Node("bare", "sd.vae_decode", mapOf("model" to "m"), sources("latent" to "s")),
                    Node("named", "sd.vae_decode", mapOf("model" to "m"), sources("latent" to "s:latent")),
                )
            ),
            mapOf("s" to Pt(0f, 0f), "bare" to Pt(0f, 0f), "named" to Pt(0f, 0f)),
        )
        val back = workflowFromJson(w.toJson(types)).workflow.graph
        assertEquals(Source("s"), back.byId["bare"]!!.inputs["latent"])
        assertEquals(Source("s", "latent"), back.byId["named"]!!.inputs["latent"])
    }

    @Test
    fun positionsSurviveIncludingFractionsAndNegatives() {
        val back = reload().workflow.positions
        assertEquals(Pt(10f, 20f), back["s"])
        assertEquals(Pt(10f, 0f), back["t"])
        assertEquals(Pt(300f, 40.5f), back["d"])
        assertEquals(Pt(-5f, 0f), back["p"])
    }

    /**
     * ⚠ Layout must never reach the executor: the graph half is what runs.
     *
     * ⚠ It is also the check that a CURRENT file is left exactly as written --
     * the migration below rewrites an old sampler, and one that fired on a
     * graph already in the new shape would add an empty Text Encode nobody
     * asked for.
     */
    @Test
    fun theGraphItselfIsUnchanged() = assertEquals(workflow.graph, reload().workflow.graph)

    // --- graphs saved before the sampler lost its prompt ---------------------

    /**
     * A file exactly as the app used to write one: the sampler holds the text
     * and nothing is wired into `cond`.
     */
    private fun oldFile(extra: String = "") = """
        {"format": 1, "nodes": [
          {"id": "s", "type": "sample", "x": 24, "y": 96,
           "params": {"prompt": "a cat", "negative": "blurry", "seed": "42", "model": "m"},
           "inputs": {}},
          {"id": "d", "type": "vae_decode", "x": 24, "y": 356,
           "params": {"model": "m"}, "inputs": {"latent": "s"}}$extra
        ]}
    """.trimIndent()

    /**
     * ⭐⭐ The one that decides whether anybody's saved work survives the
     * sampler losing its prompt (`docs/ARCHITECTURE.md` §3).
     *
     * Every graph on any device -- including the canvas autosave the app opens
     * on -- carries the sampler's own prompt and no cond wire. Left alone each
     * one would open onto a sampler that refuses to run and a prompt that has
     * vanished from the inspector, so the text is MOVED rather than dropped.
     */
    @Test
    fun anOldSamplerPromptBecomesATextNode() {
        val g = workflowFromJson(oldFile()).workflow
        val sampler = g.graph.byId.getValue("s")
        val cond = sampler.inputs["cond"]
        assertNotNull("the sampler was left with nothing on cond", cond)
        val text = g.graph.byId.getValue(cond!!.node)
        assertEquals("sd.clip_encode", text.type)
        assertEquals("a cat", text.params["prompt"])
        assertEquals("blurry", text.params["negative"])
        // ⚠⚠ Stripped, not merely ignored: an undeclared param is still hashed
        // into the cache key, so a leftover prompt would sit in the key of a
        // node whose inspector no longer shows it.
        assertNull(sampler.params["prompt"])
        assertNull(sampler.params["negative"])
        assertNotNull("the new node needs somewhere to be", g.positions[text.id])
    }

    /** ⚠ …and the rest of the file is untouched by it. */
    @Test
    fun migratingLeavesEveryOtherNodeAlone() {
        val g = workflowFromJson(oldFile()).workflow
        assertEquals(3, g.graph.nodes.size)
        assertEquals(Source("s"), g.graph.byId.getValue("d").inputs["latent"])
        assertEquals(Pt(24f, 96f), g.positions["s"])
        assertEquals(Pt(24f, 356f), g.positions["d"])
    }

    /**
     * ⚠⚠ A sampler that ALREADY has a conditioning keeps it. Its own prompt was
     * the ignored copy, so it goes -- but adding a second Text Encode would
     * silently replace the wire the user drew.
     */
    @Test
    fun anAlreadyWiredSamplerKeepsItsConditioning() {
        val json = """
            {"format": 1, "nodes": [
              {"id": "t", "type": "encode_text", "x": 0, "y": 0,
               "params": {"prompt": "a dog", "negative": ""}, "inputs": {}},
              {"id": "s", "type": "sample", "x": 0, "y": 200,
               "params": {"prompt": "a cat", "seed": "42", "model": "m"},
               "inputs": {"cond": "t"}}
            ]}
        """.trimIndent()
        val g = workflowFromJson(json).workflow.graph
        assertEquals(2, g.nodes.size)
        assertEquals(Source("t"), g.byId.getValue("s").inputs["cond"])
        assertNull(g.byId.getValue("s").params["prompt"])
    }

    /** ⚠ The synthesised id must not collide with one the file already used. */
    @Test
    fun theNewTextNodeGetsAFreeId() {
        val taken = """,
          {"id": "s_text", "type": "vae_decode", "x": 0, "y": 0,
           "params": {"model": "m"}, "inputs": {}}"""
        val g = workflowFromJson(oldFile(taken)).workflow.graph
        assertEquals(4, g.nodes.size)
        assertEquals("sd.vae_decode", g.byId.getValue("s_text").type)
        val cond = g.byId.getValue("s").inputs.getValue("cond").node
        assertEquals("sd.clip_encode", g.byId.getValue(cond).type)
    }

    // --- what a file needs to run -------------------------------------------

    /**
     * ⚠⚠ The version is the point. A node type string names its plugin but
     * carries no version, so without this a workflow could not say which
     * `com.example.pack` it was built against.
     */
    @Test
    fun theFileRecordsThePluginsItNeeds() {
        val requires = reload().requires
        assertEquals(1, requires.size)
        assertEquals("com.example.pack", requires[0].pluginId)
        assertEquals("0.1.0", requires[0].version)
    }

    /** ⚠ Built-ins are not plugins and must not appear as requirements. */
    @Test
    fun builtInsAreNotRecordedAsRequirements() {
        val onlyBuiltIns = Workflow(
            Graph(listOf(Node("s", "sd.sample"), Node("d", "sd.vae_decode"))),
            mapOf("s" to Pt(0f, 0f), "d" to Pt(0f, 0f)),
        )
        assertTrue(workflowFromJson(onlyBuiltIns.toJson(types)).requires.isEmpty())
    }

    @Test
    fun aPluginUsedTwiceIsRecordedOnce() {
        val twice = Workflow(
            Graph(listOf(Node("a", "com.example.pack:Thing"), Node("b", "com.example.pack:Thing"))),
            mapOf("a" to Pt(0f, 0f), "b" to Pt(0f, 0f)),
        )
        assertEquals(1, workflowFromJson(twice.toJson(types)).requires.size)
    }

    @Test
    fun anInstalledPluginAtTheRightVersionIsNotMissing() =
        assertTrue(missingRequirements(reload().requires, types).isEmpty())

    /** ⚠ The message names the plugin and the version, because that is the fix. */
    @Test
    fun anAbsentPluginIsReportedByName() {
        val complaints = missingRequirements(reload().requires, NODE_TYPES)
        assertEquals(1, complaints.size)
        assertTrue(complaints[0], complaints[0].contains("com.example.pack"))
        assertTrue(complaints[0], complaints[0].contains("0.1.0"))
    }

    /**
     * ⚠⚠ A version mismatch is reported, not tolerated. A plugin's version is
     * part of its nodes' cache keys and can change what a node produces, so
     * running against a different one is how a workflow quietly stops meaning
     * what it did.
     */
    @Test
    fun aDifferentVersionOfThePluginIsReported() {
        val newer = NODE_TYPES +
            ("com.example.pack:Thing" to fake("com.example.pack:Thing", "com.example.pack@0.2.0"))
        val complaints = missingRequirements(reload().requires, newer)
        assertEquals(1, complaints.size)
        assertTrue(complaints[0], complaints[0].contains("0.2.0"))
        assertTrue(complaints[0], complaints[0].contains("0.1.0"))
    }

    /**
     * ⚠⚠ A node this build cannot resolve is KEPT. Dropping it would silently
     * delete the user's work and leave a graph that looks complete.
     */
    @Test
    fun aNodeOfAnUnknownTypeIsKeptNotDropped() {
        val json = workflow.toJson(types)
        val back = workflowFromJson(json).workflow   // loaded with no type registry at all
        assertEquals(4, back.graph.nodes.size)
        assertEquals("com.example.pack:Thing", back.graph.byId["p"]!!.type)
    }

    // --- bad files ----------------------------------------------------------

    @Test
    fun garbageIsRefusedWithAMessage() {
        val e = assertThrows(WorkflowFormatError::class.java) { workflowFromJson("not json") }
        assertTrue(e.message!!, e.message!!.contains("not a workflow"))
    }

    @Test
    fun aFutureFormatIsRefusedByNumber() {
        val e = assertThrows(WorkflowFormatError::class.java) {
            workflowFromJson("""{"format": 99, "nodes": []}""")
        }
        assertTrue(e.message!!, e.message!!.contains("99"))
    }

    @Test
    fun aFileWithNoNodesArrayIsRefused() {
        assertThrows(WorkflowFormatError::class.java) {
            workflowFromJson("""{"format": 1}""")
        }
    }

    @Test
    fun aNodeWithNoTypeIsRefusedByName() {
        val e = assertThrows(WorkflowFormatError::class.java) {
            workflowFromJson("""{"format": 1, "nodes": [{"id": "x"}]}""")
        }
        assertTrue(e.message!!, e.message!!.contains("\"x\""))
    }

    // --- the store ----------------------------------------------------------

    @Test
    fun savingThenLoadingReturnsTheSameGraph() {
        val store = WorkflowStore(tmp.newFolder("wf"))
        store.save("current", workflow, types)
        assertEquals(workflow.graph, store.load("current")!!.workflow.graph)
    }

    @Test
    fun loadingWhenNothingWasSavedIsNull() =
        assertNull(WorkflowStore(tmp.newFolder("wf2")).load("current"))

    /** ⚠ Overwriting must not leave the previous graph behind. */
    @Test
    fun savingTwiceKeepsOnlyTheLatest() {
        val store = WorkflowStore(tmp.newFolder("wf3"))
        store.save("current", workflow, types)
        val smaller = Workflow(Graph(listOf(Node("only", "sd.sample"))), mapOf("only" to Pt(0f, 0f)))
        store.save("current", smaller, types)
        assertEquals(listOf("only"), store.load("current")!!.workflow.graph.nodes.map { it.id })
    }

    /** ⚠ …and must leave no temp file for the next reader to trip over. */
    @Test
    fun savingLeavesNoTemporaryFile() {
        val dir = tmp.newFolder("wf4")
        WorkflowStore(dir).save("current", workflow, types)
        assertTrue(dir.list()!!.none { it.endsWith(".tmp") })
    }

    // --- the saved view ------------------------------------------------------

    /**
     * ⭐⭐ Where the canvas was, and how it was held, survives a round trip.
     * A workflow that reopens onto empty space is a workflow the user has to go
     * looking for.
     */
    @Test
    fun theViewSurvivesARoundTrip() {
        val view = SavedView(Pt(-820f, 340f), 1.75f, zoomLocked = true, panLocked = false)
        val back = workflowFromJson(workflow.toJson(types, view)).view
        assertEquals(view, back)
    }

    /**
     * ⚠⚠ A file written before views existed still loads, and says it has none
     * -- the field is optional and this is NOT a format bump. Raising the format
     * number would make every workflow anyone has already saved fail to open.
     */
    @Test
    fun aFileWithNoViewLoadsWithNone() {
        assertNull(workflowFromJson(workflow.toJson(types)).view)
    }

    /**
     * ⚠ A scale of zero would leave the canvas un-navigable AND unable to
     * recover, since every gesture is multiplicative. Clamped on the way in,
     * because a hand-edited or truncated file is where this comes from.
     */
    @Test
    fun aSillyScaleIsClamped() {
        val json = workflow.toJson(types, SavedView(Pt(0f, 0f), 0f))
        assertEquals(0.25f, workflowFromJson(json).view!!.scale, 0.001f)
    }

    /** ⚠ …and the locks come back with it, which is half of what was asked for. */
    @Test
    fun theLocksTravelWithTheView() {
        val json = workflow.toJson(types, SavedView(Pt(0f, 0f), 1f, panLocked = true))
        val back = workflowFromJson(json).view!!
        assertTrue(back.panLocked)
        assertTrue(!back.zoomLocked)
    }

    // --- the namespaced type names -------------------------------------------

    /**
     ⭐⭐ Every saved graph says `sample`, and every one of them still opens.
     *
     * ⚠⚠ The built-ins were namespaced (`sd.sample`, `image.load`) while the app
     * had one user and no published plugins -- the type string is what a
     * contributor writes in a manifest, so renaming it later breaks their work.
     * The price is this migration, and the thing it protects is every workflow
     * anyone has already saved, the canvas autosave included.
     */
    @Test
    fun oldBareTypeNamesAreRenamedOnLoad() {
        val g = workflowFromJson(oldFile()).workflow.graph
        assertEquals("sd.sample", g.byId.getValue("s").type)
        assertEquals("sd.vae_decode", g.byId.getValue("d").type)
    }

    /** ⚠ …and a plugin's type, which was always namespaced, is left alone. */
    @Test
    fun aPluginTypeIsNotRenamed() {
        val json = """
            {"format": 1, "nodes": [
              {"id": "m", "type": "com.example.latent-mix:LatentMix", "x": 0, "y": 0,
               "params": {}, "inputs": {}}
            ]}
        """.trimIndent()
        assertEquals(
            "com.example.latent-mix:LatentMix",
            workflowFromJson(json).workflow.graph.byId.getValue("m").type,
        )
    }

    /**
     * ⚠⚠ `crop`'s mirrored padding became a BLURRED mirror under a new value,
     * so a saved `pad: mirror` has to become `pad: blur` -- left alone it names
     * a fill the node no longer produces, and the chip would offer a word for
     * something else.
     */
    @Test
    fun theOldMirrorPaddingBecomesBlur() {
        val json = """
            {"format": 1, "nodes": [
              {"id": "c", "type": "crop", "x": 0, "y": 0,
               "params": {"pad": "mirror"}, "inputs": {}}
            ]}
        """.trimIndent()
        val c = workflowFromJson(json).workflow.graph.byId.getValue("c")
        assertEquals("image.crop", c.type)
        assertEquals("blur", c.params["pad"])
    }

    private fun fake(name: String, version: String) = object : NodeType {
        override val name = name
        override val version = version
        override val category = "misc"
        override val inputs = emptyList<Port>()
        override val outputs = listOf(Port("image", "IMAGE"))
        override fun contextKey(node: Node) = null
        override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value =
            throw UnsupportedOperationException("io only")
    }
}
