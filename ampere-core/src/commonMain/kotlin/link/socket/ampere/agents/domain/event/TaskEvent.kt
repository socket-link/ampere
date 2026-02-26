package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.task.TaskId
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace

/**
 * Task lifecycle events flowing through the EventBus.
 *
 * These events complement [Event.TaskCreated] by tracking the full task lifecycle:
 * created → started → progressed → completed/failed/blocked.
 *
 * Together they enable event-sourced projection of workspace state into a live checklist.
 */
@Serializable
sealed interface TaskEvent : Event {

    /** The task this event pertains to. */
    val taskId: TaskId

    /** Emitted when an agent begins executing a task. */
    @Serializable
    data class TaskStarted(
        override val eventId: EventId,
        override val taskId: TaskId,
        override val eventSource: EventSource,
        override val timestamp: Instant,
        val assignedTo: AgentId,
        val workspace: ExecutionWorkspace? = null,
        override val urgency: Urgency = Urgency.LOW,
    ) : TaskEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Task $taskId started by $assignedTo")
            workspace?.let { append(" in ${it.baseDirectory}") }
            append(" ${formatUrgency(urgency)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "TaskStarted"
        }
    }

    /** Emitted when measurable progress is made on a task. */
    @Serializable
    data class TaskProgressed(
        override val eventId: EventId,
        override val taskId: TaskId,
        override val eventSource: EventSource,
        override val timestamp: Instant,
        val description: String,
        val progress: Float? = null,
        override val urgency: Urgency = Urgency.LOW,
    ) : TaskEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Task $taskId progress: $description")
            progress?.let { append(" (${(it * 100).toInt()}%)") }
            append(" ${formatUrgency(urgency)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "TaskProgressed"
        }
    }

    /** Emitted when a task is completed successfully. */
    @Serializable
    data class TaskCompleted(
        override val eventId: EventId,
        override val taskId: TaskId,
        override val eventSource: EventSource,
        override val timestamp: Instant,
        val summary: String,
        override val urgency: Urgency = Urgency.MEDIUM,
    ) : TaskEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = "Task $taskId completed: $summary ${formatUrgency(urgency)}"

        companion object {
            const val EVENT_TYPE: EventType = "TaskCompleted"
        }
    }

    /** Emitted when a task fails with an error. */
    @Serializable
    data class TaskFailed(
        override val eventId: EventId,
        override val taskId: TaskId,
        override val eventSource: EventSource,
        override val timestamp: Instant,
        val reason: String,
        override val urgency: Urgency = Urgency.HIGH,
    ) : TaskEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = "Task $taskId failed: $reason ${formatUrgency(urgency)}"

        companion object {
            const val EVENT_TYPE: EventType = "TaskFailed"
        }
    }

    /** Emitted when a task is blocked by another task or external dependency. */
    @Serializable
    data class TaskBlocked(
        override val eventId: EventId,
        override val taskId: TaskId,
        override val eventSource: EventSource,
        override val timestamp: Instant,
        val blockedByTaskId: TaskId,
        val reason: String,
        override val urgency: Urgency = Urgency.MEDIUM,
    ) : TaskEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = "Task $taskId blocked by $blockedByTaskId: $reason ${formatUrgency(urgency)}"

        companion object {
            const val EVENT_TYPE: EventType = "TaskBlocked"
        }
    }

    /** Emitted when a task is decomposed into a subtask. */
    @Serializable
    data class SubtaskCreated(
        override val eventId: EventId,
        override val taskId: TaskId,
        override val eventSource: EventSource,
        override val timestamp: Instant,
        val subtaskId: TaskId,
        val description: String,
        val assignedTo: AgentId? = null,
        val workspace: ExecutionWorkspace? = null,
        override val urgency: Urgency = Urgency.LOW,
    ) : TaskEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Subtask $subtaskId created under $taskId: $description")
            assignedTo?.let { append(" (assigned to $it)") }
            workspace?.let { append(" in ${it.baseDirectory}") }
            append(" ${formatUrgency(urgency)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "SubtaskCreated"
        }
    }
}
