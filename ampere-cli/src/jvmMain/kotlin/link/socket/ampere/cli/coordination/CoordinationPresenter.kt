package link.socket.ampere.cli.coordination

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.coordination.CoordinationState
import link.socket.ampere.coordination.CoordinationStatistics
import link.socket.ampere.coordination.CoordinationTracker

/**
 * Sub-modes available in the coordination view.
 */
enum class CoordinationSubMode {
    /** Full topology graph view */
    TOPOLOGY,

    /** Full interaction feed view */
    FEED,

    /** Coordination statistics view */
    STATISTICS,

    /** Meeting detail view */
    MEETING,
}

/**
 * Complete view state for the coordination view.
 *
 * @property subMode Current sub-mode being displayed
 * @property layout Calculated topology layout for rendering
 * @property coordinationState Raw coordination state from tracker
 * @property statistics Coordination statistics (calculated when in STATISTICS mode)
 * @property selectedMeetingId ID of selected meeting for MEETING mode
 * @property verbose Whether to show verbose details
 * @property focusedAgentId Currently focused agent for detail view
 */
data class CoordinationViewState(
    val subMode: CoordinationSubMode,
    val layout: TopologyLayout,
    val coordinationState: CoordinationState,
    val statistics: CoordinationStatistics?,
    val selectedMeetingId: String?,
    val verbose: Boolean,
    val focusedAgentId: AgentId?,
)

/**
 * Provider interface for coordination state.
 *
 * Implementations provide real-time updates of coordination state and statistics.
 */
interface CoordinationStateProvider {
    /**
     * Flow of coordination state updates.
     */
    val state: StateFlow<CoordinationState>

    /**
     * Get current coordination statistics.
     */
    fun getStatistics(): CoordinationStatistics
}

/**
 * Provider interface for agent operational states.
 *
 * Implementations provide real-time updates of agent states (active, idle, blocked, offline).
 */
interface AgentStateProvider {
    /**
     * Flow of agent states mapped by agent ID.
     * Values are string representations of agent states.
     */
    val agentStates: StateFlow<Map<AgentId, String>>
}

/**
 * Extension to make CoordinationTracker implement CoordinationStateProvider.
 */
fun CoordinationTracker.asProvider(): CoordinationStateProvider = object : CoordinationStateProvider {
    override val state: StateFlow<CoordinationState> = this@asProvider.state
    override fun getStatistics(): CoordinationStatistics = this@asProvider.getStatistics()
}

/**
 * Presenter for the coordination view that manages state and user interactions.
 *
 * This presenter subscribes to coordination state from the tracker and combines it
 * with agent states to produce a complete view state for rendering.
 *
 * @property coordinationStateProvider Provider of coordination state updates
 * @property agentStateProvider Provider of agent operational states
 * @property scope Coroutine scope for state management
 */
class CoordinationPresenter(
    private val coordinationStateProvider: CoordinationStateProvider,
    private val agentStateProvider: AgentStateProvider,
    private val scope: CoroutineScope,
) {

    private val layoutCalculator = TopologyLayoutCalculator()

    private val _subMode = MutableStateFlow(CoordinationSubMode.TOPOLOGY)
    private val _verbose = MutableStateFlow(false)
    private val _focusedAgentId = MutableStateFlow<AgentId?>(null)
    private val _selectedMeetingId = MutableStateFlow<String?>(null)

    private val _viewState = MutableStateFlow(createInitialState())
    val viewState: StateFlow<CoordinationViewState> = _viewState.asStateFlow()

    private val collectJob: Job

    init {
        // Combine coordination state and agent states to produce view state
        collectJob = scope.launch {
            combine(
                coordinationStateProvider.state,
                agentStateProvider.agentStates,
                _subMode,
                _verbose,
                _focusedAgentId,
                _selectedMeetingId,
            ) { flows: Array<Any?> ->
                val coordinationState = flows[0] as CoordinationState
                val agentStates = flows[1] as Map<AgentId, String>
                val subMode = flows[2] as CoordinationSubMode
                val verbose = flows[3] as Boolean
                val focusedAgentId = flows[4] as AgentId?
                val selectedMeetingId = flows[5] as String?

                updateViewState(
                    coordinationState = coordinationState,
                    agentStates = agentStates,
                    subMode = subMode,
                    verbose = verbose,
                    focusedAgentId = focusedAgentId,
                    selectedMeetingId = selectedMeetingId,
                )
            }.collect { newState ->
                _viewState.value = newState
            }
        }
    }

    /**
     * Switch to topology graph view.
     */
    fun switchToTopology() {
        _subMode.value = CoordinationSubMode.TOPOLOGY
        _selectedMeetingId.value = null
    }

    /**
     * Switch to interaction feed view.
     */
    fun switchToFeed() {
        _subMode.value = CoordinationSubMode.FEED
        _selectedMeetingId.value = null
    }

    /**
     * Switch to statistics view.
     */
    fun switchToStatistics() {
        _subMode.value = CoordinationSubMode.STATISTICS
        _selectedMeetingId.value = null
    }

    /**
     * Switch to meeting detail view.
     *
     * @param meetingId ID of the meeting to display
     */
    fun switchToMeetingDetail(meetingId: String) {
        _subMode.value = CoordinationSubMode.MEETING
        _selectedMeetingId.value = meetingId
    }

    /**
     * Toggle verbose mode on/off.
     */
    fun toggleVerbose() {
        _verbose.value = !_verbose.value
    }

    /**
     * Focus on a specific agent.
     *
     * @param agentId ID of the agent to focus on
     */
    fun focusAgent(agentId: AgentId) {
        _focusedAgentId.value = agentId
    }

    /**
     * Focus on an agent by its index in the topology layout.
     *
     * @param index Zero-based index of the agent in the node list
     */
    fun focusAgentByIndex(index: Int) {
        val currentLayout = _viewState.value.layout
        if (index >= 0 && index < currentLayout.nodes.size) {
            val agentId = currentLayout.nodes[index].agentId
            _focusedAgentId.value = agentId
        }
    }

    /**
     * Clear agent focus.
     */
    fun clearFocus() {
        _focusedAgentId.value = null
    }

    /**
     * Cancel the background collection job.
     * Should be called when the presenter is no longer needed (e.g., in tests).
     */
    fun cancel() {
        collectJob.cancel()
    }

    /**
     * Update the view state based on current inputs.
     */
    private fun updateViewState(
        coordinationState: CoordinationState,
        agentStates: Map<AgentId, String>,
        subMode: CoordinationSubMode,
        verbose: Boolean,
        focusedAgentId: AgentId?,
        selectedMeetingId: String?,
    ): CoordinationViewState {
        // Map agent state strings to NodeState enum
        val nodeStates = agentStates.mapValues { (_, stateStr) ->
            when (stateStr.uppercase()) {
                "ACTIVE" -> NodeState.ACTIVE
                "BLOCKED" -> NodeState.BLOCKED
                "OFFLINE" -> NodeState.OFFLINE
                else -> NodeState.IDLE
            }
        }

        // Calculate layout
        val layout = layoutCalculator.calculateLayout(coordinationState, nodeStates)

        // Calculate statistics if in STATISTICS mode
        val statistics = if (subMode == CoordinationSubMode.STATISTICS) {
            coordinationStateProvider.getStatistics()
        } else {
            null
        }

        return CoordinationViewState(
            subMode = subMode,
            layout = layout,
            coordinationState = coordinationState,
            statistics = statistics,
            selectedMeetingId = selectedMeetingId,
            verbose = verbose,
            focusedAgentId = focusedAgentId,
        )
    }

    /**
     * Create initial empty state.
     */
    private fun createInitialState(): CoordinationViewState {
        return CoordinationViewState(
            subMode = CoordinationSubMode.TOPOLOGY,
            layout = TopologyLayout(
                nodes = emptyList(),
                edges = emptyList(),
                width = 0,
                height = 0,
            ),
            coordinationState = createEmptyCoordinationState(),
            statistics = null,
            selectedMeetingId = null,
            verbose = false,
            focusedAgentId = null,
        )
    }

    /**
     * Create empty coordination state.
     */
    private fun createEmptyCoordinationState(): CoordinationState {
        return CoordinationState(
            edges = emptyList(),
            activeMeetings = emptyList(),
            pendingHandoffs = emptyList(),
            blockedAgents = emptyList(),
            recentInteractions = emptyList(),
            lastUpdated = kotlinx.datetime.Clock.System.now(),
        )
    }
}
