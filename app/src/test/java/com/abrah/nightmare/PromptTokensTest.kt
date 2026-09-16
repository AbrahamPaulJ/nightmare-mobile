package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ⚠⚠ The token count is only worth showing if it is the backend's count, and
 * the half that can drift is the PARSE — [PromptSyntax] is a copy of
 * `PromptProcessor.hpp`. Every expectation below was produced by compiling that
 * header and running it on the same input, 2026-09-16, not by this function.
 */
class PromptTokensTest {

    private fun seg(s: String) = PromptSyntax.segments(s)

    @Test fun commasAreTheirOwnSegments() =
        assertEquals(listOf("a cat", ",", "sitting on grass"), seg("a cat, sitting on grass"))

    /** ⚠ A weight spends no tokens — that is the whole reason to parse at all. */
    @Test fun aWeightIsStripped() =
        assertEquals(listOf("masterpiece", ",", "best quality"), seg("(masterpiece:1.2), best quality"))

    @Test fun bracketsAreStripped() =
        assertEquals(listOf("very detailed", ",", "blurry"), seg("((very detailed)), [blurry]"))

    /** ⚠ `std::stof` fails on `abc`, so the colon stays in the text. */
    @Test fun aColonThatIsNotAWeightStays() =
        assertEquals(listOf("word:abc", "end"), seg("(word:abc) end"))

    @Test fun onlyTheLastColonIsTheWeight() = assertEquals(listOf("a:b"), seg("(a:b:1.5)"))

    @Test fun whitespaceCollapsesAndDropsBeforeACommas() =
        assertEquals(listOf("spaced out", ",", "words"), seg(" spaced   out  ,  words"))

    @Test fun escapesAreLiteral() =
        assertEquals(listOf("foo(bar)", ",", "baz:qux"), seg("foo\\(bar\\), baz\\:qux"))

    @Test fun anUnclosedGroupStillCounts() =
        assertEquals(listOf("unclosed", "group", ",", "still"), seg("unclosed (group, still"))

    @Test fun aStrayCloseIsDropped() = assertEquals(listOf("extra", "close"), seg("extra) close]"))

    @Test fun nestedGroupsSplit() =
        assertEquals(listOf("a", "b", "c", "d", "e"), seg("a (b [c] d) e"))

    @Test fun aNewlineIsASpace() = assertEquals(listOf("line one line two"), seg("line one\nline two"))

    @Test fun spacesAroundAWeight() = assertEquals(listOf("x", "y", "z"), seg("x (y :  0.8 ) z"))

    @Test fun anEmptyWeightedGroupIsNothing() = assertEquals(emptyList<String>(), seg("(:1.2)"))

    // ---- what a prompt is measured against ------------------------------------

    private fun graph(vararg consumers: Node) =
        Graph(listOf(Node("p", "core.prompt")) + consumers)

    @Test fun anSdSamplerIsClip() = assertEquals(
        PromptTokens.Budget.CLIP,
        PromptTokens.budgetFor(graph(Node("s", "sdxl.sample", inputs = sources("prompt" to "p"))), "p"),
    )

    @Test fun videoIsRawClip() = assertEquals(
        PromptTokens.Budget.CLIP_RAW,
        PromptTokens.budgetFor(graph(Node("v", "nd.sample", inputs = sources("prompt" to "p"))), "p"),
    )

    /** ⚠ Unwired falls back to the selected model's family, which defaults to SD 1.5. */
    @Test fun unwiredFollowsTheSelectedFamily() =
        assertEquals(PromptTokens.Budget.CLIP, PromptTokens.budgetFor(graph(), "p"))

    /** ⚠ No vocabulary loaded (a JVM test) is no count — never a CLIP number for Anima. */
    @Test fun animaWithNoVocabularyGivesNoCount() =
        assertNull(PromptTokens.count("a cat", PromptTokens.Budget.ANIMA))

    @Test fun anAnimaSamplerIsT5() = assertEquals(
        PromptTokens.Budget.ANIMA,
        PromptTokens.budgetFor(graph(Node("s", "anima.sample", inputs = sources("prompt" to "p"))), "p"),
    )
}
