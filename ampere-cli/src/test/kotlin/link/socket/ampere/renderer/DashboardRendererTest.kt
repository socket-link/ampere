package link.socket.ampere.renderer

import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import link.socket.ampere.cli.watch.presentation.AgentActivityState
import link.socket.ampere.cli.watch.presentation.AgentState
import link.socket.ampere.cli.watch.presentation.SystemState
import link.socket.ampere.cli.watch.presentation.SystemVitals
import link.socket.ampere.cli.watch.presentation.WatchViewState
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class DashboardRendererTest {

    private fun stripAnsi(text: String): String {
        return text.replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "")
    }

    @Test
    fun `agent activity uses standard spinner and depth indicator`() {
        val terminal = Terminal()
        val renderer = DashboardRenderer(terminal, timeZone = TimeZone.UTC)
        val now = Instant.parse("2024-01-01T00:00:00Z")

        val viewState = WatchViewState(
            systemVitals = SystemVitals(
                activeAgentCount = 2,
                systemState = SystemState.WORKING,
                lastSignificantEventTime = null
            ),
            agentStates = mapOf(
                "agent-alpha" to AgentActivityState(
                    agentId = "agent-alpha",
                    displayName = "agent-alpha",
                    currentState = AgentState.WORKING,
                    lastActivityTimestamp = now,
                    consecutiveCognitiveCycles = 3,
                    isIdle = false,
                    affinityName = "ANALYTICAL",
                    sparkDepth = 3
                ),
                "agent-beta" to AgentActivityState(
                    agentId = "agent-beta",
                    displayName = "agent-beta",
                    currentState = AgentState.IDLE,
                    lastActivityTimestamp = now,
                    consecutiveCognitiveCycles = 0,
                    isIdle = true,
                    affinityName = "EXPLORATORY",
                    sparkDepth = 0
                )
            ),
            recentSignificantEvents = emptyList()
        )

        val output = stripAnsi(renderer.render(viewState))
        val lines = output.lineSequence().toList()
        val alphaLine = lines.first { it.contains("agent-alpha") }
        val betaLine = lines.first { it.contains("agent-beta") }

        val spinnerChars = setOf("◐", "◓", "◑", "◒")
        assertTrue(spinnerChars.any { alphaLine.trimStart().startsWith(it) })
        assertTrue(alphaLine.contains("WORKING"))
        assertTrue(alphaLine.contains("●●●○○"))
        assertTrue(betaLine.contains("IDLE"))
        assertTrue(betaLine.contains("○○○○○"))
    }
}
