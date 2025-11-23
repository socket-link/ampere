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
    ) : MessageEvent {

        override val eventClassType: EventClassType = EVENT_CLASS_TYPE
        override val urgency: Urgency = Urgency.MEDIUM
        override val eventSource: EventSource = thread.createdBy.toEventSource()
        override val threadId: MessageThreadId = thread.id
        override val timestamp: Instant = thread.createdAt

        companion object {
            private const val EVENT_TYPE = "ThreadCreated"
            val EVENT_CLASS_TYPE: EventClassType = ThreadCreated::class to EVENT_TYPE
        }
    }

    @Serializable
    data class MessagePosted(
        override val eventId: EventId,
        override val threadId: MessageThreadId,
        val channel: MessageChannel,
        val message: Message,
    ) : MessageEvent {

        override val eventClassType: EventClassType = EVENT_CLASS_TYPE
        override val urgency: Urgency = Urgency.LOW
        override val eventSource: EventSource = message.sender.toEventSource()
        override val timestamp: Instant = message.timestamp

        companion object {
            private const val EVENT_TYPE = "MessagePosted"
            val EVENT_CLASS_TYPE: EventClassType = MessagePosted::class to EVENT_TYPE
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
    ) : MessageEvent {

        override val eventClassType: EventClassType = EVENT_CLASS_TYPE
        override val urgency: Urgency = Urgency.MEDIUM

        companion object {
            private const val EVENT_TYPE = "ThreadStatusChanged"
            val EVENT_CLASS_TYPE: EventClassType = ThreadStatusChanged::class to EVENT_TYPE
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
    ) : MessageEvent {

        override val eventClassType: EventClassType = EVENT_CLASS_TYPE
        override val urgency: Urgency = Urgency.HIGH

        companion object {
            private const val EVENT_TYPE = "EscalationRequested"
            val EVENT_CLASS_TYPE: EventClassType = EscalationRequested::class to EVENT_TYPE
        }
    }
}
