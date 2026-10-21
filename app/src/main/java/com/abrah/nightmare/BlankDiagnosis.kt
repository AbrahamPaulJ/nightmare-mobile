package com.abrah.nightmare

/**
 * ⭐⭐⭐ **Diagnose: which part of the NPU path turns a DiT render into one flat colour.**
 *
 * The user's call, 2026-10-17: the 8s Gen 4 renders every DiT model solid grey (`docs/DEVICES.md` §3b), and
 * refusing the models there would stop the very renders whose logs can find the fault. So a blank picture's
 * report offers Diagnose: the same model re-rendered small ([SIZE], [STEPS] steps, seed [SEED]) once per [LEGS]
 * entry, each with ONE thing moved off the NPU or switched off, and each verdict written into the report.
 *
 * ⭐ It names the PART (text encoder, DiT, decode) and the KIND of op (HMX matmuls, fusions, an op group): the
 * way the v75 `CONV_2D_UPSCALE` bug was found by hand (`docs/DIT.md` §9e). ⚠ Not the exact node: that is the
 * NaN probe's job (`dit/spike-nan-probe`), and this says whether it is needed.
 *
 * ⭐ **Or into noise** (1.6.193): Iris on an 8s Gen 4 and Z-Image and Anima on an 8s Gen 3 (GitHub #53) came back
 * as static, which passed as "clean" (and so the CPU decode of 1.6.185 looked like a fix and was not). A picture
 * whose neighbouring pixels differ about as much as random ones is [Verdict.NOISE] ([NOISE_GRAIN]); then the
 * decode is not the suspect, so the legs keep the shipped launch and leave out [DECODE_ONLY] ([legsAfter]).
 *
 * ⚠ Everything here is pure, so the JVM tests reach it; [HarnessOps.diagnoseBlank] runs it.
 */
object BlankDiagnosis {

    const val SIZE = 256
    const val STEPS = 2
    const val SEED = 42
    const val PROMPT = "a red apple on a wooden table, daylight"

    /** ⭐ Appended to the launch's own `GGML_HEXAGON_OPFILTER` (the below-v79 filter stays), not a replacement. */
    const val OPFILTER_ADD = "+GGML_HEXAGON_OPFILTER"

    /**
     * ⭐ Where neighbouring pixels differ, on average, by this share of the picture's spread or more, it is noise.
     * ⚠ Measured (2026-10-18, at 256x256): twelve real renders of eight models 0.06-0.17; a tester's Iris static
     * 0.82-1.01 (a JPEG screenshot, which smooths it); random colour 1.15.
     */
    const val NOISE_GRAIN = 0.5

    /** One way to render: [env] on top of the launch's own environment. */
    data class Leg(val id: String, val what: String, val env: Map<String, String>)

    private fun cpu(id: String, what: String, ops: String) = Leg(id, "$what on the CPU", mapOf(OPFILTER_ADD to ops))

    /**
     * ⭐ The order matters: [LEGS]`[0]` is the control (as shipped), the next three split the PARTS, then the
     * matrix unit and the fusions, then one op group at a time. ⚠ Op names are ggml's (`ggml_op_desc`), matched
     * WHOLE and case-blind by ggml-hexagon (`std::regex_match`, `RE_ICASE`).
     */
    val LEGS = listOf(
        // ⚠ "everything on the NPU", not "as shipped": on a [DeviceProbe.DIT_NO_HMX_SOCS] chip the shipped launch
        // has HMX off, and Diagnose tests the NPU with it ([BackendProcess.diagnosing]).
        Leg("baseline", "everything on the NPU", emptyMap()),
        Leg("te_cpu", "text encoder on the CPU", mapOf("NM_DIT_BACKEND" to "diffusion=HTP0,te=CPU,vae=HTP0")),
        Leg("dit_cpu", "DiT on the CPU", mapOf("NM_DIT_BACKEND" to "diffusion=CPU,te=HTP0,vae=HTP0")),
        Leg("vae_cpu", "decode on the CPU", mapOf("NM_DIT_BACKEND" to "diffusion=HTP0,te=HTP0,vae=CPU")),
        // ⭐ The decode's own ops (1.6.185). On the 8s Gen 4 only `vae_cpu` was clean, and every op leg below
        // left these on the NPU: the VAE's convolutions run on HMX in fp16 (ggml-hexagon's conv2d), its group
        // norms and pads nowhere else. HMX off sends the convolutions to the CPU too, the rest of the NPU kept.
        Leg("no_hmx", "the NPU's matrix unit (HMX) off", mapOf("GGML_HEXAGON_NHMX" to "0")),
        cpu("cpu_conv", "convolutions", "CONV_2D.*|IM2COL"),
        cpu("cpu_group_norm", "group norms", "GROUP_NORM.*"),
        cpu("cpu_pad", "pads", "PAD"),
        Leg("hvx_matmul", "matmuls on HVX only (no HMX)", mapOf("GGML_HEXAGON_MM_SELECT" to "2")),
        Leg("no_fusion", "op fusions off", mapOf("GGML_HEXAGON_OPFUSION" to "0")),
        cpu("cpu_matmul", "matmuls", "MUL_MAT|MUL_MAT_SEGMENTED|MUL_MAT_ID"),
        cpu("cpu_norm", "norms", "NORM|RMS_NORM|L2_NORM|QKNORM_ROPE|RMS_NORM_MUL_SILU"),
        cpu("cpu_attention", "attention", "FLASH_ATTN_EXT|SOFT_MAX"),
        cpu("cpu_rope", "rope", "ROPE|QKNORM_ROPE"),
        cpu(
            "cpu_activation", "activations",
            "SILU|GELU|GELU_QUICK|SIGMOID|TANH|EXP|NEG|SOFTPLUS|RELU|SWIGLU|SWIGLU_OAI|SWIGLU_CLAMP|GEGLU",
        ),
        cpu("cpu_elementwise", "adds and multiplies", "ADD|MUL|SUB|DIV|SCALE|MODULATE|ADD_ID|CLAMP|SQR|SQRT|SUM_ROWS"),
        // ⚠ REPEAT only: CPY, CONT, GET_ROWS and SET_ROWS off the NPU abort on a pre-allocated HTP weight
        // (`cannot run the operation`, `docs/ANIMA-GGML.md` §3).
        cpu("cpu_repeat", "repeats", "REPEAT"),
    )

    /**
     * ⭐ The control where the shipped launch differs from "everything on the NPU" ([ModelSpec.noHmxOn]): rendered
     * first, as shipped, so a picture that is still wrong is told apart. Elsewhere [LEGS]`[0]` is already as shipped.
     */
    val SHIPPED = Leg("shipped", "as shipped (the NPU's matrix unit off)", emptyMap())

    /** ⭐ Legs about the decode alone: left out for noise, and for a model with no VAE (Iris). */
    val DECODE_ONLY = setOf("vae_cpu", "cpu_conv", "cpu_group_norm", "cpu_pad")

    /** ⭐ The engine's device spec with the decode on the CPU: the culprit legs keep a known-good decode. */
    const val CPU_VAE = "diffusion=HTP0,te=HTP0,vae=CPU"

    /**
     * ⭐ Pure: the legs after the control. [control] is how it came out; [shipped], whether the control was
     * [SHIPPED] (so the NPU with HMX is still untested); [hasVae], false for Iris. Empty: nothing to find.
     * ⚠ After a noisy control the legs keep the shipped launch ([HarnessOps.diagnoseBlank]), each one change on it.
     * ⭐⭐ After a CLEAN shipped control (HMX off, 1.6.193): the CULPRIT legs. HMX back on, the decode on the CPU
     * throughout ([CPU_VAE]), and one op group off HMX per leg; `vae_cpu` is their control (HMX everywhere in the
     * DiT). ⚠ Why: every op leg of 1.6.184's Diagnose left the decode on the NPU, which came back grey whatever the
     * DiT did, so none could ever have come back clean. HMX off is slow at full size (Anima 2.9B 1024² 2 steps:
     * 756 s of sampling on the S25 against ~30), so the op group to keep off HMX is the fast fix.
     */
    fun legsAfter(control: Verdict, shipped: Boolean, hasVae: Boolean): List<Leg> {
        val noise = control == Verdict.NOISE
        if (control == Verdict.CLEAN && !shipped) return emptyList()
        if (control == Verdict.CLEAN && hasVae) {
            return LEGS.filter { it.id !in DECODE_ONLY && it.id != "baseline" && it.id != "no_hmx" }.map { leg ->
                val backend = leg.env["NM_DIT_BACKEND"]
                leg.copy(env = leg.env + ("NM_DIT_BACKEND" to (backend?.replace("vae=HTP0", "vae=CPU") ?: CPU_VAE)))
            }.let { listOf(LEGS.first { it.id == "vae_cpu" }) + it }
        }
        val legs = if (shipped && !noise) LEGS else LEGS.drop(1)
        return legs.filter { (hasVae && !noise) || it.id !in DECODE_ONLY }
    }

    /** ⭐ [WRONG]: a picture, but not the control's (the culprit legs only, [against]). */
    enum class Verdict { CLEAN, FLAT, NOISE, WRONG, FAILED }

    /** [detail]: the colour for FLAT, [describe] for CLEAN and NOISE, the error for FAILED. */
    data class Outcome(val leg: Leg, val verdict: Verdict, val ms: Long, val detail: String)

    /** ⭐ One Diagnose: the [outcomes] so far, the [total] legs planned once the control was in, the [side] rendered. */
    data class Run(val outcomes: List<Outcome>, val total: Int, val side: Int) {
        fun report(model: String): String = report(model, outcomes, total, side)
    }

    /** ⭐ The blank check's colour back out of its message (`Ops.blankPicture`: "every pixel #817E81"). */
    fun flatColour(error: String): String? = Regex("every pixel (#[0-9A-Fa-f]{6})").find(error)?.groupValues?.get(1)

    /**
     * ⚠ Pure: what a picture looked like, in a line a report can carry: the grey spread (a picture has
     * one; a flat colour does not), its [grain] and an 8x8 grey thumbnail as 64 hex bytes, so a picture can be
     * told from noise without the PNG.
     */
    fun describe(px: IntArray, w: Int, h: Int): String {
        val grey = grey(px)
        val mean = grey.average()
        val sd = kotlin.math.sqrt(grey.sumOf { (it - mean) * (it - mean) } / grey.size.coerceAtLeast(1))
        val thumb = StringBuilder()
        for (ty in 0 until 8) for (tx in 0 until 8) {
            var sum = 0L
            var n = 0
            for (y in ty * h / 8 until (ty + 1) * h / 8) for (x in tx * w / 8 until (tx + 1) * w / 8) {
                sum += grey[y * w + x]; n++
            }
            thumb.append("%02x".format(if (n == 0) 0 else (sum / n).toInt()))
        }
        return "mean %.0f, spread %.1f, grain %.2f, 8x8 %s".format(java.util.Locale.ROOT, mean, sd, grain(grey, w, h), thumb)
    }

    private fun grey(px: IntArray) = IntArray(px.size) { i ->
        val p = px[i]
        (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
    }

    /**
     * ⭐ Pure: the mean grey difference to the right and lower neighbour over the grey spread; 0 for a flat
     * colour. ~1.1 for random pixels, ~0.1 for a picture ([NOISE_GRAIN]).
     */
    fun grain(grey: IntArray, w: Int, h: Int): Double {
        val mean = grey.average()
        val sd = kotlin.math.sqrt(grey.sumOf { (it - mean) * (it - mean) } / grey.size.coerceAtLeast(1))
        if (sd == 0.0 || w < 2 || h < 2) return 0.0
        var diff = 0L
        for (y in 0 until h - 1) for (x in 0 until w - 1) {
            val i = y * w + x
            diff += kotlin.math.abs(grey[i] - grey[i + 1]) + kotlin.math.abs(grey[i] - grey[i + w])
        }
        return diff.toDouble() / (2L * (w - 1) * (h - 1)) / sd
    }

    /** ⭐ Pure: a [describe] line's 8x8 thumbnail, 64 greys, or null. */
    fun thumb(detail: String): IntArray? =
        Regex("8x8 ([0-9a-f]{128})").find(detail)?.groupValues?.get(1)?.chunked(2)?.map { it.toInt(16) }?.toIntArray()

    /**
     * ⭐ Two thumbnails within this mean grey difference are the same picture. Measured 2026-10-18: on the S25 every
     * leg of a full Diagnose (Anima Turbo 1.1, 256², 2 steps) is right and lands 0 to 7.1 from the control (the DiT on
     * the CPU the farthest); the 8s Gen 4's wrong legs land 32.9 to 100.6 from its right picture (Z-Image, Anima 2.9B,
     * Anima Turbo). 16 sits in that gap.
     */
    const val SAME_PICTURE = 16.0

    /**
     * ⭐⭐ Pure: a culprit leg ([legsAfter], after a clean [SHIPPED]) judged against the shipped picture. The same
     * seed, steps and size, so a right render IS that picture; a CLEAN one that is not is [Verdict.WRONG].
     * ⚠ Why (an 8s Gen 4, Z-Image, 1.6.195): with HMX on, nine legs moving nine different op groups came back
     * byte-identical, grain 0.34, all "clean": the seed's noise decoded with the DiT changing nothing, under
     * [NOISE_GRAIN] at 256x256. The report concluded "only the decode needed HMX off", the opposite of what it showed.
     */
    fun against(control: Outcome, leg: Outcome): Outcome {
        if (leg.verdict != Verdict.CLEAN) return leg
        val a = thumb(control.detail) ?: return leg
        val b = thumb(leg.detail) ?: return leg
        val d = a.indices.sumOf { kotlin.math.abs(a[it] - b[it]) } / a.size.toDouble()
        return if (d <= SAME_PICTURE) leg
        else leg.copy(verdict = Verdict.WRONG, detail = leg.detail + " (%.0f from the shipped picture)".format(java.util.Locale.ROOT, d))
    }

    /** ⭐ Pure: CLEAN or NOISE for a picture that came back ([NOISE_GRAIN]). */
    fun verdictOf(px: IntArray, w: Int, h: Int): Verdict =
        if (grain(grey(px), w, h) >= NOISE_GRAIN) Verdict.NOISE else Verdict.CLEAN

    /**
     * ⭐ Pure: what the outcomes say, in a sentence or two. ⚠ Only a FLAT or NOISE control makes the other legs
     * mean anything: a clean control means the fault did not show at this size. The control is `baseline`, or
     * [SHIPPED] when the NPU decode was not tested after it (noise).
     */
    fun conclusion(outcomes: List<Outcome>): String {
        val by = outcomes.associateBy { it.leg.id }
        // ⭐ The culprit legs ([legsAfter]): shipped clean, then HMX on with the decode on the CPU.
        val shippedLeg = by[SHIPPED.id]
        val hmx = by["vae_cpu"]
        if (shippedLeg?.verdict == Verdict.CLEAN && hmx != null && "baseline" !in by) {
            if (hmx.verdict == Verdict.CLEAN) return "With HMX the DiT is right here: only the decode needed HMX off."
            val bad = when (hmx.verdict) { Verdict.NOISE -> "noise"; Verdict.WRONG -> "wrong"; else -> "flat" }
            fun ok(id: String) = by[id]?.verdict == Verdict.CLEAN
            val part = when {
                ok("te_cpu") && !ok("dit_cpu") -> "With HMX the text encoder makes it $bad."
                ok("dit_cpu") && !ok("te_cpu") -> "With HMX the DiT makes it $bad."
                else -> "With HMX the picture is $bad, whichever part is on the CPU."
            }
            val fixes = outcomes.filter { it.verdict == Verdict.CLEAN && it.leg.id !in PARTS }.map { it.leg.what }
            return part + if (fixes.isEmpty()) " No single op group off HMX fixes it: keep HMX off." else " Clean with HMX on and: ${fixes.joinToString("; ")}."
        }
        val base = by["baseline"] ?: shippedLeg ?: return "Not run."
        if (base.verdict == Verdict.CLEAN) return "Not reproduced: the control rendered at ${SIZE}x$SIZE, $STEPS steps."
        if (base.verdict == Verdict.FAILED) return "The control failed to render, so nothing can be compared: ${base.detail}"
        val bad = if (base.verdict == Verdict.NOISE) "noise" else "flat"
        fun clean(id: String) = by[id]?.verdict == Verdict.CLEAN
        val part = when {
            clean("te_cpu") && !clean("dit_cpu") -> "The text encoder on the NPU makes it $bad."
            clean("dit_cpu") && !clean("te_cpu") -> "The DiT on the NPU makes it $bad."
            clean("te_cpu") && clean("dit_cpu") -> "Either part off the NPU fixes it: something both share."
            clean("vae_cpu") -> "The decode on the NPU makes it $bad."
            else -> "No single part off the NPU fixes it."
        }
        val fixes = outcomes.filter { it.verdict == Verdict.CLEAN && it.leg.id !in PARTS }.map { it.leg.what }
        return part + if (fixes.isEmpty()) " No single op change fixes it." else " Clean with: ${fixes.joinToString("; ")}."
    }

    private val PARTS = setOf("baseline", "shipped", "te_cpu", "dit_cpu", "vae_cpu")

    /** ⭐ The section a report carries: the setup, every leg's verdict, then [conclusion]. [total]: the legs planned. */
    fun report(model: String, outcomes: List<Outcome>, total: Int = LEGS.size, side: Int = SIZE): String = buildString {
        appendLine("diagnosis (${outcomes.size} of $total legs): $model, ${side}x$side, $STEPS steps, seed $SEED")
        for (o in outcomes) {
            appendLine("  ${o.leg.id.padEnd(16)} ${o.verdict.name.lowercase().padEnd(6)} ${(o.ms / 1000.0).let { "%.0f s".format(java.util.Locale.ROOT, it) }.padStart(6)}  ${o.leg.what}: ${o.detail}")
        }
        append("conclusion: ").append(conclusion(outcomes))
    }

    /** ⭐ The control of a picture's Diagnose: its own flow, launched as shipped. */
    val PICTURE_CONTROL = Leg("shipped", "as shipped", emptyMap())

    /** ⚠ The legs that are the shipped launch itself on a chip that already runs with HMX off. */
    private val HMX_LEGS = setOf("no_hmx", "hvx_matmul")

    /**
     * ⭐⭐ Diagnose ONE picture, from its ⓘ in Results ([HarnessViewModel.diagnosePicture]): after [PICTURE_CONTROL], each
     * leg is one change on the SHIPPED launch, and the picture's own flow, size, steps and seed are re-run per leg.
     * ⚠ Why (Boingo's SM8850, TG #6213-6240): Klein on v81 came back with blown colours, grain and two glitch bars at
     * the right, and clean on the S25 (v79). A fault that shows at the picture's own size and inputs is missed by the
     * blank check's 256², 2 steps. `baseline` is left out: on every chip it is the control, except a no-HMX one, where
     * the HMX legs are the shipped launch too ([shippedNoHmx]). The decode legs need a VAE ([hasVae]: not Iris).
     */
    fun pictureLegs(hasVae: Boolean, shippedNoHmx: Boolean): List<Leg> = LEGS.filter {
        it.id != "baseline" && (hasVae || it.id !in DECODE_ONLY) && !(shippedNoHmx && it.id in HMX_LEGS)
    }

    /** ⭐ Pure: the mean absolute difference per channel (0-255) of two same-size pictures, or null when they differ in size. */
    fun meanDiff(a: IntArray, b: IntArray): Double? {
        if (a.size != b.size || a.isEmpty()) return null
        var sum = 0L
        for (i in a.indices) {
            val p = a[i]; val q = b[i]
            sum += kotlin.math.abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF)) +
                kotlin.math.abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF)) + kotlin.math.abs((p and 0xFF) - (q and 0xFF))
        }
        return sum / (3.0 * a.size)
    }

    /** One leg of a picture's Diagnose: [diff] from the control (null for the control), or the [error] it failed with. */
    data class PictureRow(val n: Int, val what: String, val ms: Long, val diff: Double?, val error: String?)

    /**
     * ⭐ Pure: the section a picture's Diagnose report carries. A leg the same as the shipped picture byte for byte did
     * not touch the fault. ⚠ A difference alone is not the verdict: on a healthy S25 (Anima Turbo 1.1, 512², 4 steps,
     * 2026-10-21) every leg that moves real work drifts 0.1 to 21.8 of 255 (the DiT or HMX off the most), while group
     * norms, pads and fusions came back 0.00. So the largest changes are ranked, and the person names the clean one
     * from Results by eye.
     */
    fun pictureReport(model: String, setup: String, rows: List<PictureRow>, total: Int): String = buildString {
        appendLine("picture diagnosis (${rows.size} of $total legs): $model, $setup")
        for (r in rows) {
            val what = when {
                r.error != null -> "failed: ${r.error.take(160)}"
                r.diff == null -> if (r.n == 1) "the control" else "no control picture to compare with"
                r.diff == 0.0 -> "same as shipped"
                r.diff < 0.0 -> "came out a different size from shipped"
                else -> "differs from shipped by %.1f of 255".format(java.util.Locale.ROOT, r.diff)
            }
            appendLine("  ${r.n.toString().padStart(2)}. ${r.what}: %.0f s, $what".format(java.util.Locale.ROOT, r.ms / 1000.0))
        }
        val moved = rows.filter { it.error == null && it.diff != null && it.diff != 0.0 }.sortedByDescending { kotlin.math.abs(it.diff!!) }
        val same = rows.filter { it.error == null && it.diff == 0.0 }.map { it.what }
        appendLine("not involved (the shipped picture exactly): " + same.ifEmpty { listOf("none") }.joinToString("; "))
        append("largest changes: ").append(
            moved.take(4).joinToString("; ") { "${it.what} %.1f".format(java.util.Locale.ROOT, it.diff) }.ifEmpty { "none" },
        ).append(". A right phone drifts up to ~22 of 255 here: compare the pictures by eye.")
    }
}
