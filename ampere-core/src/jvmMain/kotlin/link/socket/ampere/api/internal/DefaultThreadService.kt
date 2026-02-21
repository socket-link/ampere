package link.socket.ampere.api.internal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import link.socket.ampere.agents.events.messages.Message
import link.socket.ampere.agents.events.messages.MessageThread
import link.socket.ampere.agents.events.messages.MessageThreadId
import link.socket.ampere.agents.events.messages.ThreadDetail
import link.socket.ampere.agents.events.messages.ThreadSummary
import link.socket.ampere.agents.events.messages.ThreadViewService
import link.socket.ampere.agents.service.MessageActionService
import link.socket.ampere.api.model.ThreadFilter
import link.socket.ampere.api.service.ThreadBuilder
import link.socket.ampere.api.service.ThreadService

internal class DefaultThreadService(
    private val actionService: MessageActionService,
    private val viewService: ThreadViewService,
) : ThreadService {

    override suspend fun create(
        title: String,
        configure: (ThreadBuilder.() -> Unit)?,
    ): Result<MessageThread> {
        val builder = ThreadBuilder()
        configure?.invoke(builder)
        return actionService.createThread(title, builder.participantIds, builder.channel)
    }

    override suspend fun post(
        threadId: MessageThreadId,
        content: String,
        senderId: String,
    ): Result<Message> = actionService.postMessage(threadId, content, senderId)

    override suspend fun get(threadId: MessageThreadId): Result<ThreadDetail> =
        viewService.getThreadDetail(threadId)

    override suspend fun list(filter: ThreadFilter?): Result<List<ThreadSummary>> =
        viewService.listActiveThreads()

    override fun observe(threadId: MessageThreadId): Flow<Message> {
        // Thread-level observation requires MessageRouter subscription.
        // For now, return an empty flow — full implementation will wire to MessageRouter.
        return emptyFlow()
    }
}
