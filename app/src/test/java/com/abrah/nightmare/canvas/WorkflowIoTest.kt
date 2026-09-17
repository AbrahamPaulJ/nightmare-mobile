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
                // ⚠⚠ The CURRENT vocabulary (docs/ARCHITECTURE.md §5.7). A
                // fixture in an older shape is rewritten on load by one of the
                // migrations below, so it would be testing that migration and
                // calling it a round trip.
                Node("t", "core.prompt", params = mapOf("prompt" to "a cat", "negative" to "")),
                Node(
                    "s", "sd15.sample",
                    params = mapOf("seed" to "42", "model" to "dreamshaper"),
                    inputs = sources("prompt" to "t"),
                ),
                Node("d", "core.output", mapOf("save" to "false"), sources("media" to "s")),
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
        assertEquals(Source("t"), back.byId["s"]!!.inputs["prompt"])
        assertEquals(Source("s"), back.byId["d"]!!.inputs["media"])
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
                    Node("s", "sd15.sample", mapOf("seed" to "1", "model" to "m")),
                    Node("bare", "core.output", mapOf("save" to "false"), sources("media" to "s")),
                    Node("named", "core.output", mapOf("save" to "false"), sources("media" to "s:image")),
                )
            ),
            mapOf("s" to Pt(0f, 0f), "bare" to Pt(0f, 0f), "named" to Pt(0f, 0f)),
        )
        val back = workflowFromJson(w.toJson(types)).workflow.graph
        assertEquals(Source("s"), back.byId["bare"]!!.inputs["media"])
        assertEquals(Source("s", "image"), back.byId["named"]!!.inputs["media"])
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
        val cond = sampler.inputs["prompt"]
        assertNotNull("the sampler was left with nothing on prompt", cond)
        val text = g.graph.byId.getValue(cond!!.node)
        assertEquals("core.prompt", text.type)
        assertEquals("a cat", text.params["prompt"])
        assertEquals("blurry", text.params["negative"])
        // ⚠⚠ Stripped, not merely ignored: an undeclared param is still hashed
        // into the cache key, so a leftover prompt would sit in the key of a
        // node whose inspector no longer shows it.
        assertNull(sampler.params["prompt"])
        assertNull(sampler.params["negative"])
        assertNotNull("the new node needs somewhere to be", g.positions[text.id])
    }

    /**
     * ⚠⚠ …and the DECODER is collapsed INTO the sampler, because that is what
     * the same load now also does (docs/ARCHITECTURE.md §5.7). Two migrations
     * run over this one file: the prompt comes out of the sampler, and the
     * decode goes into it.
     */
    @Test
    fun theDecoderIsCollapsedIntoTheSampler() {
        val g = workflowFromJson(oldFile()).workflow
        assertEquals(2, g.graph.nodes.size)
        assertTrue(g.graph.nodes.none { it.type == "sd.vae_decode" })
        assertEquals(Pt(24f, 96f), g.positions["s"])
        // ⚠ A dropped node takes its position with it, or the canvas keeps a
        // co-ordinate for a node nobody can see.
        assertNull(g.positions["d"])
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
        assertEquals(Source("t"), g.byId.getValue("s").inputs["prompt"])
        assertNull(g.byId.getValue("s").params["prompt"])
    }

    /** ⚠ The synthesised id must not collide with one the file already used. */
    @Test
    fun theNewTextNodeGetsAFreeId() {
        val taken = """,
          {"id": "s_text", "type": "vae_decode", "x": 0, "y": 0,
           "params": {"model": "m"}, "inputs": {}}"""
        val g = workflowFromJson(oldFile(taken)).workflow.graph
        // ⚠ Two, not four: the file's own `vae_decode` collapses into the
        // sampler, and so does the unwired one squatting on the id. What is
        // left is the sampler and the prompt node the migration made.
        assertEquals(2, g.nodes.size)
        val cond = g.byId.getValue("s").inputs.getValue("prompt").node
        assertEquals("core.prompt", g.byId.getValue(cond).type)
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
            Graph(listOf(Node("s", "sd15.sample"), Node("d", "sd.vae_decode"))),
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
        val smaller = Workflow(Graph(listOf(Node("only", "sd15.sample"))), mapOf("only" to Pt(0f, 0f)))
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
        assertEquals("sd15.sample", g.byId.getValue("s").type)
        // ⚠ `vae_decode` is renamed on the way in and collapsed straight after,
        // so the rename shows only in that the load did not fail on the name.
        assertNull(g.byId["d"])
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
    fun aSavedCropNodeHandsItsFramingToTheSamplerAndGoes() {
        // ⭐ `image.crop` was deleted 2026-09-17. A saved flow naming one (here
        // under its oldest name, with the pre-blur padding) opens without it:
        // the sampler it fed takes its framing and padding and reads the photo.
        val json = """
            {"format": 1, "nodes": [
              {"id": "photo", "type": "core.image", "x": 0, "y": 0, "params": {"uri": "/a.png"}, "inputs": {}},
              {"id": "c", "type": "crop", "x": 0, "y": 0,
               "params": {"pad": "mirror", "x": "0.1", "y": "0.2", "w": "0.5", "h": "0.5"},
               "inputs": {"image": "photo"}},
              {"id": "s", "type": "sd15.sample", "x": 0, "y": 0, "params": {}, "inputs": {"image": "c"}},
              {"id": "u", "type": "image.upscale", "x": 0, "y": 0, "params": {}, "inputs": {"image": "c"}}
            ]}
        """.trimIndent()
        val loaded = workflowFromJson(json)
        val g = loaded.workflow.graph
        assertEquals(null, g.byId["c"])
        val s = g.byId.getValue("s")
        assertEquals("photo", s.inputs["image"]?.node)
        assertEquals("0.1", s.params["x"])
        assertEquals("blur", s.params["pad"])
        // ⚠ A consumer that does not frame is just rewired through.
        assertEquals("photo", g.byId.getValue("u").inputs["image"]?.node)
        assertEquals(1, loaded.notes.size)
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

    /**
     * ⭐⭐⭐ **A graph saved with the first fused video sampler still opens.**
     *
     * ⚠⚠ It is the case that decides whether deleting a node type is safe: a
     * saved workflow naming a type this build has never heard of fails with
     * `unknown type`, which is a file the user cannot open at all.
     *
     * ⚠ The shape went out and came back — one node (2026-09-12), five
     * (2026-09-13), one again (2026-09-15) — so this file has been migrated in
     * both directions. What survives every time is the ID, the seed and the text.
     */
    @Test
    fun aSavedFusedVideoSamplerStillOpens() {
        val old = """
            {"format":1,"nodes":[
              {"id":"video","type":"nd.video_sample",
               "params":{"prompt":"a fox","seed":"77","upscale":"false"},"inputs":{}},
              {"id":"save","type":"video.output",
               "params":{"save":"true"},"inputs":{"video":"video"}}
            ],"positions":{"video":{"x":10,"y":20},"save":{"x":10,"y":300}}}
        """.trimIndent()
        val g = workflowFromJson(old).workflow.graph

        assertTrue(g.nodes.none { it.type == "nd.video_sample" || it.type == "video.output" })
        // ⚠⚠ The sampler KEEPS its id, so anything naming it still resolves
        // and the run log says what the user expects.
        val v = g.byId.getValue("video")
        assertEquals("nd.sample", v.type)
        assertEquals("77", v.params["seed"])
        assertEquals("false", v.params["upscale"])

        // ⭐ The text becomes a prompt NODE, wired in.
        val prompt = g.byId.getValue(v.inputs.getValue("prompt").node)
        assertEquals("core.prompt", prompt.type)
        assertEquals("a fox", prompt.params["prompt"])
        // ⚠⚠ Stripped from the sampler: an undeclared param is still hashed
        // into the cache key, so a leftover would sit in the key of a node whose
        // inspector no longer shows it.
        assertNull(v.params["prompt"])
    }

    /**
     * ⭐⭐ **An image-to-video graph keeps its PICTURE.**
     *
     * ⚠⚠ The photo is the one thing the user chose, so it has to survive
     * every reshaping of the video path. ⚠ The five-node split of 2026-09-13
     * is folded back into one sampler here, and the picture follows the wire
     * through the encoder that is being removed.
     */
    @Test
    fun aSavedImageToVideoGraphKeepsItsPicture() {
        val old = """
            {"format":1,"nodes":[
              {"id":"photo","type":"image.load","params":{"uri":"/a.png"},"inputs":{}},
              {"id":"text","type":"nd.clip_encode","params":{"prompt":"drift"},"inputs":{}},
              {"id":"enc","type":"nd.vae_encode","params":{},"inputs":{"image":"photo"}},
              {"id":"samp","type":"nd.sample","params":{"seed":"5"},
               "inputs":{"cond":"text:cond","latent":"enc"}},
              {"id":"dec","type":"nd.vae_decode","params":{"upscale":"true"},
               "inputs":{"latent":"samp"}}
            ],"positions":{}}
        """.trimIndent()
        val g = workflowFromJson(old).workflow.graph

        assertTrue(
            "the split types are gone",
            g.nodes.none { it.type.startsWith("nd.vae") || it.type == "nd.clip_encode" },
        )
        val v = g.nodes.single { it.type == "nd.sample" }
        assertEquals("5", v.params["seed"])
        // ⭐ The photo, still wired — and straight in, with no crop between.
        assertEquals(Source("photo"), v.inputs["image"])
        assertEquals("core.image", g.byId.getValue("photo").type)
        // ⭐ …and the prompt, as text.
        val prompt = g.byId.getValue(v.inputs.getValue("prompt").node)
        assertEquals("core.prompt", prompt.type)
        assertEquals("drift", prompt.params["prompt"])
    }

    /**
     * ⚠⚠ `image.output` is gone too, and a graph that had one loses it
     * rather than failing to open. It returned its input unchanged, so anything
     * reading it is re-pointed at what fed it.
     */
    @Test
    fun aSavedImageOutputIsKeptAndRePorted() {
        val old = """
            {"format":1,"nodes":[
              {"id":"src","type":"core.image","params":{},"inputs":{}},
              {"id":"out","type":"image.output",
               "params":{"save":"true"},"inputs":{"image":"src"}},
              {"id":"up","type":"image.upscale","params":{},
               "inputs":{"image":"out"}}
            ],"positions":{}}
        """.trimIndent()
        val g = workflowFromJson(old).workflow.graph
        // ⭐ It is KEPT now, renamed and re-ported: the node came back on
        // 2026-09-15 (docs/ARCHITECTURE.md §5.7) because a chain needs
        // something that says which picture is the deliverable.
        val out = g.byId.getValue("out")
        assertEquals("core.output", out.type)
        assertEquals(Source("src"), out.inputs["media"])
        assertEquals(Source("out"), g.byId.getValue("up").inputs["image"])
    }
}
