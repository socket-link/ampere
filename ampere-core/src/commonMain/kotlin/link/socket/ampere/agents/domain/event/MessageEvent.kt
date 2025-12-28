package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.concept.status.EventStatus
import link.socket.ampere.agents.events.messages.Message
import link.socket.ampere.agents.events.messages.MessageChannel
import link.socket.ampere.agents.events.messages.MessageThread
import link.socket.ampere.agents.events.messages.MessageThreadId
import link.socket.ampere.agents.events.messages.toEventSource

/** Base sealed interface for type-safe event handling. */
sealed interface MessageEvent : Event {

    val threadId: MessageThreadId

    @Serializable
    data class ThreadCreated(
        override val eventId: EventId,
        val thread: MessageThread,
        override val urgency: Urgency = Urgency.MEDIUM,
    ) : MessageEvent {

        override val eventType: EventType = EVENT_TYPE
        override val eventSource: EventSource = thread.createdBy.toEventSource()
        override val threadId: MessageThreadId = thread.id
        override val timestamp: Instant = thread.createdAt

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = "Thread created in ${thread.channel} ${formatUrgency(urgency)} from ${formatSource(eventSource)}"

        companion object {
            const val EVENT_TYPE: EventType = "ThreadCreated"
        }
    }

    @Serializable
    data class MessagePosted(
        override val eventId: EventId,
        override val threadId: MessageThreadId,
        val channel: MessageChannel,
        val message: Message,
        override val urgency: Urgency = Urgency.LOW,
    ) : MessageEvent {

        override val eventType: EventType = EVENT_TYPE
        override val eventSource: EventSource = message.sender.toEventSource()
        override val timestamp: Instant = message.timestamp

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = "Message posted in $channel ${formatUrgency(urgency)} from ${formatSource(eventSource)}"

        companion object {
            const val EVENT_TYPE: EventType = "MessagePosted"
        }
    }

    @Serializable
    data class ThreadStatusChanged(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val threadId: MessageThreadId,
        val oldStatus: EventStatus,
        val newStatus: EventStatus,
        override val urgency: Urgency = Urgency.MEDIUM,
    ) : MessageEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = "Thread $threadId status: $oldStatus → $newStatus ${formatUrgency(urgency)}"

        companion object {
            const val EVENT_TYPE: EventType = "ThreadStatusChanged"
        }
    }

    @Serializable
    data class EscalationRequested(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val threadId: MessageThreadId,
        val reason: String,
        val context: Map<String, String> = emptyMap(),
        override val urgency: Urgency = Urgency.HIGH,
    ) : MessageEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = "Escalation requested in thread $threadId: $reason ${formatUrgency(urgency)}"

        companion object {
            const val EVENT_TYPE: EventType = "EscalationRequested"
        }
    }
}
