package link.socket.kore.agents.events.messages

import kotlinx.datetime.Clock
import link.socket.kore.agents.core.AgentId
import link.socket.kore.agents.events.EventSource
import link.socket.kore.agents.events.EventStatus
import link.socket.kore.agents.events.MessageEvent
import link.socket.kore.agents.events.api.EventFilter
import link.socket.kore.agents.events.api.EventHandler
import link.socket.kore.agents.events.bus.EventBus
import link.socket.kore.agents.events.subscription.Subscription
import link.socket.kore.agents.events.utils.ConsoleEventLogger
import link.socket.kore.agents.events.utils.EventLogger
import link.socket.kore.util.randomUUID

/**
 * Service layer that orchestrates message thread operations, enforces business rules,
 * persists state via MessageRepository, and publishes domain events via EventBus.
 */
class AgentMessageApi(
    val agentId: AgentId,
    private val messageRepository: MessageRepository,
    private val eventBus: EventBus,
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
                eventBus.publish(
                    MessageEvent.ThreadCreated(
                        eventId = randomUUID(),
                        thread = thread,
                    ),
                )

                eventBus.publish(
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
        require(thread.status != EventStatus.WAITING_FOR_HUMAN) {
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
                eventBus.publish(
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

    /** Escalate a thread to human, blocking further agent activity. */
    suspend fun escalateToHuman(
        threadId: MessageThreadId,
        reason: String,
        context: Map<String, String> = emptyMap(),
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
            logger.logError(
                message = "Thread not found: $threadId",
            )
            return
        }

        require(thread.status != EventStatus.RESOLVED) {
            "Cannot escalate a resolved thread"
        }

        val oldStatus = thread.status
        val newStatus = EventStatus.WAITING_FOR_HUMAN

        messageRepository
            .updateStatus(threadId, newStatus)
            .onSuccess {
                val now = Clock.System.now()

                eventBus.publish(
                    MessageEvent.EscalationRequested(
                        eventId = randomUUID(),
                        timestamp = now,
                        eventSource = EventSource.Agent(agentId),
                        threadId = threadId,
                        reason = reason,
                        context = context,
                    ),
                )

                eventBus.publish(
                    MessageEvent.ThreadStatusChanged(
                        eventId = randomUUID(),
                        timestamp = now,
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
        val newStatus = EventStatus.RESOLVED

        messageRepository
            .updateStatus(threadId, newStatus)
            .onSuccess {
                eventBus.publish(
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
        require(thread.status == EventStatus.WAITING_FOR_HUMAN) {
            "Can only reopen threads that are waiting for human intervention. Current status: ${thread.status}"
        }

        val oldStatus: EventStatus = thread.status
        val newStatus = EventStatus.OPEN

        messageRepository
            .updateStatus(threadId, newStatus)
            .onSuccess {
                eventBus.publish(
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
        eventBus.subscribe(
            agentId = agentId,
            eventClassType = MessageEvent.ThreadCreated.EVENT_CLASS_TYPE,
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
        eventBus.subscribe(
            agentId = agentId,
            eventClassType = MessageEvent.MessagePosted.EVENT_CLASS_TYPE,
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
        eventBus.subscribe(
            agentId = agentId,
            eventClassType = MessageEvent.MessagePosted.EVENT_CLASS_TYPE,
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
        eventBus.subscribe(
            agentId = agentId,
            eventClassType = MessageEvent.ThreadStatusChanged.EVENT_CLASS_TYPE,
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
        eventBus.subscribe(
            agentId = agentId,
            eventClassType = MessageEvent.EscalationRequested.EVENT_CLASS_TYPE,
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
