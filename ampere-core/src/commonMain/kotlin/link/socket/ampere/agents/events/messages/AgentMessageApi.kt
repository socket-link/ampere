package link.socket.ampere.agents.events.messages

import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.emission.EmissionReplyRegistry
import link.socket.ampere.agents.domain.emission.GlobalEmissionReplyRegistry
import link.socket.ampere.agents.domain.emission.emission
import link.socket.ampere.agents.domain.emission.extractFreeText
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.MessageEvent
import link.socket.ampere.agents.domain.status.EventStatus
import link.socket.ampere.agents.events.api.EventFilter
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.subscription.Subscription
import link.socket.ampere.agents.events.utils.ConsoleEventLogger
import link.socket.ampere.agents.events.utils.EventLogger
import link.socket.ampere.util.randomUUID

/**
 * Service layer that orchestrates message thread operations, enforces business rules,
 * persists state via MessageRepository, and publishes domain events via EventBus.
 */
class AgentMessageApi(
    val agentId: AgentId,
    private val messageRepository: MessageRepository,
    private val eventSerialBus: EventSerialBus,
    private val emissionReplyRegistry: EmissionReplyRegistry = GlobalEmissionReplyRegistry.instance,
    private val logger: EventLogger = ConsoleEventLogger(),
) {

    /** Create a new message thread with an initial message and publish events. */
    suspend fun createThread(
        participants: Set<MessageSenderId>,
        channel: MessageChannel,
        initialMessageContent: String,
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
                // Publish creation and initial message posted events
                eventSerialBus.publish(
                    MessageEvent.ThreadCreated(
                        eventId = randomUUID(),
                        thread = thread,
                    ),
                )

                eventSerialBus.publish(
                    MessageEvent.MessagePosted(
                        eventId = randomUUID(),
                        threadId = thread.id,
                        channel = thread.channel,
                        message = message,
                    ),
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

    /** Post a message to an existing thread. Throws if thread is blocked waiting for human. */
    suspend fun postMessage(
        threadId: MessageThreadId,
        content: String,
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
                eventSerialBus.publish(
                    MessageEvent.MessagePosted(
                        eventId = randomUUID(),
                        threadId = threadId,
                        channel = thread.channel,
                        message = message,
                    ),
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
     * 2. Publish [MessageEvent.EscalationRequested] as a side-effect bus event for thread
     *    observers (preserved for backward compatibility; not the primary CHI path).
     * 3. Produce a [HumanInteractionEvent.InputRequested] via the Emission DSL and suspend
     *    until the human replies.
     * 4. Transition thread back to [EventStatus.Open] and post the reply.
     *
     * Set [awaitReply] to `false` for legacy fire-and-forget thread escalation
     * where the caller only needs the durable status transition and bus events.
     *
     * Thread state transitions are owned exclusively by this method — handlers must not
     * re-transition (CHI cell invariant).
     */
    suspend fun escalateToHuman(
        threadId: MessageThreadId,
        reason: String,
        context: Map<String, String> = emptyMap(),
        awaitReply: Boolean = true,
    ) {
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
            return
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
                return
            }

        val now = Clock.System.now()

        // Step 2: Side-effect bus event for thread observers (backward-compat).
        eventSerialBus.publish(
            MessageEvent.EscalationRequested(
                eventId = randomUUID(),
                timestamp = now,
                eventSource = EventSource.Agent(agentId),
                threadId = threadId,
                reason = reason,
                context = context,
            ),
        )
        eventSerialBus.publish(
            MessageEvent.ThreadStatusChanged(
                eventId = randomUUID(),
                timestamp = now,
                eventSource = EventSource.Agent(agentId),
                threadId = threadId,
                oldStatus = oldStatus,
                newStatus = newStatus,
            ),
        )

        if (!awaitReply) {
            return
        }

        // Step 3: Produce the Emission and suspend for reply.
        val contextString = context.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            .ifEmpty { null }

        try {
            val reply = emission(
                eventSource = EventSource.Agent(agentId),
                eventSerialBus = eventSerialBus,
                replyRegistry = emissionReplyRegistry,
            ) {
                askHuman(
                    prompt = reason,
                    agentId = agentId,
                    context = contextString,
                    ticketId = null,
                    taskId = null,
                    timeout = 30.minutes,
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
                postMessage(threadId, responseText)
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
        }
    }

    /** Resolve an escalated thread. */
    suspend fun resolveThread(
        threadId: MessageThreadId,
    ) {
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
            .onSuccess {
                eventSerialBus.publish(
                    MessageEvent.ThreadStatusChanged(
                        eventId = randomUUID(),
                        timestamp = Clock.System.now(),
                        eventSource = EventSource.Agent(agentId),
                        threadId = threadId,
                        oldStatus = oldStatus,
                        newStatus = newStatus,
                    ),
                )
            }
            .onFailure { throwable ->
                logger.logError(
                    message = "Failed to update thread status to $newStatus for thread with id $threadId",
                    throwable = throwable,
                )
            }
    }

    /** Reopen a thread that was waiting for human intervention, allowing agents to resume activity. */
    suspend fun reopenThread(
        threadId: MessageThreadId,
    ) {
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
            .onSuccess {
                eventSerialBus.publish(
                    MessageEvent.ThreadStatusChanged(
                        eventId = randomUUID(),
                        timestamp = Clock.System.now(),
                        eventSource = EventSource.Human,
                        threadId = threadId,
                        oldStatus = oldStatus,
                        newStatus = newStatus,
                    ),
                )
            }
            .onFailure { throwable ->
                logger.logError(
                    message = "Failed to update thread status to $newStatus for thread with id $threadId",
                    throwable = throwable,
                )
            }
    }

    /** Subscribe to thread creation events. */
    fun onThreadCreated(
        filter: EventFilter<MessageEvent.ThreadCreated> = EventFilter.noFilter(),
        handler: suspend (MessageEvent.ThreadCreated, Subscription?) -> Unit,
    ): Subscription =
        eventSerialBus.subscribe(
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
        eventSerialBus.subscribe(
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
        eventSerialBus.subscribe(
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
        eventSerialBus.subscribe(
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
        eventSerialBus.subscribe(
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
