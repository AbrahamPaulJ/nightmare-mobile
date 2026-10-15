package com.abrah.nightmare

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * ⭐⭐ **The LoRA picker's library view** — what a user asked for as "sorting like ComfyUI's LoRA
 * Manager" (2026-10-09): each installed LoRA with when it arrived, how often it is used, whether
 * it is a favourite, and the BASE MODEL it was trained for — read out of the file itself, so a
 * picker can sort by any of them and filter by base.
 *
 * ⚠ The base is read from the safetensors HEADER only (8 bytes + the JSON table, never the
 * weights): kohya's `ss_base_model_version` when it is there, else the tensor names. It is a
 * label to sort and filter by, never a gate — a wrong guess hides nothing ([Base.OTHER] shows in
 * "All"). Cached per name + size + date, so a picker opening re-reads nothing.
 */
object LoraLibrary {

    enum class Base(val label: String) {
        SD15("SD 1.5"), SDXL("SDXL"), FLUX("FLUX"), ZIMAGE("Z-Image"), QWEN("Qwen"), ANIMA("Anima"),
        KREA2("Krea 2"), WAN("Wan"), OTHER("Other");

        companion object {
            /** ⭐ The base a node's family takes LoRAs for — the filter a picker can offer first. */
            fun of(family: Family?): Base? = when (family) {
                Family.SD15, Family.SD15_SWAP -> SD15
                Family.SDXL, Family.SDXL_SWAP -> SDXL
                Family.FLUX2 -> FLUX
                Family.ZIMAGE -> ZIMAGE
                Family.QWEN21 -> QWEN
                Family.ANIMA_DIT -> ANIMA
                Family.KREA2 -> KREA2
                Family.WAN -> WAN
                else -> null
            }
        }
    }

    /** ⭐ [base] is what the library files it under: the user's choice ([setBase]) or [detected]. */
    data class Item(val name: String, val bytes: Long, val added: Long, val base: Base, val detected: Base = base)

    enum class Sort { NAME, NEWEST, MOST_USED, SIZE }

    private val cache = ConcurrentHashMap<String, Pair<String, Base>>()

    /**
     * ⭐ Every `.safetensors` in [dir], with its base: the one the user set ([overrides],
     * [baseOverrides]) or else the one its header says. ⚠ Reads headers: call off the main thread.
     */
    fun list(dir: File, overrides: Map<String, Base> = emptyMap()): List<Item> =
        dir.listFiles { f -> f.isFile && f.extension.equals("safetensors", ignoreCase = true) }.orEmpty()
            .map { f -> baseOf(f).let { d -> Item(f.name, f.length(), f.lastModified(), overrides[f.name] ?: d, d) } }

    fun baseOf(f: File): Base {
        val key = "${f.length()}:${f.lastModified()}"
        cache[f.name]?.takeIf { it.first == key }?.let { return it.second }
        val b = runCatching { classify(header(f)) }.getOrDefault(Base.OTHER)
        cache[f.name] = key to b
        return b
    }

    /** The JSON table at the head of a safetensors file. ⚠ Capped: a header is kilobytes, not gigabytes. */
    private fun header(f: File): String = RandomAccessFile(f, "r").use { r ->
        val len = ByteArray(8).also { r.readFully(it) }
            .foldIndexed(0L) { k, acc, b -> acc or ((b.toLong() and 0xFF) shl (8 * k)) }
        require(len in 2..(16L shl 20))
        val bytes = ByteArray(len.toInt()).also { r.readFully(it) }
        String(bytes, Charsets.UTF_8)
    }

    /** ⭐ Pure: the base from a header's text. Metadata first, then tensor names. */
    internal fun classify(h: String): Base {
        val meta = Regex("\"ss_base_model_version\"\\s*:\\s*\"([^\"]*)\"").find(h)?.groupValues?.get(1)?.lowercase().orEmpty()
        when {
            "sdxl" in meta -> return Base.SDXL
            meta.startsWith("sd_v1") || meta.startsWith("sd_1") -> return Base.SD15
            "flux" in meta -> return Base.FLUX
        }
        return when {
            "single_blocks" in h || "single_transformer_blocks" in h -> Base.FLUX
            "noise_refiner" in h || "context_refiner" in h -> Base.ZIMAGE
            "img_mlp" in h || "txt_mlp" in h -> Base.QWEN
            // ⚠ Anima (Cosmos blocks): the official LoRAs name `adaln_modulation_*`, kohya's
            // `lora_unet_blocks_N_cross_attn_k_proj` — checked on three real headers, 2026-10-13.
            "adaln_modulation" in h || "cross_attn_k_proj" in h || "cross_attn.k_proj" in h -> Base.ANIMA
            // ⭐ Z-Image (Lumina-style `layers.N.attention` + `feed_forward`), Krea 2 (`attn.wq`,
            // `txtfusion`), Wan (`self_attn.q` + `ffn.0`, after Anima's `_proj` names). A miss is
            // fixable by hand ([setBase]: a user asked, Telegram #6322, 2026-10-15).
            ZIMAGE_LAYERS.containsMatchIn(h) || "feed_forward.w" in h || "feed_forward_w" in h -> Base.ZIMAGE
            "attn.wq" in h || "attn_wq" in h || "txtfusion" in h -> Base.KREA2
            ("self_attn.q." in h || "self_attn_q." in h) && ("ffn.0" in h || "ffn_0" in h) -> Base.WAN
            "lora_te2_" in h || "text_encoder_2" in h || "lora_unet_input_blocks" in h || "lora_unet_output_blocks" in h -> Base.SDXL
            "lora_te_" in h || "lora_unet_down_blocks" in h || "lora_unet_up_blocks" in h || "unet.down_blocks" in h -> Base.SD15
            else -> Base.OTHER
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("lora_library", Context.MODE_PRIVATE)

    fun favourites(context: Context): Set<String> = prefs(context).getStringSet("favourites", emptySet()).orEmpty().toSet()

    fun setFavourite(context: Context, name: String, on: Boolean) {
        val now = favourites(context).let { if (on) it + name else it - name }
        prefs(context).edit().putStringSet("favourites", now).apply()
    }

    fun uses(context: Context): Map<String, Int> =
        prefs(context).all.filterKeys { it.startsWith(USE) }.mapNotNull { (k, v) -> (v as? Int)?.let { k.removePrefix(USE) to it } }.toMap()

    /** ⭐ Counted when a LoRA is ticked on a node — "most used" means most often chosen. */
    fun markUsed(context: Context, name: String) {
        val p = prefs(context)
        p.edit().putInt(USE + name, p.getInt(USE + name, 0) + 1).apply()
    }

    private const val USE = "used:"
    private const val BASE = "base:"
    private val ZIMAGE_LAYERS = Regex("layers[._][0-9]+[._]attention[._]")

    /** ⭐ The bases the user set by hand, by file name (the LoRA's ⋮). */
    fun baseOverrides(context: Context): Map<String, Base> =
        prefs(context).all.filterKeys { it.startsWith(BASE) }.mapNotNull { (k, v) ->
            (v as? String)?.let { s -> Base.entries.firstOrNull { it.name == s }?.let { k.removePrefix(BASE) to it } }
        }.toMap()

    /** ⭐ File [name] under [base]; null goes back to what its header says. */
    fun setBase(context: Context, name: String, base: Base?) {
        prefs(context).edit().apply { if (base == null) remove(BASE + name) else putString(BASE + name, base.name) }.apply()
    }

    private const val FOLDER = "folder:"

    /**
     * ⭐⭐ The folders LoRAs were put in, by file name (GitHub #36, the user's call 2026-10-15: user
     * folders inside a family's list, the files staying where they are). A folder exists while a
     * LoRA is in it, so there is no empty folder to keep or to delete.
     */
    fun folders(context: Context): Map<String, String> =
        prefs(context).all.filterKeys { it.startsWith(FOLDER) }
            .mapNotNull { (k, v) -> (v as? String)?.takeIf { it.isNotBlank() }?.let { k.removePrefix(FOLDER) to it } }.toMap()

    /** ⭐ Put [name] in [folder]; null or blank takes it out of every folder. */
    fun setFolder(context: Context, name: String, folder: String?) {
        prefs(context).edit().apply {
            if (folder.isNullOrBlank()) remove(FOLDER + name) else putString(FOLDER + name, folder.trim())
        }.apply()
    }

    /**
     * ⭐ Pure: [names] (already in the picker's order) as folders, alphabetical, each keeping that
     * order inside, then the ones in no folder. [folders] maps a LoRA to its folder.
     */
    fun grouped(names: List<String>, folders: Map<String, String>): Pair<List<Pair<String, List<String>>>, List<String>> {
        val inFolder = names.filter { folders[it] != null }
        val groups = inFolder.groupBy { folders.getValue(it) }.toList().sortedBy { it.first.lowercase() }
        return groups to names.filter { folders[it] == null }
    }

    /** ⭐ Pure: the folders already used by LoRAs of [base] (null = any), for the ⋮ dialog to offer. */
    fun foldersFor(items: List<Item>, folders: Map<String, String>, base: Base?): List<String> =
        items.filter { base == null || it.base == base }.mapNotNull { folders[it.name] }.distinct().sortedBy { it.lowercase() }

    /**
     * ⭐ Pure: [items] filtered to [base] (null = all) and [query], favourites first, then by [sort].
     * ⚠ The picker puts the CHOSEN ones above this, in their own order — the order the engine
     * applies them in is not the user's sort.
     */
    fun arrange(
        items: List<Item>, sort: Sort, base: Base?, query: String,
        favourites: Set<String>, uses: Map<String, Int>,
    ): List<Item> {
        val q = query.trim().lowercase()
        val order: Comparator<Item> = when (sort) {
            Sort.NAME -> compareBy { it.name.lowercase() }
            Sort.NEWEST -> compareByDescending<Item> { it.added }.thenBy { it.name.lowercase() }
            Sort.MOST_USED -> compareByDescending<Item> { uses[it.name] ?: 0 }.thenBy { it.name.lowercase() }
            Sort.SIZE -> compareByDescending<Item> { it.bytes }.thenBy { it.name.lowercase() }
        }
        return items
            .filter { base == null || it.base == base }
            .filter { q.isEmpty() || q in it.name.lowercase() }
            .sortedWith(compareByDescending<Item> { it.name in favourites }.then(order))
    }
}
