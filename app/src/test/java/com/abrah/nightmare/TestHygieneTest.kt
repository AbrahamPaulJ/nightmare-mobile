package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ⭐ Test time is a budget (`notes/HANDOFF.md` §3c): the rules that keep the suite under it, checked
 * rather than remembered. ⚠ 2026-10-14: 22 Compose tests each waited out Espresso's 60 s idle timeout and a
 * 2-minute suite took 28. [FastIdle] caps that wait at 5 s; a Compose test class without it puts it back.
 */
class TestHygieneTest {
    /** Gradle runs unit tests from the module directory, so this is `app/src/test/java`. */
    private val sources = File("src/test/java")

    @Test
    fun everyComposeTestClassCapsItsIdleWait() {
        assertTrue("test sources not found from ${File(".").absolutePath}", sources.isDirectory)
        val compose = Regex("""create(Android)?ComposeRule""")
        val all = sources.walk().filter { it.isFile && it.extension == "kt" && it.name != "TestHygieneTest.kt" }.toList()
        val composeClasses = all.filter { compose.containsMatchIn(it.readText()) }
        // ⚠ The control: a scan that finds nothing would pass for the wrong reason.
        assertTrue("found only ${composeClasses.size} Compose test classes; is the scan broken?", composeClasses.size >= 12)
        val missing = composeClasses.filter { "FastIdle()" !in it.readText() }.map { it.name }.sorted()
        assertEquals(
            "Compose test classes without the FastIdle rule (a leak would cost 60 s per test instead of 5)",
            emptyList<String>(), missing,
        )
    }
}
