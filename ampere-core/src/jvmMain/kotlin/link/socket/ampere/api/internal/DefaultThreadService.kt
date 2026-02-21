package link.socket.ampere.api.internal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import link.socket.ampere.agents.domain.event.MessageEvent
import link.socket.ampere.agents.events.messages.Message
import link.socket.ampere.agents.events.messages.MessageThread
import link.socket.ampere.agents.events.messages.MessageThreadId
import link.socket.ampere.agents.events.messages.ThreadDetail
import link.socket.ampere.agents.events.messages.ThreadSummary
import link.socket.ampere.agents.events.messages.ThreadViewService
import link.socket.ampere.agents.events.relay.EventRelayFilters
import link.socket.ampere.agents.events.relay.EventRelayService
import link.socket.ampere.agents.service.MessageActionService
import link.socket.ampere.api.model.ThreadFilter
import link.socket.ampere.api.service.ThreadBuilder
import link.socket.ampere.api.service.ThreadService

internal class DefaultThreadService(
    private val actionService: MessageActionService,
    private val viewService: ThreadViewService,
    private val eventRelayService: EventRelayService,
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

    override suspend fun list(filter: ThreadFilter?): Result<List<ThreadSummary>> {
        val threads = viewService.listActiveThreads().getOrElse { return Result.failure(it) }
        if (filter == null) return Result.success(threads)

        val filtered = threads.filter { thread ->
            (filter.participantId == null || filter.participantId in thread.participantIds) &&
                (filter.hasEscalations == null || thread.hasUnreadEscalations == filter.hasEscalations)
        }
        return Result.success(filtered)
    }

    override fun observe(threadId: MessageThreadId): Flow<Message> {
        val filters = EventRelayFilters.forEventType(MessageEvent.MessagePosted.EVENT_TYPE)
        return eventRelayService.subscribeToLiveEvents(filters)
            .mapNotNull { event ->
                (event as? MessageEvent.MessagePosted)
                    ?.takeIf { it.threadId == threadId }
                    ?.message
            }
    }
}
