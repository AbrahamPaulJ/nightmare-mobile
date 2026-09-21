package com.abrah.nightmare

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ⭐⭐ The `loras` node param — names in, absolute paths and strengths out.
 *
 * ⚠⚠ A LoRA is named, never pathed. The engine can only open one from
 * `models/_loras` (`BackendProcess.lorasDir`), and a saved workflow carrying an
 * absolute path would also break the moment it reached another phone.
 */
class LoraParamTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun dirWith(vararg names: String): File {
        val d = tmp.newFolder("_loras")
        for (n in names) File(d, n).writeText("x")
        return d
    }

    @Test
    fun blankIsNoLoras() {
        val d = dirWith()
        assertEquals(emptyList<Pair<String, Double>>(), SdSampler.FLUX2.parseLoras(d, null))
        assertEquals(emptyList<Pair<String, Double>>(), SdSampler.FLUX2.parseLoras(d, "   "))
        // ⚠ A list of nothing but separators is still nothing, not an error.
        assertEquals(emptyList<Pair<String, Double>>(), SdSampler.FLUX2.parseLoras(d, " , , "))
    }

    /** ⚠ No strength means full strength, which is what every trainer's default is. */
    @Test
    fun aBareNameIsFullStrength() {
        val d = dirWith("style.safetensors")
        val out = SdSampler.FLUX2.parseLoras(d, "style.safetensors")
        assertEquals(1, out.size)
        assertEquals(File(d, "style.safetensors").absolutePath, out[0].first)
        assertEquals(1.0, out[0].second, 0.0)
    }

    @Test
    fun strengthComesAfterAnAt() {
        val d = dirWith("style.safetensors", "other.safetensors")
        val out = SdSampler.FLUX2.parseLoras(d, "style.safetensors@0.8, other.safetensors@1.5")
        assertEquals(listOf(0.8, 1.5), out.map { it.second })
    }

    /**
     * ⚠⚠ The LAST `@`, because a filename may contain one and a strength never
     * does. `v1@2.safetensors` is a NAME, not a name plus a strength.
     */
    @Test
    fun anAtInsideTheNameIsNotAStrength() {
        val d = dirWith("v1@2.safetensors")
        val out = SdSampler.FLUX2.parseLoras(d, "v1@2.safetensors")
        assertEquals(File(d, "v1@2.safetensors").absolutePath, out[0].first)
        assertEquals(1.0, out[0].second, 0.0)
    }

    /**
     * ⚠⚠⚠ A missing file is REFUSED, not skipped. The engine's answer to a path
     * it cannot open is to log `cannot register LoRA source` and then render
     * WITHOUT the adapter — a picture that looks like a success and is not the
     * one that was asked for. Naming the file is the only way it becomes visible.
     */
    @Test
    fun aMissingLoraIsRefusedByName() {
        val d = dirWith("present.safetensors")
        val e = runCatching {
            SdSampler.FLUX2.parseLoras(d, "present.safetensors, gone.safetensors")
        }.exceptionOrNull()
        assertTrue("expected a refusal, got $e", e != null)
        assertTrue("it has to name the file: ${e?.message}", e!!.message!!.contains("gone.safetensors"))
    }

    /**
     * ⚠⚠ A saved workflow is untrusted text. A name carrying `../` must not
     * reach outside the directory — the same shape `PluginInstaller` and the
     * embedding importer both guard against.
     */
    @Test
    fun aTraversingNameCannotEscapeTheDirectory() {
        val d = dirWith("style.safetensors")
        val outside = File(d.parentFile, "secret.safetensors").apply { writeText("x") }
        val e = runCatching {
            SdSampler.FLUX2.parseLoras(d, "../secret.safetensors")
        }.exceptionOrNull()
        assertTrue("traversal must not resolve, got $e", e != null)
        assertTrue("and the file outside is untouched", outside.isFile)
    }
}
