package link.socket.ampere.work.linear

import link.socket.ampere.canon.CanonWorkStatus

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
     * have dispatched. Since AMPR-314 it is also the read-side evidence for
     * [CanonWorkStatus.VERDICT_REQUESTED] — see [SupervisoryStatusMapping].
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
 * The work-source state *names* this adapter reads and writes, and the
 * [WorkItemStatusType] each one carries.
 *
 * A state name is workspace configuration, not vendor API — this workspace
 * happens to call its terminal state "Done" — so naming them once here is what
 * stops six string literals spreading through the adapter. The values were read
 * live in the AMPR-289 recon and are re-verified by the recorded responses in
 * this module's tests.
 *
 * ## Why the read path matches on the name at all
 *
 * Because it has to: [WorkItemStatusType.STARTED] covers "In Progress" **and**
 * "In Review", which is exactly the distinction [CanonWorkStatus.VERIFYING]
 * exists to carry. Matching name-first and falling back to the category is the
 * rule [CanonWorkStatus] already states for [CanonWorkStatus.BACKLOG]; AMPR-314
 * is the second place it bites.
 *
 * The fallback is what keeps a renamed state honest. A workspace that renames
 * "In Review" loses [CanonWorkStatus.VERIFYING] and reports
 * [CanonWorkStatus.IN_PROGRESS] — coarse but true — rather than failing, which
 * is the same asymmetry [WorkItemStatusType.fromWire] refuses for an unknown
 * *category*: a category this build does not know is vendor drift, while a state
 * name it does not know is a workspace someone configured differently.
 */
object WorkSourceStates {

    const val BACKLOG: String = "Backlog"
    const val TODO: String = "Todo"
    const val IN_PROGRESS: String = "In Progress"
    const val IN_REVIEW: String = "In Review"
    const val DONE: String = "Done"
    const val CANCELED: String = "Canceled"

    /**
     * The category each state name carries, as the server reports it.
     *
     * The write direction needs this: a transition writes a *name*, and the
     * category the server derives from it is what a subsequent read sees, so
     * round-tripping a canonical status through its provider expression has to
     * know both halves.
     */
    val TYPES: Map<String, WorkItemStatusType> = mapOf(
        BACKLOG to WorkItemStatusType.BACKLOG,
        TODO to WorkItemStatusType.UNSTARTED,
        IN_PROGRESS to WorkItemStatusType.STARTED,
        IN_REVIEW to WorkItemStatusType.STARTED,
        DONE to WorkItemStatusType.COMPLETED,
        CANCELED to WorkItemStatusType.CANCELED,
    )
}

/**
 * One [CanonWorkStatus] as this work source expresses it — the whole of the
 * provider side of the AMPR-314 mapping contract, as a value.
 *
 * A canonical status is *not* a provider status here. Four of the nine members
 * are composed out of a status plus a label or a comment, so the provider side
 * of the mapping needs a shape with room for all three, and this is it. That it
 * is a value rather than a function of an issue is what makes the contract
 * testable in both directions: [SupervisoryStatusMapping.expressionFor] produces
 * one from a canonical status, [SupervisoryStatusMapping.canonStatusFor] reads
 * one back, and the two compose to the identity.
 *
 * @property stateName The state a transition writes, or null when the status is
 *   marked without moving the ticket. [CanonWorkStatus.ESCALATED] is the null
 *   case: a ticket escalates *from wherever it is*, and overwriting its state
 *   would destroy the one record of where the work stopped.
 * @property statusType The category [stateName] carries, per
 *   [WorkSourceStates.TYPES]. Null when the state is unknown to this build or
 *   unmoved; never defaulted, because "we did not look" and "it is open" must
 *   not be the same fact — the same rule [WorkSourceIssueRef.statusType] follows.
 * @property labels The labels present. On the write side this is what the status
 *   *requires*; on the read side it is what the issue *carries*, which is why
 *   the mapping reads it with `in` rather than by equality.
 * @property claimed Whether a claim comment for this issue was found. **Read
 *   evidence, and it costs a second read**: comments are a different object from
 *   the issue, so a projection that only saw the issue passes false and gets
 *   [CanonWorkStatus.IN_PROGRESS] — true, just coarse. See
 *   [SupervisoryStatusMapping.claimEvidenceMatters], which is how a caller
 *   decides whether that read is worth paying for.
 */
data class SupervisoryExpression(
    val stateName: String?,
    val statusType: WorkItemStatusType? = stateName?.let { WorkSourceStates.TYPES[it] },
    val labels: Set<String> = emptySet(),
    val claimed: Boolean = false,
)

/**
 * The supervisory expression this issue carries, as read.
 *
 * [claimed] is not on the issue because it is not *in* the issue — a claim is a
 * comment. A caller that has read the comments passes what it found; one that
 * has not passes nothing and accepts the coarser answer.
 */
fun WorkSourceIssue.supervisoryExpression(claimed: Boolean = false): SupervisoryExpression =
    SupervisoryExpression(
        stateName = statusName,
        statusType = statusType,
        labels = labels.toSet(),
        claimed = claimed,
    )

/**
 * The bidirectional [CanonWorkStatus] ↔ provider-expression mapping, and the one
 * place this adapter decides what a ticket's canonical status is.
 *
 * ## Both directions, and why they are not inverses
 *
 * [expressionFor] is one-to-one: each canonical status has exactly one
 * *canonical* provider expression, the one a write produces.
 * [canonStatusFor] is many-to-one: it also answers for expressions no write of
 * this adapter's would produce — a `triage` category, a state name this
 * workspace renamed, a stale gate label on a closed ticket. So
 * `canonStatusFor(expressionFor(s)) == s` for every member (pinned in
 * `SupervisoryStatusMappingTest`) while the other composition is deliberately
 * lossy, which is the whole point of a coarse lifecycle.
 *
 * ## The read-side precedence, and why it is in this order
 *
 * 1. **A terminal category wins outright.** A closed ticket still carrying a
 *    gate label is [CanonWorkStatus.DONE] — the label is an untidied record, the
 *    closure is the provider's own statement of finality. Reading it the other
 *    way round strands finished work in a queue waiting on a human with nothing
 *    left to do.
 * 2. **Then the gate labels, escalation first.** An unplanned stop never tidies
 *    away the record of a planned one ([gateEdit]), so a ticket can legitimately
 *    carry both, and the unplanned one is the one a human has to act on.
 * 3. **Then the state name**, which is where the rest of the supervisory
 *    lifecycle rides and the only signal that separates *verifying* from
 *    *in progress* — see [WorkSourceStates].
 * 4. **Then the category**, for a state name this build does not know. Where
 *    there is no signal at all the answer is [CanonWorkStatus.TODO], never
 *    [CanonWorkStatus.BACKLOG]: the rule [CanonWorkStatus] states, because
 *    `TODO` keeps work visible and a guessed `BACKLOG` hides it.
 */
object SupervisoryStatusMapping {

    /**
     * Canonical statuses whose provider expression is a **protocol, not a
     * write**, mapped to the entry point that performs it.
     *
     * Both are composed with a comment, and in neither case is the comment
     * decoration. A claim comment is what *arbitrates* the claim — posted before
     * the transition so the server timestamps it first — so writing the status
     * without it produces a ticket that reads back as
     * [CanonWorkStatus.IN_PROGRESS] and a race nobody won. An escalation comment
     * carries the context that is the whole reason a human is being called, so
     * writing the label alone hands someone a stopped ticket and no reason.
     *
     * [LinearWorkSource.markStatus] refuses these rather than writing half an
     * expression.
     */
    val PROTOCOL_STATUSES: Map<CanonWorkStatus, String> = mapOf(
        CanonWorkStatus.CLAIMED to "LinearWorkSource.claim",
        CanonWorkStatus.ESCALATED to "LinearWorkSource.escalate",
    )

    /**
     * The canonical provider expression of [status] — the write direction.
     *
     * Exhaustive over [CanonWorkStatus] on purpose: a member admitted to the
     * canon and not translated here is a compile error, which is the tripwire
     * AMPR-314 wanted in place of an `else` branch that would silently write the
     * wrong state.
     */
    fun expressionFor(status: CanonWorkStatus): SupervisoryExpression = when (status) {
        CanonWorkStatus.BACKLOG -> SupervisoryExpression(WorkSourceStates.BACKLOG)
        CanonWorkStatus.TODO -> SupervisoryExpression(WorkSourceStates.TODO)
        CanonWorkStatus.IN_PROGRESS -> SupervisoryExpression(WorkSourceStates.IN_PROGRESS)
        CanonWorkStatus.CLAIMED -> SupervisoryExpression(WorkSourceStates.IN_PROGRESS, claimed = true)
        CanonWorkStatus.VERIFYING -> SupervisoryExpression(WorkSourceStates.IN_REVIEW)
        CanonWorkStatus.VERDICT_REQUESTED -> SupervisoryExpression(
            stateName = WorkSourceStates.IN_REVIEW,
            labels = setOf(WorkSourceLabels.GATE_AWAITING_VERDICT),
        )

        // No state, by ratified design: a ticket escalates from wherever it is.
        CanonWorkStatus.ESCALATED -> SupervisoryExpression(
            stateName = null,
            labels = setOf(WorkSourceLabels.GATE_ESCALATED),
        )

        CanonWorkStatus.DONE -> SupervisoryExpression(WorkSourceStates.DONE)
        CanonWorkStatus.CANCELLED -> SupervisoryExpression(WorkSourceStates.CANCELED)
    }

    /** The canonical status [expression] expresses — the read direction. */
    fun canonStatusFor(expression: SupervisoryExpression): CanonWorkStatus {
        // 1. Finality outranks every supervisory marker.
        when (expression.statusType) {
            WorkItemStatusType.COMPLETED -> return CanonWorkStatus.DONE
            WorkItemStatusType.CANCELED -> return CanonWorkStatus.CANCELLED
            else -> Unit
        }

        // 2. The gates, unplanned stop first.
        if (WorkSourceLabels.GATE_ESCALATED in expression.labels) return CanonWorkStatus.ESCALATED
        if (WorkSourceLabels.GATE_AWAITING_VERDICT in expression.labels) {
            return CanonWorkStatus.VERDICT_REQUESTED
        }

        // 3. The state name. Done and Canceled are deliberately absent — step 1
        //    already answered them off the category, which survives a rename.
        when (expression.stateName) {
            WorkSourceStates.IN_REVIEW -> return CanonWorkStatus.VERIFYING
            WorkSourceStates.IN_PROGRESS -> return if (expression.claimed) {
                CanonWorkStatus.CLAIMED
            } else {
                CanonWorkStatus.IN_PROGRESS
            }

            WorkSourceStates.BACKLOG -> return CanonWorkStatus.BACKLOG
            WorkSourceStates.TODO -> return CanonWorkStatus.TODO
        }

        // 4. The category, for a state name this build does not know.
        return when (expression.statusType) {
            // An untriaged issue has not been committed to, and TODO is canon's
            // "committed, not started". The distinction survives verbatim in
            // `providerStatus` either way.
            WorkItemStatusType.TRIAGE,
            WorkItemStatusType.BACKLOG,
            -> CanonWorkStatus.BACKLOG

            WorkItemStatusType.UNSTARTED -> CanonWorkStatus.TODO
            WorkItemStatusType.STARTED -> CanonWorkStatus.IN_PROGRESS

            // Answered by step 1; repeated rather than `else`-d so a new
            // category cannot slip through as TODO.
            WorkItemStatusType.COMPLETED -> CanonWorkStatus.DONE
            WorkItemStatusType.CANCELED -> CanonWorkStatus.CANCELLED

            null -> CanonWorkStatus.TODO
        }
    }

    /**
     * Whether reading the comments could change the answer for [expression] —
     * the one question a caller has to ask before paying for that read.
     *
     * Derived from [canonStatusFor] rather than stated, so it cannot drift away
     * from the precedence above: the claim evidence matters exactly where the
     * claimless answer is [CanonWorkStatus.IN_PROGRESS], because that is the
     * only status [CanonWorkStatus.CLAIMED] refines. A gated, closed or queued
     * ticket needs no comment scan.
     */
    fun claimEvidenceMatters(expression: SupervisoryExpression): Boolean =
        canonStatusFor(expression.copy(claimed = false)) == CanonWorkStatus.IN_PROGRESS

    /**
     * The label edit that puts a ticket carrying [present] into [target]'s gate
     * expression — preserve-and-merge, applied to labels.
     *
     * Two rules, both of them about not destroying a record:
     *
     * - **Only [WorkSourceLabels.GATES] are ever removed.** A ticket's `wave:`
     *   tag and its topic labels are not this mapping's vocabulary, and a write
     *   that reset the label set would drop them — which is why the sink offers
     *   [WorkSourceCommand.AddLabels]/[WorkSourceCommand.RemoveLabels] and not
     *   the provider's whole-set `labels` argument.
     * - **A stop does not tidy away another stop.** Moving to
     *   [CanonWorkStatus.VERDICT_REQUESTED] or [CanonWorkStatus.ESCALATED]
     *   removes nothing: the ticket is still stopped, and erasing the other
     *   gate would erase why. Moving anywhere else means the ticket is no longer
     *   stopped, so every gate it carries is stale and goes — otherwise a
     *   finished ticket keeps a gate label forever and the ready queue never
     *   offers it again.
     *
     * [remove] names only gates actually in [present]. Whether this work source
     * treats removing an absent label as a no-op is **not verified**, so the
     * edit does not rely on it.
     */
    fun gateEdit(target: CanonWorkStatus, present: Collection<String>): GateEdit {
        val carried = present.toSet()
        val wanted = expressionFor(target).labels

        return GateEdit(
            add = wanted - carried,
            remove = if (target in STOPS) emptySet() else WorkSourceLabels.GATES intersect carried,
        )
    }

    /** Whether [target] can clear a gate, and so whether [gateEdit] needs a read. */
    fun clearsGates(target: CanonWorkStatus): Boolean = target !in STOPS

    /**
     * The canonical statuses that mean *stopped, waiting on a human*.
     *
     * Exactly the two gate labels' statuses, which is not a coincidence: a gate
     * label is how this provider says "stopped", and [WorkSourceLabels.GATES] is
     * what takes a ticket out of [readyQueueRule].
     */
    private val STOPS: Set<CanonWorkStatus> = setOf(
        CanonWorkStatus.VERDICT_REQUESTED,
        CanonWorkStatus.ESCALATED,
    )
}

/**
 * Labels to add and gates to remove, as [WorkSourceCommand] arguments.
 *
 * Both sides can be empty; a caller skips the write rather than sending an empty
 * edit.
 */
data class GateEdit(
    val add: Set<String> = emptySet(),
    val remove: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = add.isEmpty() && remove.isEmpty()
}

/**
 * Where the supervisor's dispatch lifecycle lands in the work source.
 *
 * ## All six states are canon, as of AMPR-314
 *
 * [link.socket.ampere.canon.CanonWorkStatus] had five members — `BACKLOG`,
 * `TODO`, `IN_PROGRESS`, `DONE`, `CANCELLED` — and none of them could say
 * *claimed*, *verifying*, *verdict-requested* or *escalated*. That was the
 * AMPR-289 recon's gap G2, and AMPR-314 closed it: each state below now names
 * its [canonStatus], and the composition that expresses it on this provider is
 * [SupervisoryStatusMapping]'s. `providerStatus` still carries the work source's
 * own state name verbatim, but it is no longer where the lifecycle *lives* — it
 * is there for the genuinely provider-specific statuses a workspace invents.
 *
 * This enum and [SupervisoryStatusMapping] are one table read two ways, and
 * `SupervisoryStatusMappingTest` pins them together so they cannot drift. This
 * one is the *dispatch* lifecycle — six states a supervisor moves work through,
 * in order, which is what [READY] and [CLAIMED_STATE] are for. The mapping is
 * the *vocabulary*, nine canonical statuses wide, because a work source also
 * reports backlogged, plain in-progress and cancelled tickets that no supervisor
 * transition produces.
 *
 * ## The mapping
 *
 * | Supervisory state | Canon status | Work-source state | Label | Comment |
 * | --- | --- | --- | --- | --- |
 * | [QUEUED] | `TODO` | Todo | — | — |
 * | [CLAIMED] | `CLAIMED` | In Progress | — | [SupervisoryComment.Claim] |
 * | [VERIFYING] | `VERIFYING` | In Review | — | — |
 * | [VERDICT_REQUESTED] | `VERDICT_REQUESTED` | In Review | [WorkSourceLabels.GATE_AWAITING_VERDICT] | — |
 * | [ESCALATED] | `ESCALATED` | *unchanged* | [WorkSourceLabels.GATE_ESCALATED] | [SupervisoryComment.Escalation] |
 * | [DONE] | `DONE` | Done | — | — |
 *
 * @property canonStatus The canonical status this state is, since AMPR-314.
 * @property workSourceState The state name a transition writes, or null when
 *   the state is marked without moving the ticket. [ESCALATED] is the null
 *   case: a ticket escalates *from wherever it is*, and overwriting its state
 *   would destroy the one record of where the work stopped.
 * @property label The label that must be present, or null when the state needs
 *   none.
 */
enum class SupervisoryState(
    val canonStatus: CanonWorkStatus,
    val workSourceState: String?,
    val label: String? = null,
) {
    QUEUED(CanonWorkStatus.TODO, WorkSourceStates.TODO),
    CLAIMED(CanonWorkStatus.CLAIMED, WorkSourceStates.IN_PROGRESS),
    VERIFYING(CanonWorkStatus.VERIFYING, WorkSourceStates.IN_REVIEW),
    VERDICT_REQUESTED(
        CanonWorkStatus.VERDICT_REQUESTED,
        WorkSourceStates.IN_REVIEW,
        WorkSourceLabels.GATE_AWAITING_VERDICT,
    ),
    ESCALATED(CanonWorkStatus.ESCALATED, null, WorkSourceLabels.GATE_ESCALATED),
    DONE(CanonWorkStatus.DONE, WorkSourceStates.DONE),
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
