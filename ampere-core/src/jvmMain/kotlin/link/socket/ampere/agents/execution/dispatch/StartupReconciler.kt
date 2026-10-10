package link.socket.ampere.agents.execution.dispatch

import java.nio.file.Files
import java.nio.file.Paths
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.SupervisorEvent
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.process.CancellationAddress
import link.socket.ampere.agents.execution.process.ProcessGroups
import link.socket.ampere.agents.execution.process.TerminationOutcome

/**
 * Stops an agent process group from its recorded address alone.
 *
 * A seam over [ProcessGroups.terminate] so the pass's ordering can be tested
 * without spawning processes. The production value is the real thing, and
 * `StartupReconcilerProcessTest` reaps a real group through it.
 */
fun interface ProcessGroupStopper {
    fun stop(address: CancellationAddress, grace: Duration): TerminationOutcome
}

/**
 * The startup reconciliation pass: turns every crash-residue class the AMPR-291
 * fate table verified back into clean, dispatchable state (mechanism M-C, ratified
 * item B3, built in AMPR-310).
 *
 * ### The ordering principle
 *
 * **Kill writers before judging what they wrote; repair local before shared; query
 * before create.** The recon's experiment E1 watched an orphaned `git` child
 * finish a checkout *after its parent was killed*, so inspecting a worktree while
 * its agent may still be running reads a moving target. That is why the pass is
 * phase-major rather than dispatch-major: every orphan is reaped before any
 * worktree is looked at, and every worktree is repaired before the first write goes
 * to the work source. Doing it a dispatch at a time would interleave the two, which
 * is the thing the ordering forbids.
 *
 * The seven ordered checks, as ratified:
 *
 * 1. **Read the journal.** Entries in a journal with no clean-shutdown marker are
 *    suspect. The event database needs no step — SQLite rolls back torn
 *    transactions when it is opened.
 * 2. **Reap orphans.** [ProcessGroups.terminate] is the liveness probe *and* the
 *    action: it signals nothing when the address is already gone, and refuses when
 *    the recorded PID has been reused.
 * 3. **Repair local state.** Stale `*.lock` files, worktrees locked
 *    `initializing`, `git worktree prune`, orphan branches, and a dirty survivor
 *    *held* rather than deleted — see [WorktreeRepair].
 * 4. **Reconcile shared state.** Release the claims of dead supervisor instances,
 *    and defer to a human who moved a ticket (rule B4) — see [ClaimRegistry].
 * 5. **Reconcile merge requests by query**, never by retry: creation is not
 *    idempotent — see [MergeRequestLookup].
 * 6. **Requeue, never resume.** A suspect dispatch is re-dispatched from scratch,
 *    per the AMPR-281 run-based lifecycle verdict, so the pass restores nothing of
 *    the agent's own state. Reverting the ticket to its queued state in step 4 *is*
 *    the requeue; choosing when to dispatch it again is the Switchboard's.
 * 7. **Write the outcome to the journal.** A settled journal gets a reconciliation
 *    marker, which is both the durable record and what stops the next pass looking.
 *
 * ### Why it is safe to re-run, including mid-pass
 *
 * Every step is a read, an idempotent write, or a compensation guarded by a
 * read-back. Terminating a dead group answers `ALREADY_GONE`; deleting an absent
 * lock file or branch is a no-op; `prune` is idempotent; and the claim release
 * reads a ticket's comments before writing one, so a pass killed between the
 * retraction comment and the status revert finishes the revert on its next run
 * instead of commenting twice. Nothing is resumed from a progress log, because a
 * progress log is one more thing that can be torn.
 *
 * ### What it refuses to do
 *
 * Held residue is the point, not a shortfall. A worktree with uncommitted or
 * unpushed work, a ticket a person moved, a process group that survived SIGKILL, a
 * journal line nothing can parse — each is reported and left alone, and each keeps
 * its journal unmarked so the next pass says so again. Destroyed work cannot be
 * recovered by a later pass; visible mess can.
 *
 * @param journal This process's own journal, used for its directory and for the
 *   reconciliation markers it writes. Its own instance is never part of its input:
 *   a live supervisor is not residue.
 * @param worktrees The repository whose worktrees this pass may repair, or null to
 *   skip step 3 — which leaves every local verdict unsettled rather than pretending.
 * @param claims The work source, or null when none is wired. Null does not make the
 *   claims go away, so the pass reports [ClaimVerdict.SOURCE_UNAVAILABLE] rather
 *   than silence.
 * @param mergeRequests The forge lookup. Without it no branch is deleted: a branch
 *   whose merge-request state is unknown might be a live review's.
 * @param releaseUnjournaledClaims Whether the cold-start fallback may *write*. Off
 *   by default, and deliberately: a claim scan cannot tell a dead claimant from a
 *   supervisor that is running right now on another host, and stomping a live
 *   claim is worse than leaving a stale one — the same asymmetry rule B4 states.
 */
class StartupReconciler(
    private val journal: DispatchJournal,
    private val worktrees: WorktreeRepair? = null,
    private val claims: ClaimRegistry? = null,
    private val mergeRequests: MergeRequestLookup? = null,
    private val eventApi: AgentEventApi? = null,
    private val eventSource: EventSource = EventSource.Agent(DEFAULT_SOURCE_ID),
    private val now: () -> Instant = { Clock.System.now() },
    private val idGenerator: () -> String = { generateUUID() },
    private val grace: Duration = ProcessGroups.DEFAULT_GRACE,
    private val releaseUnjournaledClaims: Boolean = false,
    private val stopper: ProcessGroupStopper = ProcessGroupStopper(ProcessGroups::terminate),
) {

    /**
     * Run the pass.
     *
     * Returns rather than throws whatever happens: a recovery pass that propagated
     * the first failure would lose the dispositions it had already established, and
     * those are the evidence a person needs. Failures appear as unsettled verdicts
     * and as [ReconciliationOutcome.notes].
     */
    suspend fun reconcile(): ReconciliationOutcome {
        val startedAt = now()
        val passId = idGenerator()
        val notes = mutableListOf<String>()

        val reads = journal.scanAll().filterNot { it.instanceId == journal.instanceId }
        val quarantinedLines = reads.sumOf { it.quarantined.size }
        if (quarantinedLines > 0) {
            notes += "$quarantinedLines journal line(s) could not be parsed; " +
                "a claim or an agent process group may exist that nothing can enumerate"
        }

        val outcome = if (reads.isEmpty()) {
            coldStart(passId, startedAt, notes)
        } else {
            fromJournals(passId, startedAt, reads, quarantinedLines, notes)
        }

        publishOutcome(outcome)
        return outcome
    }

    // -----------------------------------------------------------------
    // The journal path
    // -----------------------------------------------------------------

    private suspend fun fromJournals(
        passId: String,
        startedAt: Instant,
        reads: List<JournalRead>,
        quarantinedLines: Int,
        notes: MutableList<String>,
    ): ReconciliationOutcome {
        val unsettledReads = reads.filterNot { it.cleanShutdown || it.reconciled }
        val pending = unsettledReads
            .flatMap { read -> read.suspectDispatches().map { Pending(read.instanceId, it) } }
            .sortedBy { it.record.ticketId }

        // 2. Kill the writers. Nothing below may look at a worktree until this is done.
        pending.forEach { reap(it) }

        // 3. Local state, in the ratified order: locks, locked-initializing worktrees,
        //    prune, then branches — with the merge-request query ahead of any deletion.
        repairLocalState(pending, notes)

        // 4. Shared state, last.
        pending.forEach { releaseClaim(it) }

        // 7. Settle the journals that owe nothing more.
        val completedAt = now()
        val byInstance = pending.groupBy { it.instanceId }
        val settled = unsettledReads.filter { read ->
            read.quarantined.isEmpty() && byInstance[read.instanceId].orEmpty().all { it.isSettled }
        }

        val marked = settled.mapNotNull { read ->
            val mark = ReconciliationMark(
                at = completedAt,
                by = journal.instanceId,
                passId = passId,
                dispositions = byInstance[read.instanceId].orEmpty().map { it.disposition() },
            )
            try {
                journal.markReconciled(read.instanceId, mark)
                read.instanceId
            } catch (cancelled: CancellationException) {
                // Narrower than runCatching on purpose: swallowing a cancellation
                // here would carry on marking the remaining journals after the pass
                // had been stopped.
                throw cancelled
            } catch (error: Exception) {
                notes += "could not mark journal '${read.instanceId}' reconciled: " +
                    (error.message ?: error.toString())
                null
            }
        }

        return ReconciliationOutcome(
            passId = passId,
            runnerInstanceId = journal.instanceId,
            startedAt = startedAt,
            completedAt = completedAt,
            source = ReconciliationSource.JOURNAL,
            journalsRead = reads.map { it.instanceId },
            journalsSettled = marked,
            quarantinedLines = quarantinedLines,
            dispositions = pending.map { it.disposition() },
            notes = notes.toList(),
        )
    }

    /** Step 2: stop the agent, and everything it spawned, from its recorded address. */
    private fun reap(pending: Pending) {
        val address = pending.record.agentAddress ?: return
        val outcome = stopper.stop(address, grace)
        pending.reaped = outcome
        when (outcome) {
            // Something in the group is still running, so its worktree is a moving
            // target and step 3 must not touch it (experiment E1).
            TerminationOutcome.SURVIVED -> {
                pending.notes += "process group ${address.processGroupId} survived SIGKILL"
                pending.worktree = WorktreeVerdict.HELD_WRITER_ALIVE
            }

            // The group is alive but its leader pid now belongs to someone else, so
            // nothing could be signalled — and what is alive in that group may still
            // be our agent's children.
            TerminationOutcome.REFUSED_ADDRESS_REUSED -> {
                pending.notes += "leader pid ${address.leaderPid} belongs to another process now, " +
                    "so the group was not signalled"
                pending.worktree = WorktreeVerdict.HELD_WRITER_ALIVE
            }

            else -> Unit
        }
    }

    /**
     * Steps 3 and 5, in order, for every suspect dispatch at once.
     *
     * Repository-wide operations — the worktree list, `prune` — happen once, which
     * is also why this is not a per-dispatch function.
     */
    private suspend fun repairLocalState(pending: List<Pending>, notes: MutableList<String>) {
        val repair = worktrees
        if (repair == null) {
            notes += "no repository was given, so no worktree or branch was inspected"
            return
        }

        val entries = repair.worktrees().getOrElse { error ->
            notes += "could not list worktrees of ${repair.repository}: ${error.message ?: error}"
            pending.forEach {
                if (it.worktree == WorktreeVerdict.NOT_INSPECTED) it.worktree = WorktreeVerdict.FAILED
            }
            return
        }

        pending.forEach { repairWorktree(it, repair, entries) }

        val pruned = repair.prune()
        if (!pruned.succeeded) notes += "git worktree prune failed: ${pruned.complaint}"

        pending.forEach { reconcileBranch(it, repair) }
    }

    private suspend fun repairWorktree(
        pending: Pending,
        repair: WorktreeRepair,
        entries: List<WorktreeEntry>,
    ) {
        if (pending.worktree == WorktreeVerdict.HELD_WRITER_ALIVE) return

        val recorded = pending.record.worktreePath
        if (recorded.isBlank()) {
            pending.worktree = WorktreeVerdict.NONE_RECORDED
            return
        }

        val path = Paths.get(recorded)
        val real = runCatching { path.toRealPath() }.getOrElse { path.toAbsolutePath().normalize() }
        val entry = entries.firstOrNull { listed ->
            val other = Paths.get(listed.path)
            runCatching { other.toRealPath() }.getOrElse { other.toAbsolutePath().normalize() } == real
        }

        if (entry == null) {
            // Not a worktree this repository knows about. If the directory is gone too
            // there is nothing left to repair; if it is still there, this pass cannot
            // attribute it and must not delete it.
            pending.worktree = if (Files.exists(path)) {
                pending.notes += "$recorded exists but is not a worktree of ${repair.repository}"
                WorktreeVerdict.OUTSIDE_REPOSITORY
            } else {
                WorktreeVerdict.ABSENT
            }
            return
        }

        repair.clearStaleLocks(path).fold(
            onSuccess = { cleared ->
                if (cleared.isNotEmpty()) pending.notes += "deleted stale lock(s): ${cleared.joinToString()}"
            },
            onFailure = { error ->
                pending.notes += "could not sweep stale locks: ${error.message ?: error}"
            },
        )

        if (entry.lockedInitializing) {
            // Verified residue of a kill mid-`git worktree add`: plain --force refuses
            // and prune ignores it, so this is the only sequence that clears it.
            val removed = repair.forceRemove(path)
            pending.worktree = if (removed.succeeded) {
                WorktreeVerdict.FORCE_REMOVED
            } else {
                pending.notes += "git worktree remove -f -f failed: ${removed.complaint}"
                WorktreeVerdict.FAILED
            }
            return
        }

        if (!Files.isDirectory(path)) {
            pending.worktree = WorktreeVerdict.ABSENT
            return
        }

        pending.worktree = when (repair.condition(path)) {
            WorktreeCondition.UNCOMMITTED -> WorktreeVerdict.HELD_UNCOMMITTED
            WorktreeCondition.UNPUSHED -> WorktreeVerdict.HELD_UNPUSHED
            WorktreeCondition.UNKNOWN -> {
                pending.notes += "git could not report the state of $recorded, so it was kept"
                WorktreeVerdict.FAILED
            }

            WorktreeCondition.CLEAN -> {
                val removed = repair.remove(path)
                if (removed.succeeded) {
                    WorktreeVerdict.REMOVED
                } else {
                    pending.notes += "git worktree remove failed: ${removed.complaint}"
                    WorktreeVerdict.FAILED
                }
            }
        }
    }

    /**
     * Step 5 then the branch half of step 3: ask the forge first, and let the answer
     * decide both whether a redispatch may open a merge request and whether the
     * branch is ours to delete.
     */
    private suspend fun reconcileBranch(pending: Pending, repair: WorktreeRepair) {
        val branch = pending.record.branchName
        if (branch.isBlank()) {
            pending.branchVerdict = BranchVerdict.NONE_RECORDED
            return
        }

        val lookup = mergeRequests
        val found = lookup?.forHeadBranch(branch)
        if (found != null) {
            found.fold(
                onSuccess = { requests ->
                    pending.mergeRequest = requests.mostRelevant()
                    pending.mayOpenMergeRequest = requests.isEmpty()
                },
                onFailure = { error ->
                    pending.notes += "could not look up merge requests for $branch: ${error.message ?: error}"
                },
            )
        }

        if (!pending.worktree.isSettled) {
            // The worktree is the report; the branch follows it rather than adding a
            // second hold for the same undecided work.
            pending.branchVerdict = BranchVerdict.KEPT_WORKTREE_HELD
            return
        }

        if (!repair.branchExists(branch)) {
            pending.branchVerdict = BranchVerdict.ABSENT
            return
        }

        if (lookup == null) {
            pending.notes += "no merge-request lookup was wired, so branch $branch was kept"
            pending.branchVerdict = BranchVerdict.NOT_INSPECTED
            return
        }
        if (found?.isFailure == true) {
            // The forge was asked and did not answer. Reading that as "no merge
            // request" is the one direction that deletes a live review's branch.
            pending.branchVerdict = BranchVerdict.FAILED
            return
        }

        val owner = found?.getOrNull()?.owningBranch()
        if (owner != null) {
            pending.branchVerdict = BranchVerdict.KEPT_MERGE_REQUEST
            return
        }

        pending.branchVerdict = when (repair.branchIsPushed(branch)) {
            false -> BranchVerdict.HELD_UNMERGED
            null -> {
                pending.notes += "git could not tell whether $branch is pushed, so it was kept"
                BranchVerdict.FAILED
            }

            true -> {
                val deleted = repair.deleteBranch(branch)
                if (deleted.succeeded) {
                    BranchVerdict.DELETED
                } else {
                    pending.notes += "git branch -D $branch failed: ${deleted.complaint}"
                    BranchVerdict.FAILED
                }
            }
        }
    }

    /** Step 4: retract the dead instance's claim and put the ticket back in the queue. */
    private suspend fun releaseClaim(pending: Pending) {
        val registry = claims
        if (registry == null) {
            pending.claim = ClaimVerdict.SOURCE_UNAVAILABLE
            return
        }
        pending.claim = registry.releaseClaim(pending.record.ticketId, pending.instanceId).fold(
            onSuccess = { release -> pending.verdictFor(release) },
            onFailure = { error ->
                pending.notes += "claim release failed: ${error.message ?: error}"
                ClaimVerdict.FAILED
            },
        )
    }

    // -----------------------------------------------------------------
    // The cold-start fallback
    // -----------------------------------------------------------------

    /**
     * No journal: fall back to scanning the work source for claimed tickets.
     *
     * Strictly weaker, and said so in the outcome. A claim comment proves a
     * supervisor took a ticket, not that it died, and nothing here can name the
     * worktree, branch or process group that dispatch created — so the only residue
     * class visible is a held claim, and by default it is reported rather than
     * released.
     */
    private suspend fun coldStart(
        passId: String,
        startedAt: Instant,
        notes: MutableList<String>,
    ): ReconciliationOutcome {
        val registry = claims
        if (registry == null) {
            notes += "no journal and no work source: the pass had nothing to read"
            return ReconciliationOutcome(
                passId = passId,
                runnerInstanceId = journal.instanceId,
                startedAt = startedAt,
                completedAt = now(),
                source = ReconciliationSource.NO_EVIDENCE,
                notes = notes.toList(),
            )
        }

        notes += "no journal was found, so the pass ran degraded: suspects came from a " +
            "work-source claim scan, which cannot tell a dead claimant from a live one"

        val scan = registry.claimedTickets().getOrElse { error ->
            notes += "the claim scan failed: ${error.message ?: error}"
            ClaimScan()
        }
        scan.incomplete.forEach { notes += "the claim scan may be incomplete: $it" }

        val dispositions = scan.tickets.flatMap { ticket ->
            ticket.holders.map { holder ->
                val pending = Pending(holder, degradedRecord(ticket.ticketId, holder))
                if (releaseUnjournaledClaims) {
                    releaseClaim(pending)
                } else {
                    pending.claim = ClaimVerdict.REPORTED_ONLY
                    pending.notes += "claimed by $holder with the ticket in '${ticket.state}'; " +
                        "release was not attempted because no journal proves the claimant is dead"
                }
                pending.disposition()
            }
        }

        return ReconciliationOutcome(
            passId = passId,
            runnerInstanceId = journal.instanceId,
            startedAt = startedAt,
            completedAt = now(),
            source = ReconciliationSource.DEGRADED_CLAIM_SCAN,
            quarantinedLines = 0,
            dispositions = dispositions,
            notes = notes.toList(),
        )
    }

    /**
     * A stand-in record for a dispatch the pass only knows from a claim comment.
     *
     * The paths are empty because nothing knows them, and that is exactly what
     * [WorktreeVerdict.NOT_INSPECTED] then reports — a degraded pass must not look
     * settled.
     */
    private fun degradedRecord(ticketId: String, holder: String): DispatchRecord =
        DispatchRecord(
            ticketId = ticketId,
            supervisorInstanceId = holder,
            worktreePath = "",
            branchName = "",
            phase = DispatchPhase.CLAIMED,
            recordedAt = now(),
        )

    // -----------------------------------------------------------------
    // Events
    // -----------------------------------------------------------------

    private suspend fun publishOutcome(outcome: ReconciliationOutcome) {
        val api = eventApi ?: return
        outcome.dispositions.forEach { disposition ->
            api.publish(
                SupervisorEvent.DispatchReconciled(
                    eventId = idGenerator(),
                    eventSource = eventSource,
                    timestamp = outcome.completedAt,
                    instanceId = disposition.supervisorInstanceId,
                    passId = outcome.passId,
                    ticketId = disposition.ticketId,
                    reaped = disposition.reaped,
                    worktree = disposition.worktree,
                    branch = disposition.branchVerdict,
                    claim = disposition.claim,
                    settled = disposition.isSettled,
                ),
            )
        }
        api.publish(
            SupervisorEvent.ReconciliationCompleted(
                eventId = idGenerator(),
                eventSource = eventSource,
                timestamp = outcome.completedAt,
                instanceId = outcome.runnerInstanceId,
                passId = outcome.passId,
                source = outcome.source,
                journalsRead = outcome.journalsRead.size,
                journalsSettled = outcome.journalsSettled.size,
                settled = outcome.dispositions.count { it.isSettled },
                held = outcome.held.size,
                quarantinedLines = outcome.quarantinedLines,
            ),
        )
    }

    /** One suspect dispatch's verdicts while the pass is still establishing them. */
    private class Pending(
        val instanceId: String,
        val record: DispatchRecord,
    ) {
        var reaped: TerminationOutcome? = null
        var worktree: WorktreeVerdict = WorktreeVerdict.NOT_INSPECTED
        var branchVerdict: BranchVerdict = BranchVerdict.NOT_INSPECTED
        var claim: ClaimVerdict = ClaimVerdict.NOT_INSPECTED
        var mergeRequest: MergeRequestRef? = null
        var mayOpenMergeRequest: Boolean? = null
        val notes: MutableList<String> = mutableListOf()

        val isSettled: Boolean
            get() = worktree.isSettled && branchVerdict.isSettled && claim.isSettled

        /** Turn a [ClaimRelease] into a verdict, keeping the detail as a note. */
        fun verdictFor(release: ClaimRelease): ClaimVerdict = when (release) {
            is ClaimRelease.Released -> {
                release.reason?.let { notes += "claim retracted but not reverted: $it" }
                release.revertedTo?.let { notes += "ticket reverted to '$it'" }
                // ALREADY_RELEASED means *nothing was written*, so a run that found a
                // previous run's comment and still finished the revert is a release.
                if (release.commentPosted || release.revertedTo != null) {
                    ClaimVerdict.RELEASED
                } else {
                    ClaimVerdict.ALREADY_RELEASED
                }
            }

            is ClaimRelease.NotHeld -> {
                release.liveHolder?.let { notes += "claim is held by $it, so the ticket was left alone" }
                if (release.liveHolder == null) {
                    ClaimVerdict.NOT_HELD
                } else {
                    ClaimVerdict.HELD_BY_ANOTHER_INSTANCE
                }
            }

            is ClaimRelease.Deferred -> {
                notes += "deferred to the human state '${release.observedState}': ${release.reason}"
                if (release.commentPosted) notes += "the claim was retracted; only the state was left alone"
                ClaimVerdict.DEFERRED_TO_HUMAN
            }
        }

        fun disposition(): DispatchDisposition = DispatchDisposition(
            ticketId = record.ticketId,
            supervisorInstanceId = instanceId,
            phase = record.phase,
            worktreePath = record.worktreePath.ifBlank { null },
            branch = record.branchName.ifBlank { null },
            reaped = reaped,
            worktree = worktree,
            branchVerdict = branchVerdict,
            claim = claim,
            mergeRequest = mergeRequest,
            mayOpenMergeRequest = mayOpenMergeRequest,
            notes = notes.toList(),
        )
    }

    companion object {

        /** Attribution for the pass's events when the caller does not name a source. */
        const val DEFAULT_SOURCE_ID: String = "ampere.startup-reconciler"
    }
}
