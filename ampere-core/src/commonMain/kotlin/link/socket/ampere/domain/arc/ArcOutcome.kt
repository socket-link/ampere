package link.socket.ampere.domain.arc

import link.socket.ampere.trace.ArcRunId

/**
 * Terminal state of a single Arc lifecycle run.
 *
 * An Arc ends in exactly one of three ways, and every one of them is a value rather than a
 * thrown exception:
 * - [Completed] — all three phases ran to the end. Inspect [Completed.success] for whether the
 *   Pulse evaluation considered the goal met; a `Completed` Arc can still be an unsuccessful one.
 * - [Failed] — a phase threw. The failure is carried in [Failed.cause] alongside whatever
 *   partial phase results were produced before it.
 * - [Cancelled] — the run was cancelled cooperatively (via `AmpereRuntime.cancel()` or by
 *   cancelling the caller-owned scope). Partial phase results are carried the same way.
 *
 * [chargeResult] and [flowResult] are non-null on [Completed] and best-effort on the other two,
 * so a caller can always report how far the Arc got.
 */
sealed interface ArcOutcome {

    /**
     * Identity of the run this outcome terminates (AMPR-240).
     *
     * Present on every variant, not just the successful one: a run that failed or was cancelled
     * still emitted telemetry under this id, and correlating that partial trace is exactly when
     * the id is most useful.
     */
    val runId: ArcRunId

    /** The Charge phase result, or `null` if the Arc did not get that far. */
    val chargeResult: ChargeResult?

    /** The Flow phase result, or `null` if the Arc did not get that far. */
    val flowResult: FlowResult?

    /** All three phases ran to completion. */
    data class Completed(
        override val runId: ArcRunId,
        override val chargeResult: ChargeResult,
        override val flowResult: FlowResult,
        val pulseResult: PulseResult,
    ) : ArcOutcome {
        /** Whether the Pulse evaluation judged the goal met. */
        val success: Boolean get() = pulseResult.success
    }

    /** A phase threw a non-cancellation [Throwable]. */
    data class Failed(
        override val runId: ArcRunId,
        val cause: Throwable,
        override val chargeResult: ChargeResult? = null,
        override val flowResult: FlowResult? = null,
    ) : ArcOutcome

    /** The run was cancelled before it could finish. */
    data class Cancelled(
        override val runId: ArcRunId,
        override val chargeResult: ChargeResult? = null,
        override val flowResult: FlowResult? = null,
    ) : ArcOutcome
}
