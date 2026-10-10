package link.socket.ampere.agents.execution.dispatch

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.execution.process.TerminationOutcome

/**
 * What one startup reconciliation pass found and did (AMPR-291 mechanism M-C,
 * built in AMPR-310).
 *
 * The pass runs when state is already damaged, so its output is evidence first and
 * a summary second: every destructive local action it took is named here, against
 * the dispatch that justified it, and so is every judgement it declined to make.
 *
 * ### Settled, or held for a human
 *
 * [isSettled] is the one question the caller has to ask. A pass that settled
 * everything leaves nothing owed; anything in [held] is residue the pass
 * deliberately refused to touch — an uncommitted worktree, a ticket a human moved,
 * a journal line nothing could parse — and a person has to decide what happens to
 * it. Held residue is also what keeps a journal unmarked, so the next pass reports
 * it again rather than forgetting it.
 *
 * @property passId Identifies this pass in the event trace. Not an Arc
 *   [RunId][link.socket.ampere.agents.domain.RunId]: a reconciliation pass is not a
 *   cognitive run, and conflating the two would put it in Arc trace projections.
 * @property runnerInstanceId The supervisor instance that ran the pass. Its own
 *   journal is never part of its own input.
 * @property source Where the suspect dispatches came from, and therefore how much
 *   the pass could know — see [ReconciliationSource].
 * @property journalsRead Every supervisor instance whose journal the pass read,
 *   the clean ones included.
 * @property journalsSettled The instances whose journals the pass marked
 *   reconciled, so a later pass stops treating their dispatches as suspect.
 * @property quarantinedLines Journal lines nothing could parse. Non-zero means a
 *   claim or a process group may exist that nothing can enumerate, which is the
 *   original AMPR-291 failure — so a journal with any is never marked.
 * @property dispositions One per suspect dispatch, in ticket order.
 * @property notes Pass-level remarks a disposition cannot carry: why the pass ran
 *   degraded, a work-source scan that failed, a journal it could not mark.
 */
@Serializable
data class ReconciliationOutcome(
    val passId: String,
    val runnerInstanceId: String,
    val startedAt: Instant,
    val completedAt: Instant,
    val source: ReconciliationSource,
    val journalsRead: List<String> = emptyList(),
    val journalsSettled: List<String> = emptyList(),
    val quarantinedLines: Int = 0,
    val dispositions: List<DispatchDisposition> = emptyList(),
    val notes: List<String> = emptyList(),
) {

    /** Dispatches the pass could not settle. Each one needs a person. */
    val held: List<DispatchDisposition> get() = dispositions.filterNot { it.isSettled }

    /** Whether the pass owes nothing further. False while anything is [held]. */
    val isSettled: Boolean get() = held.isEmpty() && quarantinedLines == 0

    /** Agent process groups the pass found still running and stopped. */
    val reaped: List<DispatchDisposition>
        get() = dispositions.filter {
            it.reaped == TerminationOutcome.TERMINATED || it.reaped == TerminationOutcome.KILLED
        }
}

/**
 * The reconciliation outcome as it is written into one dead supervisor's journal —
 * step 7 of the ratified specification.
 *
 * A [ReconciliationOutcome] spans a whole pass and lives in the event trace; this
 * is the slice that belongs to one journal, written as that journal's terminal
 * marker. It is deliberately the record *and* the skip signal: a journal carrying
 * one reports no suspect dispatches, so the pass costs nothing the second time it
 * runs, and nothing re-reads a dead instance's tickets off the work source forever.
 *
 * Only ever written over a journal every one of whose dispatches
 * [DispatchDisposition.isSettled]. Held residue leaves the journal unmarked so the
 * next pass reports it again.
 *
 * @property by The supervisor instance that ran the pass.
 * @property passId The pass, for joining this marker to its events.
 * @property dispositions What the pass did about each of this journal's suspect
 *   dispatches. Empty is legitimate: a journal whose dispatches had all reached a
 *   terminal phase has nothing suspect in it, and marking it is what stops the pass
 *   reopening the file every startup.
 */
@Serializable
data class ReconciliationMark(
    val at: Instant,
    val by: String,
    val passId: String,
    val dispositions: List<DispatchDisposition> = emptyList(),
)

/**
 * Where a pass got its suspect dispatches, and so how much it could know.
 *
 * The distinction is reported rather than inferred because the degraded path is
 * genuinely weaker and a reader has to be able to tell: a pass with no journal
 * cannot name a worktree, a branch or a process group, so the only residue class
 * it can even see is a held claim.
 */
@Serializable
enum class ReconciliationSource {

    /** At least one journal was read. Every suspect came from one. */
    JOURNAL,

    /**
     * No journal existed, so suspects came from a work-source scan for tickets
     * carrying a claim comment — the AMPR-310 cold-start fallback.
     */
    DEGRADED_CLAIM_SCAN,

    /** No journal and no work source: the pass had nothing to read. */
    NO_EVIDENCE,
}

/**
 * What the pass did about one suspect dispatch, residue class by residue class.
 *
 * Each verdict carries its own [WorktreeVerdict.isSettled]-style flag, and
 * [isSettled] is their conjunction, because the classes fail independently: a
 * reaped orphan whose worktree is dirty is half-recovered, and recording it as
 * either "done" or "failed" would lose the half that matters.
 *
 * @property phase How far the dispatch had got, per the journal. Null on the
 *   [ReconciliationSource.DEGRADED_CLAIM_SCAN] path, where there is no journal to
 *   say.
 * @property reaped What terminating the agent's process group did, or null if the
 *   record named no address — which is itself the answer for a dispatch that died
 *   before it spawned anything.
 * @property mergeRequest The merge request found for [branch], if any. The reason
 *   step 5 runs before any branch is deleted *and* before any dispatch reopens
 *   one: merge-request creation is not idempotent, so a blind retry duplicates it.
 * @property mayOpenMergeRequest Whether a redispatch of this ticket may create a
 *   merge request for [branch] — false when one already names it as head. Null
 *   when the question was not asked.
 * @property notes Everything a person would need that the verdicts cannot carry:
 *   which lock files were deleted, why a revert was declined, which instance holds
 *   a claim this one does not.
 */
@Serializable
data class DispatchDisposition(
    val ticketId: String,
    val supervisorInstanceId: String,
    val phase: DispatchPhase? = null,
    val worktreePath: String? = null,
    val branch: String? = null,
    val reaped: TerminationOutcome? = null,
    val worktree: WorktreeVerdict = WorktreeVerdict.NOT_INSPECTED,
    val branchVerdict: BranchVerdict = BranchVerdict.NOT_INSPECTED,
    val claim: ClaimVerdict = ClaimVerdict.NOT_INSPECTED,
    val mergeRequest: MergeRequestRef? = null,
    val mayOpenMergeRequest: Boolean? = null,
    val notes: List<String> = emptyList(),
) {

    /**
     * Whether this dispatch owes nothing further.
     *
     * All three classes have to be settled: the local state, the branch, and the
     * shared claim. A dispatch whose claim was released but whose worktree is
     * held is not recovered — the ticket is dispatchable again, and a person still
     * has uncommitted work on disk to decide about.
     */
    val isSettled: Boolean
        get() = worktree.isSettled && branchVerdict.isSettled && claim.isSettled
}

/**
 * What became of one dispatch's git worktree.
 *
 * Every unsettled member is a *hold*, not a failure to try: the pass found
 * something only a person can decide about and stopped, which is the ratified
 * behaviour for uncommitted or unpushed work.
 */
@Serializable
enum class WorktreeVerdict(val isSettled: Boolean) {

    /** The pass never looked — no repository was given to look in. */
    NOT_INSPECTED(false),

    /** The record named no worktree: the dispatch died before creating one. */
    NONE_RECORDED(true),

    /**
     * The agent's process group outlived SIGKILL, or its leader PID had been reused
     * so nothing could be signalled — so the worktree was left untouched.
     *
     * Inspecting state a writer may still be mutating reads a moving target, which
     * is the AMPR-291 experiment E1 finding and the reason the pass kills before it
     * judges. When the kill does not stick, the only correct move is to stop.
     */
    HELD_WRITER_ALIVE(false),

    /** The path is not a worktree of this repository any more. Nothing to do. */
    ABSENT(true),

    /** A clean worktree, removed so the redispatch starts from nothing. */
    REMOVED(true),

    /**
     * Metadata locked with reason "initializing", force-removed with
     * `git worktree remove -f -f` — the only sequence that clears it.
     */
    FORCE_REMOVED(true),

    /** Uncommitted changes. Held for a human redispatch decision. */
    HELD_UNCOMMITTED(false),

    /** Commits no remote-tracking ref contains. Held; deleting would lose them. */
    HELD_UNPUSHED(false),

    /**
     * The journal names a path that is not a worktree of the repository this pass
     * was given, so this pass is not the one allowed to delete it.
     */
    OUTSIDE_REPOSITORY(false),

    /** A git command failed. The residue is still there. */
    FAILED(false),
}

/** What became of one dispatch's branch. */
@Serializable
enum class BranchVerdict(val isSettled: Boolean) {

    /** The pass never looked — no repository was given to look in. */
    NOT_INSPECTED(false),

    /** The record named no branch. */
    NONE_RECORDED(true),

    /** No such branch. Residue of a dispatch that died before creating it. */
    ABSENT(true),

    /** Deleted: no surviving worktree, no merge request, nothing unmerged. */
    DELETED(true),

    /** A merge request names it as head, so the branch is the review's, not ours. */
    KEPT_MERGE_REQUEST(true),

    /** Its worktree is held, so the branch follows that hold rather than adding one. */
    KEPT_WORKTREE_HELD(true),

    /** Commits no remote-tracking ref contains. Held; deleting would lose them. */
    HELD_UNMERGED(false),

    /** A git command failed. The branch is still there. */
    FAILED(false),
}

/** What became of one dispatch's work-source claim. */
@Serializable
enum class ClaimVerdict(val isSettled: Boolean) {

    /** The pass never looked. */
    NOT_INSPECTED(false),

    /**
     * No work source was wired, so the claim could not be read, let alone
     * released. Unsettled on purpose: the ticket is still held by a dead
     * supervisor and no timeout will clear it.
     */
    SOURCE_UNAVAILABLE(false),

    /** The claim was retracted and the ticket is dispatchable again. */
    RELEASED(true),

    /** A previous run of this pass had already released it. Nothing was written. */
    ALREADY_RELEASED(true),

    /** This instance never claimed the ticket. Nothing to release. */
    NOT_HELD(true),

    /** Another supervisor instance holds the claim; the ticket is not ours to move. */
    HELD_BY_ANOTHER_INSTANCE(true),

    /**
     * The work source showed interference — a human moved the ticket — so the pass
     * deferred and wrote nothing. Ratified rule B4 of the AMPR-291 verdict.
     */
    DEFERRED_TO_HUMAN(false),

    /**
     * Found by the cold-start claim scan and reported without being touched,
     * because a scan cannot tell a dead claimant from a live one.
     */
    REPORTED_ONLY(false),

    /** The work source could not be read or written. The claim is still held. */
    FAILED(false),
}
