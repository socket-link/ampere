package link.socket.ampere.agents.events

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
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
        override val urgency: Urgency = Urgency.MEDIUM
    ) : MessageEvent {

        override val eventType: EventType = EVENT_TYPE
        override val eventSource: EventSource = thread.createdBy.toEventSource()
        override val threadId: MessageThreadId = thread.id
        override val timestamp: Instant = thread.createdAt

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
        override val urgency: Urgency = Urgency.LOW
    ) : MessageEvent {

        override val eventType: EventType = EVENT_TYPE
        override val eventSource: EventSource = message.sender.toEventSource()
        override val timestamp: Instant = message.timestamp

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
        override val urgency: Urgency = Urgency.MEDIUM
    ) : MessageEvent {

        override val eventType: EventType = EVENT_TYPE

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
        override val urgency: Urgency = Urgency.HIGH
    ) : MessageEvent {

        override val eventType: EventType = EVENT_TYPE

        companion object {
            const val EVENT_TYPE: EventType = "EscalationRequested"
        }
    }
}
