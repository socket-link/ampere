package link.socket.ampere.agents.execution.dispatch

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.execution.process.CancellationAddress

/**
 * One claim record: what a supervisor had done about one dispatch, at one moment.
 *
 * This is the on-disk schema of the claim-record journal (AMPR-291 mechanism M-A,
 * built in AMPR-307). The journal exists because a SIGKILLed supervisor leaves two
 * things behind that nothing can otherwise enumerate: work-source claims no timeout
 * will ever clear, and agent subprocesses that keep editing worktrees, spending
 * tokens, and pushing branches. A record is written *before* the dispatch can do
 * anything irreversible, so a restarted supervisor has something to iterate.
 *
 * The journal is append-only and a dispatch advances through [DispatchPhase], so a
 * dispatch contributes one record per phase it reaches. The record for a dispatch is
 * its most recently appended one — see `JournalRead.latestPerDispatch`.
 *
 * The field list is the one the recon verdict fixed (AMPR-291 item B3): ticket
 * identifier, supervisor instance-id, worktree path, branch name, agent
 * process-group id, phase, merge-request URL if reached.
 *
 * @property ticketId The work-source identifier of the claimed ticket. Identifies
 *   the dispatch, so a later record with the same value supersedes an earlier one.
 * @property supervisorInstanceId The supervisor process that claimed it. Must match
 *   the journal the record is appended to — one file per instance, so that each file
 *   has exactly one writer and its clean-shutdown marker means "*this* supervisor
 *   exited gracefully".
 * @property worktreePath Absolute path of the git worktree the agent was given.
 * @property branchName The branch created for the dispatch.
 * @property phase How far the dispatch had got when this record was written.
 * @property recordedAt When this record was written.
 * @property agentAddress Where to send "stop" to the agent subprocess and everything
 *   it spawned — the recon's "agent process-group id" ([CancellationAddress.processGroupId])
 *   plus the leader PID and start time that let `ProcessGroups.terminate` refuse a
 *   recycled PID instead of signalling an innocent process. Null until the agent is
 *   spawned, which is also why [DispatchPhase.AGENT_RUNNING] onwards should carry one.
 * @property mergeRequestUrl The merge/pull request, once the dispatch opened one.
 *   Null before [DispatchPhase.MR_OPENED]; a recovery pass uses it to tell work that
 *   reached a reviewable artifact from work that has to be redone.
 */
@Serializable
data class DispatchRecord(
    val ticketId: String,
    val supervisorInstanceId: String,
    val worktreePath: String,
    val branchName: String,
    val phase: DispatchPhase,
    val recordedAt: Instant,
    val agentAddress: CancellationAddress? = null,
    val mergeRequestUrl: String? = null,
)

/**
 * How far one dispatch has got through the supervisor's dispatch loop.
 *
 * Ordered as the loop runs, but nothing reads the ordinal: a dispatch can reach
 * [ESCALATED] from anywhere, and a recovery pass cares only about [isTerminal].
 */
@Serializable
enum class DispatchPhase {
    /** The work-source claim succeeded. Nothing local exists yet. */
    CLAIMED,

    /** The git worktree and branch exist. */
    WORKTREE_CREATED,

    /** The agent subprocess is running and may be writing to the worktree. */
    AGENT_RUNNING,

    /** The agent finished; verification (tests, lint) is running. */
    VERIFYING,

    /** A merge request is open; see [DispatchRecord.mergeRequestUrl]. */
    MR_OPENED,

    /** The dispatch completed. Nothing is owed. */
    DONE,

    /** The dispatch stopped and handed off to a human. Nothing is owed. */
    ESCALATED,
    ;

    /**
     * Whether this phase owes nothing to a recovery pass.
     *
     * A dispatch left in any other phase by a supervisor that did not shut down
     * cleanly is suspect: its claim may still be held and its agent may still be
     * running.
     */
    val isTerminal: Boolean
        get() = this == DONE || this == ESCALATED
}
