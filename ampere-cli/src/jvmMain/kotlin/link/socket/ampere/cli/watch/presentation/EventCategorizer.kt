package link.socket.ampere.cli.watch.presentation

import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.FileSystemEvent
import link.socket.ampere.agents.domain.event.MeetingEvent
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.event.MessageEvent
import link.socket.ampere.agents.domain.event.NotificationEvent
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

        // Routine cognitive operations - maintenance work
        is MemoryEvent.KnowledgeRecalled -> EventSignificance.ROUTINE
        is MemoryEvent.KnowledgeStored -> EventSignificance.ROUTINE
        is NotificationEvent<*> -> EventSignificance.ROUTINE
        is ToolEvent.ToolRegistered -> EventSignificance.ROUTINE
        is ToolEvent.ToolUnregistered -> EventSignificance.ROUTINE
        is ToolEvent.ToolDiscoveryComplete -> EventSignificance.ROUTINE
    }
}
