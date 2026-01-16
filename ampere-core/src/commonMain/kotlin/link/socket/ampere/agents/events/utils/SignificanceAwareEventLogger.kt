package link.socket.ampere.agents.events.utils

import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.EventType
import link.socket.ampere.agents.domain.event.FileSystemEvent
import link.socket.ampere.agents.domain.event.GitEvent
import link.socket.ampere.agents.domain.event.HumanInteractionEvent
import link.socket.ampere.agents.domain.event.MeetingEvent
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.event.MessageEvent
import link.socket.ampere.agents.domain.event.NotificationEvent
import link.socket.ampere.agents.domain.event.PlanEvent
import link.socket.ampere.agents.domain.event.ProductEvent
import link.socket.ampere.agents.domain.event.SparkEvent
import link.socket.ampere.agents.domain.event.TicketEvent
import link.socket.ampere.agents.domain.event.ToolEvent
import link.socket.ampere.agents.events.subscription.Subscription

/**
 * Filters events based on significance and displays rich event details.
 *
 * By default, this logger filters out ROUTINE events (like KnowledgeRecalled/KnowledgeStored)
 * to reduce noise, and uses the event's getSummary() method to show meaningful details
 * about what's actually happening.
 *
 * @param showRoutineEvents If true, shows all events including routine maintenance operations
 * @param showSubscriptions If true, shows subscription/unsubscription events (can be noisy on startup)
 */
class SignificanceAwareEventLogger(
    private val showRoutineEvents: Boolean = false,
    private val showSubscriptions: Boolean = false,
) : EventLogger {

    override fun logPublish(event: Event) {
        // Determine event significance
        val significance = categorizeEvent(event)

        // Skip routine events unless explicitly enabled
        if (!showRoutineEvents && significance == EventSignificance.ROUTINE) {
            return
        }

        // Format the event with rich details
        val severityTag = when (significance) {
            EventSignificance.CRITICAL -> "[🔴 CRITICAL]"
            EventSignificance.SIGNIFICANT -> "[🟢 EVENT]"
            EventSignificance.ROUTINE -> "[⚪ ROUTINE]"
        }

        // Use the event's built-in summary method for rich details
        val summary = event.getSummary(
            formatUrgency = { urgency -> formatUrgency(urgency) },
            formatSource = { source -> formatSource(source) },
        )

        System.err.println("$severityTag $summary")
    }

    override fun logSubscription(eventType: EventType, subscription: Subscription) {
        if (showSubscriptions) {
            System.err.println("[EventBus][SUB] type=$eventType subscription=$subscription")
        }
    }

    override fun logUnsubscription(eventType: EventType, subscription: Subscription) {
        if (showSubscriptions) {
            System.err.println("[EventBus][UNSUB] type=$eventType subscription=$subscription")
        }
    }

    override fun logError(message: String, throwable: Throwable?) {
        System.err.println(
            "[EventBus][ERROR] $message" + (
                throwable?.let {
                    ": ${it::class.simpleName} - ${it.message}"
                } ?: ""
                ),
        )
        throwable?.printStackTrace()
    }

    override fun logInfo(message: String) {
        System.err.println("[EventBus][INFO] $message")
    }

    private fun categorizeEvent(event: Event): EventSignificance = when (event) {
        // Critical events require immediate human awareness
        is Event.QuestionRaised -> EventSignificance.CRITICAL
        is TicketEvent.TicketBlocked -> EventSignificance.CRITICAL
        is MessageEvent.EscalationRequested -> EventSignificance.CRITICAL

        // Significant events represent state changes worth noting
        is Event.TaskCreated -> EventSignificance.SIGNIFICANT
        is Event.CodeSubmitted -> EventSignificance.SIGNIFICANT
        is TicketEvent.TicketCreated -> EventSignificance.SIGNIFICANT
        is TicketEvent.TicketStatusChanged -> EventSignificance.SIGNIFICANT
        is TicketEvent.TicketAssigned -> EventSignificance.SIGNIFICANT
        is TicketEvent.TicketCompleted -> EventSignificance.SIGNIFICANT
        is TicketEvent.TicketMeetingScheduled -> EventSignificance.SIGNIFICANT
        is MeetingEvent.MeetingScheduled -> EventSignificance.SIGNIFICANT
        is MeetingEvent.MeetingStarted -> EventSignificance.SIGNIFICANT
        is MeetingEvent.MeetingCompleted -> EventSignificance.SIGNIFICANT
        is MeetingEvent.MeetingCanceled -> EventSignificance.SIGNIFICANT
        is MeetingEvent.AgendaItemStarted -> EventSignificance.SIGNIFICANT
        is MeetingEvent.AgendaItemCompleted -> EventSignificance.SIGNIFICANT
        is MessageEvent.ThreadCreated -> EventSignificance.SIGNIFICANT
        is MessageEvent.ThreadStatusChanged -> EventSignificance.SIGNIFICANT
        is MessageEvent.MessagePosted -> EventSignificance.SIGNIFICANT
        is ProductEvent.FeatureRequested -> EventSignificance.SIGNIFICANT
        is ProductEvent.EpicDefined -> EventSignificance.SIGNIFICANT
        is ProductEvent.PhaseDefined -> EventSignificance.SIGNIFICANT
        is FileSystemEvent -> EventSignificance.SIGNIFICANT
        is PlanEvent.PlanStepStarted -> EventSignificance.SIGNIFICANT
        is PlanEvent.PlanStepCompleted -> EventSignificance.SIGNIFICANT
        is PlanEvent.TaskAssigned -> EventSignificance.SIGNIFICANT
        is PlanEvent.MonitoringStarted -> EventSignificance.SIGNIFICANT
        is GitEvent.BranchCreated -> EventSignificance.SIGNIFICANT
        is GitEvent.Committed -> EventSignificance.SIGNIFICANT
        is GitEvent.Pushed -> EventSignificance.SIGNIFICANT
        is GitEvent.PullRequestCreated -> EventSignificance.SIGNIFICANT
        is GitEvent.FilesStaged -> EventSignificance.ROUTINE
        is GitEvent.OperationFailed -> EventSignificance.CRITICAL

        // Routine cognitive operations - maintenance work
        is MemoryEvent.KnowledgeRecalled -> EventSignificance.ROUTINE
        is MemoryEvent.KnowledgeStored -> EventSignificance.ROUTINE
        is NotificationEvent<*> -> EventSignificance.ROUTINE
        is ToolEvent.ToolRegistered -> EventSignificance.ROUTINE
        is ToolEvent.ToolUnregistered -> EventSignificance.ROUTINE
        is ToolEvent.ToolDiscoveryComplete -> EventSignificance.ROUTINE
        is ToolEvent.ToolExecutionStarted -> EventSignificance.ROUTINE
        is ToolEvent.ToolExecutionCompleted -> EventSignificance.ROUTINE
        is HumanInteractionEvent.InputRequested -> EventSignificance.CRITICAL
        is HumanInteractionEvent.InputProvided -> EventSignificance.SIGNIFICANT
        is HumanInteractionEvent.RequestTimedOut -> EventSignificance.SIGNIFICANT

        // Spark cognitive state events - routine cognitive operations
        is SparkEvent -> EventSignificance.ROUTINE
    }

    private fun formatUrgency(urgency: Urgency): String = when (urgency) {
        Urgency.LOW -> "[LOW]"
        Urgency.MEDIUM -> "[MED]"
        Urgency.HIGH -> "[‼️ HIGH]"
    }

    private fun formatSource(source: EventSource): String = when (source) {
        is EventSource.Agent -> extractAgentName(source.agentId)
        EventSource.Human -> "Human"
    }

    private fun extractAgentName(agentId: String): String {
        // Extract readable name from agent ID like "1b3d7f83-1453-407b-b088-74f4711a8b3fProductManagerAgent"
        val uuidPattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(.+)$")
        val match = uuidPattern.find(agentId)

        return match?.groupValues?.get(1) ?: run {
            val parts = agentId.split("-")
            if (parts.size > 1 && parts.last().contains("Agent", ignoreCase = true)) {
                parts.last()
            } else {
                agentId.takeLast(20)
            }
        }
    }

    /**
     * Event significance levels (duplicated from presentation layer to avoid circular dependency).
     */
    private enum class EventSignificance {
        CRITICAL,
        SIGNIFICANT,
        ROUTINE,
    }
}
