package link.socket.ampere.room

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventId
import link.socket.ampere.agents.domain.event.MessageEvent
import link.socket.ampere.agents.domain.event.RoomEvent
import link.socket.ampere.agents.domain.status.EventStatus
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.messages.Message
import link.socket.ampere.agents.events.messages.MessageChannel
import link.socket.ampere.agents.events.messages.MessageId
import link.socket.ampere.agents.events.messages.MessageRepository
import link.socket.ampere.agents.events.messages.MessageSender
import link.socket.ampere.agents.events.messages.MessageThread
import link.socket.ampere.agents.events.messages.MessageThreadId
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.canon.CanonWorkGraph
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.roster.RoleId
import link.socket.ampere.util.randomUUID

/** How many posts a single [DefaultRoomService.transcript] collector buffers before the oldest is dropped. */
const val DEFAULT_TRANSCRIPT_BUFFER_CAPACITY: Int = 256

/**
 * [RoomService] over `MessageRepository` and the event door (AMPR-379).
 *
 * Writes go the way `MessageActionService` already writes: the repository first, then
 * the thread primitive's own event (`ThreadCreated`, `MessagePosted`,
 * `ThreadStatusChanged`) through [eventApi], then the `RoomEvent` that says what the
 * write meant to the Room, `causedBy` the primitive's event. A repository write that
 * lands but whose event does not is returned as a failure and not retried, matching
 * the orchestrators.
 *
 * Posting as any [Author] — six roles and the human — is why this does not go
 * through `AgentMessageApi`, which attributes every post to the one agent it was
 * built for.
 *
 * @param host the role that opens threads and writes their opening line.
 * @param clock every timestamp this service stamps; pin it in tests.
 * @param idGenerator every message and event id; pin it in tests.
 */
class DefaultRoomService(
    private val messageRepository: MessageRepository,
    private val eventApi: AgentEventApi,
    private val host: Author.Role,
    private val clock: Clock = Clock.System,
    private val idGenerator: () -> String = { randomUUID() },
    private val json: Json = DEFAULT_JSON,
    private val transcriptCapacity: Int = DEFAULT_TRANSCRIPT_BUFFER_CAPACITY,
) : RoomService {

    private data class Ensured(
        val threadId: MessageThreadId,
        val created: Boolean,
        val openedEventId: EventId? = null,
    )

    override suspend fun open(graph: CanonWorkGraph, causedBy: EventId?): Result<RoomId> {
        val roomId = RoomId.forProject(graph.project.canonId)

        val general = ensureThread(
            roomId = roomId,
            subject = ThreadSubject.General,
            title = "Room opened for ${graph.project.name}",
            assignedTo = null,
            causedBy = causedBy,
        ).getOrElse { return Result.failure(it) }

        if (general.created) {
            val opened = RoomEvent.RoomOpened(
                eventId = idGenerator(),
                timestamp = clock.now(),
                eventSource = host.eventSource(),
                roomId = roomId.value,
                projectId = graph.project.canonId.value,
                generalThreadId = general.threadId,
                milestones = graph.milestones.size,
            )
            eventApi.publish(opened, causedBy = general.openedEventId)
                .onFailure { return persistence("publish RoomOpened", it) }
        }

        graph.milestones.forEach { milestone ->
            ensureThread(
                roomId = roomId,
                subject = ThreadSubject.Milestone(milestone.canonId),
                title = "Milestone: ${milestone.name}",
                assignedTo = null,
                causedBy = causedBy,
            ).getOrElse { return Result.failure(it) }
        }

        return Result.success(roomId)
    }

    override suspend fun thread(
        roomId: RoomId,
        subject: ThreadSubject,
        assignedTo: RoleId?,
        title: String?,
        causedBy: EventId?,
    ): Result<MessageThreadId> =
        ensureThread(roomId, subject, title, assignedTo, causedBy).map { it.threadId }

    override suspend fun post(
        threadId: MessageThreadId,
        author: Author,
        body: String,
        card: RoomCard?,
        causedBy: EventId?,
    ): Result<MessageId> {
        val thread = messageRepository.findThreadById(threadId)
            .getOrElse { return roomFailure(RoomFailure.ThreadNotFound(threadId), it) }
        val channel = thread.channel as? MessageChannel.Room
            ?: return roomFailure(RoomFailure.NotARoomThread(threadId))

        if (author is Author.Role) {
            when (thread.status) {
                EventStatus.WaitingForHuman -> return roomFailure(RoomFailure.ThreadWaitingForHuman(threadId))
                EventStatus.Resolved -> return roomFailure(RoomFailure.ThreadResolved(threadId))
                EventStatus.Open -> Unit
            }
        }

        val message = Message(
            id = idGenerator(),
            threadId = threadId,
            sender = author.toSender(),
            content = body,
            timestamp = clock.now(),
            metadata = RoomMetadata.card(card, json),
        )

        messageRepository.addMessageToThread(threadId, message)
            .onFailure { return persistence("addMessageToThread", it) }

        val posted = MessageEvent.MessagePosted(
            eventId = idGenerator(),
            threadId = threadId,
            channel = channel,
            message = message,
        )
        eventApi.publish(posted, causedBy = causedBy)
            .onFailure { return persistence("publish MessagePosted", it) }

        // The repository writes `Open` under every post. For a human answering a waiting
        // or resolved thread that is the right state, but it must not be a silent one.
        if (thread.status != EventStatus.Open) {
            eventApi.publish(
                MessageEvent.ThreadStatusChanged(
                    eventId = idGenerator(),
                    timestamp = message.timestamp,
                    eventSource = author.eventSource(),
                    threadId = threadId,
                    oldStatus = thread.status,
                    newStatus = EventStatus.Open,
                ),
                causedBy = posted.eventId,
            ).onFailure { return persistence("publish ThreadStatusChanged", it) }
        }

        eventApi.publish(
            RoomEvent.Posted(
                eventId = idGenerator(),
                timestamp = message.timestamp,
                eventSource = author.eventSource(),
                roomId = channel.roomId,
                threadId = threadId,
                subject = RoomMetadata.subjectOf(thread, json),
                messageId = message.id,
                author = author,
                body = body,
                card = card,
            ),
            causedBy = posted.eventId,
        ).onFailure { return persistence("publish RoomEvent.Posted", it) }

        return Result.success(message.id)
    }

    override suspend fun resolve(
        threadId: MessageThreadId,
        by: Author,
        reason: String,
        causedBy: EventId?,
    ): Result<Unit> {
        val thread = messageRepository.findThreadById(threadId)
            .getOrElse { return roomFailure(RoomFailure.ThreadNotFound(threadId), it) }
        val channel = thread.channel as? MessageChannel.Room
            ?: return roomFailure(RoomFailure.NotARoomThread(threadId))

        if (thread.status == EventStatus.Resolved) return Result.success(Unit)

        messageRepository.updateStatus(threadId, EventStatus.Resolved)
            .onFailure { return persistence("updateStatus", it) }

        val now = clock.now()
        val changed = MessageEvent.ThreadStatusChanged(
            eventId = idGenerator(),
            timestamp = now,
            eventSource = by.eventSource(),
            threadId = threadId,
            oldStatus = thread.status,
            newStatus = EventStatus.Resolved,
        )
        eventApi.publish(changed, causedBy = causedBy)
            .onFailure { return persistence("publish ThreadStatusChanged", it) }

        eventApi.publish(
            RoomEvent.ThreadResolved(
                eventId = idGenerator(),
                timestamp = now,
                eventSource = by.eventSource(),
                roomId = channel.roomId,
                threadId = threadId,
                subject = RoomMetadata.subjectOf(thread, json),
                resolvedBy = by,
                reason = reason,
            ),
            causedBy = changed.eventId,
        ).onFailure { return persistence("publish RoomEvent.ThreadResolved", it) }

        return Result.success(Unit)
    }

    override suspend fun threads(roomId: RoomId): Result<List<RoomThread>> =
        roomThreads(roomId).map { threads ->
            threads.map { thread ->
                RoomThread(
                    roomId = roomId,
                    threadId = thread.id,
                    subject = RoomMetadata.subjectOf(thread, json),
                    assignedTo = RoomMetadata.assignedTo(thread),
                    status = thread.status,
                    openedAt = thread.createdAt,
                )
            }
        }

    override suspend fun history(roomId: RoomId): Result<List<RoomMessage>> =
        roomThreads(roomId).map { threads ->
            threads
                .flatMap { thread ->
                    val subject = RoomMetadata.subjectOf(thread, json)
                    thread.messages.map { message ->
                        RoomMessage(
                            roomId = roomId,
                            threadId = thread.id,
                            subject = subject,
                            messageId = message.id,
                            author = Author.fromSender(message.sender),
                            body = message.content,
                            card = RoomMetadata.cardOf(message, json),
                            postedAt = message.timestamp,
                        )
                    }
                }
                .sortedWith(compareBy({ it.postedAt }, { it.threadId }))
        }

    override fun transcript(roomId: RoomId): Flow<RoomMessage> = flow {
        val droppedTotal = MutableStateFlow(0L)
        val channel = Channel<RoomMessage>(
            capacity = transcriptCapacity,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
            onUndeliveredElement = { droppedTotal.updateAndGet { it + 1 } },
        )

        val bus = eventApi.eventSerialBus
        val subscription = bus.subscribeSuspending(
            agentId = "room-transcript-${generateUUID()}",
            eventType = RoomEvent.Posted.EVENT_TYPE,
            handler = EventHandler { event: Event, _ ->
                val posted = event as? RoomEvent.Posted
                if (posted != null && posted.roomId == roomId.value) {
                    channel.trySend(posted.toRoomMessage())
                }
            },
        )

        try {
            for (message in channel) {
                emit(message)
            }
        } finally {
            // Releasing the subscription needs the bus mutex, which a cancelled coroutine
            // cannot take; see `EventSerialBus.emissions`.
            withContext(NonCancellable) {
                bus.unsubscribeSuspending(subscription)
            }
            channel.close()
        }
    }

    private suspend fun roomThreads(roomId: RoomId): Result<List<MessageThread>> =
        messageRepository.findAllThreads().fold(
            onSuccess = { threads ->
                Result.success(
                    threads
                        .filter { RoomId.of(it.channel) == roomId }
                        .sortedWith(compareBy({ it.createdAt }, { it.id })),
                )
            },
            onFailure = { persistence("findAllThreads", it) },
        )

    private suspend fun ensureThread(
        roomId: RoomId,
        subject: ThreadSubject,
        title: String?,
        assignedTo: RoleId?,
        causedBy: EventId?,
    ): Result<Ensured> {
        val threadId = roomThreadId(roomId, subject)

        messageRepository.findThreadById(threadId)
            .onSuccess { return Result.success(Ensured(threadId, created = false)) }

        val now = clock.now()
        val opening = Message(
            id = idGenerator(),
            threadId = threadId,
            sender = host.toSender(),
            content = title ?: subject.defaultTitle(),
            timestamp = now,
            metadata = RoomMetadata.opening(subject, assignedTo, json),
        )
        val participants = buildSet<MessageSender> {
            add(host.toSender())
            assignedTo?.let { add(MessageSender.Agent(it.value)) }
        }
        val thread = MessageThread(
            id = threadId,
            channel = roomId.channel(),
            createdBy = host.toSender(),
            participants = participants,
            messages = listOf(opening),
            status = EventStatus.Open,
            createdAt = now,
            updatedAt = now,
        )

        messageRepository.saveThread(thread).onFailure { throwable ->
            // Lost the race to another opener: the row exists now, and that is the
            // idempotent answer. Anything else is the store refusing the write.
            messageRepository.findThreadById(threadId)
                .onSuccess { return Result.success(Ensured(threadId, created = false)) }
            return persistence("saveThread", throwable)
        }

        val created = MessageEvent.ThreadCreated(eventId = idGenerator(), thread = thread)
        eventApi.publish(created, causedBy = causedBy)
            .onFailure { return persistence("publish ThreadCreated", it) }

        eventApi.publish(
            MessageEvent.MessagePosted(
                eventId = idGenerator(),
                threadId = threadId,
                channel = thread.channel,
                message = opening,
            ),
            causedBy = created.eventId,
        ).onFailure { return persistence("publish MessagePosted", it) }

        val opened = RoomEvent.ThreadOpened(
            eventId = idGenerator(),
            timestamp = now,
            eventSource = host.eventSource(),
            roomId = roomId.value,
            threadId = threadId,
            subject = subject,
            openedBy = host,
            assignedTo = assignedTo,
        )
        eventApi.publish(opened, causedBy = created.eventId)
            .onFailure { return persistence("publish RoomEvent.ThreadOpened", it) }

        return Result.success(Ensured(threadId, created = true, openedEventId = opened.eventId))
    }

    private fun <T> persistence(operation: String, cause: Throwable): Result<T> =
        roomFailure(RoomFailure.Persistence(operation, cause.describe()), cause)

    private fun Throwable.describe(): String = message ?: this::class.simpleName.orEmpty()
}

/** The transcript line a `RoomEvent.Posted` describes. */
fun RoomEvent.Posted.toRoomMessage(): RoomMessage = RoomMessage(
    roomId = RoomId(roomId),
    threadId = threadId,
    subject = subject,
    messageId = messageId,
    author = author,
    body = body,
    card = card,
    postedAt = timestamp,
)

/** The opening line of a thread nobody titled. */
internal fun ThreadSubject.defaultTitle(): String = when (this) {
    ThreadSubject.General -> "Room opened"
    is ThreadSubject.Milestone -> "Milestone ${milestoneId.value}"
    is ThreadSubject.Verdict -> "${kind.name.lowercase()} verdict on $subjectId"
}
