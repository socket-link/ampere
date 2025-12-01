package link.socket.ampere.agents.core

import kotlinx.serialization.Serializable

@Serializable
sealed interface AssignedTo {

    @Serializable
    data class Agent(val agentId: AgentId) : AssignedTo

    @Serializable
    data class Team(val teamId: TeamId) : AssignedTo

    @Serializable
    data object Human : AssignedTo

    fun getIdentifier(): String = when (this) {
        is Agent -> agentId
        is Team -> teamId
        is Human -> HUMAN_ID
    }

    companion object {
        const val HUMAN_ID = "human"
    }
}
