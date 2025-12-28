package link.socket.ampere.coordination

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.NotificationEvent
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.bus.subscribe
import link.socket.ampere.agents.events.subscription.EventSubscription

/**
 * Service that tracks coordination between agents by observing events on the EventSerialBus.
 *
 * This service maintains a history of agent interactions and builds a real-time picture
 * of how agents are coordinating with each other. It does not modify agent behavior,
 * only observes existing events.
 *
 * @property eventSerialBus The event bus to subscribe to
 * @property scope Coroutine scope for async operations
 */
class CoordinationTracker(
    private val eventSerialBus: EventSerialBus,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val MAX_INTERACTION_HISTORY = 1000
        private const val EDGE_TIME_WINDOW_SECONDS = 3600 // 1 hour
    }

    private val classifier = InteractionClassifier()

    private val interactionHistory = mutableListOf<AgentInteraction>()
    private val meetings = mutableMapOf<String, ActiveMeeting>()
    private val pendingHandoffs = mutableListOf<PendingHandoff>()
    private val blockedAgents = mutableListOf<BlockedAgent>()

    private val _state = MutableStateFlow(createEmptyState())
    val state: StateFlow<CoordinationState> = _state.asStateFlow()

    /**
     * Start tracking coordination events.
     * This subscribes to various event types and updates the coordination state.
     */
    fun startTracking() {
        // Subscribe to TaskCreated events to track task assignments
        eventSerialBus.subscribe<Event.TaskCreated, EventSubscription.ByEventClassType>(
            agentId = "coordination-tracker",
            eventType = Event.TaskCreated.EVENT_TYPE,
        ) { event, _ ->
            handleEvent(event)
        }

        // Subscribe to QuestionRaised events to track clarification requests
        eventSerialBus.subscribe<Event.QuestionRaised, EventSubscription.ByEventClassType>(
            agentId = "coordination-tracker",
            eventType = Event.QuestionRaised.EVENT_TYPE,
        ) { event, _ ->
            handleEvent(event)
        }

        // Subscribe to CodeSubmitted events to track review requests
        eventSerialBus.subscribe<Event.CodeSubmitted, EventSubscription.ByEventClassType>(
            agentId = "coordination-tracker",
            eventType = Event.CodeSubmitted.EVENT_TYPE,
        ) { event, _ ->
            handleEvent(event)
        }

        // Subscribe to NotificationToAgent events to track agent-to-agent communication
        eventSerialBus.subscribe<Event, EventSubscription.ByEventClassType>(
            agentId = "coordination-tracker",
            eventType = NotificationEvent.ToAgent.EVENT_TYPE,
        ) { event, _ ->
            handleEvent(event)
        }

        // Subscribe to NotificationToHuman events to track escalations
        eventSerialBus.subscribe<Event, EventSubscription.ByEventClassType>(
            agentId = "coordination-tracker",
            eventType = NotificationEvent.ToHuman.EVENT_TYPE,
        ) { event, _ ->
            handleEvent(event)
        }
    }

    /**
     * Handle an event and extract coordination information from it.
     */
    private suspend fun handleEvent(event: Event) {
        val interaction = classifier.classify(event) ?: return

        synchronized(interactionHistory) {
            interactionHistory.add(interaction)

            // Keep only the last MAX_INTERACTION_HISTORY interactions
            if (interactionHistory.size > MAX_INTERACTION_HISTORY) {
                interactionHistory.removeAt(0)
            }
        }

        // Update state
        updateState()
    }

    /**
     * Update the coordination state based on current interaction history.
     */
    private suspend fun updateState() {
        val now = Clock.System.now()
        val edges = buildCoordinationEdges(now)
        val recentInteractions = synchronized(interactionHistory) {
            interactionHistory.takeLast(100).toList()
        }

        val newState = CoordinationState(
            edges = edges,
            activeMeetings = meetings.values.toList(),
            pendingHandoffs = pendingHandoffs.toList(),
            blockedAgents = blockedAgents.toList(),
            recentInteractions = recentInteractions,
            lastUpdated = now,
        )

        _state.emit(newState)
    }

    /**
     * Build coordination edges from interaction history.
     * Only includes interactions within the time window.
     */
    private fun buildCoordinationEdges(now: Instant): List<CoordinationEdge> {
        val cutoffTime = now.minus(EDGE_TIME_WINDOW_SECONDS.seconds)

        val recentInteractions = synchronized(interactionHistory) {
            interactionHistory.filter { it.timestamp >= cutoffTime }
        }

        // Group by (source, target) pair
        val edgeMap = mutableMapOf<Pair<String, String>, MutableList<AgentInteraction>>()

        for (interaction in recentInteractions) {
            val targetId = interaction.targetAgentId ?: continue // Skip interactions without a target
            val key = interaction.sourceAgentId to targetId
            edgeMap.getOrPut(key) { mutableListOf() }.add(interaction)
        }

        // Build edges
        return edgeMap.map { (key, interactions) ->
            val (sourceId, targetId) = key
            CoordinationEdge(
                sourceAgentId = sourceId,
                targetAgentId = targetId,
                interactionCount = interactions.size,
                lastInteraction = interactions.maxOf { it.timestamp },
                interactionTypes = interactions.map { it.interactionType }.toSet(),
            )
        }
    }

    /**
     * Get statistics about coordination patterns.
     */
    fun getStatistics(): CoordinationStatistics {
        val interactions = synchronized(interactionHistory) {
            interactionHistory.toList()
        }

        val uniquePairs = interactions
            .mapNotNull { it.targetAgentId?.let { target -> it.sourceAgentId to target } }
            .toSet()

        val agentInteractionCounts = mutableMapOf<String, Int>()
        for (interaction in interactions) {
            agentInteractionCounts[interaction.sourceAgentId] =
                agentInteractionCounts.getOrDefault(interaction.sourceAgentId, 0) + 1
            interaction.targetAgentId?.let { target ->
                agentInteractionCounts[target] =
                    agentInteractionCounts.getOrDefault(target, 0) + 1
            }
        }

        val mostActiveAgent = agentInteractionCounts.maxByOrNull { it.value }?.key

        val interactionsByType = interactions.groupingBy { it.interactionType }.eachCount()

        val averageInteractionsPerAgent = if (agentInteractionCounts.isNotEmpty()) {
            agentInteractionCounts.values.average()
        } else {
            0.0
        }

        return CoordinationStatistics(
            totalInteractions = interactions.size,
            uniqueAgentPairs = uniquePairs.size,
            mostActiveAgent = mostActiveAgent,
            interactionsByType = interactionsByType,
            averageInteractionsPerAgent = averageInteractionsPerAgent,
        )
    }

    private fun createEmptyState(): CoordinationState {
        return CoordinationState(
            edges = emptyList(),
            activeMeetings = emptyList(),
            pendingHandoffs = emptyList(),
            blockedAgents = emptyList(),
            recentInteractions = emptyList(),
            lastUpdated = Clock.System.now(),
        )
    }
}
