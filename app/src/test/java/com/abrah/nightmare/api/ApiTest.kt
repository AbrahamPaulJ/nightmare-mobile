package com.abrah.nightmare.api

import com.abrah.nightmare.Graph
import com.abrah.nightmare.NODE_TYPES
import com.abrah.nightmare.Node
import com.abrah.nightmare.SdSampler
import com.abrah.nightmare.sources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * ⭐⭐ The Nightmare API's pure halves (`docs/AGENT-API.md` §3): the server over a real socket,
 * who may connect, the token, and what a `/run` body does to a flow. ⚠ Robolectric for
 * `org.json`, which is a stub on the plain JVM.
 */
@RunWith(RobolectricTestRunner::class)
class ApiTest {

    // --- the server -------------------------------------------------------------------

    private fun <T> serving(handler: (MiniHttp.Request, MiniHttp.Response) -> Unit, body: (Int) -> T): T {
        val s = MiniHttp(0, handler)
        s.start()
        try {
            return body(s.boundPort)
        } finally {
            s.stop()
        }
    }

    private fun call(port: Int, path: String, method: String = "GET", body: String? = null): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream).bufferedReader().readText()
        return code to text
    }

    @Test
    fun aRequestReachesTheHandlerWithItsPathQueryAndBody() {
        serving({ req, res -> res.json(200, """{"m":"${req.method}","p":"${req.path}","q":"${req.query["a"]}","b":"${req.text()}"}""") }) { port ->
            val (code, text) = call(port, "/run?a=x%20y", "POST", "hello")
            assertEquals(200, code)
            assertEquals("""{"m":"POST","p":"/run","q":"x y","b":"hello"}""", text)
        }
    }

    @Test
    fun aHandlerThatThrowsIsA500NotAHang() {
        serving({ _, _ -> throw IllegalStateException("boom") }) { port ->
            val (code, text) = call(port, "/x")
            assertEquals(500, code)
            assertTrue(text.contains("boom"))
        }
    }

    @Test
    fun eventsStreamInOrder() {
        serving({ _, res ->
            val sse = res.sse()
            sse.event("node", "{\"node\":\"a\"}")
            sse.event("done", "{}")
        }) { port ->
            val (_, text) = call(port, "/run", "POST", "{}")
            assertEquals("event: node\ndata: {\"node\":\"a\"}\n\nevent: done\ndata: {}\n\n", text)
        }
    }

    @Test
    fun onlyLocalPeersMayConnect() {
        fun local(a: String) = MiniHttp.isLocalPeer(InetAddress.getByName(a))
        assertTrue(local("127.0.0.1"))
        assertTrue(local("192.168.1.20"))
        assertTrue(local("10.0.0.5"))
        assertTrue(local("172.16.3.4"))
        assertTrue("a tailnet address", local("100.101.102.103"))
        assertTrue(local("fd00::1"))
        assertFalse(local("8.8.8.8"))
        assertFalse("100.0/10 is not CGNAT", local("100.1.2.3"))
        assertFalse(local("2001:4860:4860::8888"))
    }

    @Test
    fun theTokenMustMatchExactly() {
        assertTrue(ApiSettings.matches("abc", "Bearer abc", null))
        assertTrue(ApiSettings.matches("abc", null, "abc"))
        assertFalse(ApiSettings.matches("abc", "Bearer abd", null))
        assertFalse(ApiSettings.matches("abc", null, null))
        assertFalse(ApiSettings.matches("abc", "Bearer ", null))
    }

    // --- a /run body ------------------------------------------------------------------

    private val sampler = SdSampler.SD15.name

    private fun flow() = Graph(
        listOf(
            Node("prompt", "core.prompt", params = mapOf("prompt" to "a cat", "negative" to "")),
            Node("image", "core.image", params = mapOf("uri" to "")),
            Node("generate", sampler, inputs = sources("prompt" to "prompt", "image" to "image")),
            Node("output", "core.output", inputs = sources("media" to "generate")),
        ),
    )

    private fun apply(json: String, picture: (String, String) -> String = { _, _ -> "/tmp/x" }) =
        applyOverrides(flow(), NODE_TYPES, RunRequest.parse(json), null, picture)

    private fun refused(json: String): String = try {
        apply(json)
        fail("expected a refusal for $json")
        ""
    } catch (e: ApiError) {
        assertEquals(400, e.code)
        e.message.orEmpty()
    }

    @Test
    fun theShortcutsReachThePromptAndTheSampler() {
        val g = apply("""{"flow":"img2img","prompt":"a dog","negative":"blur","seed":7,"steps":12,"cfg":4.5}""")
        assertEquals("a dog", g.byId["prompt"]!!.params["prompt"])
        assertEquals("blur", g.byId["prompt"]!!.params["negative"])
        assertEquals("7", g.byId["generate"]!!.params["seed"])
        assertEquals("12", g.byId["generate"]!!.params["steps"])
        assertEquals("4.5", g.byId["generate"]!!.params["cfg"])
    }

    @Test
    fun aPictureBecomesTheImageNodesFile() {
        val g = apply("""{"flow":"img2img","images":{"image":"AAAA"}}""") { node, b64 -> "/up/$node-$b64" }
        assertEquals("/up/image-AAAA", g.byId["image"]!!.params["uri"])
    }

    @Test
    fun everyMistakeIsNamedBeforeAnythingRuns() {
        assertTrue(refused("""{"flow":"x","params":{"nope":{"seed":"1"}}}""").contains("nope"))
        assertTrue(refused("""{"flow":"x","params":{"generate":{"colour":"red"}}}""").contains("colour"))
        assertTrue(refused("""{"flow":"x","steps":"many"}""").contains("number"))
        assertTrue(refused("""{"flow":"x","steps":1.5}""").contains("whole"))
        assertTrue(refused("""{"flow":"x","scheduler":"warp"}""").contains("one of"))
        assertTrue(refused("""{"flow":"x","images":{"generate":"AAAA"}}""").contains("not an image node"))
        assertTrue(refused("""{"flow":"x","colour":"red"}""").contains("unknown field"))
        assertTrue(refused("""{"prompt":"a"}""").contains("exactly one"))
    }
}
