package com.abrah.nightmare

import java.io.File

/**
 * ⭐⭐⭐ **What a converted model can take per render** — LoRA, ControlNet, IP-Adapter,
 * inpaint — read from the export's own `swap_features.json` (npuforge
 * `Converter.manifestJson`), never from its folder or zip name, which the user may change.
 *
 * ⭐ A Swap conversion keeps only the features ticked in npuforge; a dropped one is ABSENT
 * from the compiled graph (`docs/MODELS.md` §10), so its control on the node is dimmed and
 * says why (the user's call, 2026-10-07, `docs/UI.md` §8.18), and the run path ignores it
 * ([SdSampler]) — a dimmed tile can never change a render.
 *
 * Schema 1 (npuforge ≤ 1.0.11): `{"template":…,"features":[…]}`. Schema 2 (1.0.12+) adds
 * `producer`, `family`, `kind`, `prediction`, `text_tokens`, `size`, `soc` and `detail`;
 * only `features` is read here. ⚠ Parsed as JSON, unlike the backend's substring search
 * (patch 020) — which is why npuforge keeps `features` the LAST key.
 */
object ModelFeatures {
    const val FILE = "swap_features.json"

    const val LORA = "lora"
    const val CONTROLNET = "cn"
    const val IP_ADAPTER = "ip"
    const val INPAINT = "inp"

    /** The listed features, or null when there is no readable file. */
    fun read(dir: File): Set<String>? = runCatching {
        val arr = org.json.JSONObject(File(dir, FILE).readText()).getJSONArray("features")
        (0 until arr.length()).map { arr.getString(it) }.toSet()
    }.getOrNull()

    /**
     * ⭐ The features of the model in [dir]: its file, else what its family's older
     * exports always had — SD 1.5 Swap v1 (LoRA + ControlNet) and v2 (+ IP-Adapter, marked
     * by [IpAdapter.TARGETS_FILE]) predate the file and could not drop anything.
     */
    fun of(dir: File, family: Family): Set<String> = read(dir) ?: when (family) {
        Family.SD15_SWAP ->
            if (IpAdapter.supports(dir)) setOf(LORA, CONTROLNET, IP_ADAPTER) else setOf(LORA, CONTROLNET)
        else -> defaultFor(family)
    }

    /**
     * What a model of [family] has when nothing more is known — a built-in entry, whose
     * spec is fixed in [ModelCatalog]: the hosted SD 1.5 Swap zips are v2, the hosted SDXL
     * Swap ones LoRA-only (npuforge 1.0.11).
     */
    fun defaultFor(family: Family): Set<String> = when (family) {
        Family.SD15_SWAP -> setOf(LORA, CONTROLNET, IP_ADAPTER)
        Family.SDXL_SWAP -> setOf(LORA)
        else -> emptySet()
    }

    /**
     * ⭐ Why [spec]'s node cannot use [feature] — null when it can, or when the family has
     * no such control at all (that is not this function's question).
     */
    fun missingReason(spec: ModelSpec?, feature: String): String? {
        if (spec == null || spec.family != Family.SD15_SWAP && spec.family != Family.SDXL_SWAP) return null
        if (feature in spec.featureSet) return null
        val name = when (feature) {
            LORA -> "LoRA"
            CONTROLNET -> "ControlNet"
            IP_ADAPTER -> "IP-Adapter"
            else -> "inpaint"
        }
        return "This conversion has no $name. Convert it again in npuforge with $name ticked."
    }
}
