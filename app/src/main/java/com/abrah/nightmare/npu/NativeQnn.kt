package com.abrah.nightmare.npu

/**
 * ⭐⭐ The app's second route to the NPU: a QNN context binary run **in this
 * process**, with no backend server and no checkpoint bound at launch.
 *
 * ⚠⚠ It does not replace [com.abrah.nightmare.BackendProcess]. That is a
 * separate process holding one `(type, model, resolution)` key for its whole
 * life (`docs/ARCHITECTURE.md` §4); this loads one context binary at a time and
 * joins no key at all — the `/upscale` pattern, minus the HTTP hop. Which one a
 * node uses is decided by what the node is: an SD graph needs the resident
 * pipeline, a standalone context binary does not.
 *
 * ⚠ Every call is by tensor **NAME**, matched against the binary's own
 * metadata. The converter is free to reorder graph inputs, so binding by
 * position feeds the wrong tensor — and a wrong tensor does not fail, it
 * produces a plausible picture.
 *
 * ⚠⚠ **Nothing here is safe to call from the main thread.** [load] deserialises
 * up to 1.5 GB and [execute] is the render. The native side takes one global
 * lock, so concurrent calls serialise rather than race.
 *
 * ⇒ `app/src/main/cpp/nmqnn.cpp` for why in-process at all, and
 * `docs/NEODRAGON.md` for what runs on it.
 */
object NativeQnn {

    @Volatile
    private var loaded = false

    /**
     * ⚠ Idempotent and cheap after the first call — but the FIRST call maps a
     * 400 KB `.so`, so it is not free inside a render loop.
     */
    @Synchronized
    fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("nmqnn")
            loaded = true
        }
    }

    /**
     * Bring the QNN backend up: dlopen it, create a device, pin burst clocks.
     *
     * ⚠⚠ [skelDir] is not decoration. The Hexagon skel is found through
     * `ADSP_LIBRARY_PATH`, which fastrpc reads **when it is first loaded** — so
     * the native side sets it before the dlopen, and a wrong directory here is
     * "Failed to load skel, error 4000" with nothing pointing at the cause.
     *
     * ⇒ Pass `BackendProcess.prepareRuntime(context)`, which is already where
     * this device's arch trio is unpacked.
     */
    @JvmStatic
    external fun init(backendLib: String, systemLib: String, skelDir: String): Boolean

    /** Load a context binary and keep it resident. ⚠ **0 means failure** — [lastError]. */
    @JvmStatic
    external fun load(binPath: String): Long

    /**
     * `in <name>:d0,d1,…:<dtype>;out …;` — the binary's own metadata.
     *
     * ⭐ This is how a caller learns the SHAPE of something it must supply but
     * cannot know: the streaming VAE decoder's nine state tensors are sized
     * from here rather than from a table that would rot against the weights.
     */
    @JvmStatic
    external fun describe(handle: Long): String

    /** Output names, in the order [execute] returns them. */
    @JvmStatic
    external fun outputNames(handle: Long): Array<String>

    /**
     * Execute. [names] must name **every** graph input; the arrays are real
     * (fp32) values and the native side quantises each one with that tensor's
     * own encoding.
     *
     * ⚠ Returns null on failure, with the reason in [lastError].
     */
    @JvmStatic
    external fun execute(
        handle: Long,
        names: Array<String>,
        data: Array<FloatArray>,
    ): Array<FloatArray>?

    /** ⚠ Frees the context. A handle used after this is a crash, not an error. */
    @JvmStatic
    external fun free(handle: Long)

    /**
     * Why the last call failed.
     *
     * ⚠ Global and not per-handle, so read it immediately — the next failing
     * call on any thread overwrites it.
     */
    @JvmStatic
    external fun lastError(): String
}
