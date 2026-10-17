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
 * ⚠ Everything here is pure, so the JVM tests reach it; [HarnessOps.diagnoseBlank] runs it.
 */
object BlankDiagnosis {

    const val SIZE = 256
    const val STEPS = 2
    const val SEED = 42
    const val PROMPT = "a red apple on a wooden table, daylight"

    /** ⭐ Appended to the launch's own `GGML_HEXAGON_OPFILTER` (the below-v79 filter stays), not a replacement. */
    const val OPFILTER_ADD = "+GGML_HEXAGON_OPFILTER"

    /** One way to render: [env] on top of the launch's own environment. */
    data class Leg(val id: String, val what: String, val env: Map<String, String>)

    private fun cpu(id: String, what: String, ops: String) = Leg(id, "$what on the CPU", mapOf(OPFILTER_ADD to ops))

    /**
     * ⭐ The order matters: [LEGS]`[0]` is the control (as shipped), the next three split the PARTS, then the
     * matrix unit and the fusions, then one op group at a time. ⚠ Op names are ggml's (`ggml_op_desc`), matched
     * WHOLE and case-blind by ggml-hexagon (`std::regex_match`, `RE_ICASE`).
     */
    val LEGS = listOf(
        Leg("baseline", "as shipped", emptyMap()),
        Leg("te_cpu", "text encoder on the CPU", mapOf("NM_DIT_BACKEND" to "diffusion=HTP0,te=CPU,vae=HTP0")),
        Leg("dit_cpu", "DiT on the CPU", mapOf("NM_DIT_BACKEND" to "diffusion=CPU,te=HTP0,vae=HTP0")),
        Leg("vae_cpu", "decode on the CPU", mapOf("NM_DIT_BACKEND" to "diffusion=HTP0,te=HTP0,vae=CPU")),
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

    enum class Verdict { CLEAN, FLAT, FAILED }

    /** [detail]: the colour for FLAT, the spread and an 8x8 grey thumbnail for CLEAN, the error for FAILED. */
    data class Outcome(val leg: Leg, val verdict: Verdict, val ms: Long, val detail: String)

    /** ⭐ The blank check's colour back out of its message (`Ops.blankPicture`: "every pixel #817E81"). */
    fun flatColour(error: String): String? = Regex("every pixel (#[0-9A-Fa-f]{6})").find(error)?.groupValues?.get(1)

    /**
     * ⚠ Pure: what a CLEAN picture looked like, in a line a report can carry: the grey spread (a picture has
     * one; a flat colour does not) and an 8x8 grey thumbnail as 64 hex bytes, so a picture can be told from noise
     * without the PNG.
     */
    fun describe(px: IntArray, w: Int, h: Int): String {
        val grey = IntArray(px.size) { i ->
            val p = px[i]
            (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
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
        return "mean %.0f, spread %.1f, 8x8 %s".format(java.util.Locale.ROOT, mean, sd, thumb)
    }

    /**
     * ⭐ Pure: what the outcomes say, in a sentence or two. ⚠ Only a FLAT control makes the other legs mean
     * anything: a clean control means the fault did not show at this size.
     */
    fun conclusion(outcomes: List<Outcome>): String {
        val by = outcomes.associateBy { it.leg.id }
        val base = by["baseline"] ?: return "Not run."
        if (base.verdict == Verdict.CLEAN) return "Not reproduced: the control rendered at ${SIZE}x$SIZE, $STEPS steps."
        if (base.verdict == Verdict.FAILED) return "The control failed to render, so nothing can be compared: ${base.detail}"
        fun clean(id: String) = by[id]?.verdict == Verdict.CLEAN
        val part = when {
            clean("te_cpu") && !clean("dit_cpu") -> "The text encoder on the NPU makes it flat."
            clean("dit_cpu") && !clean("te_cpu") -> "The DiT on the NPU makes it flat."
            clean("te_cpu") && clean("dit_cpu") -> "Either part off the NPU fixes it: something both share."
            clean("vae_cpu") -> "The decode on the NPU makes it flat."
            else -> "No single part off the NPU fixes it."
        }
        val fixes = outcomes.filter { it.verdict == Verdict.CLEAN && it.leg.id !in PARTS }.map { it.leg.what }
        return part + if (fixes.isEmpty()) " No single op change fixes it." else " Clean with: ${fixes.joinToString("; ")}."
    }

    private val PARTS = setOf("baseline", "te_cpu", "dit_cpu", "vae_cpu")

    /** ⭐ The section a report carries: the setup, every leg's verdict, then [conclusion]. */
    fun report(model: String, outcomes: List<Outcome>): String = buildString {
        appendLine("diagnosis (${outcomes.size} of ${LEGS.size} legs): $model, ${SIZE}x$SIZE, $STEPS steps, seed $SEED")
        for (o in outcomes) {
            appendLine("  ${o.leg.id.padEnd(16)} ${o.verdict.name.lowercase().padEnd(6)} ${(o.ms / 1000.0).let { "%.0f s".format(java.util.Locale.ROOT, it) }.padStart(6)}  ${o.leg.what}: ${o.detail}")
        }
        append("conclusion: ").append(conclusion(outcomes))
    }
}
