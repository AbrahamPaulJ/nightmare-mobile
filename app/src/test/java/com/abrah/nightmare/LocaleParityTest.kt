package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * ⭐ `docs/UI.md` §8: every key a person reads lands in every locale in the same push. Russian and Chinese once fell 22
 * keys behind in six days, and Japanese (PR #138) was 12 behind by the time it merged; until this test, only a hand
 * sweep found them. Also checks that each translation keeps its English format specifiers, and that the locales
 * listed for Android's per-app languages are exactly the folders that exist.
 */
class LocaleParityTest {
    private val res = File("src/main/res")

    private class Entry(val texts: List<String>, val translatable: Boolean)

    private fun load(f: File): Map<String, Entry> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val root = doc.documentElement
        val out = LinkedHashMap<String, Entry>()
        val kids = root.childNodes
        for (i in 0 until kids.length) {
            val e = kids.item(i) as? org.w3c.dom.Element ?: continue
            val items = if (e.tagName == "string") listOf(e.textContent)
            else (0 until e.childNodes.length).mapNotNull { (e.childNodes.item(it) as? org.w3c.dom.Element)?.textContent }
            out[e.getAttribute("name")] = Entry(items, e.getAttribute("translatable") != "false")
        }
        return out
    }

    private val locales = res.listFiles().orEmpty().map { it.name }
        .filter { it.startsWith("values-") && File(res, "$it/strings.xml").isFile }.sorted()

    private val spec = Regex("%(\\d+\\$)?[-#+0,(]*\\d*(\\.\\d+)?[sdfxXc]")

    private fun specs(texts: List<String>) = texts.flatMap { t -> spec.findAll(t).map { it.value } }.toSortedSet()

    @Test
    fun everyLocaleHasEveryKey() {
        val en = load(File(res, "values/strings.xml"))
        assertTrue("found no locales", locales.size >= 3)
        val problems = mutableListOf<String>()
        for (loc in locales) {
            val tr = load(File(res, "$loc/strings.xml"))
            val missing = en.filter { (k, v) -> v.translatable && k != "app_name" && k !in tr }.keys
            if (missing.isNotEmpty()) problems += "$loc misses ${missing.size}: ${missing.joinToString()}"
            val extra = tr.keys - en.keys
            if (extra.isNotEmpty()) problems += "$loc has keys English lacks: ${extra.joinToString()}"
            for ((k, v) in tr) {
                val e = en[k] ?: continue
                if (specs(e.texts) != specs(v.texts)) problems += "$loc/$k: ${specs(v.texts)} where English has ${specs(e.texts)}"
            }
        }
        assertEquals("", problems.joinToString("\n"))
    }

    @Test
    fun theAppLanguageListMatchesTheFolders() {
        val listed = Regex("android:name=\"([a-z-]+)\"").findAll(File(res, "xml/locales_config.xml").readText())
            .map { it.groupValues[1] }.toSet()
        assertEquals(locales.map { it.removePrefix("values-") }.toSet() + "en", listed)
        val settings = File("src/main/java/com/abrah/nightmare/ui/SettingsScreen.kt").readText()
        for (tag in listed) assertTrue("Settings' language list lacks $tag", "\"$tag\" to \"" in settings)
    }
}
