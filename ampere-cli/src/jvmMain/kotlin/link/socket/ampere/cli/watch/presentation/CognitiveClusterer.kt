package link.socket.ampere.cli.watch.presentation

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.MemoryEvent
import kotlin.time.Duration.Companion.milliseconds

/**
 * Clusters related events into cognitive cycles to reduce noise.
 *
 * Like how the brain doesn't make you aware of every neuron firing but
 * instead surfaces patterns - "hand touched hot surface" rather than
 * "neuron A fired, then B, then C, then..."
 */
class CognitiveClusterer(
    private val clusterWindowMs: Long = 500,
    private val maxPendingEventsPerAgent: Int = 10,
    private val clock: Clock = Clock.System
) {
    private val pendingEventsByAgent = mutableMapOf<String, MutableList<PendingEvent>>()
    private val completedClusters = mutableListOf<CognitiveCluster>()

    // State machine: track last KnowledgeRecalled event per agent for O(1) matching
    private val lastRecallByAgent = mutableMapOf<String, PendingEvent>()

    private data class PendingEvent(val event: Event, val receivedAt: Instant)

    /**
     * Process an event and potentially return a completed cluster.
     */
    fun processEvent(event: Event): CognitiveCluster? {
        val agentId = (event.eventSource as? EventSource.Agent)?.agentId ?: return null

        // Add to pending buffer
        val pending = pendingEventsByAgent.getOrPut(agentId) { mutableListOf() }
        pending.add(PendingEvent(event, clock.now()))

        // Enforce size limit - remove oldest events if exceeded
        while (pending.size > maxPendingEventsPerAgent) {
            pending.removeAt(0)
        }

        // Check for completed patterns using O(1) state machine
        return checkForKnowledgeCluster(agentId, event)?.also {
            // Remove clustered events from pending
            pending.removeAll { e -> it.events.contains(e.event) }

            // Add to completed clusters
            completedClusters.add(0, it) // Add to front
            while (completedClusters.size > 50) {
                completedClusters.removeLast()
            }
        }
    }

    /**
     * Check for a KnowledgeRecalled + KnowledgeStored cluster pattern using O(1) state machine.
     */
    private fun checkForKnowledgeCluster(agentId: String, currentEvent: Event): CognitiveCluster? {
        when (currentEvent) {
            is MemoryEvent.KnowledgeRecalled -> {
                // Track this recall for potential future matching
                lastRecallByAgent[agentId] = PendingEvent(currentEvent, clock.now())
                return null
            }
            is MemoryEvent.KnowledgeStored -> {
                // Check if we have a recent recall to pair with
                val lastRecall = lastRecallByAgent[agentId] ?: return null

                val timeDiff = currentEvent.timestamp.toEpochMilliseconds() -
                              lastRecall.event.timestamp.toEpochMilliseconds()

                return if (timeDiff <= clusterWindowMs) {
                    // Clear the tracked recall since we matched it
                    lastRecallByAgent.remove(agentId)

                    CognitiveCluster(
                        agentId = agentId,
                        startTimestamp = lastRecall.event.timestamp,
                        events = listOf(lastRecall.event, currentEvent),
                        cycleType = CognitiveClusterType.KNOWLEDGE_RECALL_STORE
                    )
                } else {
                    // Too old, clear it and don't match
                    lastRecallByAgent.remove(agentId)
                    null
                }
            }
            else -> return null
        }
    }

    /**
     * Remove old pending events that never completed a cluster.
     * Also removes agent entries with no pending events to prevent unbounded map growth.
     */
    fun cleanup() {
        val cutoff = clock.now().minus(1000.milliseconds)
        val agentsToRemove = mutableListOf<String>()

        pendingEventsByAgent.forEach { (agentId, pending) ->
            pending.removeAll { it.receivedAt < cutoff }

            // Mark agents with empty pending lists for removal
            if (pending.isEmpty()) {
                agentsToRemove.add(agentId)
            }
        }

        // Remove agents with no pending events to prevent unbounded map growth
        agentsToRemove.forEach { agentId ->
            pendingEventsByAgent.remove(agentId)
        }

        // Also clean up stale lastRecall entries
        val staleRecalls = mutableListOf<String>()
        lastRecallByAgent.forEach { (agentId, pendingEvent) ->
            if (pendingEvent.receivedAt < cutoff) {
                staleRecalls.add(agentId)
            }
        }
        staleRecalls.forEach { agentId ->
            lastRecallByAgent.remove(agentId)
        }
    }

    /**
     * Get recent completed clusters for rendering.
     */
    fun getRecentClusters(): List<CognitiveCluster> {
        return completedClusters.toList()
    }
}
