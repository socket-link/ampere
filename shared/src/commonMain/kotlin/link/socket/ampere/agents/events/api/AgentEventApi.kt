package link.socket.ampere.agents.events.api

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.core.AgentId
import link.socket.ampere.agents.events.Event
import link.socket.ampere.agents.events.EventClassType
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.EventSource
import link.socket.ampere.agents.events.Urgency
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

    /** Persist and publish a pre-constructed event. */
    suspend fun publish(event: Event) {
        eventRepository.saveEvent(event)
            .onSuccess {
                eventSerialBus.publish(event)
            }
            .onFailure { throwable ->
                logger.logError(
                    message = "Failed to create event ${event.eventClassType} id=${event.eventId}",
                    throwable = throwable,
                )
            }
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

    /** Subscribe to TaskCreated events. */
    fun onTaskCreated(
        filter: EventFilter<Event.TaskCreated> = EventFilter.noFilter(),
        handler: suspend (Event.TaskCreated, Subscription?) -> Unit,
    ): Subscription =
        eventSerialBus.subscribe<Event.TaskCreated, EventSubscription.ByEventClassType>(
            agentId = agentId,
            eventClassType = Event.TaskCreated.EVENT_CLASS_TYPE,
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
            eventClassType = Event.QuestionRaised.EVENT_CLASS_TYPE,
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
            eventClassType = Event.CodeSubmitted.EVENT_CLASS_TYPE,
        ) { event, subscription ->
            if (filter.execute(event)) {
                handler(event, subscription)
            }
        }

    /** Retrieve all events since the provided timestamp, or all if null. */
    suspend fun getRecentEvents(
        since: Instant?,
        eventClassType: EventClassType? = null,
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
            if (eventClassType != null) {
                events.filter { event ->
                    event.eventClassType == eventClassType
                }
            } else {
                events
            }
        } ?: emptyList()
    }

    /** Retrieve historical events with optional type filter and since timestamp. */
    suspend fun getEventHistory(
        since: Instant? = null,
        eventClassType: EventClassType? = null,
    ): List<Event> {
        val result: Result<List<Event>> = when {
            eventClassType != null && since != null -> {
                eventRepository
                    .getEventsByType(eventClassType)
                    .map { list -> list.filter { it.timestamp >= since } }
            }
            eventClassType != null -> eventRepository.getEventsByType(eventClassType)
            since != null -> eventRepository.getEventsSince(since)
            else -> eventRepository.getAllEvents()
        }

        return result.onFailure { throwable ->
            logger.logError(
                message = "Failed to load event history (eventClassType=$eventClassType since=$since)",
                throwable = throwable,
            )
        }.getOrElse { emptyList() }
    }

    /** Replay past events by publishing them to current subscribers. */
    suspend fun replayEvents(
        since: Instant?,
        eventClassType: EventClassType? = null,
    ) {
        val events = getRecentEvents(since, eventClassType)
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
        requiredAutonomy: link.socket.ampere.agents.core.actions.AgentActionAutonomy,
        mcpServerId: String? = null,
        urgency: Urgency = Urgency.LOW,
    ) {
        val event = link.socket.ampere.agents.events.ToolEvent.ToolRegistered(
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
        val event = link.socket.ampere.agents.events.ToolEvent.ToolUnregistered(
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
        val event = link.socket.ampere.agents.events.ToolEvent.ToolDiscoveryComplete(
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
        filter: EventFilter<link.socket.ampere.agents.events.ToolEvent.ToolRegistered> = EventFilter.noFilter(),
        handler: suspend (link.socket.ampere.agents.events.ToolEvent.ToolRegistered, Subscription?) -> Unit,
    ): Subscription =
        eventSerialBus.subscribe<link.socket.ampere.agents.events.ToolEvent.ToolRegistered, EventSubscription.ByEventClassType>(
            agentId = agentId,
            eventClassType = link.socket.ampere.agents.events.ToolEvent.ToolRegistered.EVENT_CLASS_TYPE,
        ) { event, subscription ->
            if (filter.execute(event)) {
                handler(event, subscription)
            }
        }

    /** Subscribe to ToolUnregistered events. */
    fun onToolUnregistered(
        filter: EventFilter<link.socket.ampere.agents.events.ToolEvent.ToolUnregistered> = EventFilter.noFilter(),
        handler: suspend (link.socket.ampere.agents.events.ToolEvent.ToolUnregistered, Subscription?) -> Unit,
    ): Subscription =
        eventSerialBus.subscribe<link.socket.ampere.agents.events.ToolEvent.ToolUnregistered, EventSubscription.ByEventClassType>(
            agentId = agentId,
            eventClassType = link.socket.ampere.agents.events.ToolEvent.ToolUnregistered.EVENT_CLASS_TYPE,
        ) { event, subscription ->
            if (filter.execute(event)) {
                handler(event, subscription)
            }
        }

    /** Subscribe to ToolDiscoveryComplete events. */
    fun onToolDiscoveryComplete(
        filter: EventFilter<link.socket.ampere.agents.events.ToolEvent.ToolDiscoveryComplete> = EventFilter.noFilter(),
        handler: suspend (link.socket.ampere.agents.events.ToolEvent.ToolDiscoveryComplete, Subscription?) -> Unit,
    ): Subscription =
        eventSerialBus.subscribe<link.socket.ampere.agents.events.ToolEvent.ToolDiscoveryComplete, EventSubscription.ByEventClassType>(
            agentId = agentId,
            eventClassType = link.socket.ampere.agents.events.ToolEvent.ToolDiscoveryComplete.EVENT_CLASS_TYPE,
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

fun AgentEventApi.filterForEventClassType(eventClassType: EventClassType): EventFilter<Event> =
    EventFilter { event: Event ->
        event.eventClassType == eventClassType
    }
