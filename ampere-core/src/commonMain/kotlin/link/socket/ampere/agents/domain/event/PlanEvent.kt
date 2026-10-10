package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.task.AssignedTo

/**
 * Events related to plan execution in the Project Manager Agent.
 */
@Serializable
sealed interface PlanEvent : Event {

    /**
     * Emitted when a plan step begins execution.
     *
     * Published by [PlanExecutor][link.socket.ampere.agents.domain.reasoning.PlanExecutor]
     * when it has a door (AMPR-389). [runId] is the Arc run the step belongs to, and is
     * also what the envelope's `run_id` is set to, so the step is visible in that run's
     * trace; null means the plan ran outside a run.
     *
     * [assignedTo] is the seat the step was dispatched to — the one its
     * `Task.Step.assignedTo` / `Task.CodeChange.assignedTo` names (AMPR-410).
     * Null means the plan named no seat and the agent that published this ran
     * the step itself, which is every step of a single-agent plan.
     */
    @Serializable
    data class PlanStepStarted(
        override val eventId: EventId,
        val planId: String,
        val stepId: String,
        val stepDescription: String,
        val stepIndex: Int,
        val totalSteps: Int,
        override val eventSource: EventSource,
        override val timestamp: Instant,
        override val urgency: Urgency = Urgency.LOW,
        val runId: RunId? = null,
        val assignedTo: AssignedTo? = null,
    ) : PlanEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = "Plan step ${stepIndex + 1}/$totalSteps started${seatSuffix(assignedTo)}: " +
            "$stepDescription ${formatUrgency(urgency)}"

        companion object {
            const val EVENT_TYPE: EventType = "PlanStepStarted"
        }
    }

    /**
     * Emitted when a plan step completes execution.
     *
     * The pair to [PlanStepStarted], matched on [stepId] within [planId]; see that event
     * for [runId] and [assignedTo]. A step that was never started — one the executor
     * skipped after an earlier critical failure — emits neither event, and is reported only in
     * [PlanExecutionResult.stepOutcomes][link.socket.ampere.agents.domain.reasoning.PlanExecutionResult.stepOutcomes].
     */
    @Serializable
    data class PlanStepCompleted(
        override val eventId: EventId,
        val planId: String,
        val stepId: String,
        val stepDescription: String,
        val stepIndex: Int,
        val totalSteps: Int,
        val outcome: StepOutcome,
        override val eventSource: EventSource,
        override val timestamp: Instant,
        override val urgency: Urgency = Urgency.LOW,
        val runId: RunId? = null,
        val assignedTo: AssignedTo? = null,
    ) : PlanEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String {
            val status = when (outcome) {
                is StepOutcome.Success -> "✓ completed"
                is StepOutcome.PartialSuccess ->
                    "⚠ partially completed " +
                        "(${outcome.successCount}/${outcome.successCount + outcome.failureCount})"
                is StepOutcome.Failure -> "✗ failed: ${outcome.error}"
                is StepOutcome.Skipped -> "⊘ skipped: ${outcome.reason}"
            }
            return "Plan step ${stepIndex + 1}/$totalSteps${seatSuffix(assignedTo)} $status " +
                formatUrgency(urgency)
        }

        companion object {
            const val EVENT_TYPE: EventType = "PlanStepCompleted"
        }
    }

    /** Emitted when a task is assigned to an agent. */
    @Serializable
    data class TaskAssigned(
        override val eventId: EventId,
        val taskLocalId: String,
        val issueNumber: Int?,
        val agentId: AgentId,
        val reasoning: String,
        override val eventSource: EventSource,
        override val timestamp: Instant,
        override val urgency: Urgency = Urgency.MEDIUM,
    ) : PlanEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String {
            val issueRef = issueNumber?.let { "#$it" } ?: taskLocalId
            return "Task $issueRef assigned to $agentId: $reasoning ${formatUrgency(urgency)}"
        }

        companion object {
            const val EVENT_TYPE: EventType = "TaskAssigned"
        }
    }

    /** Emitted when an epic starts being monitored. */
    @Serializable
    data class MonitoringStarted(
        override val eventId: EventId,
        val epicLocalId: String,
        val epicIssueNumber: Int?,
        val taskCount: Int,
        override val eventSource: EventSource,
        override val timestamp: Instant,
        override val urgency: Urgency = Urgency.LOW,
    ) : PlanEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String {
            val epicRef = epicIssueNumber?.let { "#$it" } ?: epicLocalId
            return "Monitoring started for epic $epicRef ($taskCount tasks) ${formatUrgency(urgency)}"
        }

        companion object {
            const val EVENT_TYPE: EventType = "MonitoringStarted"
        }
    }
}

/**
 * ` [seat]`, or nothing when the plan named no seat (AMPR-410).
 *
 * A step's summary is what the CLI event stream prints, so a roster-hosted run
 * reads as which seat ran what rather than as an undifferentiated step list. A
 * single-agent plan assigns nothing and its summaries are byte-identical to
 * what they were.
 */
private fun seatSuffix(assignedTo: AssignedTo?): String =
    assignedTo?.let { " [${it.getIdentifier()}]" } ?: ""
