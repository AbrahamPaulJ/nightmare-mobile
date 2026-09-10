package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Unpacking an archive somebody else made.
 *
 * ⭐ These are the tests worth having in this file. A plugin zip is the first
 * thing in the project that arrives from outside, and the failure modes are not
 * "it didn't install" — they are "it installed somewhere else" and "it filled
 * the disk".
 *
 * ⚠ Robolectric only for `org.json`, which the manifest parse needs.
 */
@RunWith(RobolectricTestRunner::class)
class PluginInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val manifest = """
        {
          "id": "com.example.pack", "version": "0.1.0", "api": 1,
          "nodes": [{
            "type": "Thing",
            "inputs":  [{ "name": "image", "type": "IMAGE" }],
            "outputs": [{ "name": "image", "type": "IMAGE" }]
          }]
        }
    """.trimIndent()

    private fun zip(name: String, entries: List<Pair<String, ByteArray>>): File {
        val f = tmp.newFile(name)
        ZipOutputStream(f.outputStream()).use { z ->
            for ((n, bytes) in entries) {
                z.putNextEntry(ZipEntry(n))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return f
    }

    private fun goodEntries(prefix: String = "") = listOf(
        "${prefix}node.json" to manifest.toByteArray(),
        "${prefix}index.js" to "// nothing".toByteArray(),
    )

    private fun install(f: File): File =
        PluginInstaller.install(f, tmp.newFolder("plugins"), tmp.newFolder("stage"))

    @Test
    fun aFlatZipInstallsUnderItsManifestId() {
        val dir = install(zip("p.zip", goodEntries()))
        assertEquals("com.example.pack", dir.name)
        assertTrue(File(dir, "node.json").isFile)
        assertTrue(File(dir, "index.js").isFile)
    }

    /**
     * ⚠ Both shapes are things a contributor will actually produce — "zip the
     * folder" and "zip the folder's contents" — and neither is wrong.
     */
    @Test
    fun aZipWrappedInOneFolderIsFlattened() {
        val dir = install(zip("p.zip", goodEntries("pack/")))
        assertTrue(File(dir, "node.json").isFile)
    }

    /**
     * ⚠ Named by the MANIFEST, not by the file. The zip's name is chosen by
     * whoever served it, so two downloads of one plugin must be an upgrade
     * rather than two copies registering the same node type.
     */
    @Test
    fun theZipFilenameDoesNotDecideTheDirectory() =
        assertEquals("com.example.pack", install(zip("whatever-v3-final.zip", goodEntries())).name)

    // --- the ones that matter ---------------------------------------------

    /**
     * ⚠⚠ Zip slip. An entry that walks out of the destination overwrites app
     * files — databases, other plugins, anything the process can write.
     */
    @Test
    fun anEntryEscapingTheDestinationIsRefused() {
        val bad = zip("evil.zip", goodEntries() + ("../../pwned.txt" to "x".toByteArray()))
        val e = assertThrows(PluginInstaller.Refused::class.java) { install(bad) }
        assertTrue(e.message!!, e.message!!.contains("unsafe entry"))
    }

    /** …including the absolute-path spelling of the same idea. */
    @Test
    fun anAbsoluteEntryNameIsRefused() {
        val bad = zip("evil2.zip", goodEntries() + ("/etc/passwd" to "x".toByteArray()))
        assertThrows(PluginInstaller.Refused::class.java) { install(bad) }
    }

    /**
     * ⚠⚠ A zip bomb. The cap is enforced while WRITING, because an entry's
     * declared size is whatever the archive claims.
     */
    @Test
    fun anArchiveThatExpandsTooFarIsRefused() {
        val big = ByteArray(PluginInstaller.MAX_TOTAL_BYTES.toInt() + 1024)
        val bad = zip("bomb.zip", goodEntries() + ("big.bin" to big))
        val e = assertThrows(PluginInstaller.Refused::class.java) { install(bad) }
        assertTrue(e.message!!, e.message!!.contains("expands past"))
    }

    @Test
    fun tooManyEntriesIsRefused() {
        val many = (0..PluginInstaller.MAX_ENTRIES + 1).map { "f$it.txt" to "x".toByteArray() }
        val e = assertThrows(PluginInstaller.Refused::class.java) { install(zip("many.zip", many)) }
        assertTrue(e.message!!, e.message!!.contains("more than"))
    }

    @Test
    fun anEmptyArchiveIsRefused() {
        assertThrows(PluginInstaller.Refused::class.java) { install(zip("empty.zip", emptyList())) }
    }

    /**
     * ⚠⚠ A pack that fails validation must not exist in the plugins directory
     * AT ALL — `plugin_dir` walks that directory and would try to load it.
     */
    @Test
    fun aZipWithNoManifestLeavesNothingBehind() {
        val plugins = tmp.newFolder("plugins2")
        val bad = zip("nomanifest.zip", listOf("index.js" to "// only".toByteArray()))
        assertThrows(IllegalArgumentException::class.java) {
            PluginInstaller.install(bad, plugins, tmp.newFolder("stage2"))
        }
        assertEquals(0, plugins.listFiles()?.size ?: 0)
    }

    /** A manifest the parser refuses must not install either. */
    @Test
    fun aFutureApiVersionLeavesNothingBehind() {
        val plugins = tmp.newFolder("plugins3")
        val bad = zip(
            "future.zip",
            listOf(
                "node.json" to manifest.replace("\"api\": 1", "\"api\": 99").toByteArray(),
                "index.js" to "// x".toByteArray(),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            PluginInstaller.install(bad, plugins, tmp.newFolder("stage3"))
        }
        assertEquals(0, plugins.listFiles()?.size ?: 0)
    }

    /** Reinstalling replaces, and does not leave the old files behind. */
    @Test
    fun reinstallingReplacesTheOldCopy() {
        val plugins = tmp.newFolder("plugins4")
        PluginInstaller.install(
            zip("v1.zip", goodEntries() + ("stale.txt" to "old".toByteArray())),
            plugins, tmp.newFolder("s1"),
        )
        val dir = PluginInstaller.install(zip("v2.zip", goodEntries()), plugins, tmp.newFolder("s2"))
        assertFalse("a file from the previous version survived", File(dir, "stale.txt").exists())
        assertEquals(1, plugins.listFiles()?.size ?: 0)
    }
}
