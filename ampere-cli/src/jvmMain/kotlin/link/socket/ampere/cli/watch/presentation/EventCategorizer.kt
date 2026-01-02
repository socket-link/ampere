package link.socket.ampere.cli.watch.presentation

import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.FileSystemEvent
import link.socket.ampere.agents.domain.event.GitEvent
import link.socket.ampere.agents.domain.event.HumanInteractionEvent
import link.socket.ampere.agents.domain.event.MeetingEvent
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.event.MessageEvent
import link.socket.ampere.agents.domain.event.NotificationEvent
import link.socket.ampere.agents.domain.event.PlanEvent
import link.socket.ampere.agents.domain.event.ProductEvent
import link.socket.ampere.agents.domain.event.TicketEvent
import link.socket.ampere.agents.domain.event.ToolEvent

/**
 * Determines the significance of events for observation purposes.
 *
 * Think of this as the thalamus - it filters sensory input and decides
 * what deserves conscious attention versus what can be processed automatically.
 */
object EventCategorizer {
    // Bounded cache to avoid repeated categorization of the same events
    // Using LinkedHashMap for LRU eviction
    private val categorizationCache = object : LinkedHashMap<String, EventSignificance>(
        100, // Initial capacity
        0.75f, // Load factor
        true // Access order (LRU)
    ) {
        override fun removeEldestEntry(eldest: Map.Entry<String, EventSignificance>?): Boolean {
            return size > 200 // Keep max 200 entries
        }
    }

    fun categorize(event: Event): EventSignificance {
        // Check cache first
        categorizationCache[event.eventId]?.let { return it }

        // Compute and cache
        val significance = categorizeInternal(event)
        categorizationCache[event.eventId] = significance
        return significance
    }

    private fun categorizeInternal(event: Event): EventSignificance = when (event) {
        // Critical events require immediate human awareness
        is Event.QuestionRaised,
        is TicketEvent.TicketBlocked,
        is MessageEvent.EscalationRequested,
        is HumanInteractionEvent.InputRequested -> EventSignificance.CRITICAL

        // Significant events represent state changes worth noting
        is Event.TaskCreated,
        is Event.CodeSubmitted,
        is FileSystemEvent,
        is GitEvent.PullRequestCreated,
        is GitEvent.OperationFailed,
        is HumanInteractionEvent.InputProvided,
        is HumanInteractionEvent.RequestTimedOut,
        is MeetingEvent.MeetingScheduled,
        is MeetingEvent.MeetingStarted,
        is MeetingEvent.MeetingCompleted,
        is MeetingEvent.MeetingCanceled,
        is MeetingEvent.AgendaItemStarted,
        is MeetingEvent.AgendaItemCompleted,
        is MessageEvent.ThreadCreated,
        is MessageEvent.ThreadStatusChanged,
        is MessageEvent.MessagePosted,
        is PlanEvent.PlanStepStarted,
        is PlanEvent.PlanStepCompleted,
        is PlanEvent.TaskAssigned,
        is PlanEvent.MonitoringStarted,
        is ProductEvent.FeatureRequested,
        is ProductEvent.EpicDefined,
        is ProductEvent.PhaseDefined,
        is TicketEvent.TicketCreated,
        is TicketEvent.TicketStatusChanged,
        is TicketEvent.TicketAssigned,
        is TicketEvent.TicketCompleted,
        is TicketEvent.TicketMeetingScheduled -> EventSignificance.SIGNIFICANT

        // Routine cognitive operations - maintenance work
        is GitEvent.BranchCreated,
        is GitEvent.Committed,
        is GitEvent.Pushed,
        is GitEvent.FilesStaged,
        is MemoryEvent.KnowledgeRecalled,
        is MemoryEvent.KnowledgeStored,
        is NotificationEvent<*>,
        is ToolEvent.ToolRegistered,
        is ToolEvent.ToolUnregistered,
        is ToolEvent.ToolDiscoveryComplete,
        is ToolEvent.ToolExecutionStarted,
        is ToolEvent.ToolExecutionCompleted -> EventSignificance.ROUTINE
    }
}
