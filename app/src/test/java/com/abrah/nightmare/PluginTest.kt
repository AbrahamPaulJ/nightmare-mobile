package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The manifest, and the widget defaults the cache key depends on.
 *
 * ⚠ Robolectric for `org.json` only — the stub `org.json` in a plain unit test
 * returns zeros from every method, so a parser test against it would pass
 * whatever the parser did.
 *
 * ⚠ [PluginNodeType.run] is not covered here: it needs a QuickJS runtime, which
 * needs an arm64 `.so`. The device's `plugin` op is what tests that.
 */
@RunWith(RobolectricTestRunner::class)
class PluginTest {

    private val manifest = """
        {
          "id": "com.example.pack", "version": "0.1.0", "api": 1,
          "nodes": [{
            "type": "Resize", "category": "image", "tier": 0,
            "inputs":  [{ "name": "image", "type": "IMAGE" }],
            "outputs": [{ "name": "image", "type": "IMAGE" }],
            "widgets": [
              { "name": "scale",  "type": "float", "default": 0.5 },
              { "name": "filter", "type": "string" }
            ]
          }],
          "permissions": ["image"]
        }
    """.trimIndent()

    private fun plugin(json: String = manifest) = Plugin.parse(json, "// no script")

    @Test
    fun aManifestParsesIntoNodesPortsAndWidgets() {
        val p = plugin()
        assertEquals("com.example.pack", p.id)
        assertEquals(listOf("image"), p.permissions)
        val spec = p.nodes.single()
        assertEquals("Resize", spec.type)
        assertEquals(0, spec.tier)
        assertEquals(listOf("image"), spec.inputs.map { it.name })
        assertEquals(listOf("image"), spec.outputs.map { it.name })
        assertEquals(listOf("scale", "filter"), spec.widgets.map { it.name })
    }

    /**
     * ⭐⭐ A manifest can declare a DROPDOWN and a HINT, not only a number.
     *
     * ⚠⚠ Both were silently dropped by the parser until 2026-09-10 — a
     * contributor writing `"options"` got a free-text box that accepts a typo
     * and fails at Run, and there was no way to say what a knob was for. The
     * inspector had rendered chips, dropdowns and hints for built-ins the whole
     * time; only the manifest could not reach them.
     */
    @Test
    fun aManifestCanDeclareOptionsAndAHint() {
        val p = plugin(
            """
            {
              "id": "com.example.pack", "version": "0.1.0", "api": 1,
              "nodes": [{
                "type": "Edge", "category": "image", "tier": 0,
                "inputs":  [{ "name": "image", "type": "IMAGE" }],
                "outputs": [{ "name": "image", "type": "IMAGE" }],
                "widgets": [
                  { "name": "mode", "type": "string", "default": "soft",
                    "options": ["soft", "hard"], "hint": "how the edge falls off" },
                  { "name": "amount", "type": "float", "default": 0.5,
                    "min": 0, "max": 1 }
                ]
              }],
              "permissions": ["image"]
            }
            """.trimIndent()
        )
        val w = p.nodes.single().widgets
        assertEquals(listOf("soft", "hard"), w[0].options)
        assertEquals("how the edge falls off", w[0].hint)
        // ⚠ …and a knob with BOTH bounds is what makes the inspector draw a
        // slider rather than a number pad. Declaring the range IS the control.
        assertEquals(0.0, w[1].min!!, 1e-9)
        assertEquals(1.0, w[1].max!!, 1e-9)
        // ⚠ Absent stays null rather than becoming an empty list: "no options"
        // and "an empty dropdown" are different, and one of them is a bug.
        assertEquals(null, w[1].options)
        assertEquals(null, w[1].hint)
    }

    /**
     * ⚠ Namespaced by plugin id, so two packs can both ship a `Resize` and a
     * saved workflow can say which one it meant.
     */
    @Test
    fun nodeTypesAreNamespacedByPluginId() =
        assertEquals("com.example.pack:Resize", plugin().nodes.single().qualified("com.example.pack"))

    /**
     * ⚠⚠ A JSON number must become the same string on every device. `0.5` read
     * as a double and formatted under a comma-decimal locale would key
     * differently from `0.5` typed by the user, and the symptom is a cache that
     * misses for one person and hits for everyone else.
     */
    @Test
    fun aNumericDefaultBecomesItsPlainString() =
        assertEquals("0.5", plugin().nodes.single().widgets.first().default)

    @Test
    fun aWidgetWithNoDefaultHasNone() =
        assertEquals(null, plugin().nodes.single().widgets[1].default)

    /** A newer manifest is refused by name, not adapted to. */
    @Test
    fun aFutureApiVersionIsRefused() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            plugin(manifest.replace("\"api\": 1", "\"api\": 2"))
        }
        assertTrue(e.message!!, e.message!!.contains("api 2"))
    }

    @Test
    fun aPluginWithNoNodesIsRefused() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            plugin(manifest.replace(Regex("\"nodes\": \\[.*\\],", RegexOption.DOT_MATCHES_ALL), "\"nodes\": [],"))
        }
        assertTrue(e.message!!, e.message!!.contains("no nodes"))
    }

    // --- the defaults that feed the cache key ------------------------------

    private val spec get() = plugin().nodes.single()

    /**
     * ⭐ The one that matters. A widget left alone must key identically to one
     * set explicitly to the same value, or the user sees a re-render for
     * touching nothing.
     */
    @Test
    fun anOmittedWidgetTakesItsDefault() {
        val implicit = applyDefaults(spec.widgets, Node("n", "t", mapOf("filter" to "bilinear")))
        val explicit = applyDefaults(
            spec.widgets, Node("n", "t", mapOf("scale" to "0.5", "filter" to "bilinear")),
        )
        assertEquals(explicit, implicit)
        assertEquals(
            cacheKey("t", "v", explicit, emptyMap()),
            cacheKey("t", "v", implicit, emptyMap()),
        )
    }

    @Test
    fun anExplicitValueBeatsTheDefault() =
        assertEquals(
            "0.25",
            applyDefaults(spec.widgets, Node("n", "t", mapOf("scale" to "0.25", "filter" to "x")))["scale"],
        )

    /** A required widget with no value is a malformed graph, named as such. */
    @Test
    fun aMissingWidgetWithNoDefaultIsRefused() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            applyDefaults(spec.widgets, Node("n", "t", emptyMap()))
        }
        assertTrue(e.message!!, e.message!!.contains("filter"))
    }

    /**
     * ⚠ Params the manifest never declared are kept, because they are still
     * hashed into the key. Dropping one would let two different graphs share a
     * cache entry.
     */
    @Test
    fun undeclaredParamsSurvive() {
        val out = applyDefaults(spec.widgets, Node("n", "t", mapOf("filter" to "x", "extra" to "7")))
        assertEquals("7", out["extra"])
    }
}
