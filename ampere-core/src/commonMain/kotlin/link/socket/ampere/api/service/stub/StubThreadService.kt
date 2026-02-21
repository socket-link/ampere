package link.socket.ampere.api.service.stub

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.status.EventStatus
import link.socket.ampere.agents.events.messages.Message
import link.socket.ampere.agents.events.messages.MessageChannel
import link.socket.ampere.agents.events.messages.MessageSender
import link.socket.ampere.agents.events.messages.MessageThread
import link.socket.ampere.agents.events.messages.MessageThreadId
import link.socket.ampere.agents.events.messages.ThreadDetail
import link.socket.ampere.agents.events.messages.ThreadSummary
import link.socket.ampere.api.model.ThreadFilter
import link.socket.ampere.api.service.ThreadBuilder
import link.socket.ampere.api.service.ThreadService

/**
 * Stub implementation of [ThreadService] for testing and parallel development.
 *
 * Creates real [MessageThread] and [Message] objects from provided parameters.
 */
class StubThreadService : ThreadService {

    private var threadCounter = 0
    private var messageCounter = 0

    override suspend fun create(
        title: String,
        configure: (ThreadBuilder.() -> Unit)?,
    ): Result<MessageThread> {
        threadCounter++
        val builder = ThreadBuilder()
        configure?.invoke(builder)
        val now = Clock.System.now()
        return Result.success(
            MessageThread(
                id = "stub-thread-$threadCounter",
                channel = builder.channel,
                createdBy = MessageSender.Human,
                participants = setOf(MessageSender.Human),
                messages = emptyList(),
                status = EventStatus.Open,
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    override suspend fun post(
        threadId: MessageThreadId,
        content: String,
        senderId: String,
    ): Result<Message> {
        messageCounter++
        return Result.success(
            Message(
                id = "stub-msg-$messageCounter",
                threadId = threadId,
                sender = if (senderId == "human") MessageSender.Human else MessageSender.Agent(senderId),
                content = content,
                timestamp = Clock.System.now(),
            )
        )
    }

    override suspend fun get(threadId: MessageThreadId): Result<ThreadDetail> =
        Result.success(
            ThreadDetail(
                threadId = threadId,
                title = "Stub Thread",
                messages = emptyList(),
                participants = emptyList(),
            )
        )

    override suspend fun list(filter: ThreadFilter?): Result<List<ThreadSummary>> =
        Result.success(emptyList())

    override fun observe(threadId: MessageThreadId): Flow<Message> = emptyFlow()
}
