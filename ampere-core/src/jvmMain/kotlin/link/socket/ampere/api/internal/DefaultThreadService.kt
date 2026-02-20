package link.socket.ampere.api.internal

import link.socket.ampere.agents.events.messages.Message
import link.socket.ampere.agents.events.messages.MessageChannel
import link.socket.ampere.agents.events.messages.MessageThread
import link.socket.ampere.agents.events.messages.MessageThreadId
import link.socket.ampere.agents.events.messages.ThreadDetail
import link.socket.ampere.agents.events.messages.ThreadSummary
import link.socket.ampere.agents.events.messages.ThreadViewService
import link.socket.ampere.agents.service.MessageActionService
import link.socket.ampere.api.service.ThreadService

internal class DefaultThreadService(
    private val actionService: MessageActionService,
    private val viewService: ThreadViewService,
) : ThreadService {

    override suspend fun create(
        title: String,
        participantIds: List<String>,
        channel: MessageChannel,
    ): Result<MessageThread> = actionService.createThread(title, participantIds, channel)

    override suspend fun post(
        threadId: MessageThreadId,
        content: String,
        senderId: String,
    ): Result<Message> = actionService.postMessage(threadId, content, senderId)

    override suspend fun get(threadId: MessageThreadId): Result<ThreadDetail> =
        viewService.getThreadDetail(threadId)

    override suspend fun list(): Result<List<ThreadSummary>> = viewService.listActiveThreads()
}
