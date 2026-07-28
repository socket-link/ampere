package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.Urgency

/**
 * Eval `Bench` run lifecycle events flowing through the `EventSerialBus` (AMPR-186 task 4.5).
 *
 * Lives alongside [TaskEvent]/[RoutingEvent] rather than in `ampere-eval` because `Event` is a
 * sealed interface — Kotlin requires sealed subtypes to share both module and package with the
 * base type. Payloads are kept to primitives (no `ampere-eval` types) to avoid a reverse
 * dependency from `ampere-core` onto `ampere-eval`.
 */
@Serializable
sealed interface BenchEvent : Event {

    /** The bench run this event pertains to. */
    val runId: String

    @Serializable
    data class BenchRunStarted(
        override val eventId: EventId,
        override val runId: String,
        override val eventSource: EventSource,
        override val timestamp: Instant,
        /** `RunMode.Replay`/`RunMode.Live`, stringified — `ampere-eval` owns the `RunMode` type. */
        val mode: String,
        val probeCount: Int,
        override val urgency: Urgency = Urgency.LOW,
    ) : BenchEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = "Bench run $runId started ($mode, $probeCount probe(s)) ${formatUrgency(urgency)}"

        companion object {
            const val EVENT_TYPE: EventType = "BenchRunStarted"
        }
    }

    @Serializable
    data class ProbeGraded(
        override val eventId: EventId,
        override val runId: String,
        override val eventSource: EventSource,
        override val timestamp: Instant,
        val probeId: String,
        val passed: Boolean,
        val meanScore: Double,
        override val urgency: Urgency = Urgency.LOW,
    ) : BenchEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = "Probe $probeId graded: passed=$passed meanScore=$meanScore ${formatUrgency(urgency)}"

        companion object {
            const val EVENT_TYPE: EventType = "ProbeGraded"
        }
    }

    @Serializable
    data class BenchRunCompleted(
        override val eventId: EventId,
        override val runId: String,
        override val eventSource: EventSource,
        override val timestamp: Instant,
        val passRate: Double,
        val probeCount: Int,
        override val urgency: Urgency = Urgency.MEDIUM,
    ) : BenchEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = "Bench run $runId completed: passRate=$passRate ${formatUrgency(urgency)}"

        companion object {
            const val EVENT_TYPE: EventType = "BenchRunCompleted"
        }
    }
}
