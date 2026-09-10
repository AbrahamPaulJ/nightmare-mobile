package com.abrah.nightmare

import java.io.File
import java.util.zip.ZipInputStream

/**
 * Installs a plugin from a zip — the shape a *downloaded* plugin arrives in.
 *
 * ⚠⚠ This is the first code in the project that unpacks an archive somebody
 * else made, and unpacking is where archive handling goes wrong. Three limits
 * below are not defensive programming, they are the actual attack surface:
 *
 *  1. **Zip slip.** An entry named `../../../databases/x` walks out of the
 *     destination and overwrites app files. The check is on the RESOLVED
 *     canonical path, not on the name, because `a/../../b` looks harmless.
 *  2. **Zip bombs.** A few KB can expand to gigabytes. Entry count and total
 *     written bytes are both capped, and the cap is checked WHILE writing —
 *     an entry's declared size is attacker-controlled and can simply lie.
 *  3. **Absolute and rooted names**, which some zip writers emit and which
 *     resolve outside the destination on their own.
 *
 * ⚠ The manifest is parsed BEFORE anything lands in the plugins directory: a
 * zip is unpacked to a staging directory, validated, and only then moved into
 * place under the id its manifest declares. So a malformed pack never becomes a
 * half-installed plugin that the loader trips over later.
 *
 * ⚠ This does not download anything, and that is deliberate: where a plugin may
 * COME from is an open policy question (`notes/HANDOFF.md` §7, `ARCHITECTURE`
 * §7), and building a downloader would answer it by accident.
 */
object PluginInstaller {

    /** Generous for two text files; small enough that a bomb cannot finish. */
    const val MAX_ENTRIES = 64
    const val MAX_TOTAL_BYTES = 8L * 1024 * 1024

    class Refused(message: String) : Exception(message)

    /**
     * Unpack [zip] and install it under [pluginsDir], named by its manifest id.
     *
     * @return the installed directory.
     */
    fun install(zip: File, pluginsDir: File, staging: File): File {
        if (!zip.isFile) throw Refused("no such file: ${zip.absolutePath}")

        // ⚠⚠ Checked FIRST, and by name. On this device the plugins directory
        // is typically created by `adb push`, which leaves it owned by `shell`
        // with the app as *other* -- so the app can read the packs it was given
        // and cannot create a new one beside them. Without this check the
        // failure is `FileNotFoundException: .../index.js: open failed: ENOENT`
        // from inside a copy, which points at a file rather than at the
        // permission that is actually wrong. Measured 2026-09-08.
        pluginsDir.mkdirs()
        if (!pluginsDir.isDirectory || !pluginsDir.canWrite()) {
            throw Refused(
                "cannot write to ${pluginsDir.absolutePath} -- installing needs it " +
                    "app-writable (chmod a+rwX), unlike merely reading pushed packs"
            )
        }

        val stage = File(staging, "install-" + System.nanoTime())
        try {
            unpack(zip, stage)

            // Parsed here, before the move. ⚠ A pack that fails validation must
            // not exist in the plugins directory at all, not even briefly:
            // `plugin_dir` walks that directory and would load whatever is
            // sitting in it.
            val plugin = Plugin.fromDir(stage)

            // ⚠ The directory is named by the MANIFEST id, not by the zip. Two
            // downloads of the same plugin must be an upgrade, not two copies
            // that both register the same node type -- and the zip's filename
            // is chosen by whoever served it.
            require(plugin.id.matches(SAFE_ID)) {
                "plugin id \"${plugin.id}\" is not a safe directory name"
            }
            val dest = File(pluginsDir, plugin.id)
            if (dest.exists()) dest.deleteRecursively()
            if (!stage.renameTo(dest)) {
                // ⚠ rename fails across filesystems. Copy is the fallback, not
                // the default: a rename is atomic and a copy is not, so a
                // process death mid-copy leaves a partial plugin.
                stage.copyRecursively(dest, overwrite = true)
            }
            return dest
        } finally {
            stage.deleteRecursively()
        }
    }

    private fun unpack(zip: File, dest: File) {
        dest.mkdirs()
        val root = dest.canonicalFile
        var entries = 0
        var written = 0L

        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (++entries > MAX_ENTRIES) {
                    throw Refused("archive has more than $MAX_ENTRIES entries")
                }
                val name = e.name
                if (name.startsWith("/") || name.startsWith("\\") || name.contains("..")) {
                    throw Refused("unsafe entry name \"$name\"")
                }
                val out = File(dest, name)
                // ⚠⚠ The real check: where does it actually resolve? The name
                // test above is a cheap early out, this is the one that holds.
                if (!out.canonicalFile.toPath().startsWith(root.toPath())) {
                    throw Refused("entry \"$name\" escapes the destination")
                }
                if (e.isDirectory) {
                    out.mkdirs()
                    zin.closeEntry()
                    continue
                }
                out.parentFile?.mkdirs()
                out.outputStream().buffered().use { fos ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = zin.read(buf)
                        if (n <= 0) break
                        written += n
                        // ⚠ Checked while writing, not from e.size: the
                        // declared size is whatever the archive says it is.
                        if (written > MAX_TOTAL_BYTES) {
                            throw Refused(
                                "archive expands past ${MAX_TOTAL_BYTES / 1024 / 1024} MB"
                            )
                        }
                        fos.write(buf, 0, n)
                    }
                }
                zin.closeEntry()
            }
        }
        if (entries == 0) throw Refused("archive is empty")

        // ⚠ Some archives wrap everything in a single top folder ("pack/") and
        // some do not. Flattening one level when it is the ONLY entry makes
        // both shapes work, which matters because the person zipping is a
        // contributor and neither shape is wrong.
        val kids = dest.listFiles().orEmpty()
        val onlyDir = kids.singleOrNull()?.takeIf { it.isDirectory }
        if (onlyDir != null && File(onlyDir, "node.json").isFile) {
            for (f in onlyDir.listFiles().orEmpty()) {
                f.renameTo(File(dest, f.name))
            }
            onlyDir.delete()
        }
    }

    /** Letters, digits, dot, dash, underscore — nothing that means anything to a path. */
    private val SAFE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
}
