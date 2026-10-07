package com.abrah.nightmare

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * ⭐⭐ Download one LoRA version from the browser into `_loras`
 * (`docs/LORA-BROWSER.md`).
 *
 * ⚠⚠ Through [ModelInstaller.fetch] — the app's one downloader, with its resume
 * and size rules — never a second copy of them. What this adds is only what a
 * LoRA needs: the URL resolved per attempt ([LoraSources.resolve]), the SHA-256
 * the site publishes checked, and the trigger words saved as the LoRA's note.
 *
 * ⚠ Blocking. Call from `Dispatchers.IO`.
 */
object LoraInstall {

    /** @return the file name it was saved under in [BackendProcess.lorasDir]. */
    fun install(
        context: Context,
        version: LoraSources.Version,
        key: String?,
        onProgress: (ModelInstaller.Progress) -> Unit,
        isCancelled: () -> Boolean,
    ): String {
        val file = version.file ?: throw IOException("this version has no .safetensors file")
        val dir = BackendProcess.lorasDir(context).apply { mkdirs() }
        onProgress(ModelInstaller.Progress("connecting", 0, 0))
        val resolved = LoraSources.resolve(file, key)
        if (resolved.bytes > LoraSources.MAX_LORA_BYTES) {
            throw IOException("${resolved.bytes shr 20} MB is too large to be a LoRA")
        }

        val name = targetName(dir, file, version, resolved.bytes)
        val dest = File(dir, name)
        if (dest.isFile && dest.length() == resolved.bytes && matches(dest, file.sha256)) {
            saveTrigger(dir, name, version)
            return name
        }

        // ⚠ The part file sits in the downloads scratch dir, BESIDE the models
        // ([ModelCatalog.downloads]), so the finished file renames into place.
        val scratch = ModelCatalog.downloads(context).apply { mkdirs() }
        val part = File(scratch, "lora_${version.id.hashCode().toUInt()}_$name.part")
        ModelInstaller.requireFreeSpace(scratch, resolved.bytes - part.length().coerceAtMost(resolved.bytes))
        ModelInstaller.fetch(resolved.url, part, resolved.bytes, "downloading", onProgress, isCancelled)

        if (file.sha256 != null) {
            onProgress(ModelInstaller.Progress("verifying", resolved.bytes, resolved.bytes))
            val got = sha256(part, isCancelled)
            if (!got.equals(file.sha256, ignoreCase = true)) {
                // ⚠ Deleted, not kept for a resume: the bytes are wrong, and
                // resuming would only append to them.
                part.delete()
                throw IOException("the download is damaged (SHA-256 does not match). Download again.")
            }
        }
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
        saveTrigger(dir, name, version)
        return name
    }

    /**
     * ⭐ The file's own name — what a flow's `loras` param and the picker key
     * on. ⚠ A DIFFERENT file already under that name keeps its name; this one
     * gets the version id appended rather than overwriting someone's LoRA.
     */
    internal fun targetName(dir: File, file: LoraSources.LoraFile, version: LoraSources.Version, bytes: Long): String {
        val base = File(file.name).name.ifBlank { "lora_${version.id}.safetensors" }
        val existing = File(dir, base)
        if (!existing.exists() || existing.length() == bytes) return base
        val stem = base.removeSuffix(".safetensors")
        val safeId = version.id.substringAfterLast('/').filter { it.isLetterOrDigit() }.take(16)
        return "${stem}_$safeId.safetensors"
    }

    /**
     * ⭐ The trigger words become the LoRA's note ([LoraNotes]) — its first line
     * is what "Add to prompt" appends. ⚠ Never over a note the person wrote.
     */
    private fun saveTrigger(dir: File, name: String, version: LoraSources.Version) {
        if (version.trigger.isEmpty()) return
        if (LoraNotes.read(dir).containsKey(name)) return
        LoraNotes.write(dir, name, version.trigger.joinToString(", "))
    }

    private fun matches(f: File, sha: String?): Boolean = sha == null || sha256(f) { false }.equals(sha, ignoreCase = true)

    internal fun sha256(f: File, isCancelled: () -> Boolean): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                if (isCancelled()) throw ModelInstaller.Cancelled()
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
