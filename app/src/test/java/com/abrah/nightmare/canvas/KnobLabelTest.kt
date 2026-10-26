package com.abrah.nightmare.canvas

import com.abrah.nightmare.NODE_TYPES
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ⭐ #7225: a knob with no string resource is drawn in English on every locale's card
 * ([knobLabel]'s fallback). Every built-in node's knob must have one.
 */
class KnobLabelTest {

    @Test
    fun everyBuiltInKnobIsTranslated() {
        val missing = NODE_TYPES.values
            .flatMap { t -> t.widgets.map { "${t.name}.${it.name}" to it.name } }
            .filter { (id, name) -> !hiddenKnob(com.abrah.nightmare.Node("n", id.substringBeforeLast('.')), name) }
            .filter { (_, name) -> name !in KNOB_RESOURCES }
            .map { it.first }
            .sorted()
        assertEquals(emptyList<String>(), missing)
    }
}
