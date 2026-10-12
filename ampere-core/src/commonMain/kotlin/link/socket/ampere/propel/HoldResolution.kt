package link.socket.ampere.propel

import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.api.AmpereStableApi

/**
 * How a person settled the action a held run is waiting on (AMPR-431, AMPR-385 rows H31, H32).
 *
 * Handed to [RunHost.resume]. Ampere never performs the held action itself: the consumer
 * asks the person, performs the action through its own gate if they approve, and reports
 * what happened here. When the hold expires is the consumer's to decide as well — an
 * expired hold is reported as [Declined].
 */
@AmpereStableApi
sealed interface HoldResolution {

    /**
     * The person approved and the consumer performed the action.
     *
     * The run carries on with the plan that was approved: the held step becomes a success
     * whose details are [details], and the steps after it run in order, the first of them
     * handed [details] as the held step's result.
     *
     * @property details The result of the held action, as the consumer reports it. It is
     *   content from outside the run, and is labelled that way on every prompt it reaches.
     */
    data class Completed(val details: String) : HoldResolution

    /**
     * The person declined, or the hold expired.
     *
     * The run ends: the held step is recorded as a failure whose error begins `Declined:`,
     * nothing after it runs, no further cycle is planned, and the run ends
     * [RunStatus.Failed].
     *
     * @property reason Why, for the record. It becomes the held step's error.
     */
    data class Declined(val reason: String) : HoldResolution
}

/**
 * [RunHost.resume] could not take the run up (AMPR-431).
 *
 * Thrown before any seat is built, any event is published or any model is called, so a
 * resume refused this way leaves no trace in the run's record.
 *
 * @property runId The run that was asked for.
 * @property why Which of the four reasons it was.
 */
@AmpereStableApi
class HeldRunUnavailableException(
    val runId: RunId,
    val why: Why,
) : IllegalStateException("Run '$runId' cannot be resumed: ${why.explanation}") {

    /** Why a held run could not be resumed. */
    @AmpereStableApi
    enum class Why(internal val explanation: String) {

        /** The host was built with no [HeldRunStore], so nothing a run held was recorded. */
        NoStore("this host keeps no held-run store"),

        /** No held record exists under the run id — it never held, or it has finished. */
        NotHeld("no held run is recorded under this id"),

        /**
         * A run under this id is still open on this host. Close the held run first; its
         * record is kept, and is resumable once it is closed.
         */
        StillOpen("a run with this id is still open on this host"),

        /**
         * Someone has already taken this hold up. A hold is resumed at most once, and a
         * claimed record whose resume was interrupted stays claimed: some of its remaining
         * steps may have run.
         */
        AlreadyClaimed("this hold has already been taken up"),
    }
}
