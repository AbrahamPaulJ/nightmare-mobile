package com.abrah.nightmare

/**
 * ⭐⭐⭐ **Whether a checkpoint may stay resident between runs.**
 *
 * ⚠⚠ The problem this exists for, measured on the dev phone 2026-09-20: a DiT
 * engine prepares its weights LAZILY, so the first render of a process
 * materialises several GB that it then never releases. Z-Image Turbo renders
 * once — 38 s at 512², 98 s at 1024² — and the SECOND request kills the
 * process, and usually the app with it (lmkd, `oom_score_adj 0`, no tombstone
 * and no `F DEBUG`: a kill, never a crash). ⇒ Release the backend after a run
 * so the next one starts from a fresh process. The reload measured ~3 s, the
 * weights being page-cached.
 *
 * ⚠⚠⚠ **Why this is a STATIC gate and not a free-memory reading**, which is
 * what it was going to be until the control run refuted it. Both DiT models
 * take the same one-time lazy-prep drop and then sit at almost the same free
 * memory — and only one of them dies:
 *
 * | | after load | render 1 | render 2 | render 3 |
 * |---|---|---|---|---|
 * | FLUX.2 Klein | 5.24 GB | → **1.80 GB** | 1.53 → 1.96 ✅ | 1.93 → 2.05 ✅ |
 * | Z-Image Turbo | 6.06 GB | → **1.35 GB** | dead | — |
 *
 * Free memory separates "renders forever" from "dies next request" by ~450 MB,
 * which is less than the noise from whatever else the phone has open. The rule
 * that was nearly shipped — release when `availAfter < consumedByThisRender` —
 * fires for **FLUX.2 as well** (1.80 < 3.43), and FLUX.2 is the known-good
 * control: three renders back to back, 49 s / 37 s / 38 s. ⇒ A reading that
 * cannot tell the working case from the failing one is not a measurement, it is
 * a coin toss with a number attached.
 *
 * ⚠ What DOES separate them is the footprint against the machine, which is
 * known before anything runs and is the same shape as every other capability
 * gate here ([ModelSpec.minHtpArch], `minVtcmMb`, [ModelSpec.lowram],
 * `docs/DEVICES.md`) — a per-model requirement checked against a measured
 * device property, decided FOR the user rather than asked.
 *
 * ⚠⚠ And it is why there is no toggle on the node, which is what was asked for
 * first (2026-09-20) and argued against for three reasons that all held: a node
 * param is serialised into a shared workflow, so a flow saved on a 16 GB phone
 * would carry "stay resident" to a 12 GB one and kill it; it would join
 * [cacheKey] like any other param, re-rendering the node and everything
 * downstream to produce an identical picture unless specially excluded the way
 * `batch:` is; and an inpaint node can never BE a DiT model
 * ([SdSampler.ALL] has no `flux2.inpaint`), so the knob would have been inert
 * on half the nodes it appeared on.
 */
object Residency {

    /**
     * ⚠⚠ The share of a device's RAM a checkpoint may occupy and still be worth
     * keeping loaded between runs.
     *
     * Measured 2026-09-20 against this phone's 10.85 GiB of total RAM:
     *
     * | | weights | share | second render |
     * |---|---|---|---|
     * | FLUX.2 Klein | 6.22 GiB | 57% | ✅ |
     * | Z-Image Turbo | 8.17 GiB | 75% | ❌ killed |
     *
     * 65% sits between them with ~8 points of margin on each side, which is far
     * more headroom than the free-memory reading offered. ⚠ On a 16 GB phone
     * the same two models are 41% and 54%, so BOTH stay resident and nothing
     * here needs touching when Gen 5 ships — the property the canary gate has
     * for the same reason (`docs/NEODRAGON.md` §4).
     *
     * ⚠ A ratio rather than an absolute GB figure, because the thing that
     * decides this is how much of the machine is left for Android and the app,
     * not the model's size on its own.
     */
    const val RESIDENT_SHARE_PERCENT = 65

    /**
     * True when a run holding [modelBytes] of weights on a [deviceRamBytes]
     * machine should end by releasing the backend.
     *
     * ⚠ Both arguments in bytes; [deviceRamBytes] is `ActivityManager.MemoryInfo.totalMem`,
     * the same field the run bar's readout already uses. ⚠ Non-positive inputs
     * answer **false** — an unknown model or an unreadable device says "carry on
     * as before", never "tear down", because the cost of a wrong `true` is a
     * reload on every single Run.
     */
    fun releaseAfterRun(modelBytes: Long, deviceRamBytes: Long): Boolean {
        if (modelBytes <= 0L || deviceRamBytes <= 0L) return false
        return modelBytes * 100 > deviceRamBytes * RESIDENT_SHARE_PERCENT
    }

    /**
     * ⭐ The sentence the run log prints when a release happens, so the ~3 s on
     * the NEXT Run has a stated reason rather than looking like a stall.
     *
     * ⚠ Names the model and both numbers. "Released the backend" on its own
     * reads as a failure; this reads as a decision.
     */
    fun why(label: String, modelBytes: Long, deviceRamBytes: Long): String =
        "released $label — ${gib(modelBytes)} of weights on a ${gib(deviceRamBytes)} phone " +
            "does not survive a second render, so the next Run reloads it (~3 s)"

    private fun gib(bytes: Long): String =
        String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1073741824.0)
}
