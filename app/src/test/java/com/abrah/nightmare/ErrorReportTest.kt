package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ⭐⭐⭐ **The error report a user shares** ([ErrorReport]) — that it carries the
 * evidence, and that it never carries the prompt (the user's call, 2026-10-01).
 *
 * ⚠ The `Req Rcvd` line is the backend's real format (`main.cpp`'s request log).
 */
class ErrorReportTest {

    @Before
    fun clean() = BackendProcess.output.clear()

    private val prompt = "一张充满生活电影感的成年男女双人插画，9:16竖构图，超清"
    private val negative = "blurry, low quality, distorted"

    private fun facts(tail: List<String>, exit: BackendProcess.Exit? = null) = ErrorReport.Facts(
        message = "generate: generate failed http -1 — the backend stopped",
        nowMs = 10_000L,
        version = "1.6.068 (168)",
        device = "Xiaomi 25010PN30C",
        android = "15 (API 35)",
        chip = "SM8750 · HTP v79",
        ramTotal = 16_000_000_000L,
        ramAvailable = 9_000_000_000L,
        storageFree = 50_000_000_000L,
        model = "darkBeast [darkbeast] · ZIMAGE · dit",
        modelFiles = listOf("dit.safetensors" to 6_000_000_000L),
        backendRunning = false,
        backendExit = exit,
        backendTail = tail,
        appLog = listOf("run  prompt  cached · ${prompt.take(14)}…"),
        failure = ErrorReport.Failure(9_000L, "java.io.EOFException\n\tat okio.RealBufferedSource"),
    )

    @Test
    fun theBackendsRequestLineLosesBothPrompts() {
        val line = "Req Rcvd: P:$prompt NP:$negative S:8 CFG:1 Seed:1312268168 Size:768x1344"
        val out = ErrorReport.redact(line, emptyList())
        assertFalse(out, out.contains(prompt))
        assertFalse(out, out.contains(negative))
        assertTrue("the rest is evidence and stays: $out", out.contains("S:8 CFG:1 Seed:1312268168 Size:768x1344"))
    }

    /** ⚠ A prompt with a newline spills onto lines with no marker on them. */
    @Test
    fun aMultiLinePromptIsRemovedWhereverItSpills() {
        val twoLines = "first line of a long prompt\nsecond line, also private"
        val out = listOf("Req Rcvd: P:first line of a long prompt", "second line, also private NP: S:8")
            .map { ErrorReport.redact(it, listOf(twoLines)) }
        out.forEach { assertFalse(it, it.contains("private") || it.contains("long prompt")) }
        assertTrue(out[1], out[1].contains("S:8"))
    }

    /** ⚠⚠ The run log CLIPS a prompt with `…`; an exact match would miss it. */
    @Test
    fun aClippedPromptInTheRunLogIsRemoved() {
        val out = ErrorReport.redact("prompt  cached · ${prompt.take(14)}…  done", listOf(prompt))
        assertFalse(out, out.contains(prompt.take(12)))
        assertTrue(out, out.endsWith("done"))
    }

    @Test
    fun theReportCarriesTheEvidenceAndNoPrompt() {
        val text = ErrorReport.render(
            facts(
                tail = listOf(
                    "exec: libstable_diffusion_core.so --type zimage",
                    "Req Rcvd: P:$prompt NP:$negative S:8 CFG:1",
                    "   900.0ms [ ERROR ] [dit] ggml.c:123  - GGML_ASSERT failed",
                    "[exited 134]",
                ),
                exit = BackendProcess.Exit(134, 8_000L, byApp = false, pid = 1),
            ),
            listOf(prompt, negative),
        )
        assertFalse(text, text.contains(prompt.take(12)))
        assertFalse(text, text.contains(negative))
        assertTrue(text, text.contains("GGML_ASSERT failed"))
        assertTrue(text, text.contains("java.io.EOFException"))
        assertTrue(text, text.contains("SIGABRT"))
        assertTrue(text, text.contains("dit.safetensors"))
        assertTrue(text, text.contains("16.00 GB total"))
    }

    // ---- the backend's exit, which is all an lmkd kill leaves ---------------

    @Test
    fun signalsAreNamed() {
        assertTrue(BackendProcess.describeExit(137)!!.contains("memory"))
        assertTrue(BackendProcess.describeExit(134)!!.contains("SIGABRT"))
        assertTrue(BackendProcess.describeExit(139)!!.contains("SIGSEGV"))
        assertEquals("killed by signal 1", BackendProcess.describeExit(129))
        assertNull(BackendProcess.describeExit(0))
    }

    /** ⚠ [BackendProcess.stop]'s own kill is not a crash and must not read as one. */
    @Test
    fun anExitTheAppAskedForExplainsNothing() {
        assertNull(BackendProcess.exitMeaning(BackendProcess.Exit(137, 0L, byApp = true, pid = 1)))
        assertTrue(BackendProcess.exitMeaning(BackendProcess.Exit(137, 0L, byApp = false, pid = 1))!!.contains("memory"))
    }

    /**
     * ⚠⚠ The root cause is the newest LAUNCH's first error. The buffer spans
     * launches, and an earlier launch's error used to be reported for this one.
     */
    @Test
    fun anEarlierLaunchesErrorIsNotThisOnes() {
        listOf(
            "exec: first launch",
            "   1.0ms [ ERROR ] [dit] a.cpp:1  - the old failure",
            "[exited 1]",
            "exec: second launch",
            "   2.0ms [ ERROR ] [dit] b.cpp:2  - the new failure",
        ).forEach { BackendProcess.output.addFirst(it) }
        assertEquals("the new failure", BackendProcess.failureReason())
    }
}
