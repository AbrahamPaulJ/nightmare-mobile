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

    // ---- and the same answer for a backend that dies MID-RENDER ------------

    /**
     * ⭐⭐⭐ Reported 2026-09-22: `generate failed http -1 — EOFException: no
     * message` on a FLUX.2 render with a LoRA. True, and useless.
     *
     * ⚠ The tests here run with no process, so [BackendProcess.isRunning] is
     * false — which is exactly the state being described.
     */
    @Test
    fun aDeadBackendIsNamedRatherThanTheSocketException() {
        feed(realFailure)
        val msg = explainOpFailure(-1, "EOFException: no message")
        assertTrue("it should name the backend: $msg", msg.startsWith("the backend stopped"))
        assertTrue("it should carry the engine's own reason: $msg", msg.contains("comfy_quant"))
        // ⚠⚠ KEPT, not replaced: EOFException and SocketTimeoutException are
        // different failures and the next diagnosis needs to tell them apart.
        assertTrue("the socket's words must survive: $msg", msg.contains("EOFException"))
    }

    /** ⚠ No error line means no reason — the sentence still has to work. */
    @Test
    fun aDeadBackendWithNothingToSayStillReadsAsDead() {
        BackendProcess.output.clear()
        val msg = explainOpFailure(-1, "EOFException: no message")
        assertTrue(msg.startsWith("the backend stopped."))
        assertTrue(msg.contains("EOFException"))
    }

    /**
     * ⚠⚠ A real HTTP status is the BACKEND answering, so it is left exactly
     * as it came. Rewriting a 500 as "the backend stopped" would be a lie, and
     * it is the error bodies that name the real problem.
     */
    @Test
    fun aRealHttpErrorIsUntouched() {
        feed(realFailure)
        assertEquals("no such handle", explainOpFailure(500, "no such handle"))
    }

    /**
     * ⚠⚠ EVERY Wan launch logs this gguf complaint first, and lmkd's kill at 704p logs nothing: the user
     * was told the backend stopped because of a tensor's dimensions (2026-10-15). Lines from the S25.
     */
    private val wanGguf =
        "    22.4ms [ ERROR ] [dit] util.cpp:634  - gguf_init_from_reader: tensor 'patch_embedding.weight' has invalid number of dimensions: 5 > 4"

    @Test
    fun wansHarmlessGgufLineIsNeverTheReason() {
        feed(listOf(wanGguf, "151461.2ms [  INFO ] [dit] ggml_runner.cpp:949  - taehv compute buffer size: 3478.75 MB(VRAM) on HTP0"))
        assertNull(BackendProcess.failureReason())
    }

    @Test
    fun aRealErrorAfterWansGgufLineStillLeads() {
        feed(listOf(
            wanGguf,
            " 18398.8ms [ ERROR ] [dit] model_manager.cpp:1748 - model manager cannot make enough memory available on HTP0: need 3385.40 MB device / 3705.09 MB budget",
        ))
        assertTrue(BackendProcess.failureReason()!!.startsWith("model manager cannot make enough memory available"))
    }
}
