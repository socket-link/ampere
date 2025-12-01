package link.socket.ampere.agents.events.escalation

import link.socket.ampere.agents.core.AgentId
import link.socket.ampere.agents.implementations.pm.ProductManagerState
import link.socket.ampere.agents.events.tickets.Ticket

/**
 * Context for evaluating escalation decisions.
 *
 * Contains information about the specific blocker and the overall project state.
 */
data class EscalationContext(
    /** The ticket that is being blocked. */
    val ticket: Ticket,
    /** The reason for the blocker. */
    val blockingReason: String,
    /** The agent reporting the blocker. */
    val reportedByAgentId: AgentId,
    /** The current project state. May be null if not available. */
    val projectState: ProductManagerState?,
)
