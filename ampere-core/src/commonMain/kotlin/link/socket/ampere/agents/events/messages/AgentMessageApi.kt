package link.socket.ampere.agents.events.messages

import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.emission.ConsoleSurfaceIO
import link.socket.ampere.agents.domain.emission.DefaultSurfacePolicy
import link.socket.ampere.agents.domain.emission.EmissionReplyRegistry
import link.socket.ampere.agents.domain.emission.GlobalEmissionReplyRegistry
import link.socket.ampere.agents.domain.emission.Surface
import link.socket.ampere.agents.domain.emission.SurfacePolicy
import link.socket.ampere.agents.domain.emission.emission
import link.socket.ampere.agents.domain.emission.extractFreeText
import link.socket.ampere.agents.domain.event.EventId
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.MessageEvent
import link.socket.ampere.agents.domain.status.EventStatus
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.api.EventFilter
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.subscription.Subscription
import link.socket.ampere.agents.events.utils.ConsoleEventLogger
import link.socket.ampere.agents.events.utils.EventLogger
import link.socket.ampere.util.randomUUID

/**
 * Service layer that orchestrates message thread operations, enforces business rules,
 * persists state via MessageRepository, and publishes domain events through the door
 * ([AgentEventApi.publish], F1).
 *
 * Every event leaves through [eventApi], so it is persisted to the `EventStore` before any
 * subscriber sees it. The repository write commits first. Each mutation takes an optional
 * `causedBy` — the event whose handling produced it (F2) — which is stamped on the envelope of
 * the first event the mutation publishes; a second event in the same mutation is caused by the
 * first.
 *
 * Persist failures surface differently by return type: [escalateToHuman], [resolveThread] and
 * [reopenThread] return a [Result] that folds them in; [createThread] and [postMessage] keep
 * their value-returning signatures (they are called from outside this file set), so a failed
 * publish there is logged by the door and the repository write stands.
 */
class AgentMessageApi(
    val agentId: AgentId,
    private val messageRepository: MessageRepository,
    private val eventApi: AgentEventApi,
    private val emissionReplyRegistry: EmissionReplyRegistry = GlobalEmissionReplyRegistry.instance,
    private val surfacePolicy: SurfacePolicy = DefaultSurfacePolicy(),
    private val logger: EventLogger = ConsoleEventLogger(),
) {

    /**
     * Create a new message thread with an initial message and publish `ThreadCreated` then
     * `MessagePosted` (caused by the `ThreadCreated`).
     *
     * @param causedBy the event whose handling created this thread, if any.
     */
    suspend fun createThread(
        participants: Set<MessageSenderId>,
        channel: MessageChannel,
        initialMessageContent: String,
        causedBy: EventId? = null,
    ): MessageThread {
        val now = Clock.System.now()
        val threadId = randomUUID()

        val sender = MessageSender.fromSenderId(agentId)
        val message = Message(
            id = randomUUID(),
            threadId = threadId,
            sender = sender,
            content = initialMessageContent,
            timestamp = now,
            metadata = null,
        )

        // create base thread from the initial message
        var thread = MessageThread.create(
            id = threadId,
            channel = channel,
            initialMessage = message,
        )

        // merge and update for provided participants (if any)
        if (participants.isNotEmpty()) {
            val extraParticipants = participants
                .map { participant ->
                    MessageSender.fromSenderId(participant)
                }.toSet()

            val merged = (thread.participants + extraParticipants)
                .distinctBy { it.getIdentifier() }
                .toSet()

            thread = thread.copy(participants = merged)
        }

        messageRepository
            .saveThread(thread)
            .onSuccess {
                // Publish creation and initial message posted events through the door
                val threadCreated = MessageEvent.ThreadCreated(
                    eventId = randomUUID(),
                    thread = thread,
                )
                eventApi.publish(threadCreated, causedBy = causedBy)

                eventApi.publish(
                    MessageEvent.MessagePosted(
                        eventId = randomUUID(),
                        threadId = thread.id,
                        channel = thread.channel,
                        message = message,
                    ),
                    causedBy = threadCreated.eventId,
                )
            }
            .onFailure { throwable ->
                logger.logError(
                    message = "Failed to create thread in channel ${thread.channel} from $sender",
                    throwable = throwable,
                )
            }

        return thread
    }

    /**
     * Post a message to an existing thread. Throws if thread is blocked waiting for human.
     *
     * @param causedBy the event whose handling produced this message, if any.
     */
    suspend fun postMessage(
        threadId: MessageThreadId,
        content: String,
        causedBy: EventId? = null,
    ): Message {
        val thread = messageRepository
            .findThreadById(threadId)
            .onFailure { throwable ->
                logger.logError(
                    message = "Failed to find thread with id $threadId",
                    throwable = throwable,
                )
                throw IllegalArgumentException("Thread not found: $threadId")
            }
            .getOrNull()

        requireNotNull(thread)
        require(thread.status != EventStatus.WaitingForHuman) {
            "Cannot post message while thread is waiting for human intervention"
        }

        val now = Clock.System.now()
        val message = Message(
            id = randomUUID(),
            threadId = threadId,
            sender = MessageSender.fromSenderId(agentId),
            content = content,
            timestamp = now,
            metadata = null,
        )

        messageRepository
            .addMessageToThread(threadId, message)
            .onSuccess {
                eventApi.publish(
                    MessageEvent.MessagePosted(
                        eventId = randomUUID(),
                        threadId = threadId,
                        channel = thread.channel,
                        message = message,
                    ),
                    causedBy = causedBy,
                )
            }
            .onFailure { throwable ->
                logger.logError(
                    message = "Failed to add message to thread with id $threadId",
                    throwable = throwable,
                )
            }

        return message
    }

    /**
     * Escalate a thread to human using the Emission protocol (CHI invariant: Q2).
     *
     * Orchestration order per the CHI cell invariant:
     * 1. Transition thread to [EventStatus.WaitingForHuman] **before** Emission publishes.
     * 2. Publish [MessageEvent.EscalationRequested] (caused by [causedBy]) and
     *    [MessageEvent.ThreadStatusChanged] (caused by the `EscalationRequested`) through the
     *    door, for thread observers (preserved for backward compatibility; not the primary CHI
     *    path).
     * 3. Produce a [HumanInteractionEvent.InputRequested] via the Emission DSL and suspend
     *    until the human replies.
     * 4. Transition thread back to [EventStatus.Open] and post the reply.
     *
     * Set [awaitReply] to `false` for legacy fire-and-forget thread escalation
     * where the caller only needs the durable status transition and persisted events.
     *
     * Thread state transitions are owned exclusively by this method — handlers must not
     * re-transition (CHI cell invariant).
     *
     * @param causedBy the event whose handling requested this escalation, if any.
     * @return failure when the thread is missing, the status transition or a publish in step 2
     * did not persist (the transition stands — it is not retried), or the awaited reply timed
     * out; success once the escalation is durable (and, with [awaitReply], answered).
     */
    suspend fun escalateToHuman(
        threadId: MessageThreadId,
        reason: String,
        context: Map<String, String> = emptyMap(),
        awaitReply: Boolean = true,
        causedBy: EventId? = null,
    ): Result<Unit> {
        val thread = messageRepository
            .findThreadById(threadId)
            .onFailure { throwable ->
                logger.logError(
                    message = "Failed to find thread with id $threadId",
                    throwable = throwable,
                )
            }
            .getOrNull()

        if (thread == null) {
            logger.logError(message = "Thread not found: $threadId")
            return Result.failure(IllegalArgumentException("Thread not found: $threadId"))
        }

        require(thread.status != EventStatus.Resolved) { "Cannot escalate a resolved thread" }

        val oldStatus = thread.status
        val newStatus = EventStatus.WaitingForHuman

        // Step 1: Transition FIRST — the CHI invariant's load-bearing constraint.
        messageRepository
            .updateStatus(threadId, newStatus)
            .onFailure { throwable ->
                logger.logError(
                    message = "Failed to update thread status to $newStatus for thread $threadId",
                    throwable = throwable,
                )
                return Result.failure(throwable)
            }

        val now = Clock.System.now()

        // Step 2: Persist and dispatch the escalation and the status change for thread observers.
        val escalationRequested = MessageEvent.EscalationRequested(
            eventId = randomUUID(),
            timestamp = now,
            eventSource = EventSource.Agent(agentId),
            threadId = threadId,
            reason = reason,
            context = context,
        )
        eventApi
            .publish(escalationRequested, causedBy = causedBy)
            .onFailure { throwable -> return Result.failure(throwable) }
        eventApi
            .publish(
                MessageEvent.ThreadStatusChanged(
                    eventId = randomUUID(),
                    timestamp = now,
                    eventSource = EventSource.Agent(agentId),
                    threadId = threadId,
                    oldStatus = oldStatus,
                    newStatus = newStatus,
                ),
                causedBy = escalationRequested.eventId,
            )
            .onFailure { throwable -> return Result.failure(throwable) }

        if (!awaitReply) {
            return Result.success(Unit)
        }

        // Step 3: Produce the Emission and suspend for reply.
        val contextString = context.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            .ifEmpty { null }

        try {
            val reply = emission(
                eventSource = EventSource.Agent(agentId),
                eventApi = eventApi,
                replyRegistry = emissionReplyRegistry,
            ) {
                askHuman(
                    prompt = reason,
                    agentId = agentId,
                    context = contextString,
                    ticketId = null,
                    taskId = null,
                    timeout = 30.minutes,
                    onProduced = { requested ->
                        val resolution = surfacePolicy.resolve(requested.emission, requested.urgency)
                        if (resolution.surface == Surface.Console) {
                            ConsoleSurfaceIO.printPrompt(requested.emission)
                        }
                    },
                )
            }

            // Step 4: Transition back after reply arrives.
            messageRepository.updateStatus(threadId, EventStatus.Open)
                .onFailure { throwable ->
                    logger.logError(
                        message = "Failed to restore thread $threadId to Active after escalation reply",
                        throwable = throwable,
                    )
                }

            val responseText = extractFreeText(reply.replyContext)
                ?: reply.replyContext?.toString()
                ?: ""

            if (responseText.isNotEmpty()) {
                postMessage(threadId, responseText, causedBy = reply.eventId)
            }
        } catch (e: link.socket.ampere.agents.domain.emission.EmissionTimeout) {
            logger.logError(message = "Escalation for thread $threadId timed out: ${e.message}")
            messageRepository.updateStatus(threadId, EventStatus.Open)
                .onFailure { throwable ->
                    logger.logError(
                        message = "Failed to restore thread $threadId to Active after escalation timeout",
                        throwable = throwable,
                    )
                }
            return Result.failure(e)
        }

        return Result.success(Unit)
    }

    /**
     * Resolve an escalated thread and publish `ThreadStatusChanged` through the door.
     *
     * Throws when the thread does not exist. Returns failure when the status update or the
     * publish did not persist (the update stands — it is not retried).
     *
     * @param causedBy the event whose handling resolved this thread, if any.
     */
    suspend fun resolveThread(
        threadId: MessageThreadId,
        causedBy: EventId? = null,
    ): Result<Unit> {
        val thread = messageRepository
            .findThreadById(threadId)
            .onFailure { throwable ->
                logger.logError(
                    message = "Failed to find thread with id $threadId",
                    throwable = throwable,
                )
                throw IllegalArgumentException("Thread not found: $threadId")
            }
            .getOrNull()

        requireNotNull(thread)
        val oldStatus: EventStatus = thread.status
        val newStatus = EventStatus.Resolved

        messageRepository
            .updateStatus(threadId, newStatus)
            .onFailure { throwable ->
                logger.logError(
                    message = "Failed to update thread status to $newStatus for thread with id $threadId",
                    throwable = throwable,
                )
                return Result.failure(throwable)
            }

        return eventApi
            .publish(
                MessageEvent.ThreadStatusChanged(
                    eventId = randomUUID(),
                    timestamp = Clock.System.now(),
                    eventSource = EventSource.Agent(agentId),
                    threadId = threadId,
                    oldStatus = oldStatus,
                    newStatus = newStatus,
                ),
                causedBy = causedBy,
            )
            .map { }
    }

    /**
     * Reopen a thread that was waiting for human intervention, allowing agents to resume
     * activity, and publish `ThreadStatusChanged` through the door.
     *
     * Throws when the thread does not exist or is not waiting for a human. Returns failure when
     * the status update or the publish did not persist (the update stands — it is not retried).
     *
     * @param causedBy the event whose handling reopened this thread, if any.
     */
    suspend fun reopenThread(
        threadId: MessageThreadId,
        causedBy: EventId? = null,
    ): Result<Unit> {
        val thread = messageRepository
            .findThreadById(threadId)
            .onFailure { throwable ->
                logger.logError(
                    message = "Failed to find thread with id $threadId",
                    throwable = throwable,
                )
                throw IllegalArgumentException("Thread not found: $threadId")
            }
            .getOrNull()

        requireNotNull(thread)
        require(thread.status == EventStatus.WaitingForHuman) {
            "Can only reopen threads that are waiting for human intervention. Current status: ${thread.status}"
        }

        val oldStatus: EventStatus = thread.status
        val newStatus = EventStatus.Open

        messageRepository
            .updateStatus(threadId, newStatus)
            .onFailure { throwable ->
                logger.logError(
                    message = "Failed to update thread status to $newStatus for thread with id $threadId",
                    throwable = throwable,
                )
                return Result.failure(throwable)
            }

        return eventApi
            .publish(
                MessageEvent.ThreadStatusChanged(
                    eventId = randomUUID(),
                    timestamp = Clock.System.now(),
                    eventSource = EventSource.Human,
                    threadId = threadId,
                    oldStatus = oldStatus,
                    newStatus = newStatus,
                ),
                causedBy = causedBy,
            )
            .map { }
    }

    /** Subscribe to thread creation events. */
    fun onThreadCreated(
        filter: EventFilter<MessageEvent.ThreadCreated> = EventFilter.noFilter(),
        handler: suspend (MessageEvent.ThreadCreated, Subscription?) -> Unit,
    ): Subscription =
        eventApi.eventSerialBus.subscribe(
            agentId = agentId,
            eventType = MessageEvent.ThreadCreated.EVENT_TYPE,
            handler = EventHandler { event, subscription ->
                val messageEvent = event as MessageEvent.ThreadCreated
                if (filter.execute(messageEvent)) {
                    handler(event, subscription)
                }
            },
        )

    /** Subscribe to message posted events in channel. */
    fun onChannelMessagePosted(
        channel: MessageChannel,
        filter: EventFilter<MessageEvent.MessagePosted> = EventFilter.noFilter(),
        handler: suspend (MessageEvent.MessagePosted, Subscription?) -> Unit,
    ): Subscription =
        eventApi.eventSerialBus.subscribe(
            agentId = agentId,
            eventType = MessageEvent.MessagePosted.EVENT_TYPE,
            handler = EventHandler { event, subscription ->
                val messageEvent = event as MessageEvent.MessagePosted
                if (messageEvent.channel == channel && filter.execute(messageEvent)) {
                    handler(messageEvent, subscription)
                }
            },
        )

    /** Subscribe to message posted event in thread. */
    fun onThreadMessagePosted(
        threadId: MessageThreadId,
        filter: (MessageEvent.MessagePosted) -> Boolean = { true },
        handler: suspend (MessageEvent.MessagePosted, Subscription?) -> Unit,
    ): Subscription =
        eventApi.eventSerialBus.subscribe(
            agentId = agentId,
            eventType = MessageEvent.MessagePosted.EVENT_TYPE,
            handler = EventHandler { event, subscription ->
                val messageEvent = event as MessageEvent.MessagePosted
                if (messageEvent.threadId == threadId && filter(messageEvent)) {
                    handler(messageEvent, subscription)
                }
            },
        )

    /** Subscribe to thread status changed events. */
    fun onThreadStatusChanged(
        filter: (MessageEvent.ThreadStatusChanged) -> Boolean = { true },
        handler: suspend (MessageEvent.ThreadStatusChanged, Subscription?) -> Unit,
    ): Subscription =
        eventApi.eventSerialBus.subscribe(
            agentId = agentId,
            eventType = MessageEvent.ThreadStatusChanged.EVENT_TYPE,
            handler = EventHandler { event, subscription ->
                if (filter(event as MessageEvent.ThreadStatusChanged)) {
                    handler(event, subscription)
                }
            },
        )

    /** Subscribe to escalation requested events. */
    fun onEscalationRequested(
        filter: (MessageEvent.EscalationRequested) -> Boolean = { true },
        handler: suspend (MessageEvent.EscalationRequested, Subscription?) -> Unit,
    ): Subscription =
        eventApi.eventSerialBus.subscribe(
            agentId = agentId,
            eventType = MessageEvent.EscalationRequested.EVENT_TYPE,
            handler = EventHandler { event, subscription ->
                if (filter(event as MessageEvent.EscalationRequested)) {
                    handler(event, subscription)
                }
            },
        )

    /** Retrieve a thread by id. */
    suspend fun getThread(threadId: MessageThreadId): Result<MessageThread> =
        messageRepository.findThreadById(threadId)

    /** List all threads. */
    suspend fun getAllThreads(): Result<List<MessageThread>> =
        messageRepository.findAllThreads()
}
