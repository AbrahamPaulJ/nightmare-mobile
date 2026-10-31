package com.abrah.nightmare

import android.content.Context
import java.io.File

/**
 * ⭐⭐ The DiT models on the GPU (OpenCL on Adreno, `docs/GPU-ROUTES.md` §8): for a phone whose NPU cannot run a DiT
 * ([BackendProcess.ditOnCpu]: below v73 everything went to the CPU, ~15-20 min a picture), and for anyone who turns
 * on Settings, Performance, Use the GPU for DiT models ([Prefs.ditGpu]). The user's call, 2026-10-31.
 *
 * Measured on the S25 (Adreno 830), sd-cli: Klein 4B 1024² 4 steps in 188 s with taef2 decoding on the CPU, against
 * Fancy AI's 4:27 on the same model and size; the screen kept drawing because `dit/080` cuts every GPU dispatch to
 * ~16 ms ([ENV]). Without those cuts one dispatch ran 85 ms and the phone lagged until the user stopped it.
 */
object DitGpu {

    /** ⚠ The GPU engine links the vendor's OpenCL driver: a phone without one (Pixels) never takes the route. */
    private val OPENCL = listOf("/vendor/lib64/libOpenCL.so", "/system/vendor/lib64/libOpenCL.so", "/system/lib64/libOpenCL.so")

    private val detected: Boolean by lazy { OPENCL.any { File(it).isFile } }

    /** ⚠ A test's answer in place of the phone's; null = the files on this device. */
    @androidx.annotation.VisibleForTesting
    internal var openClForTest: Boolean? = null

    val hasOpenCl: Boolean get() = openClForTest ?: detected

    /** ⭐ Whether this phone's DiT renders may use the GPU at all; the engine variant follows it ([DitEngine]). */
    fun wanted(caps: DeviceProbe.Caps = DeviceProbe.caps()): Boolean =
        hasOpenCl && (Prefs.ditGpu || BackendProcess.ditOnCpu(caps))

    /** ⭐ Whether [spec] renders on the GPU: [wanted] and a model measured there ([ModelSpec.gpu]). */
    fun runs(spec: ModelSpec?, caps: DeviceProbe.Caps = DeviceProbe.caps()): Boolean =
        spec != null && spec.gpu && wanted(caps)

    /** The DiT on the GPU; the text encoder and the (tiny) decoder on the CPU, as measured. */
    const val BACKEND = "diffusion=GPUOpenCL,te=CPU,vae=CPU"

    /**
     * ⭐ `dit/080`'s cuts at the measured settings: matmuls ≤ 15 GFLOP a dispatch (f16 weights x4), attention 4 heads x
     * 1,024 query rows, a 1 ms turn for the screen after every op; ~4 % slower than none (198 against 206 s of
     * sampling). The last two are the Adreno kernels' own switches, as the fork's numbers were measured.
     */
    val ENV: Map<String, String> = mapOf(
        "GGML_OPENCL_MM_MAX_GFLOP" to "15",
        "GGML_OPENCL_FA_SPLIT_HEADS" to "4",
        "GGML_OPENCL_FA_SPLIT_Q" to "1024",
        "GGML_OPENCL_YIELD_EVERY" to "1",
        "GGML_OPENCL_YIELD_US" to "1000",
        "GGML_OPENCL_Q4_0_DENSE_DP4A" to "1",
        "GGML_OPENCL_XMEM_SDPA" to "1",
    )

    /**
     * ⭐ A tiny decoder (madebyollin's TAE family, MIT) that decodes in place of the VAE (backend 052's `NM_DIT_TAESD`).
     * The VAE on this GPU took ~4 min at 1024² in 488 ms dispatches, ~7 min on the CPU; taef2 on the CPU 13.5 s, and
     * Klein's 512² picture looked the same by eye. [Prefs.ditTaesd] (on) turns it off for the full VAE.
     */
    data class Tae(val file: String, val url: String, val bytes: Long)

    /** For the FLUX.2 VAE (Klein). */
    val TAEF2 = Tae("taef2.safetensors", "https://huggingface.co/madebyollin/taef2/resolve/main/taef2.safetensors", 10_723_524L)

    /** For the FLUX.1 VAE Z-Image decodes with. */
    val TAEF1 = Tae(
        "taef1.safetensors", "https://huggingface.co/madebyollin/taef1/resolve/main/diffusion_pytorch_model.safetensors",
        9_848_636L,
    )

    val ALL = listOf(TAEF2, TAEF1)

    fun taeFor(spec: ModelSpec): Tae? = when (spec.family) {
        Family.FLUX2 -> TAEF2
        Family.ZIMAGE -> TAEF1
        else -> null
    }

    /** ⚠ Beside the engine, in internal storage ([BackendProcess.runtimeDir]): small, and fetched with it. */
    fun taeFile(context: Context, tae: Tae): File = File(BackendProcess.runtimeDir(context), tae.file)

    fun taesPresent(context: Context): Boolean = ALL.all { taeFile(context, it).length() == it.bytes }

    /** ⚠ Blocking. Each through a `.part` and a length check, as every other download. */
    fun installTaes(context: Context, onProgress: (ModelInstaller.Progress) -> Unit, isCancelled: () -> Boolean) {
        for (tae in ALL) {
            val dest = taeFile(context, tae)
            if (dest.length() == tae.bytes) continue
            val part = File(dest.path + ".part")
            UpscalerCatalog.download(Prefs.apply(tae.url), part, tae.bytes, onProgress, isCancelled)
            if (part.length() != tae.bytes) {
                val got = part.length()
                part.delete()
                throw java.io.IOException("${tae.file}: downloaded $got bytes, expected ${tae.bytes}")
            }
            if (!part.renameTo(dest)) throw java.io.IOException("${tae.file}: cannot move into place")
        }
    }

    /**
     * ⭐ The launch environment for [spec] on the GPU, or empty when it does not take the route: the device spec,
     * the cuts, and the tiny decoder when it is on and present.
     */
    fun launchEnv(context: Context, spec: ModelSpec?, caps: DeviceProbe.Caps = DeviceProbe.caps()): Map<String, String> {
        if (!runs(spec, caps)) return emptyMap()
        return buildMap {
            put("NM_DIT_BACKEND", BACKEND)
            putAll(ENV)
            val tae = spec?.let(::taeFor)?.let { taeFile(context, it) }
            if (Prefs.ditTaesd && tae != null && tae.isFile) put("NM_DIT_TAESD", tae.absolutePath)
            put("NM_DIT_THREADS", threads().toString())
            // im2col decodes faster (17 against 24 s, 4 threads) for a 1 GB bigger buffer: only where it fits.
            if (caps.ramBytes >= ROOMY_RAM) put("NM_DIT_VAE_DIRECT", "0")
        }
    }

    /**
     * The CPU threads for the text encoder and the decoder: the GPU leaves the cores free. sd-cli, Klein 1024²: 8
     * against the backend's default 4, text 12.2 against 18.9 s, taef2 12.0 against 17.3 s, the GPU steps unchanged.
     * ⚠ In the app both run slower (17.4 and 27.7 s): its backend sits in Android's `background` cpu cgroup while the
     * activity is not on screen.
     */
    fun threads(): Int = java.lang.Runtime.getRuntime().availableProcessors().coerceIn(4, 8)

    /** A 12 GB phone reports ~11 GiB; an 8 GB one ~7.5. */
    const val ROOMY_RAM = 10L shl 30
}
