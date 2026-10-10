package com.abrah.nightmare.canvas

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import com.abrah.nightmare.TagDictionary
import com.abrah.nightmare.ui.NightmareTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * ⭐ Tag autocomplete's chips inside a prompt box's supporting slot, where the inspector puts them.
 * ⚠ Material3's text field asks that slot for its intrinsic height on every measure; a lazy list
 * cannot answer, so a `LazyRow` there crashed the app the moment the first suggestion appeared.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class TagSuggestionsTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun suggestionsShowInsideATextFieldsSupportingSlot() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        File(TagDictionary.dir(ctx), "main.csv").writeText("long_hair,0,4350743,\nlong_sleeves,0,900000,\n")
        TagDictionary.setEnabled(ctx, true)
        val value = TextFieldValue("1girl, lo", TextRange(9))
        rule.setContent {
            NightmareTheme {
                OutlinedTextField(
                    value = value,
                    onValueChange = {},
                    supportingText = { Column { TagSuggestions(value) { _, _ -> } } },
                )
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("long hair").fetchSemanticsNodes().isNotEmpty() }
    }
}
