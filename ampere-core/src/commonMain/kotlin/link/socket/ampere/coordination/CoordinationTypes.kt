package link.socket.ampere.coordination

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.events.meetings.Meeting

/**
 * Types of interactions that can occur between agents.
 */
@Serializable
enum class InteractionType {
    /** Task assigned from one agent to another */
    TICKET_ASSIGNED,

    /** Agent requesting clarification from another agent */
    CLARIFICATION_REQUEST,

    /** Agent responding to a clarification request */
    CLARIFICATION_RESPONSE,

    /** Agent requesting code review from another agent */
    REVIEW_REQUEST,

    /** Agent completing a code review */
    REVIEW_COMPLETE,

    /** Agent inviting another to a meeting */
    MEETING_INVITE,

    /** Agent sending a message in a meeting */
    MEETING_MESSAGE,

    /** Agent requesting help from another agent */
    HELP_REQUEST,

    /** Agent responding to a help request */
    HELP_RESPONSE,

    /** Agent delegating work to another agent */
    DELEGATION,

    /** Agent escalating to a human */
    HUMAN_ESCALATION,

    /** Human responding to an escalation */
    HUMAN_RESPONSE,
}

/**
 * Represents a single interaction between two agents (or an agent and human).
 *
 * @property sourceAgentId The agent initiating the interaction
 * @property targetAgentId The agent receiving the interaction (null for human)
 * @property interactionType The type of interaction
 * @property timestamp When the interaction occurred
 * @property eventId The ID of the event that triggered this interaction
 * @property context Optional context about the interaction
 */
@Serializable
data class AgentInteraction(
    val sourceAgentId: String,
    val targetAgentId: String?,
    val interactionType: InteractionType,
    val timestamp: Instant,
    val eventId: String,
    val context: String? = null,
)

/**
 * Represents an edge in the coordination graph between two agents.
 *
 * @property sourceAgentId The source agent
 * @property targetAgentId The target agent
 * @property interactionCount Number of recent interactions
 * @property lastInteraction The most recent interaction
 * @property interactionTypes Types of interactions that have occurred
 */
@Serializable
data class CoordinationEdge(
    val sourceAgentId: String,
    val targetAgentId: String,
    val interactionCount: Int,
    val lastInteraction: Instant,
    val interactionTypes: Set<InteractionType>,
)

/**
 * Represents work that has been handed off from one agent to another but not yet acknowledged.
 *
 * @property fromAgentId The agent that initiated the handoff
 * @property toAgentId The agent that should receive the work
 * @property handoffType The type of handoff
 * @property description Description of the work being handed off
 * @property timestamp When the handoff was initiated
 * @property eventId The event that triggered the handoff
 */
@Serializable
data class PendingHandoff(
    val fromAgentId: AgentId,
    val toAgentId: AgentId,
    val handoffType: InteractionType,
    val description: String,
    val timestamp: Instant,
    val eventId: String,
)

/**
 * Represents an agent that is blocked waiting for input from another agent or human.
 *
 * @property agentId The blocked agent
 * @property blockedBy The agent or human that the agent is waiting on
 * @property reason Why the agent is blocked
 * @property since When the agent became blocked
 * @property eventId The event that caused the blockage
 */
@Serializable
data class BlockedAgent(
    val agentId: AgentId,
    val blockedBy: String,
    val reason: String,
    val since: Instant,
    val eventId: String,
)

/**
 * Represents an active meeting that is currently in progress.
 *
 * @property meeting The meeting details
 * @property messageCount Number of messages exchanged so far
 * @property participants List of agent IDs participating
 */
@Serializable
data class ActiveMeeting(
    val meeting: Meeting,
    val messageCount: Int,
    val participants: List<AgentId>,
)

/**
 * A snapshot of the current coordination state in the system.
 *
 * @property edges Active coordination edges between agents
 * @property activeMeetings Meetings currently in progress
 * @property pendingHandoffs Work handoffs awaiting acknowledgment
 * @property blockedAgents Agents currently blocked waiting on others
 * @property recentInteractions Recent interactions in chronological order
 * @property lastUpdated When this snapshot was taken
 */
@Serializable
data class CoordinationState(
    val edges: List<CoordinationEdge>,
    val activeMeetings: List<ActiveMeeting>,
    val pendingHandoffs: List<PendingHandoff>,
    val blockedAgents: List<BlockedAgent>,
    val recentInteractions: List<AgentInteraction>,
    val lastUpdated: Instant,
)

/**
 * Statistics about coordination patterns in the system.
 *
 * @property totalInteractions Total number of interactions tracked
 * @property uniqueAgentPairs Number of unique agent pairs that have interacted
 * @property mostActiveAgent Agent with the most interactions
 * @property interactionsByType Breakdown of interactions by type
 * @property averageInteractionsPerAgent Average interactions per agent
 */
@Serializable
data class CoordinationStatistics(
    val totalInteractions: Int,
    val uniqueAgentPairs: Int,
    val mostActiveAgent: AgentId?,
    val interactionsByType: Map<InteractionType, Int>,
    val averageInteractionsPerAgent: Double,
)
