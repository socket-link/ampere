package link.socket.ampere.work.linear

/**
 * The label conventions the supervisor reads and writes.
 *
 * Labels are load-bearing because of what the work source's query surface can
 * and cannot do. Server-side filtering covers project, state, and **one
 * positive label** — verified. So a positive label is the only supervisory fact
 * a query can push down, and [WAVE_PREFIX] uses it. Everything else a
 * ready-queue needs is a negative ("not gated") or a relation ("no open
 * blocker"), both inexpressible server-side, both filtered client-side.
 */
object WorkSourceLabels {

    /** `wave:w0` — wave membership. The one server-filterable supervisory fact. */
    const val WAVE_PREFIX: String = "wave:"

    /**
     * `gate:awaiting-verdict` — the ticket is stopped at a human-verdict gate.
     *
     * Ratified in the AMPR-289 verdict as the mechanism for gap M3, where the
     * probe's ready-queue returned a gated fixture that a trusting caller would
     * have dispatched.
     */
    const val GATE_AWAITING_VERDICT: String = "gate:awaiting-verdict"

    /**
     * `gate:escalated` — the ticket is stopped waiting on a human, with the
     * reason in an [SupervisoryComment.Escalation] comment.
     *
     * The AMPR-289 verdict ratified *that* escalation is marked by a label and
     * an `esc:` comment (gap M6) without naming the label. This adapter puts it
     * in the `gate:` namespace because the property that matters to the
     * ready-queue is the one it shares with [GATE_AWAITING_VERDICT]: a ticket
     * carrying either is never ready. The authoring-side convention is
     * AMPR-306's to document.
     */
    const val GATE_ESCALATED: String = "gate:escalated"

    /**
     * Every label that stops a ticket being ready.
     *
     * Deliberately a set of whole labels rather than a `gate:` prefix rule. The
     * predicate vocabulary is closed at
     * [link.socket.ampere.plug.spi.PerceivePredicate.Equals],
     * [link.socket.ampere.plug.spi.PerceivePredicate.Not] and
     * [link.socket.ampere.plug.spi.PerceivePredicate.HasNoRelation] — there is
     * no prefix form — so "not gated" has to be one `Not(Equals(label, …))` per
     * gate, and the set is what enumerates them. Adding a gate here adds a term
     * to [readyQueueRule].
     */
    val GATES: Set<String> = setOf(GATE_AWAITING_VERDICT, GATE_ESCALATED)

    /** The label marking membership of wave [id]. */
    fun wave(id: String): String = WAVE_PREFIX + id

    /** The wave id [label] marks, or null when it marks none. */
    fun waveId(label: String): String? =
        label.takeIf { it.startsWith(WAVE_PREFIX) }?.removePrefix(WAVE_PREFIX)?.takeIf { it.isNotBlank() }
}

/**
 * Where the supervisor's dispatch lifecycle lands in the work source.
 *
 * ## Four of these states are not canon
 *
 * [link.socket.ampere.canon.CanonWorkStatus] has five members —
 * `BACKLOG`, `TODO`, `IN_PROGRESS`, `DONE`, `CANCELLED` — and none of them can
 * say *claimed*, *verifying*, *verdict-requested* or *escalated*. That is the
 * AMPR-289 recon's gap G2, ticketed as AMPR-314. Until it lands, those four
 * states ride in the two places that can carry them losslessly:
 * [link.socket.ampere.canon.CanonWorkItem.providerStatus], which holds the
 * work source's own state name verbatim, and
 * [link.socket.ampere.canon.CanonWorkItem.labels]. Nothing in this adapter
 * invents a canon member, and nothing reads a supervisory state back out of
 * `status` — see [WorkItemCanonAdapter].
 *
 * ## The mapping
 *
 * | Supervisory state | Work-source state | Label | Comment |
 * | --- | --- | --- | --- |
 * | [QUEUED] | Todo | — | — |
 * | [CLAIMED] | In Progress | — | [SupervisoryComment.Claim] |
 * | [VERIFYING] | In Review | — | — |
 * | [VERDICT_REQUESTED] | In Review | [WorkSourceLabels.GATE_AWAITING_VERDICT] | — |
 * | [ESCALATED] | *unchanged* | [WorkSourceLabels.GATE_ESCALATED] | [SupervisoryComment.Escalation] |
 * | [DONE] | Done | — | — |
 *
 * @property workSourceState The state name a transition writes, or null when
 *   the state is marked without moving the ticket. [ESCALATED] is the null
 *   case: a ticket escalates *from wherever it is*, and overwriting its state
 *   would destroy the one record of where the work stopped.
 * @property label The label that must be present, or null when the state needs
 *   none.
 */
enum class SupervisoryState(
    val workSourceState: String?,
    val label: String? = null,
) {
    QUEUED("Todo"),
    CLAIMED("In Progress"),
    VERIFYING("In Review"),
    VERDICT_REQUESTED("In Review", WorkSourceLabels.GATE_AWAITING_VERDICT),
    ESCALATED(null, WorkSourceLabels.GATE_ESCALATED),
    DONE("Done"),
    ;

    companion object {
        /**
         * The state a ready ticket sits in, and so the state the ready-queue
         * query filters on.
         */
        val READY: SupervisoryState = QUEUED

        /**
         * The state a claimed ticket moves to, non-null.
         *
         * [workSourceState] is nullable because [ESCALATED] moves nothing, so
         * every claim call site would otherwise carry a `requireNotNull` for a
         * value that is a compile-time constant here.
         */
        val CLAIMED_STATE: String = requireNotNull(CLAIMED.workSourceState)
    }
}
