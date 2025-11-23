package link.socket.ampere.agents.core

import kotlinx.serialization.Serializable

typealias TeamId = String
typealias SprintId = String
typealias PRId = String

@Serializable
sealed interface AssignedTo {

    @Serializable
    data class Team(val teamId: TeamId) : AssignedTo

    @Serializable
    data class Agent(val agentId: AgentId) : AssignedTo

    @Serializable
    data object Human : AssignedTo

    fun getIdentifier(): String = when (this) {
        is Agent -> agentId
        is Human -> "human"
        is Team -> teamId
    }
}
