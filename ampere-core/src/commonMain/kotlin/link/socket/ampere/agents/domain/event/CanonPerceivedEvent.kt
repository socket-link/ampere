package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.link.LinkId

/**
 * A [link.socket.ampere.plug.spi.PerceiveSource] enumerated canon entities over a Link (F7).
 *
 * The record an observed entity points back at: every entity on the page carries this event's
 * [eventId] as its `CanonProvenance.perceiveEventId`, and enters the Field as an Observation
 * whose `Provenance.createdBy` is the same id. Published by
 * [link.socket.ampere.plug.spi.recordedPerceive], through the one door, before the page reaches
 * its caller.
 *
 * Ids only, never entities. No [Event] carries a `CanonEntity` (AMPR-267) — the entity is the
 * Environment state the Field holds; this is the fact that it was observed. Keeping the payload
 * out also keeps a perceived person's fields out of a log that every replay and bake reads.
 *
 * Lives in `agents.domain.event` because [Event] is sealed: every subtype has to share its
 * module and package.
 *
 * @property linkId The Link the enumeration ran over. A `CanonId` is Link-scoped, so [canonIds]
 *   names entities only together with this.
 * @property canonIds Every entity on the page, in page order, as `CanonId` values. The page as
 *   the source returned it — before any residual filtering — because that is what was observed.
 */
@Serializable
@SerialName("CanonPerceivedEvent")
data class CanonPerceivedEvent(
    override val eventId: EventId,
    override val timestamp: Instant,
    override val eventSource: EventSource,
    val linkId: LinkId,
    val canonIds: List<String>,
    override val urgency: Urgency = Urgency.LOW,
) : Event {

    override val eventType: EventType = EVENT_TYPE

    override fun getSummary(
        formatUrgency: (Urgency) -> String,
        formatSource: (EventSource) -> String,
    ): String = buildString {
        append("Perceived ${canonIds.size} canon entit${if (canonIds.size == 1) "y" else "ies"}")
        append(" over Link ${linkId.value}")
        append(" ${formatUrgency(urgency)}")
        append(" from ${formatSource(eventSource)}")
    }

    companion object {
        const val EVENT_TYPE: EventType = "CanonPerceived"
    }
}
