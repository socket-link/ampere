package link.socket.ampere.room

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.events.messages.MessageSender
import link.socket.ampere.roster.RoleId

/**
 * Who posted in a Room: a role on the roster, or the human (AMPR-379).
 *
 * Maps onto the thread primitive's `MessageSender` — a role posts as the agent whose
 * id is the role id, the human as `MessageSender.Human` — so a Room message is an
 * ordinary thread message with a readable sender. The mapping is reversible, which
 * is how `history` recovers authorship from stored rows.
 */
@Serializable
sealed interface Author {

    @Serializable
    @SerialName("Author.Role")
    data class Role(val id: RoleId) : Author

    @Serializable
    @SerialName("Author.Human")
    data object Human : Author

    fun toSender(): MessageSender = when (this) {
        is Role -> MessageSender.Agent(id.value)
        Human -> MessageSender.Human
    }

    fun eventSource(): EventSource = when (this) {
        is Role -> EventSource.Agent(id.value)
        Human -> EventSource.Human
    }

    /** The word a transcript shows: the role id, or `human`. */
    val label: String
        get() = when (this) {
            is Role -> id.value
            Human -> "human"
        }

    companion object {
        fun fromSender(sender: MessageSender): Author = when (sender) {
            is MessageSender.Agent -> Role(RoleId(sender.agentId))
            MessageSender.Human -> Human
        }
    }
}
