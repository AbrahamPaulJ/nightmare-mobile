package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ⭐⭐⭐ **The backend's own reason, turned into one line a person can act on.**
 *
 * ⚠⚠⚠ Reported 2026-09-21: importing a community FLUX checkpoint failed with
 * *"the backend would not start — see Settings > Diagnostics"*, and Settings
 * has had no Diagnostics section since 2026-09-19. The deeper failure is that
 * the backend had already said exactly what was wrong and there was nowhere to
 * read it: `HarnessOps` `say`s the whole log, and `say` writes to the harness
 * log, which lived on the deleted screen.
 *
 * ⚠⚠ The fixture below is the REAL output, captured off the phone for
 * `tinflux2Klein4B_4bFp8`. Inventing a plausible-looking log would test the
 * parser against my idea of the format rather than against the format.
 */
class BackendFailureReasonTest {

    /**
     * ⚠⚠ Feeds lines in ARRIVAL order, exactly as `BackendProcess` does
     * (`output.addFirst(line)` per line read), so index 0 ends up newest and the
     * oldest sits last. ⚠ Getting this backwards is how the first version of
     * this test "failed": it fed the log reversed and then blamed the parser for
     * returning the last line. The fixture was wrong, not the code.
     */
    private fun feed(oldestFirst: List<String>) {
        BackendProcess.output.clear()
        oldestFirst.forEach { BackendProcess.output.addFirst(it) }
    }

    @Before
    fun clean() = BackendProcess.output.clear()

    /** The capture, oldest first, exactly as the device printed it. */
    private val realFailure = listOf(
        "   521.1ms [  INFO ] [dit] model_loader.cpp:197  - load /storage/.../dit.safetensors using safetensors format",
        "   521.1ms [  INFO ] [dit] model_loader.cpp:265  - init from '/storage/.../dit.safetensors', prefix = 'model.diffusion_model.'",
        "   522.3ms [ ERROR ] [dit] model_loader.cpp:270  - parsing ComfyUI quantization metadata tensor failed: 'model.diffusion_model.double_blocks.0.img_attn.proj.comfy_quant'",
        "   522.3ms [ ERROR ] [dit] diffusion_engine.cpp:705  - loading diffusion model from '/storage/.../dit.safetensors' failed",
        "   536.1ms [ ERROR ] [dit] diffusion_engine.cpp:893  - get sd version from file failed: ''",
        "   536.4ms [ ERROR ] engine create failed: new_sd_ctx failed",
        "ERROR: Pipeline initialization failed!",
        "[exited 1]",
    )

    /**
     * ⭐⭐⭐ **The FIRST error, not the last** — the clause the whole thing turns
     * on.
     *
     * ⚠⚠ A failing launch always ends with its most generic line. Taking the
     * newest would report *"engine create failed: new_sd_ctx failed"* to a user
     * whose actual problem is a quantisation format their checkpoint uses, which
     * is the difference between "try a different file" and "this app is broken".
     */
    @Test
    fun itReportsTheRootCauseNotTheLastGasp() {
        feed(realFailure)
        assertEquals(
            "parsing ComfyUI quantization metadata tensor failed: " +
                "'model.diffusion_model.double_blocks.0.img_attn.proj.comfy_quant'",
            BackendProcess.failureReason(),
        )
    }

    /** ⚠ The timing, level and source file are noise on a chip. */
    @Test
    fun itStripsTheEnginesPrefix() {
        feed(realFailure)
        val why = BackendProcess.failureReason()!!
        assertTrue("timing must go: $why", !why.contains("ms ["))
        assertTrue("the level must go: $why", !why.contains("[ ERROR ]"))
        assertTrue("the source file must go: $why", !why.contains("model_loader.cpp"))
    }

    /** ⚠ A line with no ` - ` separator still yields its message. */
    @Test
    fun itHandlesAnUnprefixedError() {
        feed(listOf("starting", "ERROR: Pipeline initialization failed!", "[exited 1]"))
        assertEquals("Pipeline initialization failed!", BackendProcess.failureReason())
    }

    /**
     * ⚠⚠ Null rather than a guess. A launch that produced no error line has no
     * reason to offer, and the caller then says only that it would not start —
     * inventing one would be worse than the dead pointer this replaced.
     */
    @Test
    fun aCleanLogHasNoReason() {
        feed(listOf("   12.0ms [  INFO ] [dit] loading", "   99.0ms [  INFO ] serving"))
        assertNull(BackendProcess.failureReason())
    }

    @Test
    fun anEmptyLogHasNoReason() {
        BackendProcess.output.clear()
        assertNull(BackendProcess.failureReason())
    }
}
