package link.socket.ampere.cli.coordination

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.coordination.CoordinationState
import link.socket.ampere.coordination.CoordinationStatistics
import link.socket.ampere.coordination.InteractionType

@OptIn(ExperimentalCoroutinesApi::class)
class CoordinationViewTest {

    @Test
    fun `renders header with mode name`() = runTest {
        val presenter = createMockPresenter(this)
        val view = CoordinationView(presenter)

        val output = view.render(terminalWidth = 80, terminalHeight = 40)

        assertTrue(output.contains("COORDINATION VIEW"), "Should have view title")
        assertTrue(output.contains("Coordination Topology"), "Should show current mode")
    }

    @Test
    fun `renders footer with keybindings`() = runTest {
        val presenter = createMockPresenter(this)
        val view = CoordinationView(presenter)

        val output = view.render(terminalWidth = 80, terminalHeight = 40)

        assertTrue(output.contains("[t]opology"), "Should show topology key")
        assertTrue(output.contains("[f]eed"), "Should show feed key")
        assertTrue(output.contains("[s]tats"), "Should show stats key")
        assertTrue(output.contains("[v]erbose"), "Should show verbose key")
        assertTrue(output.contains("[q] exit"), "Should show exit key")
    }

    @Test
    fun `shows verbose indicator in header when enabled`() = runTest {
        val presenter = createMockPresenter(this)
        val view = CoordinationView(presenter)

        presenter.toggleVerbose() // Enable verbose mode

        val output = view.render(terminalWidth = 80, terminalHeight = 40)

        assertTrue(output.contains("VERBOSE"), "Should show VERBOSE indicator")
    }

    @Test
    fun `shows focused agent in header when set`() = runTest {
        val presenter = createMockPresenter(this)
        val view = CoordinationView(presenter)

        presenter.focusAgent("agent-A") // Set focused agent

        val output = view.render(terminalWidth = 80, terminalHeight = 40)

        assertTrue(output.contains("Focus: agent-A"), "Should show focused agent")
    }

    @Test
    fun `topology mode renders topology and active coordination`() = runTest {
        val presenter = createMockPresenter(this)
        val view = CoordinationView(presenter)

        // Default mode is TOPOLOGY
        val output = view.render(terminalWidth = 80, terminalHeight = 40)

        assertTrue(output.contains("TOPOLOGY"), "Should have topology section")
        assertTrue(output.contains("ACTIVE COORDINATION"), "Should have active coordination section")
    }

    @Test
    fun `feed mode renders interaction feed`() = runTest {
        val presenter = createMockPresenter(this)
        val view = CoordinationView(presenter)

        presenter.switchToFeed() // Switch to feed mode

        val output = view.render(terminalWidth = 80, terminalHeight = 40)

        assertTrue(output.contains("INTERACTION FEED"), "Should have feed section")
    }

    @Test
    fun `statistics mode renders statistics`() = runTest {
        val presenter = createMockPresenter(this)
        val view = CoordinationView(presenter)

        presenter.switchToStatistics() // Switch to statistics mode

        val output = view.render(terminalWidth = 80, terminalHeight = 40)

        assertTrue(
            output.contains("COORDINATION STATISTICS") || output.contains("Loading statistics"),
            "Should have statistics section or loading message",
        )
    }

    @Test
    fun `meeting mode renders meeting details`() = runTest {
        val presenter = createMockPresenter(this)
        val view = CoordinationView(presenter)

        presenter.switchToMeetingDetail("meeting-123") // Switch to meeting mode

        val output = view.render(terminalWidth = 80, terminalHeight = 40)

        assertTrue(
            output.contains("MEETING DETAILS") || output.contains("No meeting selected") || output.contains("Meeting not found"),
            "Should have meeting section",
        )
    }

    @Test
    fun `handleKey t switches to topology mode`() = runTest {
        val presenter = createMockPresenter(this)
        val view = CoordinationView(presenter)

        val shouldContinue = view.handleKey('t')

        assertTrue(shouldContinue, "Should continue after handling key")
        assertEquals(CoordinationSubMode.TOPOLOGY, presenter.viewState.value.subMode)
    }

    @Test
    fun `handleKey f switches to feed mode`() = runTest {
        val presenter = createMockPresenter(this)
        val view = CoordinationView(presenter)

        val shouldContinue = view.handleKey('f')

        assertTrue(shouldContinue, "Should continue after handling key")
        assertEquals(CoordinationSubMode.FEED, presenter.viewState.value.subMode)
    }

    @Test
    fun `handleKey s switches to statistics mode`() = runTest {
        val presenter = createMockPresenter(this)
        val view = CoordinationView(presenter)

        val shouldContinue = view.handleKey('s')

        assertTrue(shouldContinue, "Should continue after handling key")
        assertEquals(CoordinationSubMode.STATISTICS, presenter.viewState.value.subMode)
    }

    @Test
    fun `handleKey v toggles verbose mode`() = runTest {
        val presenter = createMockPresenter(this)
        val view = CoordinationView(presenter)

        // Default is verbose=false
        view.handleKey('v')

        assertTrue(presenter.viewState.value.verbose, "Verbose should be toggled on")

        view.handleKey('v')

        assertFalse(presenter.viewState.value.verbose, "Verbose should be toggled off")
    }

    @Test
    fun `handleKey with digit focuses agent by index`() = runTest {
        val presenter = createMockPresenter(this)
        val view = CoordinationView(presenter)

        val shouldContinue = view.handleKey('1')

        assertTrue(shouldContinue, "Should continue after handling key")
        // Focus behavior is tested in presenter tests
    }

    @Test
    fun `handleKey c clears focus`() = runTest {
        val presenter = createMockPresenter(this)
        val view = CoordinationView(presenter)

        presenter.focusAgent("agent-A") // Set focus first
        view.handleKey('c')

        assertEquals(null, presenter.viewState.value.focusedAgentId, "Focus should be cleared")
    }

    @Test
    fun `handleKey q returns false to exit`() = runTest {
        val presenter = createMockPresenter(this)
        val view = CoordinationView(presenter)

        val shouldContinue = view.handleKey('q')

        assertFalse(shouldContinue, "Should return false to exit")
    }

    @Test
    fun `handleKey Q returns false to exit`() = runTest {
        val presenter = createMockPresenter(this)
        val view = CoordinationView(presenter)

        val shouldContinue = view.handleKey('Q')

        assertFalse(shouldContinue, "Should return false to exit (uppercase)")
    }

    @Test
    fun `handleKey with unknown key continues`() = runTest {
        val presenter = createMockPresenter(this)
        val view = CoordinationView(presenter)

        val shouldContinue = view.handleKey('x')

        assertTrue(shouldContinue, "Should continue for unknown keys")
    }

    @Test
    fun `animation tick increments on each render`() = runTest {
        val presenter = createMockPresenter(this)
        val view = CoordinationView(presenter)

        // Render multiple times - tick should increment internally
        // We can't directly verify tick, but we can verify renders succeed
        repeat(5) {
            val output = view.render(terminalWidth = 80, terminalHeight = 40)
            assertTrue(output.isNotEmpty(), "Should produce output on render $it")
        }
    }

    @Test
    fun `handles case-insensitive key input`() = runTest {
        val presenter = createMockPresenter(this)
        val view = CoordinationView(presenter)

        // Test uppercase variants
        assertTrue(view.handleKey('T'), "Should handle uppercase T")
        assertTrue(view.handleKey('F'), "Should handle uppercase F")
        assertTrue(view.handleKey('S'), "Should handle uppercase S")
        assertTrue(view.handleKey('V'), "Should handle uppercase V")
        assertTrue(view.handleKey('C'), "Should handle uppercase C")
    }

    // Helper methods

    private fun createMockPresenter(
        scope: kotlinx.coroutines.test.TestScope,
    ): CoordinationPresenter {
        val initialCoordinationState = CoordinationState(
            edges = emptyList(),
            activeMeetings = emptyList(),
            pendingHandoffs = emptyList(),
            blockedAgents = emptyList(),
            recentInteractions = emptyList(),
            lastUpdated = Clock.System.now(),
        )

        val coordinationStateProvider = object : CoordinationStateProvider {
            override val state: StateFlow<CoordinationState> = MutableStateFlow(initialCoordinationState)
            override suspend fun getStatistics(): CoordinationStatistics {
                return CoordinationStatistics(
                    totalInteractions = 0,
                    uniqueAgentPairs = 0,
                    mostActiveAgent = null,
                    interactionsByType = emptyMap(),
                    averageInteractionsPerAgent = 0.0,
                )
            }
        }

        val agentStateProvider = object : AgentStateProvider {
            override val agentStates: StateFlow<Map<AgentId, String>> = MutableStateFlow(emptyMap())
        }

        return CoordinationPresenter(
            coordinationStateProvider = coordinationStateProvider,
            agentStateProvider = agentStateProvider,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        )
    }
}
