package link.socket.ampere.agents.events.messages

import link.socket.ampere.agents.domain.status.EventStatus

/**
 * Default implementation of ThreadViewService that queries thread state
 * through MessageRepository and transforms it into presentation-friendly formats.
 */
class DefaultThreadViewService(
    private val messageRepository: MessageRepository,
) : ThreadViewService {

    override suspend fun listActiveThreads(): Result<List<ThreadSummary>> =
        messageRepository.findAllThreads().mapCatching { threads ->
            threads
                .filter { it.messages.isNotEmpty() && it.status != EventStatus.Resolved }
                .map { thread ->
                    ThreadSummary(
                        threadId = thread.id,
                        title = deriveThreadTitle(thread),
                        messageCount = thread.messages.size,
                        participantIds = thread.participants.map { it.getIdentifier() },
                        lastActivity = thread.updatedAt,
                        hasUnreadEscalations = thread.status == EventStatus.WaitingForHuman,
                    )
                }
                .sortedByDescending { it.lastActivity }
        }

    override suspend fun getThreadDetail(threadId: MessageThreadId): Result<ThreadDetail> =
        messageRepository.findThreadById(threadId).map { thread ->
            ThreadDetail(
                threadId = thread.id,
                title = deriveThreadTitle(thread),
                messages = thread.messages.sortedBy { it.timestamp },
                participants = thread.participants.map { it.getIdentifier() },
            )
        }

    /**
     * Derives a human-readable title for the thread.
     * Uses the channel name and first message preview.
     */
    private fun deriveThreadTitle(thread: MessageThread): String {
        val channelName = when (val channel = thread.channel) {
            is MessageChannel.Public -> channel.id
            is MessageChannel.Direct -> "DM with ${channel.sender.agentId}"
        }

        val preview = if (thread.messages.isNotEmpty()) {
            val firstMessage = thread.messages.first().content
            val truncated = firstMessage.take(50)
            if (firstMessage.length > 50) "$truncated..." else truncated
        } else {
            "Empty thread"
        }

        return "$channelName: $preview"
    }
}
