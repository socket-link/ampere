package link.socket.ampere.api.service

import link.socket.ampere.agents.events.messages.Message
import link.socket.ampere.agents.events.messages.MessageChannel
import link.socket.ampere.agents.events.messages.MessageThread
import link.socket.ampere.agents.events.messages.MessageThreadId
import link.socket.ampere.agents.events.messages.ThreadDetail
import link.socket.ampere.agents.events.messages.ThreadSummary

/**
 * SDK service for message threads and inter-agent communication.
 *
 * Maps to CLI commands: `thread list`, `thread show`, `message post`, `message create-thread`
 */
interface ThreadService {

    /**
     * Create a new message thread.
     *
     * ```
     * ampere.threads.create(
     *     title = "Auth design discussion",
     *     participantIds = listOf("pm-agent", "engineer-agent"),
     * )
     * ```
     */
    suspend fun create(
        title: String,
        participantIds: List<String> = emptyList(),
        channel: MessageChannel = MessageChannel.Public.Engineering,
    ): Result<MessageThread>

    /**
     * Post a message to a thread.
     *
     * ```
     * ampere.threads.post(threadId, "What about OAuth2 PKCE?")
     * ```
     */
    suspend fun post(
        threadId: MessageThreadId,
        content: String,
        senderId: String = "human",
    ): Result<Message>

    /**
     * Get a thread with its messages.
     */
    suspend fun get(threadId: MessageThreadId): Result<ThreadDetail>

    /**
     * List all active threads.
     */
    suspend fun list(): Result<List<ThreadSummary>>
}
