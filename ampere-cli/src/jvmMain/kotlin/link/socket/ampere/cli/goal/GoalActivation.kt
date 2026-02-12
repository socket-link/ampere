package link.socket.ampere.cli.goal

import link.socket.ampere.agents.definition.AgentId

/**
 * Represents an activated goal with its associated ticket and agent.
 *
 * This is returned by [GoalHandler.activateGoal] to provide information
 * about the created ticket and agent.
 */
data class GoalActivation(
    /** The ID of the created ticket */
    val ticketId: String,

    /** The ID of the agent assigned to work on the goal */
    val agentId: AgentId,

    /** The original goal description provided by the user */
    val description: String,

    /** The extracted/inferred title for the ticket */
    val title: String,
)
