package link.socket.ampere.lifecycle

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.Principal
import link.socket.ampere.canon.CanonId

/**
 * A stop on a unit of work that holds downstream work until a person acts.
 *
 * ## Not a permission gate
 *
 * `GateResult`, `LinkResolutionGate` and `ToolExecutionPermissionGate` all
 * answer *may this action proceed right now*, synchronously, from grants that
 * already exist. A lifecycle gate is a **wait** for something that has not
 * happened yet — a person locking a [DecisionRegister] — and the answer may be
 * days away. Nor is it a Probe: a Probe evaluates over present evidence and
 * carries no pending state, so it can *test* whether a gate has passed but
 * cannot *be* one.
 *
 * ## Plain data, no coroutine
 *
 * A gate outlives the process that raised it, so nothing here suspends, holds
 * a `Deferred`, or carries a timeout. Each state is a value that can be
 * written down, read back by a different process, and moved on by
 * [awaitPerson][Open.awaitPerson] and [close] — pure functions from one value
 * to the next. That is also the difference from `AgentPause`, which is a live
 * request with a timeout and a channel list; a pause may be how a person is
 * *told* about a gate, and the gate is what is still there after the pause
 * times out.
 *
 * ## "Open" is the ticket sense, not the farm-gate sense
 *
 * [Open] means *unresolved*, as an open issue is — **not** *passable*. An open
 * gate blocks. Read [blocksDownstream] rather than reasoning from the state's
 * name; it is the one question downstream work should ask.
 *
 * ## Relation to `CanonWorkStatus`
 *
 * `CanonWorkStatus.VERDICT_REQUESTED` is the coarse status of a work item
 * stopped at a gate; this is the gate. The status says *that* a person is
 * being waited on, the gate says what for, since when, and — once
 * [Closed] — how it came out. Neither is derived from the other here: keeping
 * a provider's status and labels in step with a gate is a binding's job.
 *
 * Not a canon entity, for the reason [ReconFinding] gives.
 *
 * @property subject The unit of work the gate stands on. A same-Link [CanonId]
 *   under the cross-reference contract in `docs/concepts/domain-canon.md`.
 * @property name Which gate this is, e.g. `stop-gate`. A unit of work can pass
 *   through more than one.
 * @property raisedAt When the gate was raised. Carried unchanged through every
 *   later state.
 */
@Serializable
sealed interface LifecycleGate {

    val subject: CanonId
    val name: String
    val raisedAt: Instant

    /**
     * Whether downstream work must hold. True for every state except
     * [Closed] with [GateOutcome.PASSED] — convict-but-not-acquit: a gate
     * nobody ruled on, or that was withdrawn rather than passed, releases
     * nothing.
     */
    val blocksDownstream: Boolean
        get() = when (this) {
            is Open, is AwaitingPerson -> true
            is Closed -> outcome != GateOutcome.PASSED
        }

    /**
     * Raised, and nobody has been asked yet — the machine side is still
     * assembling what the person will rule on.
     */
    @Serializable
    @SerialName("lifecycle.gate.open")
    data class Open(
        override val subject: CanonId,
        override val name: String,
        override val raisedAt: Instant,
    ) : LifecycleGate {

        /** Hand the gate to a person. [waitingFor] says what they are asked to do. */
        fun awaitPerson(since: Instant, waitingFor: String): AwaitingPerson = AwaitingPerson(
            subject = subject,
            name = name,
            raisedAt = raisedAt,
            waitingSince = since,
            waitingFor = waitingFor,
        )
    }

    /**
     * Everything the machine can do is done; the next move is a person's.
     *
     * @property waitingSince When the wait began — not [raisedAt], which may
     *   be much earlier.
     * @property waitingFor What the person is asked to do, in prose, e.g.
     *   "lock the decision register".
     */
    @Serializable
    @SerialName("lifecycle.gate.awaiting_person")
    data class AwaitingPerson(
        override val subject: CanonId,
        override val name: String,
        override val raisedAt: Instant,
        val waitingSince: Instant,
        val waitingFor: String,
    ) : LifecycleGate

    /**
     * Resolved. Terminal: a closed gate's outcome is never rewritten, and work
     * that must stop again raises a new gate.
     *
     * @property closedBy On whose authority. Today always [Principal.Ambient];
     *   see [DecisionLock.lockedBy].
     * @property note Why, in the closer's words — most useful on a
     *   [GateOutcome.REJECTED], where it is what gets reworked.
     */
    @Serializable
    @SerialName("lifecycle.gate.closed")
    data class Closed(
        override val subject: CanonId,
        override val name: String,
        override val raisedAt: Instant,
        val outcome: GateOutcome,
        val closedAt: Instant,
        val closedBy: Principal,
        val note: String? = null,
    ) : LifecycleGate

    /**
     * Close the gate with [outcome]. Legal from [Open] as well as
     * [AwaitingPerson] — a gate can be withdrawn before anyone was asked.
     * Fails when already [Closed].
     */
    fun close(
        outcome: GateOutcome,
        at: Instant,
        by: Principal,
        note: String? = null,
    ): Result<Closed> = when (this) {
        is Closed ->
            Result.failure(IllegalStateException("gate '$name' on ${subject.value} is already closed"))

        is Open, is AwaitingPerson -> Result.success(
            Closed(
                subject = subject,
                name = name,
                raisedAt = raisedAt,
                outcome = outcome,
                closedAt = at,
                closedBy = by,
                note = note,
            ),
        )
    }
}

/** How a [LifecycleGate] came out. Only [PASSED] releases downstream work. */
@Serializable
enum class GateOutcome {

    /** A person ruled, and the work may proceed. */
    @SerialName("passed")
    PASSED,

    /** A person ruled, and the work may not proceed as it stands. */
    @SerialName("rejected")
    REJECTED,

    /**
     * Closed without a ruling — the unit of work was cancelled or superseded.
     * Nobody said yes, so nothing downstream may proceed on the strength of it.
     */
    @SerialName("withdrawn")
    WITHDRAWN,
}
