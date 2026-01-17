package link.socket.ampere.cli.watch.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.event.CognitiveStateSnapshot
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.MeetingEvent
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.event.MessageEvent
import link.socket.ampere.agents.domain.event.ProductEvent
import link.socket.ampere.agents.domain.event.SparkAppliedEvent
import link.socket.ampere.agents.domain.event.SparkEvent
import link.socket.ampere.agents.domain.event.SparkRemovedEvent
import link.socket.ampere.agents.domain.event.TicketEvent
import link.socket.ampere.agents.events.relay.EventRelayFilters
import link.socket.ampere.agents.events.relay.EventRelayService

/**
 * The sensory cortex of the watch interface.
 *
 * Subscribes to all events from the nervous system (EventSerialBus),
 * filters and interprets them, and maintains presentable state for
 * the dashboard view.
 */
class WatchPresenter(
    private val eventRelayService: EventRelayService,
    private val clock: Clock = Clock.System
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val categorizer = EventCategorizer
    private val clusterer = CognitiveClusterer(clock = clock)
    private val sparkHistory = SparkHistoryCollector()

    // Mutable state - only modified by event handler
    private val agentStates = mutableMapOf<String, AgentActivityState>()
    private val significantEvents = mutableListOf<SignificantEventSummary>()
    private var systemVitals = SystemVitals()

    private var eventCollectionJob: Job? = null
    private var cleanupJob: Job? = null

    fun start() {
        // Subscribe to all events
        eventCollectionJob = scope.launch {
            eventRelayService.subscribeToLiveEvents(EventRelayFilters())
                .catch { e ->
                    // Log error but don't crash
                    println("Error in event subscription: ${e.message}")
                }
                .collect { event ->
                    handleEvent(event)
                }
        }

        // Start periodic cleanup of stale pending events and idle detection
        cleanupJob = scope.launch {
            while (isActive) {
                delay(1000)
                clusterer.cleanup()
                updateIdleStates()
            }
        }
    }

    fun stop() {
        eventCollectionJob?.cancel()
        cleanupJob?.cancel()
        scope.cancel()
    }

    private fun handleEvent(event: Event) {
        try {
            val agentId = (event.eventSource as? EventSource.Agent)?.agentId ?: return
            val significance = categorizer.categorize(event)

            // Try to cluster the event
            val cluster = clusterer.processEvent(event)

            // Handle Spark events specially - track cognitive state
            handleSparkEvent(event)

            // Update agent state
            updateAgentState(agentId, event, cluster)

            // Add to significant events feed if warranted
            if (significance.shouldDisplayByDefault) {
                addSignificantEvent(event, significance)
            }

            // Update system vitals
            updateSystemVitals(significance)

        } catch (e: Exception) {
            // Log error but don't crash the watch system
            println("Error processing event in watch presenter: ${e.message}")
        }
    }

    /**
     * Process SparkEvents to track cognitive state changes.
     */
    private fun handleSparkEvent(event: Event) {
        when (event) {
            is SparkAppliedEvent -> {
                sparkHistory.recordApplied(
                    agentId = event.agentId,
                    timestamp = event.timestamp,
                    sparkName = event.sparkName,
                    sparkType = event.sparkType,
                    stackDepth = event.stackDepth,
                    stackDescription = event.stackDescription
                )
            }
            is SparkRemovedEvent -> {
                sparkHistory.recordRemoved(
                    agentId = event.agentId,
                    timestamp = event.timestamp,
                    previousSparkName = event.previousSparkName,
                    stackDepth = event.stackDepth,
                    stackDescription = event.stackDescription
                )
            }
            is CognitiveStateSnapshot -> {
                sparkHistory.recordSnapshot(
                    agentId = event.agentId,
                    affinity = event.affinity,
                    sparkNames = event.sparkNames,
                    stackDepth = event.stackDepth,
                    effectivePromptLength = event.effectivePromptLength,
                    availableToolCount = event.availableToolCount,
                    stackDescription = event.stackDescription
                )
            }
            else -> { /* Not a Spark event */ }
        }
    }

    private fun updateAgentState(
        agentId: String,
        event: Event,
        cluster: CognitiveCluster?
    ) {
        val current = agentStates[agentId] ?: AgentActivityState(
            agentId = agentId,
            displayName = extractAgentName(agentId),
            currentState = AgentState.IDLE,
            lastActivityTimestamp = clock.now(),
            consecutiveCognitiveCycles = 0,
            isIdle = true
        )

        // Increment cognitive cycle counter if this completed a cluster
        val newCycleCount = if (cluster != null) {
            current.consecutiveCognitiveCycles + 1
        } else {
            current.consecutiveCognitiveCycles
        }

        // Determine new state based on event type and cycle count
        val newState = determineAgentState(event, newCycleCount)

        // Get cognitive state from Spark tracking
        val cognitiveState = sparkHistory.getCognitiveState(agentId)

        agentStates[agentId] = current.copy(
            currentState = newState,
            lastActivityTimestamp = event.timestamp,
            consecutiveCognitiveCycles = newCycleCount,
            isIdle = newState == AgentState.IDLE,
            affinityName = cognitiveState?.affinityName ?: current.affinityName,
            sparkNames = cognitiveState?.sparkNames ?: current.sparkNames,
            sparkDepth = cognitiveState?.depth ?: current.sparkDepth,
            cognitiveStateDescription = cognitiveState?.description ?: current.cognitiveStateDescription
        )

        invalidateCache()
    }

    private fun determineAgentState(event: Event, cycleCount: Int): AgentState {
        return when {
            event is Event.TaskCreated -> AgentState.WORKING
            event is TicketEvent.TicketAssigned -> AgentState.WORKING
            event is TicketEvent.TicketStatusChanged -> AgentState.WORKING
            event is ProductEvent.FeatureRequested -> AgentState.WORKING
            event is ProductEvent.EpicDefined -> AgentState.WORKING
            event is Event.CodeSubmitted -> AgentState.WORKING
            event is MemoryEvent.KnowledgeRecalled && cycleCount > 3 -> AgentState.THINKING
            event is MeetingEvent.MeetingStarted -> AgentState.IN_MEETING
            event is MessageEvent.MessagePosted -> AgentState.WORKING
            else -> AgentState.IDLE
        }
    }

    private fun addSignificantEvent(event: Event, significance: EventSignificance) {
        val agentId = (event.eventSource as? EventSource.Agent)?.agentId ?: "unknown"
        val summary = SignificantEventSummary(
            eventId = event.eventId,
            timestamp = event.timestamp,
            eventType = event.eventType,
            sourceAgentName = extractAgentName(agentId),
            summaryText = generateSummaryText(event),
            significance = significance
        )

        significantEvents.add(0, summary) // Add to front

        // Keep only most recent 20
        while (significantEvents.size > 20) {
            significantEvents.removeLast()
        }

        invalidateCache()
    }

    private fun updateSystemVitals(significance: EventSignificance) {
        val activeCount = agentStates.count { !it.value.isIdle }
        val hasCritical = significance == EventSignificance.CRITICAL

        systemVitals = systemVitals.copy(
            activeAgentCount = activeCount,
            systemState = when {
                hasCritical -> SystemState.ATTENTION_NEEDED
                activeCount > 0 -> SystemState.WORKING
                else -> SystemState.IDLE
            },
            lastSignificantEventTime = if (significance == EventSignificance.SIGNIFICANT ||
                                           significance == EventSignificance.CRITICAL) {
                clock.now()
            } else {
                systemVitals.lastSignificantEventTime
            }
        )

        invalidateCache()
    }

    private fun updateIdleStates() {
        val now = clock.now()
        val idleThresholdMs = 5000L // 5 seconds without activity = idle
        val removalThresholdMs = 30000L // 30 seconds idle = remove from memory

        // Collect agents to remove (can't modify map while iterating)
        val agentsToRemove = mutableListOf<String>()
        var stateChanged = false

        agentStates.forEach { (agentId, state) ->
            val timeSinceActivity = now.toEpochMilliseconds() -
                                   state.lastActivityTimestamp.toEpochMilliseconds()

            when {
                // Remove agents that have been idle for too long
                timeSinceActivity > removalThresholdMs -> {
                    agentsToRemove.add(agentId)
                    stateChanged = true
                }
                // Mark as idle if past idle threshold
                timeSinceActivity > idleThresholdMs && !state.isIdle -> {
                    agentStates[agentId] = state.copy(
                        currentState = AgentState.IDLE,
                        isIdle = true,
                        consecutiveCognitiveCycles = 0
                    )
                    stateChanged = true
                }
            }
        }

        // Remove stale agents to prevent unbounded memory growth
        agentsToRemove.forEach { agentId ->
            agentStates.remove(agentId)
            sparkHistory.clearAgent(agentId)
        }

        // Only invalidate cache if state actually changed
        if (stateChanged) {
            invalidateCache()
        }
    }

    private fun extractAgentName(agentId: String): String {
        // Extract readable name from agent ID like "1b3d7f83-1453-407b-b088-74f4711a8b3fProductManagerAgent"
        // Pattern: UUID (with hyphens) followed directly by agent class name
        val uuidPattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(.+)$")
        val match = uuidPattern.find(agentId)

        return match?.groupValues?.get(1) ?: run {
            // Fallback: if no match, just take last part after last hyphen if it contains "Agent"
            val parts = agentId.split("-")
            if (parts.size > 1 && parts.last().contains("Agent", ignoreCase = true)) {
                parts.last()
            } else {
                agentId.takeLast(20)
            }
        }
    }

    private fun generateSummaryText(event: Event): String {
        return when (event) {
            is Event.TaskCreated -> "Task created: ${event.description.take(50)}"
            is TicketEvent.TicketStatusChanged -> "Ticket status: ${event.newStatus}"
            is TicketEvent.TicketCreated -> "Ticket: ${event.title.take(50)}"
            is TicketEvent.TicketAssigned -> {
                val assignee = event.assignedTo?.let { extractAgentName(it) } ?: "unassigned"
                "Assigned to $assignee"
            }
            is MessageEvent.MessagePosted -> {
                val content = event.message.content.take(50).replace("\n", " ")
                "\"$content\""
            }
            is MessageEvent.EscalationRequested -> "⚠️ Escalation: ${event.reason.take(50)}"
            is MemoryEvent.KnowledgeRecalled -> {
                val count = event.resultsFound
                if (count > 0) "Recalled $count items (relevance: ${String.format("%.0f%%", event.averageRelevance * 100)})"
                else "No relevant knowledge found"
            }
            is MemoryEvent.KnowledgeStored -> {
                val type = event.knowledgeType.name.lowercase()
                val tags = event.tags.take(3).joinToString(", ")
                if (tags.isNotEmpty()) "Stored $type: $tags" else "Stored $type knowledge"
            }
            is Event.QuestionRaised -> "Question: ${event.questionText.take(50)}"
            is MeetingEvent.MeetingScheduled -> "Meeting: ${event.meeting.invitation.title.take(50)}"
            is MeetingEvent.MeetingStarted -> "Meeting started (${event.meetingId.takeLast(8)})"
            is ProductEvent.FeatureRequested -> "Feature: ${event.featureTitle.take(50)}"
            is ProductEvent.EpicDefined -> "Epic: ${event.epicTitle.take(50)}"
            else -> event.eventType
        }
    }

    private var cachedViewState: WatchViewState? = null
    private var cacheInvalidated = true

    fun getViewState(): WatchViewState {
        // Return cached view state if still valid
        if (!cacheInvalidated && cachedViewState != null) {
            return cachedViewState!!
        }

        // Create new view state snapshot
        val viewState = WatchViewState(
            systemVitals = systemVitals,
            agentStates = agentStates.toMap(), // Immutable copy
            recentSignificantEvents = significantEvents.toList() // Immutable copy
        )

        cachedViewState = viewState
        cacheInvalidated = false
        return viewState
    }

    private fun invalidateCache() {
        cacheInvalidated = true
    }

    fun getRecentClusters(): List<CognitiveCluster> {
        return clusterer.getRecentClusters()
    }

    /**
     * Get Spark transition history for a specific agent.
     */
    fun getSparkHistory(agentId: String, limit: Int = 20): List<SparkTransition> {
        return sparkHistory.getHistory(agentId, limit)
    }

    /**
     * Get all recent Spark transitions across all agents.
     */
    fun getAllRecentSparkTransitions(limit: Int = 50): List<SparkTransition> {
        return sparkHistory.getAllRecentTransitions(limit)
    }

    /**
     * Get current cognitive state for an agent.
     */
    fun getCognitiveState(agentId: String): CognitiveDisplayState? {
        return sparkHistory.getCognitiveState(agentId)
    }
}
