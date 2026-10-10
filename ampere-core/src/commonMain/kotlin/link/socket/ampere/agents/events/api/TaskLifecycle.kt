package link.socket.ampere.agents.events.api

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.EventId
import link.socket.ampere.agents.domain.task.ExecutionAssignment
import link.socket.ampere.agents.domain.task.TaskId
import link.socket.ampere.agents.domain.task.WorkPhase
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.events.utils.generateUUID

/**
 * One unit of work's task lifecycle, published through one door under one run id.
 *
 * Open one with [openTaskLifecycle] — which publishes `TaskCreated` and
 * `TaskStarted` — then call [progressed], [subtaskCreated] or [blocked] as the work goes, and
 * exactly one of [completed] or [failed] when it ends. That sequence is what moves a
 * `WorkspaceStateStore` item off `Pending`, and what `MilestoneTracker` needs to see before it
 * can report a `FIRST_SUCCESS` or a `RECOVERY`: before this existed, every caller published a
 * bare `TaskCreated` and nothing else, so every item it added stayed `Pending` forever and no
 * milestone ever fired (AMPR-404).
 *
 * Every event carries [runId] (F4), so the whole lifecycle is one `run_id` query on the store
 * even though the publishers return no event ids to chain `causedBy` through.
 *
 * ### Terminal exactly once
 *
 * The first [completed] or [failed] wins; a later one is a no-op. That makes a `finally` block
 * safe —
 *
 * ```kotlin
 * val lifecycle = eventApi.openTaskLifecycle(taskId = task.id, description = task.summary)
 * try {
 *     lifecycle.completed(doTheWork())
 * } finally {
 *     // Covers the throw, the early return and the cancellation. No-op if we completed.
 *     lifecycle.failed("abandoned before completing")
 * }
 * ```
 *
 * — and it means a projection can treat the first terminal event as the one that happened.
 *
 * Both terminal methods publish under [NonCancellable], so a cancelled unit of work still
 * records how it ended instead of leaving the item mid-flight.
 *
 * The claim is staked before the publish, not after it, because the publishers return `Unit`
 * and there is nothing to read a failure from. So a terminal event the store refuses leaves
 * this lifecycle terminal with nothing recorded — the door says so on the bus as
 * `EventStoreEvent.PersistenceFailed`, and no retry happens here.
 *
 * Not thread-safe beyond that guarantee: one unit of work, one owner.
 */
@OptIn(ExperimentalAtomicApi::class)
class TaskLifecycle internal constructor(
    private val eventApi: AgentEventApi,
    /** The task every event in this lifecycle is about. */
    val taskId: TaskId,
    /** The run every event in this lifecycle carries (F4). */
    val runId: RunId,
    private val taskType: String?,
    private val workspace: ExecutionWorkspace?,
    private val execution: ExecutionAssignment?,
) {
    private val terminated = AtomicBoolean(false)

    /** True once [completed] or [failed] has published. */
    val isTerminal: Boolean
        get() = terminated.load()

    /**
     * Report measurable progress.
     *
     * @param progress a fraction in `0f..1f`; omit it to let `WorkspaceStateStore` advance the
     * item by its own increment instead.
     */
    suspend fun progressed(
        description: String,
        progress: Float? = null,
        urgency: Urgency = Urgency.LOW,
    ) {
        eventApi.publishTaskProgressed(
            taskId = taskId,
            description = description,
            progress = progress,
            urgency = urgency,
            runId = runId,
        )
    }

    /**
     * Report that this task decomposed into [subtaskId].
     *
     * `WorkspaceStateStore` folds this into a child item of [taskId], so a plan's steps show up
     * under the work they came from.
     *
     * @param phase stated per subtask, never inherited from the parent (AMPR-369).
     */
    suspend fun subtaskCreated(
        subtaskId: TaskId,
        description: String,
        assignedTo: AgentId? = null,
        urgency: Urgency = Urgency.LOW,
        phase: WorkPhase? = null,
        execution: ExecutionAssignment? = this.execution,
    ) {
        eventApi.publishSubtaskCreated(
            parentTaskId = taskId,
            subtaskId = subtaskId,
            description = description,
            assignedTo = assignedTo,
            workspace = workspace,
            urgency = urgency,
            runId = runId,
            phase = phase,
            execution = execution,
        )
    }

    /**
     * Report that this task cannot proceed until [blockedByTaskId] does.
     *
     * Not terminal: a task that is unblocked goes on to [completed] or [failed].
     */
    suspend fun blocked(
        blockedByTaskId: TaskId,
        reason: String,
        urgency: Urgency = Urgency.MEDIUM,
    ) {
        eventApi.publishTaskBlocked(
            taskId = taskId,
            blockedByTaskId = blockedByTaskId,
            reason = reason,
            urgency = urgency,
            runId = runId,
        )
    }

    /**
     * End the lifecycle successfully. A no-op once terminal.
     *
     * @return true if this call published the terminal event
     */
    suspend fun completed(
        summary: String,
        urgency: Urgency = Urgency.MEDIUM,
    ): Boolean {
        if (!terminated.compareAndSet(false, true)) return false

        withContext(NonCancellable) {
            eventApi.publishTaskCompleted(
                taskId = taskId,
                summary = summary,
                taskType = taskType,
                runId = runId,
                urgency = urgency,
            )
        }

        return true
    }

    /**
     * End the lifecycle in failure. A no-op once terminal.
     *
     * @return true if this call published the terminal event
     */
    suspend fun failed(
        reason: String,
        urgency: Urgency = Urgency.HIGH,
    ): Boolean {
        if (!terminated.compareAndSet(false, true)) return false

        withContext(NonCancellable) {
            eventApi.publishTaskFailed(
                taskId = taskId,
                reason = reason,
                runId = runId,
                urgency = urgency,
            )
        }

        return true
    }

    /**
     * End the lifecycle from a caught [throwable], reading [CancellationException] as an
     * abandoned run rather than a failure of the work itself. A no-op once terminal.
     */
    suspend fun failed(throwable: Throwable): Boolean =
        if (throwable is CancellationException) {
            failed("Cancelled before completing")
        } else {
            failed(throwable.message ?: throwable::class.simpleName ?: "Unknown failure")
        }
}

/**
 * Publish `TaskCreated` and `TaskStarted` for one unit of work and return its [TaskLifecycle].
 *
 * The caller is responsible for ending it — see [TaskLifecycle] for the `finally` shape that
 * guarantees it.
 *
 * @param runId the run every event in this lifecycle carries (F4). Pass the run you already
 * hold; the default mints one so the lifecycle is still queryable as a unit.
 * @param assignedTo who owns the work. Defaults to this door's own agent, which is also what
 * `TaskStarted` records regardless — `MilestoneTracker` only counts tasks this agent published.
 * @param causedBy the event whose handling produced this work, if any (F2).
 * @param phase read-only investigation or implementation, fixed here at creation (AMPR-369).
 * @param execution the model and effort the work runs with.
 * @param taskType the type key `MilestoneTracker` counts a `FIRST_SUCCESS` against. Defaults to
 * null, which makes the tracker fall back to [taskId] — i.e. every task is its own first.
 */
suspend fun AgentEventApi.openTaskLifecycle(
    taskId: TaskId,
    description: String,
    runId: RunId = generateUUID("task-run"),
    assignedTo: AgentId? = agentId,
    urgency: Urgency = Urgency.MEDIUM,
    taskType: String? = null,
    workspace: ExecutionWorkspace? = null,
    causedBy: EventId? = null,
    phase: WorkPhase? = null,
    execution: ExecutionAssignment? = null,
): TaskLifecycle {
    publishTaskCreated(
        taskId = taskId,
        urgency = urgency,
        description = description,
        assignedTo = assignedTo,
        causedBy = causedBy,
        runId = runId,
        phase = phase,
        execution = execution,
    )

    publishTaskStarted(
        taskId = taskId,
        workspace = workspace,
        urgency = urgency,
        causedBy = causedBy,
        runId = runId,
        execution = execution,
    )

    return TaskLifecycle(
        eventApi = this,
        taskId = taskId,
        runId = runId,
        taskType = taskType,
        workspace = workspace,
        execution = execution,
    )
}
