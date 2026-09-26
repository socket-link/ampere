package link.socket.ampere.agents.events.api

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.CognitiveEvent
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventId
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.EventStoreEvent
import link.socket.ampere.agents.domain.event.EventStoreFailure
import link.socket.ampere.agents.domain.event.EventType
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.event.MessageEvent
import link.socket.ampere.agents.domain.event.MilestoneCategory
import link.socket.ampere.agents.domain.event.PermissionDeniedEvent
import link.socket.ampere.agents.domain.event.PermissionDeniedReason
import link.socket.ampere.agents.domain.event.TaskEvent
import link.socket.ampere.agents.domain.event.ToolEvent
import link.socket.ampere.agents.domain.task.TaskId
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.events.EventEnvelope
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.StoredEvent
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.bus.subscribe
import link.socket.ampere.agents.events.messages.MessageThreadId
import link.socket.ampere.agents.events.subscription.EventSubscription
import link.socket.ampere.agents.events.subscription.Subscription
import link.socket.ampere.agents.events.utils.ConsoleEventLogger
import link.socket.ampere.agents.events.utils.EventLogger
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.plug.permission.PlugPermission

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
 *
 * This is the one door into the Field: [publish] persists the event inside its
 * [EventEnvelope] and only then dispatches it on the bus. Every timestamp the door stamps
 * comes from [clock], never from `Clock.System` directly, so tests can pin time.
 *
 * @property clock the time source for every event this api constructs. Public so the
 * emission seam can read the same clock.
 * @property eventSerialBus the bus this door dispatches on. Exposed for *subscribing* only —
 * the emission reply router, `TraceRecorder`, and surface renderers register handlers here.
 * Nothing outside this class publishes on it: every event enters through [publish] so the
 * store sees exactly what the bus sees (F1). The one event the store does *not* see is
 * [EventStoreEvent.PersistenceFailed], which exists to report that the store could not see
 * something; see [publish].
 */
class AgentEventApi(
    val agentId: AgentId,
    private val eventRepository: EventRepository,
    val eventSerialBus: EventSerialBus,
    private val logger: EventLogger = ConsoleEventLogger(),
    milestoneTrackerState: MilestoneTrackerState = MilestoneTrackerState(),
    val clock: Clock = Clock.System,
) {
    private val milestoneTracker = MilestoneTracker(this, milestoneTrackerState)

    init {
        milestoneTracker.start()
    }

    /**
     * The door: persist [event] inside its envelope, then dispatch it on the bus.
     *
     * This is the only way an [Event] enters the system (F1, AMPR-340). `EventSerialBus.publish`
     * is `internal` and, within `ampere-core`, `EventDoorBoundaryTest` allows it to be called
     * only from this file — so the store sees exactly what the bus sees. Obtain an instance
     * with `EnvironmentService.createEventApi(agentId)`, or hold the one your agent was built
     * with. The envelope columns and what assigns them:
     *
     * - `sequence` — the store, inside the insert transaction; unique and monotonic.
     * - `recorded_at` — this door's [clock] at publish; the event's own `timestamp` is untouched.
     * - `caused_by` — [causedBy], the publisher's statement of which event it is reacting to.
     * - `run_id` — [runId], the publisher's statement of the Arc run. There is no fallback:
     *   what you pass is what is stored, and null is stored as NULL.
     *
     * The bus dispatch only happens once the row is committed, and a persist failure is
     * returned to the caller as well as logged — it is never swallowed (recon C59).
     *
     * A failure also goes out on the bus as [EventStoreEvent.PersistenceFailed] (AMPR-301).
     * That one event is dispatched *without* being persisted first, breaking this method's own
     * rule on purpose: the store is what just failed, so the alternative to an unpersisted
     * signal is no signal — the silent divergence the AMPR-291 fate table flagged, where the
     * durable record and what actually happened part ways with nothing to say so. It cannot
     * recurse, because nothing about it goes back through [publish].
     *
     * @param causedBy the event whose handling produced this one, if any (F2). Pass the id of
     * the event you are handling; leave it null only for an event with no trigger.
     * @param runId the Arc run this event belongs to (F4). Pass the run id you hold — from
     * `ExecutionRequest.runId`, `RoutingContext.workflowId`, or the `emission(…, runId)` scope;
     * an event carrying its own `runId` field passes that same value here.
     * @return the [StoredEvent] as recorded, including its assigned `sequence`.
     */
    suspend fun publish(
        event: Event,
        causedBy: EventId? = null,
        runId: RunId? = null,
    ): Result<StoredEvent> =
        eventRepository
            .saveEvent(
                event = event,
                envelope = EventEnvelope(causedBy = causedBy, runId = runId),
                recordedAt = clock.now(),
            )
            .onSuccess {
                eventSerialBus.publish(event)
            }
            .onFailure { throwable ->
                logger.logError(
                    message = "Failed to create event ${event.eventType} id=${event.eventId}",
                    throwable = throwable,
                )
                eventSerialBus.publish(
                    EventStoreEvent.PersistenceFailed(
                        eventId = generateUUID("event-store-persistence-failed", agentId),
                        timestamp = clock.now(),
                        eventSource = EventSource.Agent(agentId),
                        failedEventId = event.eventId,
                        failedEventType = event.eventType,
                        failure = EventStoreFailure.classify(throwable),
                        reason = throwable.message ?: throwable::class.simpleName.orEmpty(),
                    ),
                )
            }

    /** Publish a TaskCreated event with auto-generated ID and current timestamp. */
    suspend fun publishTaskCreated(
        taskId: String,
        urgency: Urgency,
        description: String,
        assignedTo: AgentId? = null,
        causedBy: EventId? = null,
        runId: RunId? = null,
    ) {
        val event = Event.TaskCreated(
            eventId = generateUUID(taskId, agentId),
            urgency = urgency,
            timestamp = clock.now(),
            eventSource = EventSource.Agent(agentId),
            taskId = taskId,
            description = description,
            assignedTo = assignedTo,
        )

        publish(event, causedBy = causedBy, runId = runId)
    }

    /** Publish a QuestionRaised event with auto-generated ID and current timestamp. */
    suspend fun publishQuestionRaised(
        urgency: Urgency,
        questionText: String,
        context: String,
        causedBy: EventId? = null,
        runId: RunId? = null,
    ) {
        val event = Event.QuestionRaised(
            eventId = generateUUID(agentId),
            urgency = urgency,
            timestamp = clock.now(),
            eventSource = EventSource.Agent(agentId),
            questionText = questionText,
            context = context,
        )

        publish(event, causedBy = causedBy, runId = runId)
    }

    /** Publish a CodeSubmitted event with auto-generated ID and current timestamp. */
    suspend fun publishCodeSubmitted(
        urgency: Urgency,
        filePath: String,
        changeDescription: String,
        reviewRequired: Boolean = false,
        assignedTo: AgentId? = null,
        causedBy: EventId? = null,
        runId: RunId? = null,
    ) {
        val event = Event.CodeSubmitted(
            eventId = generateUUID(agentId),
            urgency = urgency,
            timestamp = clock.now(),
            eventSource = EventSource.Agent(agentId),
            filePath = filePath,
            changeDescription = changeDescription,
            reviewRequired = reviewRequired,
            assignedTo = assignedTo,
        )

        publish(event, causedBy = causedBy, runId = runId)
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
        causedBy: EventId? = null,
        runId: RunId? = null,
    ) {
        val event = MessageEvent.EscalationRequested(
            eventId = generateUUID(threadId, agentId),
            timestamp = clock.now(),
            eventSource = EventSource.Agent(agentId),
            threadId = threadId,
            reason = reason,
            context = context,
            urgency = urgency,
        )

        publish(event, causedBy = causedBy, runId = runId)
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

    /** Subscribe to threshold-driven cognitive escalation events. */
    fun onEscalationFired(
        filter: EventFilter<CognitiveEvent.EscalationFired> = EventFilter.noFilter(),
        handler: suspend (CognitiveEvent.EscalationFired, Subscription?) -> Unit,
    ): Subscription =
        eventSerialBus.subscribe<CognitiveEvent.EscalationFired, EventSubscription.ByEventClassType>(
            agentId = agentId,
            eventType = CognitiveEvent.EscalationFired.EVENT_TYPE,
        ) { event, subscription ->
            if (filter.execute(event)) {
                handler(event, subscription)
            }
        }

    /**
     * Subscribe to every uncertainty evaluation, including near-misses.
     *
     * High-volume telemetry — fires on every evaluation, potentially thousands per agent run.
     * Subscribe only for telemetry, calibration analysis, or near-miss UI. For action signals
     * use [onEscalationFired] instead.
     */
    fun onEscalationConsidered(
        filter: EventFilter<CognitiveEvent.EscalationConsidered> = EventFilter.noFilter(),
        handler: suspend (CognitiveEvent.EscalationConsidered, Subscription?) -> Unit,
    ): Subscription =
        eventSerialBus.subscribe<CognitiveEvent.EscalationConsidered, EventSubscription.ByEventClassType>(
            agentId = agentId,
            eventType = CognitiveEvent.EscalationConsidered.EVENT_TYPE,
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

    // ==================== Task Lifecycle Publishing Methods ====================

    /** Publish a TaskStarted event when an agent begins executing a task. */
    suspend fun publishTaskStarted(
        taskId: TaskId,
        workspace: ExecutionWorkspace? = null,
        urgency: Urgency = Urgency.LOW,
        causedBy: EventId? = null,
        runId: RunId? = null,
    ) {
        val event = TaskEvent.TaskStarted(
            eventId = generateUUID(taskId, agentId),
            taskId = taskId,
            eventSource = EventSource.Agent(agentId),
            timestamp = clock.now(),
            assignedTo = agentId,
            workspace = workspace,
            urgency = urgency,
        )

        publish(event, causedBy = causedBy, runId = runId)
    }

    /** Publish a TaskProgressed event for measurable progress on a task. */
    suspend fun publishTaskProgressed(
        taskId: TaskId,
        description: String,
        progress: Float? = null,
        urgency: Urgency = Urgency.LOW,
        causedBy: EventId? = null,
        runId: RunId? = null,
    ) {
        val event = TaskEvent.TaskProgressed(
            eventId = generateUUID(taskId, agentId),
            taskId = taskId,
            eventSource = EventSource.Agent(agentId),
            timestamp = clock.now(),
            description = description,
            progress = progress,
            urgency = urgency,
        )

        publish(event, causedBy = causedBy, runId = runId)
    }

    /** Publish a TaskCompleted event when a task finishes successfully. */
    suspend fun publishTaskCompleted(
        taskId: TaskId,
        summary: String,
        taskType: String? = null,
        runId: String? = null,
        urgency: Urgency = Urgency.MEDIUM,
        causedBy: EventId? = null,
    ) {
        val event = TaskEvent.TaskCompleted(
            eventId = generateUUID(taskId, agentId),
            taskId = taskId,
            eventSource = EventSource.Agent(agentId),
            timestamp = clock.now(),
            summary = summary,
            taskType = taskType,
            runId = runId,
            urgency = urgency,
        )

        publish(event, causedBy = causedBy, runId = runId)
    }

    /** Publish a TaskFailed event when a task cannot be completed. */
    suspend fun publishTaskFailed(
        taskId: TaskId,
        reason: String,
        runId: String? = null,
        urgency: Urgency = Urgency.HIGH,
        causedBy: EventId? = null,
    ) {
        val event = TaskEvent.TaskFailed(
            eventId = generateUUID(taskId, agentId),
            taskId = taskId,
            eventSource = EventSource.Agent(agentId),
            timestamp = clock.now(),
            reason = reason,
            runId = runId,
            urgency = urgency,
        )

        publish(event, causedBy = causedBy, runId = runId)
    }

    /**
     * Publish a milestone when an agent or external consumer identifies a significant checkpoint.
     */
    suspend fun reachMilestone(
        category: MilestoneCategory,
        description: String,
        knowledgeId: String? = null,
        taskId: TaskId? = null,
        runId: String? = null,
        milestoneId: String = generateUUID("milestone", agentId),
        urgency: Urgency = Urgency.MEDIUM,
        causedBy: EventId? = null,
    ) {
        val event = MemoryEvent.MilestoneReached(
            eventId = generateUUID(milestoneId, agentId),
            timestamp = clock.now(),
            eventSource = EventSource.Agent(agentId),
            agentId = agentId,
            milestoneId = milestoneId,
            description = description,
            knowledgeId = knowledgeId,
            taskId = taskId,
            runId = runId,
            category = category,
            urgency = urgency,
        )

        publish(event, causedBy = causedBy, runId = runId)
    }

    /** Publish a TaskBlocked event when a task is blocked by another task. */
    suspend fun publishTaskBlocked(
        taskId: TaskId,
        blockedByTaskId: TaskId,
        reason: String,
        urgency: Urgency = Urgency.MEDIUM,
        causedBy: EventId? = null,
        runId: RunId? = null,
    ) {
        val event = TaskEvent.TaskBlocked(
            eventId = generateUUID(taskId, agentId),
            taskId = taskId,
            eventSource = EventSource.Agent(agentId),
            timestamp = clock.now(),
            blockedByTaskId = blockedByTaskId,
            reason = reason,
            urgency = urgency,
        )

        publish(event, causedBy = causedBy, runId = runId)
    }

    /** Publish a SubtaskCreated event when a task is decomposed. */
    suspend fun publishSubtaskCreated(
        parentTaskId: TaskId,
        subtaskId: TaskId,
        description: String,
        assignedTo: AgentId? = null,
        workspace: ExecutionWorkspace? = null,
        urgency: Urgency = Urgency.LOW,
        causedBy: EventId? = null,
        runId: RunId? = null,
    ) {
        val event = TaskEvent.SubtaskCreated(
            eventId = generateUUID(subtaskId, agentId),
            taskId = parentTaskId,
            eventSource = EventSource.Agent(agentId),
            timestamp = clock.now(),
            subtaskId = subtaskId,
            description = description,
            assignedTo = assignedTo,
            workspace = workspace,
            urgency = urgency,
        )

        publish(event, causedBy = causedBy, runId = runId)
    }

    // ==================== Task Lifecycle Subscription Methods ====================

    /** Subscribe to TaskStarted events. */
    fun onTaskStarted(
        filter: EventFilter<TaskEvent.TaskStarted> = EventFilter.noFilter(),
        handler: suspend (TaskEvent.TaskStarted, Subscription?) -> Unit,
    ): Subscription =
        eventSerialBus.subscribe<TaskEvent.TaskStarted, EventSubscription.ByEventClassType>(
            agentId = agentId,
            eventType = TaskEvent.TaskStarted.EVENT_TYPE,
        ) { event, subscription ->
            if (filter.execute(event)) {
                handler(event, subscription)
            }
        }

    /** Subscribe to TaskCompleted events. */
    fun onTaskCompleted(
        filter: EventFilter<TaskEvent.TaskCompleted> = EventFilter.noFilter(),
        handler: suspend (TaskEvent.TaskCompleted, Subscription?) -> Unit,
    ): Subscription =
        eventSerialBus.subscribe<TaskEvent.TaskCompleted, EventSubscription.ByEventClassType>(
            agentId = agentId,
            eventType = TaskEvent.TaskCompleted.EVENT_TYPE,
        ) { event, subscription ->
            if (filter.execute(event)) {
                handler(event, subscription)
            }
        }

    /** Subscribe to TaskFailed events. */
    fun onTaskFailed(
        filter: EventFilter<TaskEvent.TaskFailed> = EventFilter.noFilter(),
        handler: suspend (TaskEvent.TaskFailed, Subscription?) -> Unit,
    ): Subscription =
        eventSerialBus.subscribe<TaskEvent.TaskFailed, EventSubscription.ByEventClassType>(
            agentId = agentId,
            eventType = TaskEvent.TaskFailed.EVENT_TYPE,
        ) { event, subscription ->
            if (filter.execute(event)) {
                handler(event, subscription)
            }
        }

    /** Subscribe to TaskBlocked events. */
    fun onTaskBlocked(
        filter: EventFilter<TaskEvent.TaskBlocked> = EventFilter.noFilter(),
        handler: suspend (TaskEvent.TaskBlocked, Subscription?) -> Unit,
    ): Subscription =
        eventSerialBus.subscribe<TaskEvent.TaskBlocked, EventSubscription.ByEventClassType>(
            agentId = agentId,
            eventType = TaskEvent.TaskBlocked.EVENT_TYPE,
        ) { event, subscription ->
            if (filter.execute(event)) {
                handler(event, subscription)
            }
        }

    /** Subscribe to milestone events. */
    fun onMilestoneReached(
        filter: EventFilter<MemoryEvent.MilestoneReached> = EventFilter.noFilter(),
        handler: suspend (MemoryEvent.MilestoneReached, Subscription?) -> Unit,
    ): Subscription =
        eventSerialBus.subscribe<MemoryEvent.MilestoneReached, EventSubscription.ByEventClassType>(
            agentId = agentId,
            eventType = MemoryEvent.MilestoneReached.EVENT_TYPE,
        ) { event, subscription ->
            if (filter.execute(event)) {
                handler(event, subscription)
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
        causedBy: EventId? = null,
        runId: RunId? = null,
    ) {
        val event = ToolEvent.ToolRegistered(
            eventId = generateUUID(toolId, agentId),
            urgency = urgency,
            timestamp = clock.now(),
            eventSource = EventSource.Agent(agentId),
            toolId = toolId,
            toolName = toolName,
            toolType = toolType,
            requiredAutonomy = requiredAutonomy,
            mcpServerId = mcpServerId,
        )

        publish(event, causedBy = causedBy, runId = runId)
    }

    /** Publish a ToolUnregistered event with auto-generated ID and current timestamp. */
    suspend fun publishToolUnregistered(
        toolId: String,
        toolName: String,
        reason: String,
        mcpServerId: String? = null,
        urgency: Urgency = Urgency.MEDIUM,
        causedBy: EventId? = null,
        runId: RunId? = null,
    ) {
        val event = ToolEvent.ToolUnregistered(
            eventId = generateUUID(toolId, agentId),
            urgency = urgency,
            timestamp = clock.now(),
            eventSource = EventSource.Agent(agentId),
            toolId = toolId,
            toolName = toolName,
            reason = reason,
            mcpServerId = mcpServerId,
        )

        publish(event, causedBy = causedBy, runId = runId)
    }

    /** Publish a ToolDiscoveryComplete event with auto-generated ID and current timestamp. */
    suspend fun publishToolDiscoveryComplete(
        totalToolsDiscovered: Int,
        functionToolCount: Int,
        mcpToolCount: Int,
        mcpServerCount: Int,
        urgency: Urgency = Urgency.LOW,
        causedBy: EventId? = null,
        runId: RunId? = null,
    ) {
        val event = ToolEvent.ToolDiscoveryComplete(
            eventId = generateUUID(agentId),
            urgency = urgency,
            timestamp = clock.now(),
            eventSource = EventSource.Agent(agentId),
            totalToolsDiscovered = totalToolsDiscovered,
            functionToolCount = functionToolCount,
            mcpToolCount = mcpToolCount,
            mcpServerCount = mcpServerCount,
        )

        publish(event, causedBy = causedBy, runId = runId)
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

    suspend fun publishPermissionDenied(
        plugId: String,
        toolId: String,
        toolName: String,
        permission: PlugPermission,
        reason: PermissionDeniedReason,
        urgency: Urgency = Urgency.HIGH,
        causedBy: EventId? = null,
        runId: RunId? = null,
    ) {
        val event = PermissionDeniedEvent(
            eventId = generateUUID("permission-denied", plugId, toolId, agentId),
            timestamp = clock.now(),
            eventSource = EventSource.Agent(agentId),
            urgency = urgency,
            plugId = plugId,
            toolId = toolId,
            toolName = toolName,
            permission = permission,
            reason = reason,
        )

        publish(event, causedBy = causedBy, runId = runId)
    }

    fun onPermissionDenied(
        filter: EventFilter<PermissionDeniedEvent> = EventFilter.noFilter(),
        handler: suspend (PermissionDeniedEvent, Subscription?) -> Unit,
    ): Subscription =
        eventSerialBus.subscribe<PermissionDeniedEvent, EventSubscription.ByEventClassType>(
            agentId = agentId,
            eventType = PermissionDeniedEvent.EVENT_TYPE,
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
