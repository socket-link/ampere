package link.socket.ampere.agents.events.api

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.EventType
import link.socket.ampere.agents.domain.event.MessageEvent
import link.socket.ampere.agents.domain.event.ToolEvent
import link.socket.ampere.agents.events.messages.MessageThreadId
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.bus.subscribe
import link.socket.ampere.agents.events.subscription.EventSubscription
import link.socket.ampere.agents.events.subscription.Subscription
import link.socket.ampere.agents.events.utils.ConsoleEventLogger
import link.socket.ampere.agents.events.utils.EventLogger
import link.socket.ampere.agents.events.utils.generateUUID

open class EventHandler<E : Event, S : Subscription>(
    private val executeOverride: (suspend (E, S?) -> Unit)? = null,
) {
    open suspend operator fun invoke(event: E, subscription: S?) {
        executeOverride?.invoke(event, subscription)
    }
}

class EventFilter<E : Event>(
    val execute: (E) -> Boolean,
) {
    companion object {
        fun <E : Event> noFilter(): EventFilter<E> =
            EventFilter(
                execute = { _: E -> true },
            )
    }
}

/**
 * High-level, agent-friendly API for interacting with the EventBus.
 *
 * This facade hides event creation details and provides convenience publish/subscribe
 * methods that agents can call directly.
 */
class AgentEventApi(
    val agentId: AgentId,
    private val eventRepository: EventRepository,
    private val eventSerialBus: EventSerialBus,
    private val logger: EventLogger = ConsoleEventLogger(),
) {

    /** Persist and publish a pre-constructed event.
     *
     * The event is always published to the bus for real-time delivery,
     * regardless of whether the repository save succeeds. Persistence
     * failures are logged but must not block live event propagation
     * (e.g. the WatchPresenter's agent activity tracking depends on
     * receiving events promptly).
     */
    suspend fun publish(event: Event) {
        eventRepository.saveEvent(event)
            .onFailure { throwable ->
                logger.logError(
                    message = "Failed to persist event ${event.eventType} id=${event.eventId}",
                    throwable = throwable,
                )
            }
        eventSerialBus.publish(event)
    }

    /** Publish a TaskCreated event with auto-generated ID and current timestamp. */
    suspend fun publishTaskCreated(
        taskId: String,
        urgency: Urgency,
        description: String,
        assignedTo: AgentId? = null,
    ) {
        val event = Event.TaskCreated(
            eventId = generateUUID(taskId, agentId),
            urgency = urgency,
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent(agentId),
            taskId = taskId,
            description = description,
            assignedTo = assignedTo,
        )

        publish(event)
    }

    /** Publish a QuestionRaised event with auto-generated ID and current timestamp. */
    suspend fun publishQuestionRaised(
        urgency: Urgency,
        questionText: String,
        context: String,
    ) {
        val event = Event.QuestionRaised(
            eventId = generateUUID(agentId),
            urgency = urgency,
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent(agentId),
            questionText = questionText,
            context = context,
        )

        publish(event)
    }

    /** Publish a CodeSubmitted event with auto-generated ID and current timestamp. */
    suspend fun publishCodeSubmitted(
        urgency: Urgency,
        filePath: String,
        changeDescription: String,
        reviewRequired: Boolean = false,
        assignedTo: AgentId? = null,
    ) {
        val event = Event.CodeSubmitted(
            eventId = generateUUID(agentId),
            urgency = urgency,
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent(agentId),
            filePath = filePath,
            changeDescription = changeDescription,
            reviewRequired = reviewRequired,
            assignedTo = assignedTo,
        )

        publish(event)
    }

    /**
     * Publish an EscalationRequested event with auto-generated ID and current timestamp.
     *
     * This event is visible in the event pane and marked as SIGNIFICANT/CRITICAL for visibility.
     */
    suspend fun publishEscalationRequested(
        threadId: MessageThreadId,
        reason: String,
        context: Map<String, String> = emptyMap(),
        urgency: Urgency = Urgency.HIGH,
    ) {
        val event = MessageEvent.EscalationRequested(
            eventId = generateUUID(threadId, agentId),
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent(agentId),
            threadId = threadId,
            reason = reason,
            context = context,
            urgency = urgency,
        )

        publish(event)
    }

    /** Subscribe to TaskCreated events. */
    fun onTaskCreated(
        filter: EventFilter<Event.TaskCreated> = EventFilter.noFilter(),
        handler: suspend (Event.TaskCreated, Subscription?) -> Unit,
    ): Subscription =
        eventSerialBus.subscribe<Event.TaskCreated, EventSubscription.ByEventClassType>(
            agentId = agentId,
            eventType = Event.TaskCreated.EVENT_TYPE,
        ) { event, subscription ->
            if (filter.execute(event)) {
                handler(event, subscription)
            }
        }

    /** Subscribe to QuestionRaised events. */
    fun onQuestionRaised(
        filter: EventFilter<Event.QuestionRaised> = EventFilter.noFilter(),
        handler: suspend (Event.QuestionRaised, Subscription?) -> Unit,
    ): Subscription =
        eventSerialBus.subscribe<Event.QuestionRaised, EventSubscription.ByEventClassType>(
            agentId = agentId,
            eventType = Event.QuestionRaised.EVENT_TYPE,
        ) { event, subscription ->
            if (filter.execute(event)) {
                handler(event, subscription)
            }
        }

    /** Subscribe to CodeSubmitted events. */
    fun onCodeSubmitted(
        filter: EventFilter<Event.CodeSubmitted> = EventFilter.noFilter(),
        handler: suspend (Event.CodeSubmitted, Subscription?) -> Unit,
    ): Subscription =
        eventSerialBus.subscribe<Event.CodeSubmitted, EventSubscription.ByEventClassType>(
            agentId = agentId,
            eventType = Event.CodeSubmitted.EVENT_TYPE,
        ) { event, subscription ->
            if (filter.execute(event)) {
                handler(event, subscription)
            }
        }

    /** Retrieve all events since the provided timestamp, or all if null. */
    suspend fun getRecentEvents(
        since: Instant?,
        eventType: EventType? = null,
    ): List<Event> {
        val result = if (since != null) {
            eventRepository.getEventsSince(since)
        } else {
            eventRepository.getAllEvents()
        }

        result.onFailure { throwable ->
            logger.logError(
                message = "Failed to load recent events since=$since",
                throwable = throwable,
            )
        }

        return result.getOrNull()?.let { events ->
            if (eventType != null) {
                events.filter { event ->
                    event.eventType == eventType
                }
            } else {
                events
            }
        } ?: emptyList()
    }

    /** Retrieve historical events with optional type filter and since timestamp. */
    suspend fun getEventHistory(
        since: Instant? = null,
        eventType: EventType? = null,
    ): List<Event> {
        val result: Result<List<Event>> = when {
            eventType != null && since != null -> {
                eventRepository
                    .getEventsByType(eventType)
                    .map { list -> list.filter { it.timestamp >= since } }
            }
            eventType != null -> eventRepository.getEventsByType(eventType)
            since != null -> eventRepository.getEventsSince(since)
            else -> eventRepository.getAllEvents()
        }

        return result.onFailure { throwable ->
            logger.logError(
                message = "Failed to load event history (eventClassType=$eventType since=$since)",
                throwable = throwable,
            )
        }.getOrElse { emptyList() }
    }

    /** Replay past events by publishing them to current subscribers. */
    suspend fun replayEvents(
        since: Instant?,
        eventType: EventType? = null,
    ) {
        val events = getRecentEvents(since, eventType)
        for (event in events) {
            eventSerialBus.publish(event)
        }
    }

    // ==================== Tool Event Publishing Methods ====================

    /** Publish a ToolRegistered event with auto-generated ID and current timestamp. */
    suspend fun publishToolRegistered(
        toolId: String,
        toolName: String,
        toolType: String,
        requiredAutonomy: AgentActionAutonomy,
        mcpServerId: String? = null,
        urgency: Urgency = Urgency.LOW,
    ) {
        val event = ToolEvent.ToolRegistered(
            eventId = generateUUID(toolId, agentId),
            urgency = urgency,
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent(agentId),
            toolId = toolId,
            toolName = toolName,
            toolType = toolType,
            requiredAutonomy = requiredAutonomy,
            mcpServerId = mcpServerId,
        )

        publish(event)
    }

    /** Publish a ToolUnregistered event with auto-generated ID and current timestamp. */
    suspend fun publishToolUnregistered(
        toolId: String,
        toolName: String,
        reason: String,
        mcpServerId: String? = null,
        urgency: Urgency = Urgency.MEDIUM,
    ) {
        val event = ToolEvent.ToolUnregistered(
            eventId = generateUUID(toolId, agentId),
            urgency = urgency,
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent(agentId),
            toolId = toolId,
            toolName = toolName,
            reason = reason,
            mcpServerId = mcpServerId,
        )

        publish(event)
    }

    /** Publish a ToolDiscoveryComplete event with auto-generated ID and current timestamp. */
    suspend fun publishToolDiscoveryComplete(
        totalToolsDiscovered: Int,
        functionToolCount: Int,
        mcpToolCount: Int,
        mcpServerCount: Int,
        urgency: Urgency = Urgency.LOW,
    ) {
        val event = ToolEvent.ToolDiscoveryComplete(
            eventId = generateUUID(agentId),
            urgency = urgency,
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent(agentId),
            totalToolsDiscovered = totalToolsDiscovered,
            functionToolCount = functionToolCount,
            mcpToolCount = mcpToolCount,
            mcpServerCount = mcpServerCount,
        )

        publish(event)
    }

    // ==================== Tool Event Subscription Methods ====================

    /** Subscribe to ToolRegistered events. */
    fun onToolRegistered(
        filter: EventFilter<ToolEvent.ToolRegistered> = EventFilter.noFilter(),
        handler: suspend (ToolEvent.ToolRegistered, Subscription?) -> Unit,
    ): Subscription =
        eventSerialBus.subscribe<ToolEvent.ToolRegistered, EventSubscription.ByEventClassType>(
            agentId = agentId,
            eventType = ToolEvent.ToolRegistered.EVENT_TYPE,
        ) { event, subscription ->
            if (filter.execute(event)) {
                handler(event, subscription)
            }
        }

    /** Subscribe to ToolUnregistered events. */
    fun onToolUnregistered(
        filter: EventFilter<ToolEvent.ToolUnregistered> = EventFilter.noFilter(),
        handler: suspend (ToolEvent.ToolUnregistered, Subscription?) -> Unit,
    ): Subscription =
        eventSerialBus.subscribe<ToolEvent.ToolUnregistered, EventSubscription.ByEventClassType>(
            agentId = agentId,
            eventType = ToolEvent.ToolUnregistered.EVENT_TYPE,
        ) { event, subscription ->
            if (filter.execute(event)) {
                handler(event, subscription)
            }
        }

    /** Subscribe to ToolDiscoveryComplete events. */
    fun onToolDiscoveryComplete(
        filter: EventFilter<ToolEvent.ToolDiscoveryComplete> = EventFilter.noFilter(),
        handler: suspend (ToolEvent.ToolDiscoveryComplete, Subscription?) -> Unit,
    ): Subscription =
        eventSerialBus.subscribe<ToolEvent.ToolDiscoveryComplete, EventSubscription.ByEventClassType>(
            agentId = agentId,
            eventType = ToolEvent.ToolDiscoveryComplete.EVENT_TYPE,
        ) { event, subscription ->
            if (filter.execute(event)) {
                handler(event, subscription)
            }
        }
}

fun <E : Event> AgentEventApi.filterForEventsCreatedByMe(): EventFilter<E> =
    EventFilter { event: Event ->
        event.eventSource.getIdentifier() == agentId
    }

fun AgentEventApi.filterForTasksAssignedToMe(): EventFilter<Event.TaskCreated> =
    EventFilter { event: Event.TaskCreated ->
        event.assignedTo == agentId
    }

fun AgentEventApi.filterForQuestionsRaisedByMe(): EventFilter<Event.QuestionRaised> =
    EventFilter { event: Event.QuestionRaised ->
        event.questionText.contains(agentId)
    }

fun AgentEventApi.filterForCodeSubmittedByMe(): EventFilter<Event.CodeSubmitted> =
    EventFilter { event: Event.CodeSubmitted ->
        event.reviewRequired && event.eventSource.getIdentifier() == agentId
    }

fun AgentEventApi.filterForCodeAssignedToMe(): EventFilter<Event.CodeSubmitted> =
    EventFilter { event: Event.CodeSubmitted ->
        event.reviewRequired && event.assignedTo == agentId
    }

fun AgentEventApi.filterForEventClassType(eventType: EventType): EventFilter<Event> =
    EventFilter { event: Event ->
        event.eventType == eventType
    }
