package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.domain.arc.CompletionRecord

/**
 * Signals about an Arc run as a whole, from the runtime that ran it rather than the agents it
 * spawned (AMPR-359).
 *
 * Lives in `agents.domain.event` because [Event] is sealed: every subtype has to share its module
 * and package.
 */
@Serializable
sealed interface ArcRunEvent : Event {

    /** The Arc run this event is about. */
    val runId: RunId

    /**
     * An Arc run ended without closing its loop — it was cancelled, or a phase threw — and this is
     * the record it left in place of a `Knowledge` entry.
     *
     * Published once, as the run settles, and persisted under [runId], so `ArcTraceProjection`
     * folds it into `ArcRunTrace.completion` beside the rest of the run. [record] is the bounded
     * form of the run's `CompletionManifest`; see [CompletionRecord] for what it keeps.
     */
    @Serializable
    @SerialName("ArcRunEvent.CompletionManifestRecorded")
    data class CompletionManifestRecorded(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        val record: CompletionRecord,
        override val urgency: Urgency = Urgency.MEDIUM,
    ) : ArcRunEvent {

        override val runId: RunId
            get() = record.runId

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Arc run $runId ${record.summary()}")
            append(" ${formatUrgency(urgency)}")
            append(" from ${formatSource(eventSource)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "CompletionManifestRecorded"
        }
    }
}
