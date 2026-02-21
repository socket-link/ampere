package link.socket.ampere.api.service

import kotlinx.coroutines.flow.Flow
import link.socket.ampere.agents.events.messages.Message
import link.socket.ampere.agents.events.messages.MessageChannel
import link.socket.ampere.agents.events.messages.MessageThread
import link.socket.ampere.agents.events.messages.MessageThreadId
import link.socket.ampere.agents.events.messages.ThreadDetail
import link.socket.ampere.agents.events.messages.ThreadSummary
import link.socket.ampere.api.model.ThreadFilter

/**
 * SDK service for message threads and inter-agent communication.
 *
 * Maps to CLI commands: `thread list`, `thread show`, `message post`, `message create-thread`
 *
 * ```
 * val thread = ampere.threads.create("Auth design discussion") {
 *     participants("pm-agent", "engineer-agent")
 * }
 * ampere.threads.post(thread.id, "What about OAuth2 PKCE?")
 * ```
 */
interface ThreadService {

    /**
     * Create a new message thread.
     *
     * ```
     * // Simple form:
     * ampere.threads.create("Auth design discussion")
     *
     * // With builder DSL:
     * ampere.threads.create("Auth design discussion") {
     *     participants("pm-agent", "engineer-agent")
     *     channel(MessageChannel.Public.Engineering)
     * }
     * ```
     *
     * @param title Thread title
     * @param configure Optional builder for additional thread properties
     */
    suspend fun create(
        title: String,
        configure: (ThreadBuilder.() -> Unit)? = null,
    ): Result<MessageThread>

    /**
     * Post a message to a thread.
     *
     * ```
     * ampere.threads.post(threadId, "What about OAuth2 PKCE?")
     *
     * // As a specific sender:
     * ampere.threads.post(threadId, "Approved.", senderId = "pm-agent")
     * ```
     *
     * @param threadId The thread to post to
     * @param content The message content
     * @param senderId Who is sending the message (defaults to "human")
     */
    suspend fun post(
        threadId: MessageThreadId,
        content: String,
        senderId: String = "human",
    ): Result<Message>

    /**
     * Get a thread with its messages.
     *
     * ```
     * val thread = ampere.threads.get("thread-123")
     * thread.messages.forEach { println("${it.sender}: ${it.content}") }
     * ```
     */
    suspend fun get(threadId: MessageThreadId): Result<ThreadDetail>

    /**
     * List all threads, optionally filtered.
     *
     * ```
     * val threads = ampere.threads.list()
     * val escalated = ampere.threads.list(ThreadFilter(hasEscalations = true))
     * ```
     *
     * @param filter Optional filter criteria; null returns all active threads
     */
    suspend fun list(filter: ThreadFilter? = null): Result<List<ThreadSummary>>

    /**
     * Observe new messages in a thread as they arrive.
     *
     * ```
     * ampere.threads.observe("thread-123").collect { message ->
     *     println("${message.sender}: ${message.content}")
     * }
     * ```
     *
     * @param threadId The thread to observe
     * @return Flow emitting new messages as they are posted
     */
    fun observe(threadId: MessageThreadId): Flow<Message>
}

/**
 * Builder DSL for configuring a new thread.
 */
class ThreadBuilder {
    var participantIds: List<String> = emptyList()
    var channel: MessageChannel = MessageChannel.Public.Engineering

    /** Set the thread participants. */
    fun participants(vararg ids: String) { participantIds = ids.toList() }

    /** Set the message channel. */
    fun channel(value: MessageChannel) { channel = value }
}
