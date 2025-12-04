package link.socket.ampere.agents.service

import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.concept.status.EventStatus
import link.socket.ampere.agents.domain.event.MessageEvent
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.messages.Message
import link.socket.ampere.agents.events.messages.MessageChannel
import link.socket.ampere.agents.events.messages.MessageRepository
import link.socket.ampere.agents.events.messages.MessageSender
import link.socket.ampere.agents.events.messages.MessageThread
import link.socket.ampere.agents.events.messages.MessageThreadId
import link.socket.ampere.agents.events.utils.generateUUID

/**
 * Service for message and thread operations that affect the substrate.
 */
class MessageActionService(
    private val messageRepository: MessageRepository,
    private val eventApi: AgentEventApi,
) {
    /**
     * Post a message to an existing thread and emit MessagePosted event.
     */
    suspend fun postMessage(
        threadId: MessageThreadId,
        content: String,
        senderId: String = "human",
    ): Result<Message> {
        return try {
            val now = Clock.System.now()
            val sender = MessageSender.fromSenderId(senderId)

            val message = Message(
                id = generateMessageId(),
                threadId = threadId,
                sender = sender,
                content = content,
                timestamp = now,
                metadata = null,
            )

            // Get the thread to find the channel
            val thread = messageRepository.findThreadById(threadId)
                .getOrNull()
                ?: return Result.failure(IllegalArgumentException("Thread not found: $threadId"))

            messageRepository.addMessageToThread(threadId, message)
                .onSuccess {
                    // Emit event to notify the substrate
                    val event = MessageEvent.MessagePosted(
                        eventId = generateUUID(message.id),
                        threadId = threadId,
                        channel = thread.channel,
                        message = message,
                    )
                    eventApi.publish(event)
                }

            Result.success(message)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Create a new message thread and emit ThreadCreated event.
     */
    suspend fun createThread(
        title: String,
        participantIds: List<String>,
        channel: MessageChannel = MessageChannel.Public.Engineering,
    ): Result<MessageThread> {
        return try {
            val now = Clock.System.now()
            val threadId = generateThreadId()

            // Create initial message for the thread
            val initialMessage = Message(
                id = generateMessageId(),
                threadId = threadId,
                sender = MessageSender.Human,
                content = "Thread created: $title",
                timestamp = now,
                metadata = null,
            )

            val participants = participantIds.map { MessageSender.fromSenderId(it) }.toSet() + MessageSender.Human

            val thread = MessageThread(
                id = threadId,
                channel = channel,
                createdBy = MessageSender.Human,
                participants = participants,
                messages = listOf(initialMessage),
                status = EventStatus.Open,
                createdAt = now,
                updatedAt = now,
            )

            messageRepository.saveThread(thread)
                .onSuccess {
                    // Emit event to notify the substrate
                    val event = MessageEvent.ThreadCreated(
                        eventId = generateUUID(threadId),
                        thread = thread,
                    )
                    eventApi.publish(event)
                }

            Result.success(thread)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun generateThreadId(): MessageThreadId {
        return "thread-${Clock.System.now().toEpochMilliseconds()}"
    }

    private fun generateMessageId(): String {
        return "msg-${Clock.System.now().toEpochMilliseconds()}"
    }
}
