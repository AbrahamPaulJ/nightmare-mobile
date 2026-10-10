package com.abrah.nightmare

import androidx.compose.foundation.layout.fillMaxSize
import com.abrah.nightmare.agent.AgentConfig
import com.abrah.nightmare.agent.AgentMode
import com.abrah.nightmare.agent.ChatItem
import com.abrah.nightmare.agent.Provider
import com.abrah.nightmare.canvas.RunLine
import com.abrah.nightmare.canvas.RunLogState
import com.abrah.nightmare.ui.AgentActions
import com.abrah.nightmare.ui.AgentScreen
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.abrah.nightmare.ui.AgentUi
import com.abrah.nightmare.ui.NightmareTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ⭐ The Agent view (`docs/AGENT-API.md` §4b): the setup before a key (with saved setups), a new
 * chat's three starts, and a conversation with a question and a live run.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class AgentScreenshotTest {

    private val ready = AgentConfig(Provider.OPENROUTER, Provider.OPENROUTER.baseUrl, "openai/gpt-4o-mini", hasKey = true)

    private fun shoot(name: String, ui: AgentUi) =
        captureRoboImage(filePath = goldenPath(this, name)) {
            NightmareTheme(darkTheme = true) {
                androidx.compose.material3.Surface(
                    androidx.compose.ui.Modifier.fillMaxSize(),
                    // ⚠ The theme's own background, as the app's Agent view uses — a custom colour has
                    // no content colour and drew every unstyled text black.
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                ) {
                    AgentScreen(ui, AgentActions(), androidx.compose.ui.Modifier.padding(16.dp))
                }
            }
        }

    private fun ui(
        items: List<ChatItem> = emptyList(),
        config: AgentConfig = ready,
        mode: AgentMode? = null,
        busy: Boolean = false,
        runLog: RunLogState? = null,
    ) = AgentUi(items, busy, config, mode, emptyMap(), false, null, runLog)

    @Test
    fun setup() = shoot(
        "agent-setup",
        AgentUi(
            emptyList(), false, AgentConfig(Provider.OPENROUTER, Provider.OPENROUTER.baseUrl, "", hasKey = false),
            null, emptyMap(), false, null, null, profiles = listOf("Grok NSFW", "GPT cheap"),
        ),
    )

    @Test
    fun start() = shoot("agent-start", ui(mode = AgentMode.CREATE_RUN))

    @Test
    fun chat() = shoot(
        "agent-chat",
        ui(
            items = listOf(
                ChatItem.User("a lighthouse on a cliff at sunset"),
                ChatItem.Tool("list_models", "", true),
                ChatItem.Tool("new_flow", "txt2img · absolutereality", true),
                ChatItem.Ask(1, "The flow on the canvas has unsaved changes. Save it before opening txt2img?", listOf("Save first", "Discard", "Cancel"), answer = "Save first"),
                ChatItem.Tool("set_params", "“a lighthouse on a cliff at sunset” · steps 20", true),
                ChatItem.Assistant("## Your flow is ready\nI set up **Text to image** on *AbsoluteReality*:\n- 20 steps, CFG 7.5\n- seed `random`\n\nRunning it now."),
                ChatItem.Tool("run", "", true),
            ),
            mode = AgentMode.CREATE_RUN,
            busy = true,
            runLog = RunLogState(
                lines = listOf(RunLine("generate", "rendering"), RunLine("generate", "reading the prompt")),
                now = "generate", step = 12 to 20, startedAtMs = 1L,
            ),
        ),
    )
}
