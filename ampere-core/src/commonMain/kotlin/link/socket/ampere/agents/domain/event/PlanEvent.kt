package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.outcome.StepOutcome

/**
 * Events related to plan execution in the Project Manager Agent.
 */
@Serializable
sealed interface PlanEvent : Event {

    /** Emitted when a plan step begins execution. */
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
    ) : PlanEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = "Plan step ${stepIndex + 1}/$totalSteps started: $stepDescription ${formatUrgency(urgency)}"

        companion object {
            const val EVENT_TYPE: EventType = "PlanStepStarted"
        }
    }

    /** Emitted when a plan step completes execution. */
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
    ) : PlanEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String {
            val status = when (outcome) {
                is StepOutcome.Success -> "✓ completed"
                is StepOutcome.PartialSuccess -> "⚠ partially completed (${outcome.successCount}/${outcome.successCount + outcome.failureCount})"
                is StepOutcome.Failure -> "✗ failed: ${outcome.error}"
                is StepOutcome.Skipped -> "⊘ skipped: ${outcome.reason}"
            }
            return "Plan step ${stepIndex + 1}/$totalSteps $status ${formatUrgency(urgency)}"
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
