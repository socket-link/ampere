package link.socket.ampere.dsl.events

import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.HumanInteractionEvent
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.event.PlanEvent
import link.socket.ampere.agents.domain.event.TicketEvent
import link.socket.ampere.agents.domain.outcome.StepOutcome

/**
 * Adapts internal Event types to simplified TeamEvent types for the DSL.
 *
 * This adapter bridges the gap between the internal event system (30+ event types)
 * and the user-friendly DSL events (Perceived, Recalled, Planned, Executed, Escalated).
 */
class TeamEventAdapter {

    /**
     * Convert an internal event to a TeamEvent, or null if not relevant for the DSL.
     */
    fun adapt(event: Event): TeamEvent? = when (event) {
        // Memory events
        is MemoryEvent.KnowledgeRecalled -> adaptKnowledgeRecalled(event)
        is MemoryEvent.KnowledgeStored -> null // Internal, not surfaced to DSL users

        // Plan events
        is PlanEvent.PlanStepStarted -> adaptPlanStepStarted(event)
        is PlanEvent.PlanStepCompleted -> adaptPlanStepCompleted(event)
        is PlanEvent.TaskAssigned -> adaptTaskAssigned(event)
        is PlanEvent.MonitoringStarted -> null // Internal

        // Human interaction events (escalation)
        is HumanInteractionEvent.InputRequested -> adaptInputRequested(event)
        is HumanInteractionEvent.InputProvided -> null // Response, not escalation
        is HumanInteractionEvent.RequestTimedOut -> adaptRequestTimedOut(event)

        // Ticket events
        is TicketEvent.TicketCompleted -> adaptTicketCompleted(event)
        is TicketEvent.TicketBlocked -> adaptTicketBlocked(event)
        is TicketEvent.TicketCreated -> adaptTicketCreated(event)
        is TicketEvent.TicketStatusChanged -> null // Detailed status, not relevant
        is TicketEvent.TicketAssigned -> null
        is TicketEvent.TicketMeetingScheduled -> null

        // Base events
        is Event.TaskCreated -> adaptTaskCreated(event)
        is Event.CodeSubmitted -> adaptCodeSubmitted(event)
        is Event.QuestionRaised -> adaptQuestionRaised(event)

        else -> null // Unknown event type, skip
    }

    private fun adaptKnowledgeRecalled(event: MemoryEvent.KnowledgeRecalled): Recalled {
        val memoryDescription = event.retrievedKnowledge.firstOrNull()?.approach
            ?: event.context.description
        return Recalled(
            agent = event.eventSource.getIdentifier(),
            memory = memoryDescription,
            relevance = event.averageRelevance,
            timestamp = event.timestamp,
        )
    }

    private fun adaptPlanStepStarted(event: PlanEvent.PlanStepStarted): Planned {
        return Planned(
            agent = event.eventSource.getIdentifier(),
            plan = "Step ${event.stepIndex + 1}/${event.totalSteps}: ${event.stepDescription}",
            timestamp = event.timestamp,
        )
    }

    private fun adaptPlanStepCompleted(event: PlanEvent.PlanStepCompleted): Executed {
        return Executed(
            agent = event.eventSource.getIdentifier(),
            action = event.stepDescription,
            result = formatOutcome(event.outcome),
            timestamp = event.timestamp,
        )
    }

    private fun adaptTaskAssigned(event: PlanEvent.TaskAssigned): TaskDelegated {
        return TaskDelegated(
            fromAgent = event.eventSource.getIdentifier(),
            toAgent = event.agentId,
            task = event.reasoning,
            timestamp = event.timestamp,
        )
    }

    private fun adaptInputRequested(event: HumanInteractionEvent.InputRequested): Escalated {
        return Escalated(
            agent = event.agentId,
            reason = event.question,
            context = event.context,
            timestamp = event.timestamp,
        )
    }

    private fun adaptRequestTimedOut(event: HumanInteractionEvent.RequestTimedOut): Escalated {
        return Escalated(
            agent = event.agentId,
            reason = "Request timed out after ${event.timeoutMinutes} minutes",
            timestamp = event.timestamp,
        )
    }

    private fun adaptTicketCompleted(event: TicketEvent.TicketCompleted): TaskCompleted {
        return TaskCompleted(
            agent = event.eventSource.getIdentifier(),
            task = "Ticket ${event.ticketId}",
            success = true,
            timestamp = event.timestamp,
        )
    }

    private fun adaptTicketBlocked(event: TicketEvent.TicketBlocked): Escalated {
        return Escalated(
            agent = event.eventSource.getIdentifier(),
            reason = event.blockingReason,
            timestamp = event.timestamp,
        )
    }

    private fun adaptTicketCreated(event: TicketEvent.TicketCreated): Planned {
        return Planned(
            agent = event.eventSource.getIdentifier(),
            plan = "Created ticket ${event.ticketId}: ${event.title}",
            timestamp = event.timestamp,
        )
    }

    private fun adaptTaskCreated(event: Event.TaskCreated): Planned {
        return Planned(
            agent = event.eventSource.getIdentifier(),
            plan = "Task: ${event.description}",
            timestamp = event.timestamp,
        )
    }

    private fun adaptCodeSubmitted(event: Event.CodeSubmitted): Executed {
        return Executed(
            agent = event.eventSource.getIdentifier(),
            action = "Code submitted: ${event.filePath}",
            result = event.changeDescription,
            timestamp = event.timestamp,
        )
    }

    private fun adaptQuestionRaised(event: Event.QuestionRaised): Escalated {
        return Escalated(
            agent = event.eventSource.getIdentifier(),
            reason = event.questionText,
            context = mapOf("context" to event.context),
            timestamp = event.timestamp,
        )
    }

    private fun formatOutcome(outcome: StepOutcome): String = when (outcome) {
        is StepOutcome.Success -> "Success: ${outcome.details}"
        is StepOutcome.Failure -> "Failed: ${outcome.error}"
        is StepOutcome.PartialSuccess ->
            "Partial: ${outcome.successCount}/${outcome.successCount + outcome.failureCount} succeeded"
        is StepOutcome.Skipped -> "Skipped: ${outcome.reason}"
    }
}
