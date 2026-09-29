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
 * (8 Gen 2 and newer — newer chips load older-arch contexts), `min` a v68 one
 * with 2 MB VTCM for the chips below v73 (888, 8 Gen 1). A type with no build for
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

    data class Build(val path: String, val bytes: Long)

    /** One TYPE: its builds per tier. A missing tier = not offered on those chips. */
    data class Entry(val type: String, val label: String, val v73: Build?, val min: Build?)

    val ENTRIES = listOf(
        Entry(
            SwapInputs.DEPTH, "Depth ControlNet",
            v73 = Build("depth/controlnet_8gen2.bin", 371_146_864L),
            min = Build("depth/controlnet_min.bin", 373_518_408L),
        ),
        Entry(
            SwapInputs.OPENPOSE, "Openpose ControlNet",
            v73 = Build("openpose/controlnet_8gen2.bin", 370_966_640L),
            min = null,
        ),
    )

    fun entry(type: String): Entry? = ENTRIES.firstOrNull { it.type == type }

    /** This phone's build of [type], or null when there is none for its chip. */
    fun buildFor(type: String, caps: DeviceProbe.Caps = DeviceProbe.caps()): Build? {
        val e = entry(type) ?: return null
        // ⚠ An UNKNOWN chip is offered the v73 build (`DeviceProbe.Caps.known`'s rule
        // for flows: an unknown chip is offered everything).
        return if (!caps.known || caps.arch >= 73) e.v73 ?: e.min else e.min
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
        UpscalerCatalog.download("$BASE/${b.path}", part, b.bytes, onProgress, isCancelled)
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
