package link.socket.ampere.agents.domain.event

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.type.AgentId

/** Source of an event, either an agent or a human. */
@Serializable
sealed class EventSource {

    @Serializable
    data class Agent(val agentId: AgentId) : EventSource()

    @Serializable
    data object Human : EventSource() {
        const val ID = "human"
    }

    fun getIdentifier(): String = when (this) {
        is Agent -> agentId
        is Human -> Human.ID
    }
}
