package link.socket.ampere.pause

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * One stored [AgentPause] and everything the store knows about its fate.
 *
 * This is the durable form of a pending human decision (AMPR-370). [AgentPause] describes what is
 * being asked and how to reach a person; `PauseRecord` adds when it was asked, when it stops being
 * answerable, and — once something answers it or time does — the [AgentPauseResponse] it settled
 * to. The pause contract itself is unchanged: the record wraps it rather than growing it, so a
 * renderer built against [AgentPause] keeps compiling.
 *
 * A record is in exactly one of two states, and the difference is always answerable from stored
 * data:
 *
 * - **open** — [response] and [settledAt] are both null. Nobody has answered, and [expiresAt] has
 *   not been reached as of the read that produced this record. See [PauseStore] for why an open
 *   record can never be a stale one.
 * - **settled** — [response] and [settledAt] are both non-null. The decision is final;
 *   `response` is what it settled to, including [AgentPauseResponse.TimedOut] when it settled by
 *   expiry. Settling is once and for all: a settled record never reopens.
 *
 * @property pause the pause as raised, byte-for-byte. [correlationId] reads through to it.
 * @property raisedAt when the pause was persisted, which is also when its clock started.
 * @property expiresAt `raisedAt + pause.timeoutMillis`, stored rather than recomputed on read.
 *   Two reasons: the expiry sweep needs the deadline without decoding [pause] (see
 *   `PauseStore.sq`), and a raised pause's deadline must not move if a later build changes how a
 *   timeout is chosen. Treat the stored value as authoritative over the arithmetic.
 * @property response what the decision settled to, or null while it is open.
 * @property settledAt when it settled, or null while it is open. Always non-null exactly when
 *   [response] is.
 */
@Serializable
data class PauseRecord(
    val pause: AgentPause,
    val raisedAt: Instant,
    val expiresAt: Instant,
    val response: AgentPauseResponse? = null,
    val settledAt: Instant? = null,
) {

    /** Echo of [AgentPause.correlationId] — the key this record is addressed by. */
    val correlationId: PauseCorrelationId
        get() = pause.correlationId

    /** True while nothing has answered this pause and nothing has expired it. */
    val isOpen: Boolean
        get() = response == null

    /**
     * True when this decision settled because its deadline passed rather than because a person
     * answered. Distinguishing this from an open record is the whole point of settling expiry
     * durably; distinguishing it from a [AgentPauseResponse.Rejected] matters because a timeout
     * is an absence of a decision, not a negative one.
     */
    val isTimedOut: Boolean
        get() = response is AgentPauseResponse.TimedOut
}

/**
 * What a pause settled to, as [PauseStore.resolve] found it after writing.
 *
 * Deliberately smaller than [PauseRecord]: it carries no [AgentPause], so reporting a resolution
 * never requires decoding the stored pause. That is what lets `resolve` answer a pause this build
 * is too old to read.
 *
 * @property settledByThisCall true when this call's response is the one that landed. False means
 * something settled the decision first — most often the expiry sweep — and [response] is that
 * earlier answer, not the caller's. A caller that must know its `Approved` took effect checks this
 * rather than assuming.
 */
@Serializable
data class PauseResolution(
    val correlationId: PauseCorrelationId,
    val response: AgentPauseResponse,
    val settledAt: Instant,
    val settledByThisCall: Boolean,
)
