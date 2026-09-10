package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one part of the plugin runtime that can be tested without a phone.
 *
 * ⚠ [JsRuntime] itself cannot: it loads an arm64 `libnmjs.so`, so a JVM test
 * would fail on the `System.loadLibrary` in its companion object regardless of
 * what it asserted. The engine is checked by the device's `js` op instead.
 *
 * ⭐ But `quote` is where a bug would be *silent*: it builds the JS source of a
 * call, so a mis-escaped input does not fail loudly -- it either changes what
 * the plugin sees or ends the statement early.
 */
class JsQuoteTest {

    private fun q(s: String) = JsRuntime.quote(s)

    @Test
    fun plainStringIsJustQuoted() = assertEquals("\"hello\"", q("hello"))

    @Test
    fun quotesAndBackslashesEscape() = assertEquals("\"a\\\\b\\\"c\"", q("a\\b\"c"))

    @Test
    fun newlinesEscape() = assertEquals("\"a\\nb\"", q("a\nb"))

    /**
     * ⚠⚠ The reason this file exists. U+2028 and U+2029 are ordinary characters
     * in JSON but LINE TERMINATORS in JS source, so a string carrying one --
     * pasted out of a word processor into a prompt, say -- would end the
     * statement mid-literal and produce a syntax error that is not the
     * plugin's fault and does not point at the input that caused it.
     */
    @Test
    fun jsLineSeparatorsEscape() {
        assertEquals("\"a\\u2028b\"", q("a\u2028b"))
        assertEquals("\"a\\u2029b\"", q("a\u2029b"))
    }

    /** Other control characters must not travel raw either. */
    @Test
    fun controlCharactersEscape() = assertEquals("\"a\\u0000b\"", q("a\u0000b"))

    /** ⚠ Non-ASCII that is NOT a line terminator must pass through unharmed. */
    @Test
    fun unicodeIsNotMangled() = assertEquals("\"café 猫\"", q("café 猫"))
}
