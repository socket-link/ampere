package link.socket.ampere.cli.coordination

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.coordination.CoordinationEdge
import link.socket.ampere.coordination.CoordinationState
import link.socket.ampere.coordination.CoordinationStatistics
import link.socket.ampere.coordination.CoordinationTracker
import link.socket.ampere.coordination.InteractionType

@OptIn(ExperimentalCoroutinesApi::class)
class CoordinationPresenterTest {

    @Test
    fun `initial state has TOPOLOGY sub-mode`() = runTest {
        withPresenter(this) { presenter ->
            assertEquals(CoordinationSubMode.TOPOLOGY, presenter.viewState.value.subMode)
        }
    }

    @Test
    fun `switchToStatistics changes sub-mode and loads stats`() = runTest {
        val tracker = createMockTracker()
        withPresenter(this, tracker) { presenter ->
            presenter.switchToStatistics()
            advanceUntilIdle()

            assertEquals(CoordinationSubMode.STATISTICS, presenter.viewState.value.subMode)
            assertNotNull(presenter.viewState.value.statistics)
        }
    }

    @Test
    fun `switchToFeed changes sub-mode`() = runTest {
        withPresenter(this) { presenter ->
            presenter.switchToFeed()
            advanceUntilIdle()

            assertEquals(CoordinationSubMode.FEED, presenter.viewState.value.subMode)
        }
    }

    @Test
    fun `switchToTopology changes sub-mode`() = runTest {
        withPresenter(this) { presenter ->
            // First switch to another mode
            presenter.switchToFeed()
            advanceUntilIdle()

            // Then switch back to topology
            presenter.switchToTopology()
            advanceUntilIdle()

            assertEquals(CoordinationSubMode.TOPOLOGY, presenter.viewState.value.subMode)
        }
    }

    @Test
    fun `switchToMeetingDetail changes sub-mode and sets meeting ID`() = runTest {
        withPresenter(this) { presenter ->
            presenter.switchToMeetingDetail("meeting-123")
            advanceUntilIdle()

            assertEquals(CoordinationSubMode.MEETING, presenter.viewState.value.subMode)
            assertEquals("meeting-123", presenter.viewState.value.selectedMeetingId)
        }
    }

    @Test
    fun `toggleVerbose changes verbose flag`() = runTest {
        withPresenter(this) { presenter ->
            val initialVerbose = presenter.viewState.value.verbose
            presenter.toggleVerbose()
            advanceUntilIdle()

            assertEquals(!initialVerbose, presenter.viewState.value.verbose)

            presenter.toggleVerbose()
            advanceUntilIdle()

            assertEquals(initialVerbose, presenter.viewState.value.verbose)
        }
    }

    @Test
    fun `focusAgent sets focused agent ID`() = runTest {
        withPresenter(this) { presenter ->
            presenter.focusAgent("agent-A")
            advanceUntilIdle()

            assertEquals("agent-A", presenter.viewState.value.focusedAgentId)
        }
    }

    @Test
    fun `clearFocus removes focused agent`() = runTest {
        withPresenter(this) { presenter ->
            presenter.focusAgent("agent-A")
            advanceUntilIdle()
            assertEquals("agent-A", presenter.viewState.value.focusedAgentId)

            presenter.clearFocus()
            advanceUntilIdle()

            assertNull(presenter.viewState.value.focusedAgentId)
        }
    }

    @Test
    fun `focusAgentByIndex focuses correct agent from layout`() = runTest {
        val tracker = createMockTrackerWithAgents()
        withPresenter(this, tracker) { presenter ->
            advanceUntilIdle() // Wait for initial state to populate

            presenter.focusAgentByIndex(0)
            advanceUntilIdle()

            assertNotNull(presenter.viewState.value.focusedAgentId)
        }
    }

    @Test
    fun `focusAgentByIndex with invalid index does not change focus`() = runTest {
        withPresenter(this) { presenter ->
            presenter.focusAgentByIndex(999)
            advanceUntilIdle()

            assertNull(presenter.viewState.value.focusedAgentId)
        }
    }

    @Test
    fun `layout is calculated from coordination state`() = runTest {
        val tracker = createMockTrackerWithAgents()
        withPresenter(this, tracker) { presenter ->
            advanceUntilIdle()

            val layout = presenter.viewState.value.layout
            assertEquals(3, layout.nodes.size)
        }
    }

    @Test
    fun `switching modes clears meeting ID`() = runTest {
        withPresenter(this) { presenter ->
            presenter.switchToMeetingDetail("meeting-123")
            advanceUntilIdle()
            assertEquals("meeting-123", presenter.viewState.value.selectedMeetingId)

            presenter.switchToTopology()
            advanceUntilIdle()

            assertNull(presenter.viewState.value.selectedMeetingId)
        }
    }

    // Helper methods

    private suspend fun withPresenter(
        scope: TestScope,
        tracker: MockCoordinationTracker = createMockTracker(),
        block: suspend (CoordinationPresenter) -> Unit,
    ) {
        val presenter = createPresenter(scope, tracker)
        try {
            block(presenter)
        } finally {
            presenter.cancel()
        }
    }

    private fun createPresenter(
        scope: TestScope,
        tracker: MockCoordinationTracker = createMockTracker(),
    ): CoordinationPresenter {
        val agentStateProvider = MockAgentStateProvider()
        return CoordinationPresenter(tracker, agentStateProvider, scope)
    }

    private fun createMockTracker(): MockCoordinationTracker {
        return MockCoordinationTracker(
            CoordinationState(
                edges = emptyList(),
                activeMeetings = emptyList(),
                pendingHandoffs = emptyList(),
                blockedAgents = emptyList(),
                recentInteractions = emptyList(),
                lastUpdated = Clock.System.now(),
            ),
        )
    }

    private fun createMockTrackerWithAgents(): MockCoordinationTracker {
        val now = Clock.System.now()
        return MockCoordinationTracker(
            CoordinationState(
                edges = listOf(
                    CoordinationEdge(
                        sourceAgentId = "agent-A",
                        targetAgentId = "agent-B",
                        interactionCount = 5,
                        lastInteraction = now,
                        interactionTypes = setOf(InteractionType.TICKET_ASSIGNED),
                    ),
                    CoordinationEdge(
                        sourceAgentId = "agent-B",
                        targetAgentId = "agent-C",
                        interactionCount = 3,
                        lastInteraction = now,
                        interactionTypes = setOf(InteractionType.DELEGATION),
                    ),
                ),
                activeMeetings = emptyList(),
                pendingHandoffs = emptyList(),
                blockedAgents = emptyList(),
                recentInteractions = emptyList(),
                lastUpdated = now,
            ),
        )
    }

    // Mock implementations

    private class MockCoordinationTracker(
        private val initialState: CoordinationState,
    ) : CoordinationStateProvider {
        private val _state = MutableStateFlow(initialState)
        override val state: StateFlow<CoordinationState> = _state.asStateFlow()

        override fun getStatistics(): CoordinationStatistics {
            return CoordinationStatistics(
                totalInteractions = 10,
                uniqueAgentPairs = 5,
                mostActiveAgent = "agent-A",
                interactionsByType = mapOf(InteractionType.TICKET_ASSIGNED to 6, InteractionType.DELEGATION to 4),
                averageInteractionsPerAgent = 3.3,
            )
        }
    }

    private class MockAgentStateProvider : AgentStateProvider {
        private val _agentStates = MutableStateFlow<Map<AgentId, String>>(
            mapOf(
                "agent-A" to "ACTIVE",
                "agent-B" to "IDLE",
                "agent-C" to "IDLE",
            ),
        )
        override val agentStates: StateFlow<Map<AgentId, String>> = _agentStates.asStateFlow()
    }
}
