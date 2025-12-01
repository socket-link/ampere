package link.socket.ampere.agents.events.messages

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Service providing high-level views of thread state.
 *
 * This transforms raw message data into the kind of summary information
 * that's useful for presentation: participant lists, activity timestamps,
 * message counts, escalation status.
 */
interface ThreadViewService {
    /**
     * Get summary information for all active threads.
     * Active means threads that have messages and aren't archived.
     */
    suspend fun listActiveThreads(): Result<List<ThreadSummary>>

    /**
     * Get complete details for a specific thread including all messages.
     */
    suspend fun getThreadDetail(threadId: MessageThreadId): Result<ThreadDetail>
}

/**
 * Summary information about a thread for list displays.
 */
@Serializable
data class ThreadSummary(
    val threadId: MessageThreadId,
    val title: String,
    val messageCount: Int,
    val participantIds: List<String>,
    val lastActivity: Instant,
    val hasUnreadEscalations: Boolean,
)

/**
 * Complete thread contents for detail views.
 */
@Serializable
data class ThreadDetail(
    val threadId: MessageThreadId,
    val title: String,
    val messages: List<Message>,
    val participants: List<String>,
)
