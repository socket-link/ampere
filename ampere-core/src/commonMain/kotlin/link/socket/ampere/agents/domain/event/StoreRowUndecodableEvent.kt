package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.Urgency

/**
 * A table whose rows carry serialized domain objects, and can therefore hold a row this build
 * cannot read (AMPR-364).
 *
 * Both members are reachable by version skew alone: a `Links` row whose `link_json` names a
 * canon member this build does not have, an `EventStore` row whose `payload` names an [Event]
 * subtype it does not have. Neither needs a third-party extension to happen — an older binary
 * reading a newer profile's database is enough.
 */
@Serializable
enum class PersistedStore(val tableName: String) {

    /** `Links.link_json` holds a `Link`, whose `scope` is a set of canon members. */
    LINK_STORE("Links"),

    /** `EventStore.payload` holds an [Event], named by its class discriminator. */
    EVENT_STORE("EventStore"),
}

/**
 * One stored row this build could not decode, skipped so the rest of the query survives
 * (AMPR-364).
 *
 * The store this reports on degrades rather than failing: before this existed, a single
 * undecodable row turned `LinkStore.list()` into a `Result.failure` that
 * `LinkResolutionService` read as "no Links", and turned every `EventRepository` list query
 * into a thrown `EventSerializationException`. One unreadable row is now one skipped row.
 *
 * A skip has to be *said*, which is why this is an [Event] and not a log line: a quietly
 * dropped row is precisely the opacity the trace exists to prevent. The row is named, so an
 * operator can go look at it, and the reason is the decoder's own message, so they can tell
 * version skew from corruption without decoding anything themselves.
 *
 * One event for both stores rather than one per store, on purpose (AMPR-364 task 1): the skip
 * is the same fact in both places, and the registry churn an `Event` subtype costs — the
 * registry, two exhaustive `when`s in `ampere-cli`, one in `ampere-core` — is worth paying once.
 *
 * Deduplicated by row id at each emitter: the row stays undecodable until something rewrites
 * it, and `list()` runs on every Plug resolution, so reporting per read would turn one bad row
 * into an unbounded stream of events about it. The first read of a process says it; later reads
 * of the same row stay quiet.
 *
 * Lives in `agents.domain.event` because [Event] is sealed: every subtype has to share its
 * module and package.
 *
 * @property store which table the row is in; see [PersistedStore].
 * @property rowId the row's primary key — `Links.link_id`, `EventStore.event_id`. Read from its
 * own column rather than from the payload, so it is available even when nothing in the payload
 * can be parsed.
 * @property reason the decoder's own message, verbatim. An unknown enum member and a truncated
 * payload read very differently here, and that difference is the whole diagnostic.
 */
@Serializable
@SerialName("StoreRowUndecodableEvent")
data class StoreRowUndecodableEvent(
    override val eventId: EventId,
    override val timestamp: Instant,
    override val eventSource: EventSource,
    val store: PersistedStore,
    val rowId: String,
    val reason: String,
    override val urgency: Urgency = Urgency.HIGH,
) : Event {

    override val eventType: EventType = EVENT_TYPE

    override fun getSummary(
        formatUrgency: (Urgency) -> String,
        formatSource: (EventSource) -> String,
    ): String = buildString {
        append("Skipped undecodable ${store.tableName} row $rowId")
        if (reason.isNotBlank()) append(" — $reason")
        append(" ${formatUrgency(urgency)}")
        append(" from ${formatSource(eventSource)}")
    }

    companion object {
        const val EVENT_TYPE: EventType = "StoreRowUndecodable"
    }
}
