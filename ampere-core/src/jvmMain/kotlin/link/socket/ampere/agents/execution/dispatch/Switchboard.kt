package link.socket.ampere.agents.execution.dispatch

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 * What a Switchboard is configured with, beyond its collaborators.
 *
 * @property wave The wave whose ready queue it drains.
 * @property repositorySlug The repository it dispatches into, as `owner/name`, when
 *   known; see [ReadyQueueEvaluator].
 * @property logDirectory Where each run's output is written, one file per dispatch.
 * @property defaultModel The model and effort for a ticket whose recommendation line
 *   names neither.
 * @property pollInterval How long `--watch` waits between passes.
 * @property maxAgents At most this many dispatches held at once; null for no limit.
 * @property shutdownGrace How long a terminated agent group gets between SIGTERM and SIGKILL.
 */
data class SwitchboardConfig(
    val wave: String,
    val logDirectory: Path,
    val repositorySlug: String? = null,
    val defaultModel: ModelChoice = ModelChoice(model = "sonnet", effort = "medium"),
    val pollInterval: Duration = 5.minutes,
    val maxAgents: Int? = null,
    val shutdownGrace: Duration = ProcessGroups.DEFAULT_GRACE,
) {
    init {
        require(wave.isNotBlank()) { "A wave id is required" }
        require(maxAgents == null || maxAgents > 0) { "maxAgents must be positive" }
    }
}

/** What a Switchboard tells its operator as it works. Every method defaults to doing nothing. */
interface SwitchboardListener {
    fun onNote(message: String) {}
    fun onRefused(refusal: Refusal) {}
    fun onClaimLost(ticketId: String, detail: String) {}
    fun onDispatched(ticketId: String, plan: WorktreePlan, model: ModelChoice, address: CancellationAddress) {}
    fun onStopped(ticketId: String, run: AgentRun) {}
    fun onSettled(ticketId: String, phase: DispatchPhase?, detail: String) {}

    companion object {
        val NONE: SwitchboardListener = object : SwitchboardListener {}
    }
}

/**
 * How a Switchboard run ended.
 *
 * @property refused The latest refusal per ticket still refused when it ended.
 * @property settled The terminal phase each settled dispatch reached.
 * @property released Tickets whose claim was given back unstarted.
 * @property owed Dispatches the Switchboard could not settle, and why. Non-empty
 *   means the journal was left without its clean-shutdown marker, so `ampere
 *   reconcile` will treat them as suspect — which is the point.
 */
data class SwitchboardReport(
    val instanceId: String,
    val dispatched: List<String>,
    val refused: List<Refusal>,
    val settled: Map<String, DispatchPhase>,
    val released: List<String>,
    val owed: Map<String, String>,
    val cleanShutdown: Boolean,
)

/**
 * The automated dispatch loop: from a ready ticket to a running, journaled,
 * process-group-owned agent in its own worktree (AMPR-308).
 *
 * It replaces the operator's manual path from "this ticket is ready" to "an agent is
 * working on it", step for step as the AMPR-288 dispatch ethnography traced it:
 *
 * 1. **Evaluate** the wave's ready queue — [ReadyQueueEvaluator], including scope
 *    overlap against everything in flight and the strict research-blocker rule.
 * 2. **Claim** through the work source's comment-arbitrated protocol, which also moves
 *    the ticket to In Progress. Journal `CLAIMED`, with the planned worktree path and
 *    branch — before either exists, so a crash leaves the reconciliation pass a record
 *    that authorises their repair.
 * 3. **Create the worktree** on the work source's suggested branch. Journal
 *    `WORKTREE_CREATED` only after it succeeds.
 * 4. **Materialize the context** — the ticket and its comments where the operator's
 *    tooling puts them ([TicketContext]).
 * 5. **Launch the agent** in its own process group, pinned to the worktree, with the
 *    ticket's recommended model and effort ([AgentLauncher]). Journal `AGENT_RUNNING`
 *    with the group's address *before* the agent runs: it is held at a gate until the
 *    record is on disk ([GatedAgent]).
 * 6. **Leave it alone until it stops** ([RunSupervisor]), with one automated kill: ten
 *    silent minutes terminates the group and escalates.
 *
 * Then it settles the dispatch. A run that opened a merge request is journaled
 * `MR_OPENED` and `DONE` and left for a person to review and merge. Anything else — a
 * failure, a silence kill, a run with no merge request — is escalated: an `esc:`
 * comment saying what happened and what decision is needed, plus `gate:escalated`, and
 * journaled `ESCALATED`. A `research` ticket always ends at escalation, after
 * `gate:awaiting-verdict` is applied.
 *
 * ### The trust boundaries, and where each is enforced
 *
 * - **It never removes a gate label, never transitions a ticket to Done, never writes
 *   a human verdict** — there is no method for any of them on [DispatchWorkSource],
 *   the only door it holds onto the work source, and every comment it can cause opens
 *   with a supervisory header ([HumanVerdicts]).
 * - **A gated ticket never dispatches** — the gate labels are checked on a read taken
 *   immediately before the claim.
 * - **It never merges** — nothing it holds can: [MergeRequestLookup] only reads, and
 *   the git it runs is worktree and branch bookkeeping.
 *
 * ### Graceful shutdown
 *
 * [requestShutdown] (wired to SIGTERM/SIGINT by [SwitchboardShutdownHook]) stops the
 * passes, terminates every running agent's group with a bounded grace, escalates
 * those interrupted runs, gives back every claim whose agent never started, and only
 * then writes the journal's clean-shutdown marker. A dispatch it could not settle
 * leaves the marker unwritten, so the reconciliation pass picks it up.
 *
 * ### What it does not do
 *
 * Run the verification gate (the Breaker, a later wave), reconcile a crashed
 * predecessor's residue (`ampere reconcile`), or plan, create or order tickets beyond
 * the ready rule.
 */
class Switchboard(
    private val config: SwitchboardConfig,
    private val workSource: DispatchWorkSource,
    private val journal: DispatchJournal,
    private val worktrees: WorktreeProvisioner,
    private val launcher: AgentLauncher,
    private val supervisor: RunSupervisor = RunSupervisor(),
    private val mergeRequests: MergeRequestLookup? = null,
    private val eventApi: AgentEventApi? = null,
    private val listener: SwitchboardListener = SwitchboardListener.NONE,
    private val eventSource: EventSource = EventSource.Agent(DEFAULT_SOURCE_ID),
    private val now: () -> Instant = { Clock.System.now() },
    private val idGenerator: () -> String = { generateUUID() },
) {

    /** This supervisor's instance id: its journal's, and the one its claims carry. */
    val instanceId: String get() = journal.instanceId

    private val evaluator = ReadyQueueEvaluator(workSource, config.wave, config.repositorySlug)
    private val context = TicketContext(workSource.conventions)

    /** Guards [stopping] against a gate release and [passJob] against its own assignment. */
    private val lock = Any()

    @Volatile
    private var stopping = false

    @Volatile
    private var passJob: Job? = null

    private val started = AtomicBoolean(false)
    private val finished = CompletableDeferred<SwitchboardReport>()

    private val active = ConcurrentHashMap<String, Dispatch>()
    private val supervisions = CopyOnWriteArrayList<Job>()
    private val lastRefusals = ConcurrentHashMap<String, Refusal>()
    private val dispatched = CopyOnWriteArrayList<String>()
    private val settled = ConcurrentHashMap<String, DispatchPhase>()
    private val released = CopyOnWriteArrayList<String>()
    private val owed = ConcurrentHashMap<String, String>()

    /** One dispatch this Switchboard holds the claim for. */
    private class Dispatch(
        val ticket: DispatchTicket,
        val scope: TicketScope,
        val plan: WorktreePlan,
        val model: ModelChoice,
        @Volatile var record: DispatchRecord,
    ) {
        val id: String get() = ticket.identifier

        @Volatile var worktreeCreated: Boolean = false

        @Volatile var agent: GatedAgent? = null

        /** Whether the gate was released, i.e. whether the agent ever ran. */
        @Volatile var launched: Boolean = false

        @Volatile var shutdownTermination: TerminationOutcome? = null
    }

    /**
     * Run: one pass, or with [watch] a pass every [SwitchboardConfig.pollInterval]
     * until [requestShutdown]. Returns once every dispatched agent has stopped and been
     * settled, and the journal is marked — or deliberately left unmarked.
     */
    suspend fun run(watch: Boolean): SwitchboardReport {
        check(started.compareAndSet(false, true)) { "A Switchboard runs once" }
        try {
            coroutineScope {
                val supervisionScope = this
                val job = launch { passes(watch, supervisionScope) }
                synchronized(lock) {
                    passJob = job
                    if (stopping) job.cancel()
                }
                job.join()
                // No supervision starts once the passes have ended, so this list is final.
                supervisions.joinAll()
            }
            val report = finish()
            finished.complete(report)
            return report
        } catch (e: Throwable) {
            finished.completeExceptionally(e)
            throw e
        }
    }

    /**
     * Begin a graceful shutdown: stop the passes and terminate every running agent's
     * group. Blocks for at most the grace period plus the SIGKILL settle; [run] then
     * settles what was stopped and returns. Safe to call from any thread, more than once.
     */
    fun requestShutdown() {
        val running = synchronized(lock) {
            if (stopping) return
            stopping = true
            passJob?.cancel()
            active.values.filter { it.launched }
        }
        running.map { dispatch ->
            thread(name = "switchboard-stop-${dispatch.id}") {
                dispatch.agent?.let { gated ->
                    dispatch.shutdownTermination = ProcessGroups.terminate(gated.grouped.address, config.shutdownGrace)
                }
            }
        }.forEach { it.join() }
    }

    /** The report [run] returns, for a caller that did not start it — the shutdown hook. */
    suspend fun awaitFinished(): SwitchboardReport = finished.await()

    // -----------------------------------------------------------------
    // Passes
    // -----------------------------------------------------------------

    private suspend fun passes(watch: Boolean, supervisionScope: CoroutineScope) {
        while (!stopping) {
            try {
                pass(supervisionScope)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                note("the pass failed: ${e.describe()}")
            }
            if (!watch) return
            delay(config.pollInterval)
        }
    }

    private suspend fun pass(supervisionScope: CoroutineScope) {
        worktrees.refreshBase()?.let { note(it) }

        val scan = workSource.readyCandidates(config.wave).getOrElse { error ->
            note("could not read the ready queue for wave ${config.wave}: ${error.describe()}")
            return
        }
        scan.incomplete.forEach { note("the ready queue may be incomplete: $it") }
        scan.deferred.forEach { refuse(Refusal(it.ticketId, RefusalReason.UNREADABLE, it.reason)) }

        val candidates = scan.tickets
            .filter { !active.containsKey(it.identifier) }
            .sortedWith(compareBy(IDENTIFIER_ORDER) { it.identifier })
        if (candidates.isEmpty()) return

        val scanned = workSource.inFlight().getOrElse { error ->
            note("could not read the in-flight tickets, so no scope can be checked: ${error.describe()}")
            return
        }
        if (scanned.incomplete.isNotEmpty()) {
            note("the in-flight scan may have missed a ticket, so nothing dispatches: ${scanned.incomplete.first()}")
            return
        }

        val inFlight = linkedMapOf<String, InFlightTicket>()
        scanned.tickets.forEach {
            inFlight[it.identifier] = InFlightTicket(it.identifier, TicketScopes.parse(it.description))
        }
        active.values.forEach { inFlight[it.id] = InFlightTicket(it.id, it.scope) }

        for (candidate in candidates) {
            if (stopping) return
            val limit = config.maxAgents
            if (limit != null && active.size >= limit) {
                note("$limit dispatch(es) held; the rest of the queue waits for a later pass")
                return
            }

            when (val readiness = evaluator.evaluate(candidate, inFlight.values)) {
                is Readiness.Refused -> refuse(readiness.refusal)
                is Readiness.Ready -> {
                    lastRefusals.remove(candidate.identifier)
                    // Claim to launch is not interruptible: a shutdown landing between the
                    // claim comment and the journal would leave a claim nothing records.
                    val held = withContext(NonCancellable) { dispatch(readiness, supervisionScope) }
                    if (held) inFlight[candidate.identifier] = InFlightTicket(candidate.identifier, readiness.scope)
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // One dispatch
    // -----------------------------------------------------------------

    /**
     * Claim [ready]'s ticket and take it as far as a running agent. Returns whether the
     * ticket is now held by somebody — this instance or a claimant that beat it — so
     * its scope blocks the rest of the pass.
     */
    private suspend fun dispatch(ready: Readiness.Ready, supervisionScope: CoroutineScope): Boolean {
        val ticket = ready.ticket
        val id = ticket.identifier
        val plan = worktrees.plan(ticket)
        val model = ModelRecommendations.resolve(ticket.description, config.defaultModel)

        val claim = workSource.claim(id).getOrElse { error ->
            note("could not claim $id: ${error.describe()}")
            // The claim comment may have landed before the failure, and an unretracted
            // claim stays live forever. Retract it; failing that, leave it owed.
            retract(id, "the claim attempt failed partway")
            return true
        }
        when (claim) {
            is DispatchClaim.Lost -> {
                listener.onClaimLost(id, "claimed first by ${claim.winner ?: "another supervisor"}")
                return true
            }

            is DispatchClaim.Deferred -> {
                listener.onClaimLost(id, claim.reason)
                return true
            }

            DispatchClaim.Won -> Unit
        }

        val dispatch = Dispatch(
            ticket = ticket,
            scope = ready.scope,
            plan = plan,
            model = model,
            record = DispatchRecord(
                ticketId = id,
                supervisorInstanceId = instanceId,
                worktreePath = plan.path.toString(),
                branchName = plan.branch,
                phase = DispatchPhase.CLAIMED,
                recordedAt = now(),
            ),
        )
        active[id] = dispatch
        dispatched += id
        if (!append(dispatch, dispatch.record)) {
            abandon(dispatch, "the journal could not record the claim")
            return false
        }
        if (stopping) return true

        worktrees.create(plan).getOrElse { error ->
            note("could not create the worktree for $id: ${error.describe()}")
            escalate(dispatch, Escalations.worktree(plan.branch))
            return false
        }
        dispatch.worktreeCreated = true
        if (!advance(dispatch, DispatchPhase.WORKTREE_CREATED)) {
            abandon(dispatch, "the journal could not record the worktree")
            return false
        }
        if (stopping) return true

        val comments = workSource.comments(id).getOrElse { error ->
            note("could not read the comments of $id; the context file says so: ${error.describe()}")
            null
        }
        val contextPath = context.relativePath(ticket)
        runCatching { withContext(Dispatchers.IO) { context.materialize(plan.path, ticket, comments) } }
            .onFailure { error ->
                note("could not write the context for $id: ${error.describe()}")
                escalate(dispatch, Escalations.context(plan.branch))
                return false
            }

        AgentLauncher.checkPinned(plan.path)?.let { why ->
            note("$id: $why")
            escalate(dispatch, Escalations.pin(plan.branch))
            return false
        }
        if (stopping) return true

        val launch = AgentLaunch(ticketId = id, worktree = plan.path, contextPath = contextPath, model = model)
        val gated = runCatching { withContext(Dispatchers.IO) { launcher.startGated(launch) } }.getOrElse { error ->
            note("could not start the agent for $id: ${error.describe()}")
            escalate(dispatch, Escalations.spawn(plan.branch))
            return false
        }
        dispatch.agent = gated

        // The address goes to disk while the agent is still held at the gate.
        if (!advance(dispatch, DispatchPhase.AGENT_RUNNING, agentAddress = gated.grouped.address)) {
            abandon(dispatch, "the journal could not record the agent's process group")
            return false
        }

        val gateOpened: Result<Boolean> = synchronized(lock) {
            if (stopping) {
                Result.success(false)
            } else {
                runCatching { gated.release() }.map {
                    dispatch.launched = true
                    true
                }
            }
        }
        val opened = gateOpened.getOrElse { error ->
            note("the agent for $id exited before it could be released: ${error.describe()}")
            withContext(Dispatchers.IO) { gated.grouped.terminate(config.shutdownGrace) }
            dispatch.agent = null
            escalate(dispatch, Escalations.spawn(plan.branch))
            return false
        }
        // Shutting down: finish() stops the gate and gives the claim back.
        if (!opened) return true

        listener.onDispatched(id, plan, model, gated.grouped.address)
        supervisions += supervisionScope.launch(Dispatchers.IO) { supervise(dispatch, gated) }
        return true
    }

    private suspend fun supervise(dispatch: Dispatch, gated: GatedAgent) {
        try {
            val observed = supervisor.supervise(gated.grouped, logFile(dispatch)) { stopping }
            val run = if (observed.stop == AgentStop.SHUTDOWN) {
                observed.copy(termination = dispatch.shutdownTermination ?: observed.termination)
            } else {
                observed
            }
            listener.onStopped(dispatch.id, run)
            publish(
                SupervisorEvent.AgentRunStopped(
                    eventId = idGenerator(),
                    eventSource = eventSource,
                    timestamp = now(),
                    instanceId = instanceId,
                    ticketId = dispatch.id,
                    stop = run.stop,
                    exitCode = run.exitCode,
                    processGroupId = gated.grouped.address.processGroupId,
                    termination = run.termination,
                ),
            )
            withContext(NonCancellable) { settle(dispatch, run) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { gated.grouped.terminate(config.shutdownGrace) }
            active.remove(dispatch.id)
            owed[dispatch.id] = "supervising the run failed: ${e.describe()}"
            note("supervising ${dispatch.id} failed; its group was terminated and it is owed: ${e.describe()}")
        }
    }

    /** End a dispatch whose agent stopped: a merge request found, or an escalation. */
    private suspend fun settle(dispatch: Dispatch, run: AgentRun) {
        val branch = dispatch.plan.branch
        val research = workSource.conventions.researchLabel in dispatch.ticket.labels

        when {
            run.stop == AgentStop.SILENCED ->
                escalate(dispatch, Escalations.silenced(supervisor.silenceLimit, branch, run.termination))

            run.stop == AgentStop.SHUTDOWN -> escalate(dispatch, Escalations.interrupted(branch))

            run.exitCode != 0 -> escalate(dispatch, Escalations.exited(run.exitCode, branch))

            // A research ticket's deliverable is findings and a human verdict, never a
            // merge: it stops at the gate and a person, whatever it produced.
            research -> {
                val gated = workSource.requestVerdict(dispatch.id).fold(
                    onSuccess = { true },
                    onFailure = { error ->
                        note("could not apply the verdict gate to ${dispatch.id}: ${error.describe()}")
                        false
                    },
                )
                escalate(dispatch, Escalations.research(gated))
            }

            else -> settleByMergeRequest(dispatch, branch)
        }
    }

    /**
     * A clean exit is settled by asking the forge, never by trusting the agent: query
     * by head branch, as the AMPR-291 verdict requires before anything is created. A
     * closed request does not count — it is not work a person can merge.
     */
    private suspend fun settleByMergeRequest(dispatch: Dispatch, branch: String) {
        val lookup = mergeRequests
        if (lookup == null) {
            escalate(dispatch, Escalations.unconfirmed(branch))
            return
        }
        val requests = lookup.forHeadBranch(branch).getOrElse { error ->
            note("could not look up a merge request for $branch: ${error.describe()}")
            escalate(dispatch, Escalations.unconfirmed(branch))
            return
        }
        val request = requests.firstOrNull { it.state != MergeRequestState.CLOSED }
        if (request == null) {
            escalate(dispatch, Escalations.noMergeRequest(branch))
            return
        }

        val recorded = advance(dispatch, DispatchPhase.MR_OPENED, mergeRequestUrl = request.url) &&
            advance(dispatch, DispatchPhase.DONE)
        if (recorded) {
            markSettled(dispatch, DispatchPhase.DONE, request.url)
        } else {
            active.remove(dispatch.id)
            owed[dispatch.id] = "the journal could not record the merge request ${request.url}"
        }
    }

    // -----------------------------------------------------------------
    // Shutdown
    // -----------------------------------------------------------------

    private suspend fun finish(): SwitchboardReport = withContext(NonCancellable) {
        active.values.toList().forEach { dispatch ->
            if (dispatch.launched) {
                // Unreachable while supervision settles every launched run; kept so a bug
                // there leaves the dispatch owed rather than marked clean.
                dispatch.agent?.let { withContext(Dispatchers.IO) { it.grouped.terminate(config.shutdownGrace) } }
                active.remove(dispatch.id)
                owed[dispatch.id] = "its run was never settled"
            } else {
                abandon(dispatch, "the supervisor stopped before its agent started")
            }
        }

        val clean = owed.isEmpty() && runCatching { journal.markCleanShutdown() }.fold(
            onSuccess = { true },
            onFailure = { error ->
                note("could not write the clean-shutdown marker: ${error.describe()}")
                false
            },
        )
        if (owed.isNotEmpty()) {
            note("${owed.size} dispatch(es) are owed, so the journal is left unmarked for `ampere reconcile`")
        }

        SwitchboardReport(
            instanceId = instanceId,
            dispatched = dispatched.toList(),
            refused = lastRefusals.values.sortedWith(compareBy(IDENTIFIER_ORDER) { it.ticketId }),
            settled = settled.toMap(),
            released = released.toList(),
            owed = owed.toMap(),
            cleanShutdown = clean,
        )
    }

    /**
     * Give back a dispatch whose agent never ran: stop the gate if there is one, remove
     * the worktree and branch this Switchboard created, then release the claim — local
     * before shared, so a redispatch never meets a half-removed worktree.
     */
    private suspend fun abandon(dispatch: Dispatch, why: String) {
        dispatch.agent?.let { gated ->
            if (!dispatch.launched) withContext(Dispatchers.IO) { gated.grouped.terminate(config.shutdownGrace) }
        }
        if (dispatch.worktreeCreated) {
            worktrees.discard(dispatch.plan).onFailure { error ->
                note("could not remove the unused worktree for ${dispatch.id}: ${error.describe()}")
            }
        }
        active.remove(dispatch.id)
        retract(dispatch.id, why)
    }

    /** Release this instance's claim on [id], or record it as owed. */
    private suspend fun retract(id: String, why: String) {
        workSource.release(id).fold(
            onSuccess = { outcome ->
                released += id
                listener.onSettled(id, null, "claim released ($why): ${outcome.describe()}")
            },
            onFailure = { error ->
                owed[id] = "$why, and releasing the claim failed: ${error.describe()}"
                note("could not release the claim on $id: ${error.describe()}")
            },
        )
    }

    // -----------------------------------------------------------------
    // Writes
    // -----------------------------------------------------------------

    private suspend fun escalate(dispatch: Dispatch, reason: String) {
        workSource.escalate(dispatch.id, reason).getOrElse { error ->
            active.remove(dispatch.id)
            owed[dispatch.id] = "escalating failed: ${error.describe()}"
            note("could not escalate ${dispatch.id}: ${error.describe()}")
            return
        }
        if (advance(dispatch, DispatchPhase.ESCALATED)) {
            markSettled(dispatch, DispatchPhase.ESCALATED, reason.lineSequence().first())
        } else {
            active.remove(dispatch.id)
            owed[dispatch.id] = "escalated, but the journal could not record it"
        }
    }

    private fun markSettled(dispatch: Dispatch, phase: DispatchPhase, detail: String) {
        active.remove(dispatch.id)
        settled[dispatch.id] = phase
        listener.onSettled(dispatch.id, phase, detail)
    }

    private suspend fun advance(
        dispatch: Dispatch,
        phase: DispatchPhase,
        agentAddress: CancellationAddress? = null,
        mergeRequestUrl: String? = null,
    ): Boolean = append(
        dispatch,
        dispatch.record.copy(
            phase = phase,
            recordedAt = now(),
            agentAddress = agentAddress ?: dispatch.record.agentAddress,
            mergeRequestUrl = mergeRequestUrl ?: dispatch.record.mergeRequestUrl,
        ),
    )

    private suspend fun append(dispatch: Dispatch, record: DispatchRecord): Boolean =
        runCatching { journal.append(record) }.fold(
            onSuccess = {
                dispatch.record = record
                true
            },
            onFailure = { error ->
                note("the journal refused ${record.phase} for ${record.ticketId}: ${error.describe()}")
                false
            },
        )

    private suspend fun refuse(refusal: Refusal) {
        if (lastRefusals.put(refusal.ticketId, refusal) == refusal) return
        listener.onRefused(refusal)
        publish(
            SupervisorEvent.DispatchRefused(
                eventId = idGenerator(),
                eventSource = eventSource,
                timestamp = now(),
                instanceId = instanceId,
                ticketId = refusal.ticketId,
                reason = refusal.reason,
                detail = refusal.detail,
            ),
        )
    }

    private suspend fun publish(event: SupervisorEvent) {
        eventApi?.publish(event)
    }

    private fun note(message: String) = listener.onNote(message)

    private fun logFile(dispatch: Dispatch): Path =
        config.logDirectory.resolve(instanceId).resolve("${dispatch.plan.path.fileName}.log")

    private fun ClaimRelease.describe(): String = when (this) {
        is ClaimRelease.Released -> revertedTo?.let { "returned to '$it'" } ?: (reason ?: "retracted")
        is ClaimRelease.NotHeld -> "this instance held no claim"
        is ClaimRelease.Deferred -> "retracted; the state was left alone: $reason"
    }

    private fun Throwable.describe(): String = message ?: this::class.simpleName.orEmpty()

    companion object {

        /** Attribution for the events a Switchboard publishes. */
        const val DEFAULT_SOURCE_ID: String = "ampere.switchboard"

        /**
         * Tickets in identifier order — team prefix, then number — so the earlier-filed
         * of two overlapping tickets is the one dispatched. The operator orders by
         * nothing beyond readiness (AMPR-288, answer 1); this only makes the choice
         * repeatable.
         */
        internal val IDENTIFIER_ORDER: Comparator<String> = Comparator { a, b ->
            val left = IDENTIFIER.matchEntire(a)
            val right = IDENTIFIER.matchEntire(b)
            if (left == null || right == null) return@Comparator a.compareTo(b)
            val prefix = left.groupValues[1].compareTo(right.groupValues[1])
            if (prefix != 0) prefix else left.groupValues[2].toLong().compareTo(right.groupValues[2].toLong())
        }

        private val IDENTIFIER = Regex("""([A-Za-z]+)-(\d{1,18})""")
    }
}

/**
 * Installs [Switchboard.requestShutdown] on SIGTERM and SIGINT (AMPR-308, Expected
 * Outcome 5).
 *
 * A JVM shutdown hook rather than a signal handler: it runs for both signals and for
 * an ordinary exit, and the JVM does not halt until it returns. The hook terminates
 * the agents and then waits — up to [timeout] — for the Switchboard to settle them,
 * release what it holds and write the clean-shutdown marker. Agents in their own
 * process groups do not receive a terminal's Ctrl+C themselves; this is what stops
 * them.
 */
object SwitchboardShutdownHook {

    fun install(switchboard: Switchboard, timeout: Duration = 2.minutes): Thread {
        val hook = Thread({
            switchboard.requestShutdown()
            runBlocking { withTimeoutOrNull(timeout) { switchboard.awaitFinished() } }
        }, "switchboard-shutdown")
        Runtime.getRuntime().addShutdownHook(hook)
        return hook
    }

    /** Remove [hook] after a run that ended on its own. A JVM already shutting down keeps it. */
    fun remove(hook: Thread) {
        runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
    }
}

/**
 * The bodies of the Switchboard's escalation comments.
 *
 * Each one lands on a public mirror, so each is composed from fixed text, a branch
 * name and numbers — never a local path, a command's output or anything an agent
 * wrote. Each answers what the TicketConventions M6 body asks for: what failed, what
 * was tried, and what decision is needed.
 */
internal object Escalations {

    fun worktree(branch: String): String = body(
        "The Switchboard could not create a worktree for branch `$branch` — the branch or its directory " +
            "may already exist.",
        "Nothing; the agent was not started.",
        "A person to clear the branch or worktree, then redispatch.",
    )

    fun context(branch: String): String = body(
        "The Switchboard could not write the ticket into the worktree for branch `$branch`.",
        "Nothing; the agent was not started.",
        "A person to check the supervisor host, then redispatch.",
    )

    fun pin(branch: String): String = body(
        "The repository's Ampere configuration on branch `$branch` sets the workspace outside the worktree, " +
            "so an agent could write outside its sandbox.",
        "Nothing; the agent was not started.",
        "A person to fix the configuration's `workspace:` setting, then redispatch.",
    )

    fun spawn(branch: String): String = body(
        "The Switchboard could not start the agent for branch `$branch`.",
        "Nothing further; the run is not retried automatically.",
        "A person to check the agent command on the supervisor host, then redispatch.",
    )

    fun silenced(limit: Duration, branch: String, termination: TerminationOutcome?): String = body(
        "The agent produced no output for $limit — the Switchboard's one automated kill rule — so its " +
            "process group " +
            "was terminated (${termination?.name ?: "outcome unknown"}).",
        "Nothing further; the run is not retried automatically.",
        "A person to inspect branch `$branch` and decide whether to redispatch.",
    )

    fun interrupted(branch: String): String = body(
        "The supervisor shut down while the agent was running, so its process group was terminated.",
        "Nothing further; the run is not resumed.",
        "A person to inspect branch `$branch` and decide whether to redispatch.",
    )

    fun exited(exitCode: Int?, branch: String): String = body(
        "The agent exited with ${exitCode?.let { "status $it" } ?: "an unreadable status"}.",
        "Nothing further; the run is not retried automatically.",
        "A person to inspect branch `$branch` and decide whether to redispatch.",
    )

    fun noMergeRequest(branch: String): String = body(
        "The agent exited cleanly but no open pull request exists for branch `$branch`.",
        "Looked up pull requests by head branch.",
        "A person to inspect the branch and decide whether to open one or redispatch.",
    )

    fun unconfirmed(branch: String): String = body(
        "The agent exited cleanly, but the Switchboard could not confirm whether a pull request exists " +
            "for branch `$branch`.",
        "Looking up pull requests by head branch, which did not answer.",
        "A person to check for the pull request; the ticket is otherwise in progress with nothing working on it.",
    )

    fun research(verdictGateApplied: Boolean): String = body(
        "Research findings are ready for a human verdict." +
            if (verdictGateApplied) {
                " The verdict gate is applied."
            } else {
                " Applying the verdict gate failed, so it still needs adding."
            },
        "Nothing; a research ticket stops at a person, and recording the verdict is a human-only write.",
        "A human verdict on the findings.",
    )

    private fun body(what: String, tried: String, needs: String): String =
        "$what\n\nTried: $tried\nNeeds: $needs"
}
