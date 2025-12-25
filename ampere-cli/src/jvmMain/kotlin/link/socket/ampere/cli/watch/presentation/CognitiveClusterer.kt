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
    private val clock: Clock = Clock.System
) {
    private val pendingEventsByAgent = mutableMapOf<String, MutableList<PendingEvent>>()
    private val completedClusters = mutableListOf<CognitiveCluster>()

    private data class PendingEvent(val event: Event, val receivedAt: Instant)

    /**
     * Process an event and potentially return a completed cluster.
     */
    fun processEvent(event: Event): CognitiveCluster? {
        val agentId = (event.eventSource as? EventSource.Agent)?.agentId ?: return null

        // Add to pending buffer
        val pending = pendingEventsByAgent.getOrPut(agentId) { mutableListOf() }
        pending.add(PendingEvent(event, clock.now()))

        // Check for completed patterns
        return checkForKnowledgeCluster(agentId)?.also {
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
     * Check for a KnowledgeRecalled + KnowledgeStored cluster pattern.
     */
    private fun checkForKnowledgeCluster(agentId: String): CognitiveCluster? {
        val pending = pendingEventsByAgent[agentId] ?: return null
        if (pending.size < 2) return null

        // Look for KnowledgeRecalled followed by KnowledgeStored
        for (i in 0 until pending.size - 1) {
            val first = pending[i]
            val second = pending[i + 1]

            if (first.event is MemoryEvent.KnowledgeRecalled &&
                second.event is MemoryEvent.KnowledgeStored
            ) {
                val timeDiff = second.event.timestamp.toEpochMilliseconds() -
                              first.event.timestamp.toEpochMilliseconds()

                if (timeDiff <= clusterWindowMs) {
                    return CognitiveCluster(
                        agentId = agentId,
                        startTimestamp = first.event.timestamp,
                        events = listOf(first.event, second.event),
                        cycleType = CognitiveClusterType.KNOWLEDGE_RECALL_STORE
                    )
                }
            }
        }

        return null
    }

    /**
     * Remove old pending events that never completed a cluster.
     */
    fun cleanup() {
        val cutoff = clock.now().minus(1000.milliseconds)
        pendingEventsByAgent.values.forEach { pending ->
            pending.removeAll { it.receivedAt < cutoff }
        }
    }

    /**
     * Get recent completed clusters for rendering.
     */
    fun getRecentClusters(): List<CognitiveCluster> {
        return completedClusters.toList()
    }
}
