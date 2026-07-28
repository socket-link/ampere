package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.link.LinkId
import link.socket.ampere.link.LinkResolutionFailure
import link.socket.ampere.link.RevocationScope
import link.socket.ampere.link.Transport

/**
 * Lifecycle of a Link: granted, resolved for a Plug, resolution refused,
 * revoked.
 *
 * These exist so the trace can answer "which wire carried this, and who
 * authorized it" without asking the Plug. Resolution failures in particular
 * must reach the bus — a Plug that quietly does nothing because its Link was
 * revoked is the exact opacity the glass brain exists to prevent.
 *
 * Lives in `agents.domain.event` because [Event] is sealed: every subtype has
 * to share its module and package.
 */
@Serializable
sealed interface LinkEvent : Event {

    val linkId: LinkId

    /** A Plug was granted use of a Link. */
    @Serializable
    @SerialName("LinkEvent.LinkGranted")
    data class LinkGranted(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val urgency: Urgency = Urgency.MEDIUM,
        override val linkId: LinkId,
        val plugId: String,
        val transport: Transport,
    ) : LinkEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Link granted: ${linkId.value} to plug=$plugId")
            append(" transport=$transport")
            append(" ${formatUrgency(urgency)}")
            append(" from ${formatSource(eventSource)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "LinkGranted"
        }
    }

    /**
     * A Link, its credential, or one Plug's grant on it was revoked.
     *
     * [affectedPlugIds] is the cascade: a Link-level revocation names every
     * Plug that loses access, so the consent surface can show the true blast
     * radius rather than one row.
     */
    @Serializable
    @SerialName("LinkEvent.LinkRevoked")
    data class LinkRevoked(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val urgency: Urgency = Urgency.HIGH,
        override val linkId: LinkId,
        val scope: RevocationScope,
        val affectedPlugIds: List<String> = emptyList(),
    ) : LinkEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Link revoked: ${linkId.value} scope=$scope")
            append(" affects=${affectedPlugIds.size} plug(s)")
            append(" ${formatUrgency(urgency)}")
            append(" from ${formatSource(eventSource)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "LinkRevoked"
        }
    }

    /** A Plug's Link requirement was satisfied at Arc execution time. */
    @Serializable
    @SerialName("LinkEvent.LinkResolved")
    data class LinkResolved(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val urgency: Urgency = Urgency.LOW,
        override val linkId: LinkId,
        val plugId: String,
        val requirementName: String,
        val transport: Transport,
    ) : LinkEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Link resolved: $requirementName -> ${linkId.value}")
            append(" plug=$plugId transport=$transport")
            append(" ${formatUrgency(urgency)}")
            append(" from ${formatSource(eventSource)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "LinkResolved"
        }
    }

    /**
     * A Plug's Link requirement could not be satisfied.
     *
     * [linkId] is the Link that came closest — the empty [LinkId] when nothing
     * of the required transport was available at all.
     */
    @Serializable
    @SerialName("LinkEvent.LinkResolutionFailed")
    data class LinkResolutionFailed(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val urgency: Urgency = Urgency.HIGH,
        override val linkId: LinkId,
        val plugId: String,
        val failure: LinkResolutionFailure,
    ) : LinkEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Link resolution failed: ${failure.requirementName}")
            append(" plug=$plugId reason=${failure::class.simpleName}")
            append(" ${formatUrgency(urgency)}")
            append(" from ${formatSource(eventSource)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "LinkResolutionFailed"

            /** Stands in for "no candidate Link existed". */
            val NO_LINK: LinkId = LinkId("")
        }
    }
}
