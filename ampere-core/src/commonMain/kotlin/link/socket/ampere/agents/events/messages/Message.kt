package link.socket.ampere.agents.events.messages

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.event.EventSource

typealias MessageSenderId = String
typealias MessageId = String

@Serializable
sealed class MessageSender {

    @Serializable
    data class Agent(val agentId: AgentId) : MessageSender()

    @Serializable
    data object Human : MessageSender()

    fun getIdentifier(): MessageSenderId = when (this) {
        is Agent -> agentId
        is Human -> HUMAN_ID
    }

    companion object {
        private const val HUMAN_ID: MessageSenderId = "human"

        fun fromSenderId(id: MessageSenderId): MessageSender = when (id) {
            HUMAN_ID -> Human
            else -> Agent(id)
        }
    }
}

@Serializable
data class Message(
    val id: MessageId,
    val threadId: MessageThreadId,
    val sender: MessageSender,
    val content: String,
    val timestamp: Instant,
    val metadata: Map<String, String>? = null,
)

fun MessageSender.toEventSource(): EventSource =
    when (this) {
        is MessageSender.Agent -> EventSource.Agent(agentId)
        MessageSender.Human -> EventSource.Human
    }
