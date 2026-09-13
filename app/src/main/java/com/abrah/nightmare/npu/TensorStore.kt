package com.abrah.nightmare.npu

import java.security.MessageDigest

/**
 * ⭐⭐ The video path's intermediates, held app-side between nodes.
 *
 * ⚠⚠ **This is the third home for a tensor and it needs its own reason.**
 * `Value.Handle` names a tensor living in the BACKEND process; `ImageStore`
 * holds pixels. A video conditioning and a video latent are neither: they are
 * plain float arrays produced and consumed in-process by `libnmqnn.so`, and
 * there is no server holding them. Splitting the sampler into nodes is what
 * made them cross a wire at all (`docs/NEODRAGON.md` §8).
 *
 * ⚠ **Handles, not payloads**, for the same reason as everywhere else
 * (`docs/ARCHITECTURE.md` §6): a value crosses the executor, the cache and
 * potentially the JS boundary as ~40 bytes rather than as megabytes of array.
 *
 * ⚠⚠ **Ids are CONTENT ADDRESSES.** The executor's cache is keyed on them, so
 * a counter would make every re-run look like new data and quietly turn the
 * cache off — the exact trap `ImageStore` documents.
 *
 * ⚠⚠⚠ **Bounded, and small, because these are big.** A video latent at
 * 512×320 is `lat_c × units × 40 × 64` floats — tens of MB — where an image is
 * one. Four entries is two renders' worth of live values; beyond that the
 * executor re-runs the node, which is the right trade against being killed.
 */
object TensorStore {

    /** ⚠ Four, not twelve. See the class note: these are 100× an image. */
    private const val LIMIT = 4

    class Bundle(val named: Map<String, FloatArray>, val meta: Map<String, Int> = emptyMap()) {
        val bytes: Long get() = named.values.sumOf { it.size.toLong() * 4 }
    }

    /** Access-ordered: the least recently *used* goes first. */
    private val entries = LinkedHashMap<String, Bundle>(8, 0.75f, true)

    /**
     * @param seed everything the bundle was computed FROM, as a string. ⚠ It is
     *   what the id addresses: hashing the float arrays themselves would cost
     *   more than recomputing some of them, and two bundles made from the same
     *   inputs are interchangeable to a consumer whether or not their bits
     *   agree — which is the property the cache actually needs.
     */
    fun put(kind: String, seed: String, bundle: Bundle): String {
        val id = kind + "_" + sha(seed)
        synchronized(entries) {
            entries[id] = bundle
            while (entries.size > LIMIT) {
                val it = entries.keys.iterator()
                it.next()
                it.remove()
            }
        }
        return id
    }

    fun get(id: String): Bundle? = synchronized(entries) { entries[id] }

    operator fun contains(id: String): Boolean = synchronized(entries) { id in entries }

    /** ⚠ For a readout: what the intermediates are costing right now. */
    fun bytes(): Long = synchronized(entries) { entries.values.sumOf { it.bytes } }

    fun clear() = synchronized(entries) { entries.clear() }

    private fun sha(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
}
