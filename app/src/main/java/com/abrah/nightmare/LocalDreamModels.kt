package com.abrah.nightmare

import android.os.Environment
import java.io.File

/**
 * ⭐⭐⭐ **Local Dream's models, used in place** (the user's call, 2026-10-09: read them, never
 * write or delete them). Local Dream v3.0.0-alpha.5 can keep its models in `Download/LocalDream`
 * (its PR #328, from this project's author) with the layout this app already uses —
 * `models/<id>/` — so a model downloaded there need not be downloaded twice.
 *
 * - A **catalogue** model whose id both apps share (the SD 1.5 five, Z-Image, FLUX.2 Klein 4B,
 *   Qwen 2.1, the upscalers) resolves to Local Dream's folder when this app's own copy is not
 *   complete there ([ModelSpec.dir]) — same files, same names, same sources.
 * - Local Dream's **imports** (its `SDXL` / `ZIMAGE` / `KLEIN` / `npucustom` markers) and its
 *   SDXL built-ins this catalogue lacks ([SDXL_BUILT_INS]) are scanned by [CustomModels.scan].
 *
 * ⚠ Nothing here is written or deleted ([ModelInstaller.delete] refuses). The ONE write that
 * remains is the backend's MNN tuning file in `<model>/cache/`, which Local Dream's own backend
 * writes in the same place (`MnnUtils.hpp`, `ensureCacheDir`).
 * ⚠ Needs All files access, as `Download/Nightmare` does ([ModelStorage.hasAccess]).
 */
object LocalDreamModels {
    /** ⚠ Local Dream's own folder name under `Download/` (`ModelStorage.PUBLIC_FOLDER` there). */
    const val FOLDER = "LocalDream"

    /** ⭐ Local Dream's SDXL built-ins this catalogue has no entry for — their family and name. */
    val SDXL_BUILT_INS = mapOf(
        "cyber_realistic_v10" to "CyberRealistic v10",
        "cyber_realistic_v10_dmd2" to "CyberRealistic v10 DMD2",
        "illustrious_v16" to "Illustrious v16",
        "illustrious_v16_dmd2" to "Illustrious v16 DMD2",
    )

    /** `Download/LocalDream/models`. ⚠ A test may point it elsewhere ([rootOverride]). */
    fun root(): File = rootOverride ?: File(
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER), "models",
    )

    @Volatile
    internal var rootOverride: File? = null

    /** ⭐ Readable at all: the access, and the folder. */
    fun available(): Boolean =
        (rootOverride != null || ModelStorage.hasAccess()) && runCatching { root().isDirectory }.getOrDefault(false)

    /** Local Dream's folder for [id], when there is one. */
    fun dirFor(id: String): File? =
        if (!available()) null else File(root(), id).takeIf { it.isDirectory }

    /** ⚠ True for anything inside Local Dream's folder — never ours to write or delete. */
    fun owns(dir: File): Boolean {
        val r = runCatching { root().canonicalPath }.getOrNull() ?: return false
        val d = runCatching { dir.canonicalPath }.getOrNull() ?: return false
        return d == r || d.startsWith(r + File.separator)
    }
}
