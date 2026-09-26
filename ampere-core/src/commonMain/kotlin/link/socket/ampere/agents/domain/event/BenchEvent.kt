package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.domain.arc.TerminationReason

/**
 * Eval `Bench` run lifecycle events flowing through the `EventSerialBus` (AMPR-186 task 4.5).
 *
 * Lives alongside [TaskEvent]/[RoutingEvent] rather than in `ampere-eval` because `Event` is a
 * sealed interface — Kotlin requires sealed subtypes to share both module and package with the
 * base type. Payloads carry primitives and `ampere-core` types only — never an `ampere-eval`
 * type — so `ampere-core` never acquires a reverse dependency on `ampere-eval`.
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

    /**
     * Grades an `EvalCase` (the eval type formerly named `Probe`, renamed in AMPR-318). This
     * event keeps its `ProbeGraded` name — it is serialized into recorded traces, and renaming
     * it would make existing traces undecodable.
     */
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

    /**
     * How one eval case's Arc run settled: its terminal shape, as the `Bench` that ran it observed
     * it (AMPR-187).
     *
     * ### Why the bench publishes this, and not the runtime
     *
     * `AmpereRuntime` publishes nothing for a run that closes its loop — an
     * [ArcRunEvent.CompletionManifestRecorded] is owed only by a run that *didn't* (AMPR-282/359).
     * A successful Arc run therefore left no event at all, so a golden trace of one was empty and
     * there was nothing for a `Meter` to grade. The bench wraps `AmpereRuntime.execute` and knows
     * every field below by construction, so it is the one place that can say how a run settled
     * without new instrumentation inside the phases. Per-phase and per-tick telemetry from the
     * runtime itself is AMPR-277's; when it lands, this event keeps its meaning as the run's
     * terminal summary and golden traces simply get richer around it.
     *
     * ### Determinism
     *
     * Every field is a count, an enum, or a clipped string — **no identifiers and no timing**, so
     * two runs of the same seed over the same code produce byte-identical payloads and a committed
     * golden trace diffs only when the Arc's behavior actually changed. [arcRunId] is the one
     * exception and is deliberately excluded from that comparison; it is here so a graded reading
     * can be correlated back to the run's own events in the store.
     *
     * @property probeId the `EvalCase` this run was the subject of.
     * @property arcId the Arc that ran, as registered in `ArcRegistry`.
     * @property arcRunId the Arc run's own id — *not* [runId], which is the bench run's.
     * @property terminal which [link.socket.ampere.domain.arc.ArcOutcome] the run ended in, as
     *   [ArcTerminal].
     * @property terminationReason how Flow's tick loop ended, or `null` if Flow never ran.
     * @property pulseSuccess whether Pulse judged the goal met; `null` unless the run completed.
     * @property agentCount agents Charge spawned; `0` if Charge never got that far.
     * @property goalNodeCount nodes in the goal tree Charge built, root included.
     * @property completedGoalCount goal completions Flow recorded. Not capped by [goalNodeCount] —
     *   Flow re-marks the goal it is on for every successful outcome in the tick, so this counts
     *   completions rather than distinct goals, and a golden trace pins that.
     * @property finalTick Flow ticks that finished, or `null` if Flow never ran.
     * @property outcomeTotal every outcome the run's agents recorded, blank ones included.
     * @property outcomeSucceeded outcomes that were an `Outcome.Success`.
     * @property outcomeFailed outcomes that were an `Outcome.Failure`.
     * @property failure the `Type: message` of what ended a failed run, clipped to
     *   [MAX_FAILURE_CHARS]; `null` for a run that was not [ArcTerminal.FAILED].
     */
    @Serializable
    @SerialName("BenchEvent.ArcSettled")
    data class ArcSettled(
        override val eventId: EventId,
        override val runId: String,
        override val eventSource: EventSource,
        override val timestamp: Instant,
        val probeId: String,
        val arcId: String,
        val arcRunId: String,
        val terminal: ArcTerminal,
        val terminationReason: TerminationReason? = null,
        val pulseSuccess: Boolean? = null,
        val agentCount: Int = 0,
        val goalNodeCount: Int = 0,
        val completedGoalCount: Int = 0,
        val finalTick: Int? = null,
        val outcomeTotal: Int = 0,
        val outcomeSucceeded: Int = 0,
        val outcomeFailed: Int = 0,
        val failure: String? = null,
        override val urgency: Urgency = Urgency.LOW,
    ) : BenchEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Probe $probeId ran $arcId: ${terminal.name.lowercase()}")
            terminationReason?.let { append(" ($it)") }
            append(", $completedGoalCount/$goalNodeCount goal(s) at tick ${finalTick ?: 0}")
            failure?.let { append(" — $it") }
            append(" ${formatUrgency(urgency)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "ArcSettled"

            /** Longest [failure] kept, in characters; a message can embed a whole payload. */
            const val MAX_FAILURE_CHARS: Int = 500
        }
    }

    /**
     * Which terminal `ArcOutcome` a bench case's run reached, named rather than stringified so a
     * probe's expectation is a compile-checked value.
     */
    @Serializable
    enum class ArcTerminal {
        /** All three phases ran to their end — `ArcOutcome.Completed`. */
        COMPLETED,

        /** A phase threw — `ArcOutcome.Failed`. */
        FAILED,

        /** The run was cancelled before it finished — `ArcOutcome.Cancelled`. */
        CANCELLED,
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
