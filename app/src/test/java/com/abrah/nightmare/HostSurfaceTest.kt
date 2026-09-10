package com.abrah.nightmare

import android.graphics.Bitmap
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The sandbox boundary.
 *
 * ⭐ `host.call` is the only native global a plugin gets, so this class is the
 * complete list of what a downloaded script can do to the device. These tests
 * are about the *edges* of that list — who is allowed to call what, and what
 * happens at the ends of the arguments — rather than about whether a resize
 * resizes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HostSurfaceTest {

    private val images = ImageStore()
    private val surface = HostSurface(images)

    private fun plugin(vararg permissions: String) = Plugin.parse(
        """
        {
          "id": "com.example.p", "version": "1", "api": 1,
          "nodes": [{ "type": "T", "outputs": [{ "name": "image", "type": "IMAGE" }] }],
          "permissions": [${permissions.joinToString(",") { "\"$it\"" }}]
        }
        """.trimIndent(),
        "// none",
    )

    private fun anImage(w: Int = 64, h: Int = 32): String {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF00FF00.toInt())
        return images.put(bmp)
    }

    // --- permissions -------------------------------------------------------

    @Test
    fun aDeclaredGroupIsAllowed() {
        val id = anImage()
        val out = surface.asPlugin(plugin("image")) {
            surface.call("image.info", JSONObject().put("image", id).toString())
        }
        assertEquals(64, JSONObject(out).getInt("width"))
    }

    /** ⚠ The message must name the permission to add, not just say no. */
    @Test
    fun anUndeclaredGroupIsDenied() {
        val id = anImage()
        val e = assertThrows(HostSurface.Denied::class.java) {
            surface.asPlugin(plugin()) {
                surface.call("image.info", JSONObject().put("image", id).toString())
            }
        }
        assertTrue(e.message!!, e.message!!.contains("\"image\" permission"))
        assertTrue(e.message!!, e.message!!.contains("node.json"))
    }

    /**
     * ⚠⚠ Default deny. A host call with no plugin running is an executor bug,
     * and allowing it "because it is probably ours" is the hole a plugin finds.
     */
    @Test
    fun aCallWithNoPluginRunningIsDenied() {
        val e = assertThrows(HostSurface.Denied::class.java) {
            surface.call("image.info", "{}")
        }
        assertTrue(e.message!!, e.message!!.contains("no plugin running"))
    }

    /** The caller is cleared even when the op throws. */
    @Test
    fun theCallerIsClearedAfterAFailedCall() {
        assertThrows(Exception::class.java) {
            surface.asPlugin(plugin("image")) { surface.call("image.info", "{}") }
        }
        assertThrows(HostSurface.Denied::class.java) { surface.call("image.info", "{}") }
    }

    /**
     * ⚠ Denied BEFORE the arguments are parsed. If the check ran after
     * validation, a denied plugin could still probe which handles exist by the
     * error it got back.
     */
    @Test
    fun denialBeatsArgumentValidation() {
        val e = assertThrows(HostSurface.Denied::class.java) {
            surface.asPlugin(plugin()) { surface.call("image.resize", "not even json") }
        }
        assertTrue(e.message!!, e.message!!.contains("without the"))
    }

    @Test
    fun anUnknownOpIsRefusedRatherThanIgnored() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            surface.asPlugin(plugin("image")) { surface.call("image.rotate", "{}") }
        }
        assertTrue(e.message!!, e.message!!.contains("unknown host op"))
    }

    /** A manifest asking for something this build cannot enforce is refused. */
    @Test
    fun anUnknownPermissionIsRefusedAtParse() {
        val e = assertThrows(IllegalArgumentException::class.java) { plugin("image", "net") }
        assertTrue(e.message!!, e.message!!.contains("net"))
    }

    // --- the ops themselves ------------------------------------------------

    @Test
    fun resizeProducesTheAskedForSize() {
        val id = anImage(64, 32)
        val out = surface.asPlugin(plugin("image")) {
            surface.call(
                "image.resize",
                JSONObject().put("image", id).put("width", 16).put("height", 8).toString(),
            )
        }
        val newId = JSONObject(out).getString("image")
        assertEquals(16, images.get(newId)!!.width)
        assertEquals(8, images.get(newId)!!.height)
    }

    /** ⚠ The numbers come from a script, so the ends of the range are real inputs. */
    @Test
    fun anAbsurdResizeIsRefused() {
        val id = anImage()
        val e = assertThrows(IllegalArgumentException::class.java) {
            surface.asPlugin(plugin("image")) {
                surface.call(
                    "image.resize",
                    JSONObject().put("image", id).put("width", 60000).put("height", 60000)
                        .toString(),
                )
            }
        }
        assertTrue(e.message!!, e.message!!.contains("out of range"))
    }

    @Test
    fun aCropOutsideTheSourceIsRefusedByName() {
        val id = anImage(64, 32)
        val e = assertThrows(IllegalArgumentException::class.java) {
            surface.asPlugin(plugin("image")) {
                surface.call(
                    "image.crop",
                    JSONObject().put("image", id).put("x", 40).put("y", 0)
                        .put("width", 40).put("height", 10).toString(),
                )
            }
        }
        assertTrue(e.message!!, e.message!!.contains("does not fit"))
    }

    @Test
    fun cropTakesTheRectangleAskedFor() {
        val id = anImage(64, 32)
        val out = surface.asPlugin(plugin("image")) {
            surface.call(
                "image.crop",
                JSONObject().put("image", id).put("x", 8).put("y", 4)
                    .put("width", 16).put("height", 16).toString(),
            )
        }
        val bmp = images.get(JSONObject(out).getString("image"))!!
        assertEquals(16, bmp.width)
        assertEquals(16, bmp.height)
    }

    /** An id the store never held is a named error, not a null dereference. */
    @Test
    fun anUnknownHandleIsRefusedByName() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            surface.asPlugin(plugin("image")) {
                surface.call("image.info", JSONObject().put("image", "img_nope").toString())
            }
        }
        assertTrue(e.message!!, e.message!!.contains("unknown image handle"))
    }

    /**
     * ⭐ Two identical resizes must yield the SAME handle, because ids are
     * content addresses — this is what lets the executor skip a downstream node
     * after its upstream was recomputed.
     */
    @Test
    fun identicalOpsYieldIdenticalHandles() {
        val id = anImage()
        fun resize() = surface.asPlugin(plugin("image")) {
            JSONObject(
                surface.call(
                    "image.resize",
                    JSONObject().put("image", id).put("width", 16).put("height", 8).toString(),
                )
            ).getString("image")
        }
        assertEquals(resize(), resize())
    }
}
