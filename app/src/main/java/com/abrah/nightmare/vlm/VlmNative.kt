package com.abrah.nightmare.vlm

/**
 * ⭐ `libnmvlm.so` (`engines/vlm/nmvlm.cpp`): llama.cpp's mtmd behind our own JNI bridge. ⚠ Loaded ONLY in the `:vlm`
 * process ([VlmService]), never the app's: fastrpc reads `ADSP_LIBRARY_PATH` once per process and the in-process
 * Neodragon runner sets its own, and ending that process is how the ~4 GB model is freed.
 */
internal object VlmNative {

    /** The answer as it streams, UTF-8 cut on whole characters; return false to stop. ⚠ Called from C (R8 keep rule). */
    fun interface Sink {
        fun onText(utf8: ByteArray): Boolean
    }

    @Volatile
    private var loaded = false

    /** ⚠ System.load, not loadLibrary: the engine is downloaded into the app's files ([VlmEngine]), not the APK. */
    fun load(so: String) = synchronized(this) {
        if (!loaded) {
            System.load(so)
            loaded = true
        }
    }

    @JvmStatic external fun nativeInit(skelDir: String, devices: String): Boolean
    @JvmStatic external fun nativeLastError(): String
    @JvmStatic external fun nativeLoad(
        model: String, mmproj: String, nCtx: Int, nThreads: Int, npuLayers: Int, visionOnNpu: Boolean,
        imageTokens: Int,
    ): Long

    @JvmStatic external fun nativeDescribe(
        handle: Long, rgb: ByteArray, w: Int, h: Int, system: String, user: String, maxTokens: Int,
        temp: Float, topK: Int, topP: Float, seed: Int, sink: Sink?,
    ): ByteArray?

    @JvmStatic external fun nativeFree(handle: Long)
}
