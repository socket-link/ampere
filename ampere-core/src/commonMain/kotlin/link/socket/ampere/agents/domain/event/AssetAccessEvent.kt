package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.link.LinkId

/**
 * An [link.socket.ampere.plug.spi.AssetResolver] resolved a reference.
 *
 * Out-of-band but not invisible: asset bytes never become an [Emission] or
 * enter a trace as payload, but resolution itself must be observable. Modelled
 * on [LinkEvent] — metadata only, no payload bytes — rather than a new
 * [EmissionKind], since it carries no affordances, reply semantics, or dedup
 * key that would justify going through the heavier Emission wire contract.
 *
 * Lives in `agents.domain.event` because [Event] is sealed: every subtype has
 * to share its module and package.
 *
 * @property linkId Null for a [link.socket.ampere.canon.CanonAssetRef.Url] —
 *   there is no Link, and therefore no consent check, for a plain URL.
 * @property byteCount Size of the resolved bytes. Never the bytes themselves.
 */
@Serializable
@SerialName("AssetAccessEvent")
data class AssetAccessEvent(
    override val eventId: EventId,
    override val timestamp: Instant,
    override val eventSource: EventSource,
    override val urgency: Urgency = Urgency.LOW,
    val linkId: LinkId?,
    val plugId: String,
    val byteCount: Long,
) : Event {

    override val eventType: EventType = EVENT_TYPE

    override fun getSummary(
        formatUrgency: (Urgency) -> String,
        formatSource: (EventSource) -> String,
    ): String = buildString {
        append("Asset resolved: plug=$plugId")
        linkId?.let { append(" link=${it.value}") }
        append(" bytes=$byteCount")
        append(" ${formatUrgency(urgency)}")
        append(" from ${formatSource(eventSource)}")
    }

    companion object {
        const val EVENT_TYPE: EventType = "AssetAccessEvent"
    }
}
