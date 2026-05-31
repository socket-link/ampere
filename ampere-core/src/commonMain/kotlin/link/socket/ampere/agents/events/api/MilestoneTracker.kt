package link.socket.ampere.agents.events.api

import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.event.MilestoneCategory
import link.socket.ampere.agents.domain.event.TaskEvent
import link.socket.ampere.agents.domain.task.TaskId
import link.socket.ampere.agents.events.subscription.Subscription

/**
 * Per-agent milestone detector driven by task lifecycle events.
 *
 * The tracker keeps process-local state for the [AgentEventApi] that owns it.
 * It does not persist milestone detection state; cross-process aggregation is
 * left to consumers that subscribe to [MemoryEvent.MilestoneReached].
 */
internal class MilestoneTracker(
    private val agentEventApi: AgentEventApi,
    private val state: MilestoneTrackerState = MilestoneTrackerState(),
) {
    private val subscriptions = mutableListOf<Subscription>()

    fun start() {
        subscriptions += agentEventApi.onTaskFailed(
            filter = agentEventApi.filterForEventsCreatedByMe(),
        ) { event, _ ->
            state.failedTasks += event.taskId
        }

        subscriptions += agentEventApi.onTaskCompleted(
            filter = agentEventApi.filterForEventsCreatedByMe(),
        ) { event, _ ->
            publishFirstSuccessMilestone(event)
            publishRecoveryMilestone(event)
        }
    }

    private suspend fun publishFirstSuccessMilestone(event: TaskEvent.TaskCompleted) {
        val taskType = event.taskType?.takeIf { it.isNotBlank() } ?: event.taskId
        if (!state.seenSuccessfulTaskTypes.add(taskType)) {
            return
        }

        agentEventApi.reachMilestone(
            category = MilestoneCategory.FIRST_SUCCESS,
            description = "First successful completion for task type $taskType",
            taskId = event.taskId,
            runId = event.runId,
        )
    }

    private suspend fun publishRecoveryMilestone(event: TaskEvent.TaskCompleted) {
        if (!state.failedTasks.remove(event.taskId)) {
            return
        }

        agentEventApi.reachMilestone(
            category = MilestoneCategory.RECOVERY,
            description = "Recovered after a prior failure on task ${event.taskId}",
            taskId = event.taskId,
            runId = event.runId,
        )
    }
}

/**
 * Process-local milestone detection state shared by APIs for the same agent.
 */
class MilestoneTrackerState {
    internal val seenSuccessfulTaskTypes = mutableSetOf<String>()
    internal val failedTasks = mutableSetOf<TaskId>()
}
