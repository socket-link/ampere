package link.socket.kore.agents.events.messages

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.kore.agents.events.EventStatus

typealias MessageThreadId = String

@Serializable
data class MessageThread(
    val id: MessageThreadId,
    val channel: MessageChannel,
    val createdBy: MessageSender,
    val participants: Set<MessageSender>,
    val messages: List<Message>,
    val status: EventStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    // ** Function to create a new conversation thread with an initial message and participant. */
    companion object Companion {
        fun create(
            id: String,
            channel: MessageChannel,
            initialMessage: Message,
        ): MessageThread {
            val now = initialMessage.timestamp
            val initialParticipants = setOf(initialMessage.sender)

            return MessageThread(
                id = id,
                channel = channel,
                createdBy = initialMessage.sender,
                participants = initialParticipants,
                messages = listOf(initialMessage),
                status = EventStatus.OPEN,
                createdAt = now,
                updatedAt = now,
            )
        }
    }

    // ** Function to add a participant to the thread. */
    fun addParticipant(participant: MessageSender): MessageThread =
        copy(
            participants = participants + participant,
            updatedAt = Clock.System.now(),
        )

    // ** Function to remove a participant from the thread. */
    fun removeParticipant(participant: MessageSender): MessageThread =
        copy(
            participants = participants - participant,
        )

    // ** Function to clear all participants, usually after a thread has ended. */
    fun removeAllParticipants(): MessageThread =
        copy(
            participants = emptySet(),
        )

    fun addMessage(message: Message): MessageThread =
        copy(
            participants = participants + message.sender,
            messages = this.messages + message,
            updatedAt = message.timestamp,
        )

    // ** Function to update status after first checking that it's a valid status transition. */
    fun updateStatus(newStatus: EventStatus): MessageThread {
        val validStatusTransition = status.canTransitionTo(newStatus)

        require(validStatusTransition) {
            "Invalid status transition from ${this.status} to $newStatus"
        }

        return copy(
            status = newStatus,
            updatedAt = Clock.System.now(),
        )
    }
}
