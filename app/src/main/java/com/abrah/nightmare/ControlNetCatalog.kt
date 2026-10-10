package com.abrah.nightmare

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * ⭐⭐ Where SD 1.5 Swap's ControlNets come from: our own QNN builds, hosted at
 * [REPO] (the user's go-ahead to host them, 2026-09-30), one file per TYPE and
 * chip TIER, installed as `models/_controlnet/<type>.bin` ([SwapInputs.controlnetFile]).
 *
 * ⚠ The tier is the phone's, not a preference: `8gen2` is a Hexagon v73 context
 * (8 Gen 2 and newer — newer chips load older-arch contexts), `8gen1` a v69 one with 8 MB
 * VTCM (8 Gen 1 / 8+ Gen 1 — built 2026-10-09 after an 8 Gen 1 user's `_min` canny failed
 * to load), `min` a v68 one with 2 MB VTCM for the rest below v73 (888). A type with no build for
 * this phone's tier is offered as "not available for this chip" — never a
 * download that fails at load. ⚠⚠ Both are QAIRT 2.49 contexts: they need fp16
 * on the NPU, which 8s Gen 4 (SM8735) lacks.
 *
 * Installed = the file is there AND its size is the size of THIS tier's build,
 * so a file from another source (AI Hub's v79-only canny, found on the dev phone)
 * reads as not current and is replaced by a download, not trusted.
 */
object ControlNetCatalog {

    private const val TAG = "ControlNetCatalog"

    const val REPO = "AbrahamPJ/nightmare-sd15-controlnet-qnn"
    private const val BASE = "https://huggingface.co/$REPO/resolve/main"

    /** ⭐ SDXL Swap's ControlNets (backend 023): QAIRT 2.50 contexts per Hexagon arch. */
    const val SDXL_REPO = "AbrahamPJ/nightmare-sdxl-controlnet-qnn"
    private const val SDXL_BASE = "https://huggingface.co/$SDXL_REPO/resolve/main"

    data class Build(val path: String, val bytes: Long)

    /**
     * One TYPE for one family: its builds per tier. A missing tier = not offered on those chips.
     * ⭐ SD 1.5's are by tier ([v73] / [min]); SDXL's by exact arch ([byArch]: 75, 79, 81 — a
     * phone takes the highest at or below its own).
     */
    data class Entry(
        val type: String,
        val label: String,
        val v73: Build?,
        val min: Build?,
        /** ⭐ The v69 / 8 MB tier (8 Gen 1), ahead of [min] on those phones. */
        val v69: Build? = null,
        val family: Family = Family.SD15_SWAP,
        val byArch: Map<Int, Build> = emptyMap(),
        val base: String = BASE,
    ) {
        /** ⭐ The key of its file, its download row and its install — [idFor]. */
        val id: String get() = idFor(family, type)
    }

    /**
     * ⭐⭐ One ControlNet = one id: SD 1.5's keep their bare type (`canny`, the files and rows
     * that already exist), SDXL's are `sdxl_<type>` — a different network for the same idea.
     */
    fun idFor(family: Family?, type: String): String =
        if (family == Family.SDXL_SWAP) "sdxl_$type" else type

    val ENTRIES = listOf(
        // ⭐ Our own build (2026-09-30) — AI Hub's canny is v79-only.
        Entry(
            SwapInputs.CANNY, "Canny ControlNet",
            v73 = Build("canny/controlnet_8gen2.bin", 370_987_120L),
            min = Build("canny/controlnet_min.bin", 373_538_888L),
            v69 = Build("canny/controlnet_8gen1.bin", 369_672_304L),
        ),
        Entry(
            SwapInputs.DEPTH, "Depth ControlNet",
            v73 = Build("depth/controlnet_8gen2.bin", 371_146_864L),
            min = Build("depth/controlnet_min.bin", 373_518_408L),
            v69 = Build("depth/controlnet_8gen1.bin", 369_705_072L),
        ),
        Entry(
            SwapInputs.OPENPOSE, "Openpose ControlNet",
            v73 = Build("openpose/controlnet_8gen2.bin", 370_966_640L),
            min = null,
            v69 = Build("openpose/controlnet_8gen1.bin", 369_692_784L),
        ),
        // ⭐⭐ SDXL (npuforge `docs/SDXL-SWAP-TEMPLATE.md` §7b; hosted 2026-10-07): canny and depth
        // openrail++ as their diffusers sources; openpose is thibaud's, whose card defers to
        // OpenPose's (non-commercial) licence — hosted at the user's call, 2026-10-07.
        Entry(
            SwapInputs.CANNY, "SDXL Canny ControlNet", v73 = null, min = null,
            family = Family.SDXL_SWAP, base = SDXL_BASE,
            byArch = mapOf(
                75 to Build("canny/controlnet_v75.bin", 1_287_945_272L),
                79 to Build("canny/controlnet_v79.bin", 1_286_491_192L),
                81 to Build("canny/controlnet_v81.bin", 1_294_216_248L),
            ),
        ),
        Entry(
            SwapInputs.DEPTH, "SDXL Depth ControlNet", v73 = null, min = null,
            family = Family.SDXL_SWAP, base = SDXL_BASE,
            byArch = mapOf(
                75 to Build("depth/controlnet_v75.bin", 1_288_334_392L),
                79 to Build("depth/controlnet_v79.bin", 1_286_433_848L),
                81 to Build("depth/controlnet_v81.bin", 1_294_036_024L),
            ),
        ),
        Entry(
            SwapInputs.OPENPOSE, "SDXL Openpose ControlNet", v73 = null, min = null,
            family = Family.SDXL_SWAP, base = SDXL_BASE,
            byArch = mapOf(
                75 to Build("openpose/controlnet_v75.bin", 1_287_969_848L),
                79 to Build("openpose/controlnet_v79.bin", 1_286_593_592L),
                81 to Build("openpose/controlnet_v81.bin", 1_294_060_600L),
            ),
        ),
    )

    /** The entry for [id] ([idFor]). */
    fun entry(id: String): Entry? = ENTRIES.firstOrNull { it.id == id }

    /** This phone's build of [id], or null when there is none for its chip. */
    fun buildFor(id: String, caps: DeviceProbe.Caps = DeviceProbe.caps()): Build? {
        val e = entry(id) ?: return null
        // ⚠ A known chip with no Skel here (an 8 Elite Gen 6) runs no context at all.
        if (caps.known && !caps.staged) return null
        if (e.byArch.isNotEmpty()) {
            // ⚠ An UNKNOWN chip is offered the v79 build — the arch most SDXL Swap phones have.
            val arch = if (caps.known) caps.arch else 79
            return e.byArch.filterKeys { it <= arch }.maxByOrNull { it.key }?.value
        }
        // ⚠ An UNKNOWN chip is offered the v73 build (`DeviceProbe.Caps.known`'s rule
        // for flows: an unknown chip is offered everything).
        return when {
            !caps.known || caps.arch >= 73 -> e.v73 ?: e.min
            caps.arch >= 69 && caps.vtcmMb >= 8 -> e.v69 ?: e.min
            else -> e.min
        }
    }

    fun isInstalled(context: Context, type: String): Boolean {
        val b = buildFor(type) ?: return false
        return SwapInputs.controlnetFile(context, type).length() == b.bytes
    }

    fun bytesOnDisk(context: Context, type: String): Long = SwapInputs.controlnetFile(context, type).length()

    /** Fetch this phone's build of [type]. ⚠ Blocking — off the main thread. */
    fun install(
        context: Context,
        type: String,
        onProgress: (ModelInstaller.Progress) -> Unit,
        isCancelled: () -> Boolean = { false },
    ) {
        val b = buildFor(type) ?: throw IOException("no $type ControlNet for this phone's chip yet")
        val target = SwapInputs.controlnetFile(context, type)
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, "${target.name}.part")
        UpscalerCatalog.download("${entry(type)!!.base}/${b.path}", part, b.bytes, onProgress, isCancelled)
        if (part.length() != b.bytes) {
            val got = part.length()
            part.delete()
            throw IOException("${entry(type)?.label}: downloaded $got bytes, expected ${b.bytes}")
        }
        target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        Log.i(TAG, "installed $type (${b.path}, ${b.bytes} bytes)")
    }

    fun delete(context: Context, type: String) {
        SwapInputs.controlnetFile(context, type).delete()
    }
}
