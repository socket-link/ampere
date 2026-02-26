package link.socket.ampere.agents.environment

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventRegistry
import link.socket.ampere.agents.domain.event.TaskEvent
import link.socket.ampere.agents.domain.state.WorkItem
import link.socket.ampere.agents.domain.state.WorkspaceState
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.AssignedTo
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.subscription.Subscription
import link.socket.ampere.agents.events.utils.ConsoleEventLogger
import link.socket.ampere.agents.events.utils.EventLogger

/**
 * Event-sourced projection of task lifecycle events into a live workspace checklist.
 *
 * Subscribes to [EventSerialBus] for task-related events and folds them into a
 * [StateFlow] of [WorkspaceState]. The core [fold] function is pure — no I/O,
 * no side effects — making it trivially testable and replayable.
 *
 * UI layers (TUI, Compose) collect [state] for reactive checklist updates.
 */
class WorkspaceStateStore(
    private val eventSerialBus: EventSerialBus,
    private val scope: CoroutineScope,
    private val eventRepository: EventRepository? = null,
    private val clock: Clock = Clock.System,
    private val logger: EventLogger = ConsoleEventLogger(),
) {
    private val _state = MutableStateFlow(WorkspaceState.empty())

    /** Reactive snapshot of workspace state. Collect this in your UI. */
    val state: StateFlow<WorkspaceState> = _state.asStateFlow()

    private var replayJob: Job? = null

    /**
     * Start the store: replay historical events (if repository available), then subscribe to live events.
     */
    fun start() {
        // Subscribe to live events immediately
        subscribeToTaskEvents()

        // Replay historical events in the background to reconstruct state
        if (eventRepository != null) {
            replayJob = scope.launch {
                replayHistoricalEvents()
            }
        }
    }

    private fun subscribeToTaskEvents() {
        val taskEventTypes = listOf(
            Event.TaskCreated.EVENT_TYPE,
            TaskEvent.TaskStarted.EVENT_TYPE,
            TaskEvent.TaskProgressed.EVENT_TYPE,
            TaskEvent.TaskCompleted.EVENT_TYPE,
            TaskEvent.TaskFailed.EVENT_TYPE,
            TaskEvent.TaskBlocked.EVENT_TYPE,
            TaskEvent.SubtaskCreated.EVENT_TYPE,
        )

        taskEventTypes.forEach { eventType ->
            eventSerialBus.subscribe(
                agentId = STORE_AGENT_ID,
                eventType = eventType,
                handler = EventHandler { event: Event, _: Subscription? ->
                    applyEvent(event)
                },
            )
        }
    }

    private suspend fun replayHistoricalEvents() {
        eventRepository?.getAllEvents()
            ?.onSuccess { events ->
                // Events come newest-first from the repo, reverse for chronological fold
                events.asReversed().forEach { event ->
                    applyEvent(event)
                }
            }
            ?.onFailure { throwable ->
                logger.logError(
                    message = "Failed to replay events for workspace state",
                    throwable = throwable,
                )
            }
    }

    private fun applyEvent(event: Event) {
        _state.value = fold(_state.value, event)
    }

    companion object {
        private const val STORE_AGENT_ID = "WORKSPACE_STATE_STORE"

        /**
         * Pure fold function: given current state and an event, produce next state.
         *
         * Deterministic, side-effect-free, and replayable.
         */
        fun fold(current: WorkspaceState, event: Event): WorkspaceState = when (event) {
            is Event.TaskCreated -> current.addItem(
                WorkItem(
                    id = event.taskId,
                    title = event.description,
                    status = TaskStatus.Pending,
                    assignedTo = event.assignedTo?.let { AssignedTo.Agent(it) },
                    createdAt = event.timestamp,
                    updatedAt = event.timestamp,
                    events = listOf(event.eventId),
                )
            )

            is TaskEvent.TaskStarted -> current.updateItem(event.taskId) {
                copy(
                    status = TaskStatus.InProgress,
                    assignedTo = AssignedTo.Agent(event.assignedTo),
                    workspace = event.workspace ?: workspace,
                    updatedAt = event.timestamp,
                    events = events + event.eventId,
                )
            }

            is TaskEvent.TaskProgressed -> current.updateItem(event.taskId) {
                copy(
                    progress = event.progress
                        ?: (progress + PROGRESS_INCREMENT).coerceAtMost(MAX_INCOMPLETE_PROGRESS),
                    updatedAt = event.timestamp,
                    events = events + event.eventId,
                )
            }

            is TaskEvent.TaskCompleted -> current.updateItem(event.taskId) {
                copy(
                    status = TaskStatus.Completed(
                        completedAt = event.timestamp,
                        completedBy = event.eventSource,
                    ),
                    progress = 1f,
                    updatedAt = event.timestamp,
                    events = events + event.eventId,
                )
            }

            is TaskEvent.TaskFailed -> current.updateItem(event.taskId) {
                copy(
                    status = TaskStatus.Blocked(reason = event.reason),
                    updatedAt = event.timestamp,
                    events = events + event.eventId,
                )
            }

            is TaskEvent.TaskBlocked -> current.updateItem(event.taskId) {
                copy(
                    status = TaskStatus.Blocked(reason = event.reason),
                    blockedBy = blockedBy + event.blockedByTaskId,
                    updatedAt = event.timestamp,
                    events = events + event.eventId,
                )
            }

            is TaskEvent.SubtaskCreated -> current.addItem(
                WorkItem(
                    id = event.subtaskId,
                    title = event.description,
                    status = TaskStatus.Pending,
                    assignedTo = event.assignedTo?.let { AssignedTo.Agent(it) },
                    workspace = event.workspace,
                    parentId = event.taskId,
                    createdAt = event.timestamp,
                    updatedAt = event.timestamp,
                    events = listOf(event.eventId),
                )
            )

            // Ignore all other event types
            else -> current
        }

        private const val PROGRESS_INCREMENT = 0.1f
        private const val MAX_INCOMPLETE_PROGRESS = 0.9f
    }
}
