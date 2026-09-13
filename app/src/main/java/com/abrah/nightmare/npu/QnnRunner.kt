package com.abrah.nightmare.npu

import android.content.Context
import android.util.Log
import com.abrah.nightmare.BackendProcess
import java.io.File

/**
 * ⭐⭐ A cache of loaded NPU graphs — the thing that makes running a context
 * binary from a node affordable.
 *
 * ⚠⚠ **Residency is the whole point.** `../Neodragon`'s video loop visits three
 * ~1.5 GB MMDiT contexts on every autoregressive unit; deserialising one per
 * call would cost more than the render. [handleOf] is paid once per binary and
 * the context stays live until [release]. Its residency probe had all three
 * co-resident in 3.58 GB with ~1.5 GB spare.
 *
 * ⚠ Keyed by **absolute path**, not by a short name. A second naming scheme
 * ("which suffix does this arch use?") is a thing that can disagree with the
 * download manifest, and the manifest is the only list that has to be right.
 *
 * ⚠⚠ Every method blocks and several of them for seconds. Call from a worker.
 *
 * ⇒ `docs/NEODRAGON.md`. The native side is `app/src/main/cpp/nmqnn.cpp`.
 */
class QnnRunner(private val context: Context) {

    private val handles = HashMap<String, Long>()

    @Volatile
    private var initialised = false

    /**
     * Bring the backend and the DSP up. Idempotent.
     *
     * ⭐ The libraries come from the **same** `filesDir/qnnruntime` the backend
     * process already unpacks ([BackendProcess.prepareRuntime]) — this device's
     * arch trio plus the two shared libs. Two consequences worth stating:
     *
     * ⚠⚠ It is **proven on this device**, which is not a small thing to inherit:
     * the backend process finds `libQnnHtpV79Skel.so` through that exact
     * directory today, so the Hexagon side is known to accept a skel living
     * there. The in-process path sets `ADSP_LIBRARY_PATH` where the backend
     * process sets `DSP_LIBRARY_PATH`, and both reach the same files.
     *
     * ⚠ It also means the arch follows the DEVICE, not the binary. A context
     * compiled for v79 running on a v81 phone uses the **V81** skel — which is
     * exactly the forward-compatibility `docs/DEVICES.md` §2 describes, and the
     * reason one conversion serves 8 Elite and 8 Elite Gen 5.
     */
    @Synchronized
    fun initBackend() {
        if (initialised) return
        NativeQnn.ensureLoaded()
        val runtime = BackendProcess.prepareRuntime(context)
        val ok = NativeQnn.init(
            File(runtime, "libQnnHtp.so").absolutePath,
            File(runtime, "libQnnSystem.so").absolutePath,
            runtime.absolutePath,
        )
        check(ok) { "QNN init failed: ${NativeQnn.lastError()}" }
        initialised = true
        Log.i(TAG, "in-process QNN ready (runtime ${runtime.absolutePath})")
    }

    /** Load (and cache) a context binary. ⚠ Throws rather than returning 0. */
    @Synchronized
    fun handleOf(bin: File): Long {
        initBackend()
        handles[bin.absolutePath]?.let { return it }
        check(bin.isFile && bin.length() > 0) { "no context binary at ${bin.absolutePath}" }
        val h = NativeQnn.load(bin.absolutePath)
        check(h != 0L) { "load ${bin.name} failed: ${NativeQnn.lastError()}" }
        handles[bin.absolutePath] = h
        Resident.add(bin)
        return h
    }

    /**
     * The binary's own tensor list: `in <name>:dims:dtype;out …;`.
     *
     * ⭐ The only honest source for a shape a caller must supply but cannot
     * know — the streaming VAE decoder's nine state tensors are sized from this
     * rather than from a table that would rot against the weights.
     */
    fun describe(bin: File): String = NativeQnn.describe(handleOf(bin))

    /**
     * Execute with named real-valued tensors, returning outputs by name.
     *
     * ⚠ Token ids go in as floats: every id up to 49407 is exact in fp32, and
     * the native side writes each one in the graph's actual dtype. ⚠⚠ That is
     * not a convenience — the old exec path needed `--use_native_input_files`
     * for integer inputs and had to omit it for quantised graphs, and getting
     * it backwards produced identical plausible output for every prompt.
     */
    fun run(bin: File, inputs: Map<String, FloatArray>): Map<String, FloatArray> {
        val h = handleOf(bin)
        val names = inputs.keys.toTypedArray()
        val data = names.map { inputs.getValue(it) }.toTypedArray()
        val out = NativeQnn.execute(h, names, data)
            ?: error("${bin.name}: execute failed: ${NativeQnn.lastError()}")
        val outNames = NativeQnn.outputNames(h)
        return outNames.indices.associate { outNames[it] to out[it] }
    }

    /** ⚠ Frees the context. The next [run] reloads it from flash. */
    @Synchronized
    fun release(bin: File) {
        handles.remove(bin.absolutePath)?.let { NativeQnn.free(it) }
        Resident.remove(bin)
    }

    @Synchronized
    fun releaseAll() {
        handles.values.forEach { NativeQnn.free(it) }
        handles.keys.forEach { Resident.remove(File(it)) }
        handles.clear()
    }

    /**
     * ⭐⭐ What the NPU is holding **right now**, across every runner in the
     * process — so the canvas can say so.
     *
     * ⚠⚠ **The load readout could not see this path at all.** It reads
     * `BackendProcess.launchedKey`, which is the checkpoint the backend SERVER
     * was launched with; an in-process context binds no key and launches no
     * server (`docs/NEODRAGON.md` §3). So through a whole video render the bar
     * named whichever SD checkpoint happened to be selected, marked `(idle)` —
     * true of that checkpoint and a lie about the machine. Reported from the
     * phone, 2026-09-13.
     *
     * ⚠ A counter per path, not a set of runners: a node builds a runner per
     * run and two can legitimately hold the same binary, so the entry survives
     * until the LAST holder frees it.
     *
     * ⚠ Process-wide state in an object, and it has to be: the readout runs
     * on the view model and the loads happen inside whichever node is running.
     */
    object Resident {
        private val counts = LinkedHashMap<String, Int>()

        @Synchronized
        internal fun add(bin: File) {
            counts[bin.name] = (counts[bin.name] ?: 0) + 1
        }

        @Synchronized
        internal fun remove(bin: File) {
            val n = (counts[bin.name] ?: 0) - 1
            if (n > 0) counts[bin.name] = n else counts.remove(bin.name)
        }

        /** How many context binaries are mapped. 0 when the NPU is idle. */
        @Synchronized
        fun count(): Int = counts.size

        /** ⚠ For a readout and a log line, newest last. */
        @Synchronized
        fun names(): List<String> = counts.keys.toList()
    }

    /** ⚠ For a readout: which binaries are holding memory right now. */
    @Synchronized
    fun resident(): List<String> = handles.keys.map { File(it).name }.sorted()

    // ---- by NAME ----------------------------------------------------------
    //
    // ⭐ The video pipeline names its graphs (`vaedecsn`, `mmdit_s0fs`) and lets
    // [NpuFiles] find the file. That is the layer where the arch suffix lives,
    // and keeping it there is what lets a v75 rebuild drop in without touching
    // a single call site (`docs/ROADMAP.md` §3c).
    //
    // ⚠ The path-keyed methods above are still the real ones; these resolve and
    // delegate. Two caches would be two answers to "is this graph resident".

    /**
     * ⚠ A name, not a descriptor. `../Neodragon`'s version carried input and
     * output name lists and a `nativeInput` flag; all three were vestigial by
     * the time it shipped — the binary's own metadata answers the first two and
     * the third belonged to the `qnn-net-run` path this replaced.
     */
    @JvmInline
    value class Graph(val name: String)

    fun fileOf(name: String): File = NpuFiles.contextFile(context, name)
        ?: error("model \"$name\" is not on this device (${NpuFiles.ctxDir(context)})")

    fun isReady(name: String): Boolean = NpuFiles.isReady(context, name)

    fun handleOf(name: String): Long = handleOf(fileOf(name))

    fun describe(name: String): String = describe(fileOf(name))

    fun release(name: String) {
        NpuFiles.contextFile(context, name)?.let { release(it) }
    }

    /**
     * Execute a named graph.
     *
     * ⚠ [intInputs] exists because token ids are integers and every id up to
     * 49407 is exact in fp32 — the native side writes each tensor in the dtype
     * the graph actually declares, so the conversion here is lossless and the
     * old "which flag do integer inputs need" question is gone.
     */
    fun run(
        g: Graph,
        floats: Map<String, FloatArray> = emptyMap(),
        intInputs: Map<String, IntArray> = emptyMap(),
    ): Map<String, FloatArray> {
        val all = LinkedHashMap<String, FloatArray>(floats)
        intInputs.forEach { (k, v) -> all[k] = FloatArray(v.size) { v[it].toFloat() } }
        return run(fileOf(g.name), all)
    }

    private companion object {
        const val TAG = "QnnRunner"
    }
}
