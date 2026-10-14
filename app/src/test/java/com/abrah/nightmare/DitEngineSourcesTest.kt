package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ⭐ The engine has two hosts (a user report, 2026-10-14: `Unable to resolve host "github.com"`).
 * GitHub first by default; Hugging Face first once the user picked a mirror, which rewrites it.
 */
class DitEngineSourcesTest {
    @Test
    fun githubFirstByDefaultHuggingFaceFirstWithAMirror() {
        assertEquals(listOf(DitEngine.URL, DitEngine.HF_URL), DitEngine.sources(Prefs.HF_ORIGIN))
        assertEquals(listOf(DitEngine.HF_URL, DitEngine.URL), DitEngine.sources(Prefs.HF_MIRROR))
        assertEquals(listOf(DitEngine.HF_URL, DitEngine.URL), DitEngine.sources("https://example.org/hf/"))
    }

    /** ⚠ One archive under one name on both hosts, so a new engine cannot update one and forget the other. */
    @Test
    fun bothHostsServeTheSameFileName() {
        assertEquals(DitEngine.URL.substringAfterLast('/'), DitEngine.HF_URL.substringAfterLast('/'))
        assert(DitEngine.HF_URL.startsWith(Prefs.HF_ORIGIN)) { "the mirror setting rewrites only ${Prefs.HF_ORIGIN}" }
    }
}
