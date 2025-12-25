package link.socket.ampere.cli.watch.presentation

import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.Event

/**
 * Represents a group of related events that form one cognitive cycle.
 *
 * In biological terms, this is like grouping the action potential,
 * neurotransmitter release, and receptor binding into one "neuron fired" event
 * rather than showing three separate molecular events.
 */
data class CognitiveCluster(
    val agentId: String,
    val startTimestamp: Instant,
    val events: List<Event>,
    val cycleType: CognitiveClusterType
) {
    val durationMillis: Long = events.lastOrNull()?.timestamp?.toEpochMilliseconds()
        ?.minus(startTimestamp.toEpochMilliseconds()) ?: 0
}

enum class CognitiveClusterType {
    KNOWLEDGE_RECALL_STORE,
    TASK_PROCESSING,
    MEETING_PARTICIPATION
}
