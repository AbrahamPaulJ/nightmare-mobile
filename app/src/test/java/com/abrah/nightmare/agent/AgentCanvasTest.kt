package com.abrah.nightmare.agent

import androidx.test.core.app.ApplicationProvider
import com.abrah.nightmare.HarnessViewModel
import com.abrah.nightmare.api.ApiCore
import com.abrah.nightmare.canvas.RECIPES
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ⭐⭐ The agent's canvas tools against a REAL view model (`docs/AGENT-API.md` §4b): what it does
 * lands on the canvas the person sees, through the same rules, and it asks before throwing
 * away unsaved work or deleting a file (the user's two, 2026-10-08).
 */
@RunWith(RobolectricTestRunner::class)
class AgentCanvasTest {

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val vm = HarnessViewModel(app)
    private val asked = mutableListOf<String>()
    private var answer = AgentTools.CANCEL

    private val tools = AgentTools(
        app,
        ApiCore(app, com.abrah.nightmare.HarnessOps(app, object : com.abrah.nightmare.HarnessOps.Sink {
            override fun say(text: String, bad: Boolean) {}
        }), "agent"),
        // ⚠ Identity: the test IS the main thread.
        VmCanvasHost(vm) { it() },
        ask = { q, _ -> asked += q; answer },
    )

    private fun call(name: String, args: String = "{}") = tools.call(name, JSONObject(args))

    private fun opened(id: String) = vm.openWorkflow(RECIPES.first { it.id == id }.build())

    @Test
    fun aCleanCanvasIsReplacedWithoutAsking() {
        opened("txt2img")
        val r = call("new_flow", """{"flow":"img2img"}""")
        assertTrue(r.json.toString(), r.ok)
        assertTrue(asked.isEmpty())
        assertTrue("the img2img recipe has an image node", vm.canvas.workflow.graph.nodes.any { it.type == "core.image" })
    }

    @Test
    fun unsavedWorkIsAskedAboutAndCancelLeavesItAlone() {
        opened("txt2img")
        call("set_params", """{"prompt":"a lighthouse"}""")
        assertTrue(vm.activeFlow.dirty)
        answer = AgentTools.CANCEL
        val r = call("new_flow", """{"flow":"img2img"}""")
        assertFalse(r.ok)
        assertEquals(1, asked.size)
        assertEquals("a lighthouse", vm.canvas.workflow.graph.nodes.first { it.type == "core.prompt" }.params["prompt"])
    }

    @Test
    fun saveFirstSavesThenOpens() {
        opened("txt2img")
        call("set_params", """{"prompt":"a lighthouse"}""")
        answer = AgentTools.SAVE
        assertTrue(call("new_flow", """{"flow":"img2img"}""").ok)
        assertTrue("the old flow is in Saved", com.abrah.nightmare.canvas.WorkflowStore(java.io.File(app.filesDir, "workflows")).saved().isNotEmpty())
        assertTrue(vm.canvas.workflow.graph.nodes.any { it.type == "core.image" })
    }

    @Test
    fun paramsAreCheckedAndWritten() {
        opened("txt2img")
        val sampler = vm.canvas.workflow.graph.nodes.first { it.type in com.abrah.nightmare.IMAGE_SAMPLER_TYPES }.id
        assertTrue(call("set_params", """{"steps":12,"params":{"$sampler":{"seed":"7"}}}""").ok)
        val p = vm.canvas.workflow.graph.byId[sampler]!!.params
        assertEquals("12", p["steps"])
        assertEquals("7", p["seed"])
        assertFalse(call("set_params", """{"params":{"$sampler":{"colour":"red"}}}""").ok)
    }

    @Test
    fun nodesAreAddedWiredAndRemoved() {
        opened("txt2img")
        val id = call("add_node", """{"type":"core.image"}""").json.getString("id")
        assertNotNull(vm.canvas.workflow.graph.byId[id])
        val sampler = vm.canvas.workflow.graph.nodes.first { it.type in com.abrah.nightmare.IMAGE_SAMPLER_TYPES }.id
        assertTrue(call("connect", """{"from":"$id","to":"$sampler","port":"image"}""").ok)
        assertEquals(id, vm.canvas.workflow.graph.byId[sampler]!!.inputs["image"]?.node)
        assertFalse("a port the node has not got", call("connect", """{"from":"$id","to":"$sampler","port":"nope"}""").ok)
        assertTrue(call("remove_node", """{"id":"$id"}""").ok)
        assertNull(vm.canvas.workflow.graph.byId[id])
        assertNull(vm.canvas.workflow.graph.byId[sampler]!!.inputs["image"])
    }

    @Test
    fun theCanvasReadsBackWithItsSchema() {
        opened("txt2img")
        val c = call("get_canvas").json
        assertTrue(c.getJSONArray("nodes").length() >= 3)
        assertTrue(c.has("unsaved"))
    }

    @Test
    fun aLoraNotInstalledIsNotDeletedAndNothingIsAsked() {
        assertFalse(call("delete_lora", """{"name":"nope.safetensors"}""").ok)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun theOneRuleHoldsForCanvasEditsToo() {
        opened("txt2img")
        assertFalse(call("set_params", """{"prompt":"nude loli"}""").ok)
    }
}
