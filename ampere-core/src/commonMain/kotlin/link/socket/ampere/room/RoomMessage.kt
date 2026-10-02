package link.socket.ampere.room

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.status.EventStatus
import link.socket.ampere.agents.events.messages.MessageId
import link.socket.ampere.agents.events.messages.MessageThreadId
import link.socket.ampere.roster.RoleId

/**
 * One line of a Room transcript (AMPR-379): a message with its Room-level context
 * attached — which thread, what that thread is about, who wrote it, and the card.
 */
@Serializable
data class RoomMessage(
    val roomId: RoomId,
    val threadId: MessageThreadId,
    val subject: ThreadSubject?,
    val messageId: MessageId,
    val author: Author,
    val body: String,
    val card: RoomCard? = null,
    val postedAt: Instant,
)

/**
 * One thread of a Room as the Room sees it: its subject, the role it is assigned
 * to, and the thread primitive's status.
 */
@Serializable
data class RoomThread(
    val roomId: RoomId,
    val threadId: MessageThreadId,
    val subject: ThreadSubject?,
    val assignedTo: RoleId? = null,
    val status: EventStatus,
    val openedAt: Instant,
)
