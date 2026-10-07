package com.abrah.nightmare

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import com.abrah.nightmare.ui.LoraBrowserContent
import com.abrah.nightmare.ui.NightmareTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ⭐ Get LoRAs, drawn on the JVM against the recorded CivitAI search
 * (`src/test/resources/lora/`). On the sheet's colour, because the body of a
 * [com.abrah.nightmare.ui.PullDownSheet] is what a person sees — the sheet
 * itself is a Dialog, which a golden cannot draw (`docs/UI.md` §8.9).
 *
 * ⚠ Thumbnails are dropped: [com.abrah.nightmare.ui.RemoteThumb] would go to
 * the network.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class LoraBrowserScreenshotTest {

    private fun hits(target: LoraSources.Target): List<LoraSources.Hit> {
        val json = javaClass.classLoader!!.getResourceAsStream("lora/civitai_search.json")!!
            .readBytes().decodeToString()
        return LoraSources.parseCivitaiItems(JSONObject(json).getJSONArray("items"), target, mature = false)
            .map { it.copy(thumb = null) }
    }

    private fun shoot(name: String, key: String, search: (LoraSources.Target) -> LoraSources.Page) {
        captureRoboImage(filePath = goldenPath(this, name)) {
            NightmareTheme(darkTheme = true) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                    LoraBrowserContent(
                        initialTarget = LoraSources.Target.SD15,
                        installed = setOf("blindbox_v1_mix.safetensors"),
                        fetch = null,
                        fetchError = null,
                        busy = false,
                        civitaiKey = key,
                        mature = false,
                        onSaveKey = {},
                        onDownload = { _, _ -> },
                        onCancel = {},
                        search = { _, t, _, _, _ -> search(t) },
                    )
                }
            }
        }
    }

    /** ⭐ Most downloaded for SD 1.5, with a key saved: the list a person opens on. */
    @Test
    fun results() = shoot("lora-browser-results", key = "k") { LoraSources.Page(hits(it), null) }

    /**
     * ⭐ The state the phone showed on 2026-10-07 — CivitAI from Australia, no key:
     * the key card, then the region refusal as an [com.abrah.nightmare.ui.ErrorNotice].
     */
    @Test
    fun regionBlockedWithoutKey() = shoot("lora-browser-region", key = "") {
        throw LoraSources.Failure.RegionBlocked()
    }
}
