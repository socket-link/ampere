package link.socket.ampere.agents.execution.dispatch

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.execution.process.CancellationAddress
import link.socket.ampere.agents.execution.process.GroupedProcess
import link.socket.ampere.agents.execution.process.ProcessGroups
import link.socket.ampere.agents.execution.process.TerminationOutcome

/**
 * The ordered recovery pass (AMPR-310, AMPR-291 mechanism M-C).
 *
 * The properties under test are the ratified specification's own: suspect dispatches
 * come from the *absence* of a clean-shutdown marker; writers are killed before
 * anything judges what they wrote; local state is repaired before shared state is
 * touched; a merge request is found by query and never by retry; anything a person
 * has to decide is held and reported rather than deleted; and the whole pass is
 * safe to run again over the top of an interrupted run of itself.
 *
 * Step 3 runs against a real repository — see [GitFixture]. Only the two edges the
 * pass cannot own are stubbed: killing a process group (one test uses the real
 * thing) and the work source.
 */
class StartupReconcilerTest {

    private val recordedAt = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val temporary = mutableListOf<Path>()
    private val spawned = mutableListOf<CancellationAddress>()
    private var nextId = 0

    @AfterTest
    fun tearDown() {
        spawned.forEach { ProcessGroups.terminate(it, grace = 100.milliseconds) }
        temporary.forEach { it.toFile().deleteRecursively() }
    }

    private fun tempDir(): Path =
        Files.createTempDirectory("ampere-reconcile").toRealPath().also { temporary.add(it) }

    private fun journal(directory: Path, instanceId: String, eventApi: AgentEventApi? = null) =
        DispatchJournal.open(
            directory = directory,
            instanceId = instanceId,
            eventApi = eventApi,
            now = { recordedAt },
            idGenerator = { "reconcile-event-${nextId++}" },
        )

    private fun record(
        ticketId: String,
        instanceId: String = DEAD,
        phase: DispatchPhase = DispatchPhase.AGENT_RUNNING,
        worktreePath: String = "",
        branchName: String = "",
        address: CancellationAddress? = null,
    ) = DispatchRecord(
        ticketId = ticketId,
        supervisorInstanceId = instanceId,
        worktreePath = worktreePath,
        branchName = branchName,
        phase = phase,
        recordedAt = recordedAt,
        agentAddress = address,
    )

    /**
     * A journal directory, a repository, and one suspect dispatch whose worktree and
     * branch really exist — the state a SIGKILLed supervisor leaves behind.
     */
    private inner class Crashed(
        val branch: String = "miley/ampr-310",
        val address: CancellationAddress? = CancellationAddress(4242, 4242, null),
        val configure: (Path) -> Unit = {},
    ) {
        val root: Path = tempDir()
        val journalDir: Path = root.resolve("journal")
        val repo: Path = GitFixture.repository(root)
        val worktree: Path = GitFixture.addWorktree(repo, root.resolve("wt-ampr-310"), branch)

        init {
            configure(worktree)
            runBlocking {
                journal(journalDir, DEAD).append(
                    record(
                        ticketId = TICKET,
                        worktreePath = worktree.toString(),
                        branchName = branch,
                        address = address,
                    ),
                )
            }
        }

        fun branches(): List<String> =
            GitFixture.git(repo, "branch", "--format=%(refname:short)").lines().filter { it.isNotBlank() }

        /** Worktree paths relative to [root], so two fixtures are comparable. */
        fun worktreePaths(): List<String> =
            WorktreeRepair.parseWorktreeList(GitFixture.git(repo, "worktree", "list", "--porcelain"))
                .map { root.relativize(Path.of(it.path).toAbsolutePath()).toString() }

        fun marked(): Boolean = runBlocking { journal(journalDir, RUNNER).scanAll() }
            .single { it.instanceId == DEAD }
            .reconciled

        fun endState(): EndState = EndState(
            worktreeOnDisk = Files.exists(worktree),
            branches = branches().sorted(),
            worktrees = worktreePaths().sorted(),
            journalMarked = marked(),
        )
    }

    private data class EndState(
        val worktreeOnDisk: Boolean,
        val branches: List<String>,
        val worktrees: List<String>,
        val journalMarked: Boolean,
    )

    private fun Crashed.reconciler(
        stopper: ProcessGroupStopper = StubStopper(),
        claims: ClaimRegistry? = StubClaims(),
        mergeRequests: MergeRequestLookup? = StubLookup(),
        git: GitRunner = ProcessGroupGitRunner,
        worktrees: WorktreeRepair? = WorktreeRepair(repo, git),
        eventApi: AgentEventApi? = null,
        releaseUnjournaledClaims: Boolean = false,
    ) = StartupReconciler(
        journal = journal(journalDir, RUNNER, eventApi),
        worktrees = worktrees,
        claims = claims,
        mergeRequests = mergeRequests,
        eventApi = eventApi,
        now = { recordedAt },
        idGenerator = { "pass-${nextId++}" },
        releaseUnjournaledClaims = releaseUnjournaledClaims,
        stopper = stopper,
    )

    /** A process group with two grandchildren, as an agent subprocess tree is. */
    private fun sleepingGroup(): GroupedProcess =
        ProcessGroups.start(ProcessBuilder("sh", "-c", "sleep 30 & sleep 30 & wait"))
            .also { spawned += it.address }

    private fun waitUntil(timeout: Duration = 10.seconds, condition: () -> Boolean) {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        while (!condition()) {
            check(deadline.hasNotPassedNow()) { "condition did not hold within $timeout" }
            Thread.sleep(20)
        }
    }

    // -----------------------------------------------------------------
    // 1. Journal scan and suspect classification
    // -----------------------------------------------------------------

    @Test
    fun `a journal with a clean shutdown marker contributes no suspect`() = runBlocking<Unit> {
        val dir = tempDir()
        journal(dir, DEAD).apply {
            append(record(TICKET))
            markCleanShutdown()
        }
        val claims = StubClaims()

        val outcome = StartupReconciler(
            journal = journal(dir, RUNNER),
            claims = claims,
            now = { recordedAt },
            idGenerator = { "pass-${nextId++}" },
        ).reconcile()

        assertEquals(ReconciliationSource.JOURNAL, outcome.source)
        assertTrue(outcome.dispositions.isEmpty())
        assertTrue(claims.released.isEmpty(), "a graceful exit released its own claims")
        assertTrue(outcome.isSettled)
    }

    @Test
    fun `a dispatch with no marker is suspect and a terminal one is not`() = runBlocking<Unit> {
        val dir = tempDir()
        journal(dir, DEAD).apply {
            append(record("AMPR-1"))
            append(record("AMPR-2", phase = DispatchPhase.DONE))
            append(record("AMPR-3", phase = DispatchPhase.ESCALATED))
        }

        val outcome = StartupReconciler(
            journal = journal(dir, RUNNER),
            claims = StubClaims(),
            now = { recordedAt },
            idGenerator = { "pass-${nextId++}" },
        ).reconcile()

        assertEquals(listOf("AMPR-1"), outcome.dispositions.map { it.ticketId })
    }

    @Test
    fun `a torn final record is reported and keeps its journal unmarked`() = runBlocking<Unit> {
        val dir = tempDir()
        val whole = journal(dir, DEAD).let { subject ->
            subject.append(record(TICKET))
            Files.readAllLines(dir.resolve("$DEAD.${DispatchJournal.EXTENSION}")).single()
        }
        Files.write(
            dir.resolve("$DEAD.${DispatchJournal.EXTENSION}"),
            "$whole\n${whole.dropLast(25)}\n".toByteArray(),
        )

        val outcome = StartupReconciler(
            journal = journal(dir, RUNNER),
            claims = StubClaims(),
            now = { recordedAt },
            idGenerator = { "pass-${nextId++}" },
        ).reconcile()

        assertEquals(1, outcome.quarantinedLines)
        assertFalse(outcome.isSettled, "a line nothing can parse may name an unenumerable claim")
        assertTrue(outcome.journalsSettled.isEmpty())
        assertTrue(outcome.notes.any { it.contains("could not be parsed") }, "${outcome.notes}")
    }

    @Test
    fun `the pass never reads its own journal`() = runBlocking<Unit> {
        // A live supervisor is not residue, and a pass that reaped its own dispatches
        // would kill the agents it is about to supervise.
        val dir = tempDir()
        journal(dir, RUNNER).append(record(TICKET, instanceId = RUNNER))
        journal(dir, DEAD).append(record("AMPR-500"))

        val outcome = StartupReconciler(
            journal = journal(dir, RUNNER),
            claims = StubClaims(),
            now = { recordedAt },
            idGenerator = { "pass-${nextId++}" },
        ).reconcile()

        assertEquals(ReconciliationSource.JOURNAL, outcome.source)
        assertEquals(listOf(DEAD), outcome.journalsRead)
        assertEquals(listOf("AMPR-500"), outcome.dispositions.map { it.ticketId })
    }

    // -----------------------------------------------------------------
    // 2. Orphan reaping
    // -----------------------------------------------------------------

    @Test
    fun `every orphan is reaped before any worktree is inspected`() = runBlocking<Unit> {
        // The ordering principle: an orphaned git child was observed finishing a
        // checkout after its parent was killed, so inspecting a worktree whose agent
        // may still be writing reads a moving target.
        val crashed = Crashed()
        val order = mutableListOf<String>()

        crashed.reconciler(
            stopper = StubStopper(log = order),
            git = GitRunner { directory, args ->
                order += "git ${args.first()}"
                ProcessGroupGitRunner.run(directory, args)
            },
        ).reconcile()

        val lastStop = order.indexOfLast { it.startsWith("stop") }
        val firstGit = order.indexOfFirst { it.startsWith("git") }
        assertTrue(lastStop in 0 until firstGit, "$order")
    }

    @Test
    fun `a group that outlives the kill leaves its worktree untouched`() = runBlocking<Unit> {
        val crashed = Crashed()

        val outcome = crashed.reconciler(
            stopper = StubStopper(default = TerminationOutcome.SURVIVED),
        ).reconcile()

        val disposition = outcome.dispositions.single()
        assertEquals(TerminationOutcome.SURVIVED, disposition.reaped)
        assertEquals(WorktreeVerdict.HELD_WRITER_ALIVE, disposition.worktree)
        assertEquals(BranchVerdict.KEPT_WORKTREE_HELD, disposition.branchVerdict)
        assertTrue(Files.exists(crashed.worktree), "nothing may touch a worktree with a live writer")
        assertFalse(outcome.isSettled)
        assertFalse(crashed.marked())
    }

    @Test
    fun `a recycled leader pid is never signalled and holds the worktree`() = runBlocking<Unit> {
        val crashed = Crashed()

        val outcome = crashed.reconciler(
            stopper = StubStopper(default = TerminationOutcome.REFUSED_ADDRESS_REUSED),
        ).reconcile()

        assertEquals(WorktreeVerdict.HELD_WRITER_ALIVE, outcome.dispositions.single().worktree)
        assertTrue(outcome.dispositions.single().notes.any { it.contains("another process") })
    }

    @Test
    fun `a dispatch that never spawned an agent is reaped vacuously`() = runBlocking<Unit> {
        val crashed = Crashed(address = null)

        val outcome = crashed.reconciler().reconcile()

        assertNull(outcome.dispositions.single().reaped)
        assertEquals(WorktreeVerdict.REMOVED, outcome.dispositions.single().worktree)
    }

    @Test
    fun `a real process group is reaped from its recorded address alone`() = runBlocking<Unit> {
        // Validation for step 2 in full: a live group, a supervisor that is gone, and
        // a new pass holding nothing but what the journal recorded.
        if (!POSIX) return@runBlocking
        val root = tempDir()
        val grouped = sleepingGroup()
        val leader = ProcessHandle.of(grouped.address.leaderPid).orElseThrow()
        waitUntil { leader.descendants().count() >= 2L }
        val grandchildren = leader.descendants().toList()

        journal(root, DEAD).append(
            record(TICKET, address = grouped.address),
        )

        val outcome = StartupReconciler(
            journal = journal(root, RUNNER),
            claims = StubClaims(),
            now = { recordedAt },
            idGenerator = { "pass-${nextId++}" },
        ).reconcile()

        val reaped = assertNotNull(outcome.dispositions.single().reaped)
        assertTrue(
            reaped == TerminationOutcome.TERMINATED || reaped == TerminationOutcome.KILLED,
            "$reaped",
        )
        waitUntil { grandchildren.none { it.isAlive } }
        assertTrue(grandchildren.isNotEmpty())
        assertFalse(grandchildren.any { it.isAlive }, "the group's grandchildren outlived the reap")
    }

    // -----------------------------------------------------------------
    // 3. Worktree and branch repair
    // -----------------------------------------------------------------

    @Test
    fun `a clean worktree is removed and its orphan branch deleted`() = runBlocking<Unit> {
        val crashed = Crashed()

        val outcome = crashed.reconciler().reconcile()

        val disposition = outcome.dispositions.single()
        assertEquals(WorktreeVerdict.REMOVED, disposition.worktree)
        assertEquals(BranchVerdict.DELETED, disposition.branchVerdict)
        assertFalse(Files.exists(crashed.worktree))
        assertFalse(crashed.branch in crashed.branches())
        assertTrue(outcome.isSettled)
        assertTrue(crashed.marked())
    }

    @Test
    fun `a stale index lock is cleared before the worktree is judged`() = runBlocking<Unit> {
        val crashed = Crashed(
            configure = { worktree -> Files.createFile(GitFixture.gitDirOf(worktree).resolve("index.lock")) },
        )

        val outcome = crashed.reconciler().reconcile()

        val disposition = outcome.dispositions.single()
        assertTrue(disposition.notes.any { it.contains("index.lock") }, "${disposition.notes}")
        assertEquals(WorktreeVerdict.REMOVED, disposition.worktree)
    }

    @Test
    fun `a worktree locked initializing is force removed`() = runBlocking<Unit> {
        val crashed = Crashed()
        GitFixture.git(crashed.repo, "worktree", "lock", "--reason", "initializing", crashed.worktree.toString())

        val outcome = crashed.reconciler().reconcile()

        assertEquals(WorktreeVerdict.FORCE_REMOVED, outcome.dispositions.single().worktree)
        assertFalse(Files.exists(crashed.worktree))
        assertTrue(outcome.isSettled)
    }

    @Test
    fun `an uncommitted worktree is held and reported rather than deleted`() = runBlocking<Unit> {
        val crashed = Crashed(
            configure = { worktree -> Files.writeString(worktree.resolve("scratch.txt"), "half an idea\n") },
        )

        val outcome = crashed.reconciler().reconcile()

        val disposition = outcome.dispositions.single()
        assertEquals(WorktreeVerdict.HELD_UNCOMMITTED, disposition.worktree)
        assertEquals(BranchVerdict.KEPT_WORKTREE_HELD, disposition.branchVerdict)
        assertTrue(Files.exists(crashed.worktree.resolve("scratch.txt")))
        assertTrue(crashed.branch in crashed.branches())
        assertFalse(outcome.isSettled)
        assertFalse(crashed.marked(), "a hold has to be reported again next time")
    }

    @Test
    fun `an unpushed worktree is held`() = runBlocking<Unit> {
        val crashed = Crashed(configure = { GitFixture.commitUnpushed(it) })

        val outcome = crashed.reconciler().reconcile()

        assertEquals(WorktreeVerdict.HELD_UNPUSHED, outcome.dispositions.single().worktree)
        assertTrue(Files.exists(crashed.worktree))
    }

    @Test
    fun `a path outside this repository is reported and never deleted`() = runBlocking<Unit> {
        val crashed = Crashed()
        val foreign = crashed.root.resolve("not-a-worktree")
        Files.createDirectories(foreign)
        Files.writeString(foreign.resolve("keep.txt"), "someone else's\n")
        journal(crashed.journalDir, OTHER).append(
            record(
                "AMPR-999",
                instanceId = OTHER,
                worktreePath = foreign.toString(),
                branchName = "",
            ),
        )

        val outcome = crashed.reconciler().reconcile()

        val disposition = outcome.dispositions.single { it.ticketId == "AMPR-999" }
        assertEquals(WorktreeVerdict.OUTSIDE_REPOSITORY, disposition.worktree)
        assertTrue(Files.exists(foreign.resolve("keep.txt")))
    }

    @Test
    fun `with no repository the local verdicts stay unsettled`() = runBlocking<Unit> {
        val crashed = Crashed()

        val outcome = crashed.reconciler(worktrees = null, mergeRequests = null).reconcile()

        val disposition = outcome.dispositions.single()
        assertEquals(WorktreeVerdict.NOT_INSPECTED, disposition.worktree)
        assertEquals(BranchVerdict.NOT_INSPECTED, disposition.branchVerdict)
        assertFalse(outcome.isSettled)
        assertTrue(Files.exists(crashed.worktree))
    }

    @Test
    fun `a dispatch that died before creating anything settles with nothing to repair`() = runBlocking<Unit> {
        val crashed = Crashed()
        journal(crashed.journalDir, OTHER).append(
            record("AMPR-998", instanceId = OTHER, phase = DispatchPhase.CLAIMED),
        )

        val outcome = crashed.reconciler().reconcile()

        val disposition = outcome.dispositions.single { it.ticketId == "AMPR-998" }
        assertEquals(WorktreeVerdict.NONE_RECORDED, disposition.worktree)
        assertEquals(BranchVerdict.NONE_RECORDED, disposition.branchVerdict)
        assertTrue(disposition.isSettled)
    }

    // -----------------------------------------------------------------
    // 5. Merge requests, by query
    // -----------------------------------------------------------------

    @Test
    fun `an open merge request keeps the branch and forbids a second one`() = runBlocking<Unit> {
        val crashed = Crashed()
        val lookup = StubLookup(
            mapOf(
                crashed.branch to Result.success(
                    listOf(MergeRequestRef("https://example.invalid/pull/7", 7, MergeRequestState.OPEN)),
                ),
            ),
        )

        val outcome = crashed.reconciler(mergeRequests = lookup).reconcile()

        val disposition = outcome.dispositions.single()
        assertEquals(listOf(crashed.branch), lookup.asked)
        assertEquals(BranchVerdict.KEPT_MERGE_REQUEST, disposition.branchVerdict)
        assertEquals(false, disposition.mayOpenMergeRequest)
        assertEquals(7, disposition.mergeRequest?.number)
        assertTrue(crashed.branch in crashed.branches(), "the review owns the branch")
    }

    @Test
    fun `a merged request still forbids a second one`() = runBlocking<Unit> {
        // Creation is not idempotent, so a closed or merged request is still proof it
        // happened — even though only an open one owns the branch.
        val crashed = Crashed()
        val lookup = StubLookup(
            mapOf(
                crashed.branch to Result.success(
                    listOf(MergeRequestRef("https://example.invalid/pull/8", 8, MergeRequestState.MERGED)),
                ),
            ),
        )

        val outcome = crashed.reconciler(mergeRequests = lookup).reconcile()

        val disposition = outcome.dispositions.single()
        assertEquals(false, disposition.mayOpenMergeRequest)
        assertEquals(BranchVerdict.DELETED, disposition.branchVerdict)
    }

    @Test
    fun `no merge request permits creation and deletes the branch`() = runBlocking<Unit> {
        val crashed = Crashed()

        val outcome = crashed.reconciler().reconcile()

        val disposition = outcome.dispositions.single()
        assertEquals(true, disposition.mayOpenMergeRequest)
        assertNull(disposition.mergeRequest)
        assertEquals(BranchVerdict.DELETED, disposition.branchVerdict)
    }

    @Test
    fun `a forge that does not answer keeps the branch`() = runBlocking<Unit> {
        // "We could not ask" must never read as "there is no merge request": that is
        // the one direction that deletes a live review's branch.
        val crashed = Crashed()
        val lookup = StubLookup(
            mapOf(crashed.branch to Result.failure(IllegalStateException("gh is not authenticated"))),
        )

        val outcome = crashed.reconciler(mergeRequests = lookup).reconcile()

        val disposition = outcome.dispositions.single()
        assertEquals(BranchVerdict.FAILED, disposition.branchVerdict)
        assertNull(disposition.mayOpenMergeRequest)
        assertTrue(crashed.branch in crashed.branches())
        assertFalse(outcome.isSettled)
    }

    @Test
    fun `with no forge lookup the branch is kept`() = runBlocking<Unit> {
        val crashed = Crashed()

        val outcome = crashed.reconciler(mergeRequests = null).reconcile()

        assertEquals(BranchVerdict.NOT_INSPECTED, outcome.dispositions.single().branchVerdict)
        assertTrue(crashed.branch in crashed.branches())
    }

    // -----------------------------------------------------------------
    // 4 and 6. The work source, last
    // -----------------------------------------------------------------

    @Test
    fun `a released claim requeues the ticket and settles the journal`() = runBlocking<Unit> {
        val crashed = Crashed()
        val claims = StubClaims()

        val outcome = crashed.reconciler(claims = claims).reconcile()

        assertEquals(listOf(TICKET to DEAD), claims.released)
        val disposition = outcome.dispositions.single()
        assertEquals(ClaimVerdict.RELEASED, disposition.claim)
        assertTrue(disposition.notes.any { it.contains("reverted to 'Todo'") }, "${disposition.notes}")
        assertTrue(crashed.marked())
    }

    @Test
    fun `the work source is written only after the local repair`() = runBlocking<Unit> {
        // Repair local before shared: a claim released while a worktree is still
        // being judged can be re-dispatched into a repository mid-surgery.
        val crashed = Crashed()
        val order = mutableListOf<String>()

        crashed.reconciler(
            claims = StubClaims(log = order),
            git = GitRunner { directory, args ->
                order += "git ${args.first()}"
                ProcessGroupGitRunner.run(directory, args)
            },
        ).reconcile()

        val lastGit = order.indexOfLast { it.startsWith("git") }
        val firstRelease = order.indexOfFirst { it.startsWith("release") }
        assertTrue(lastGit in 0 until firstRelease, "$order")
    }

    @Test
    fun `an already released claim is settled without a second write`() = runBlocking<Unit> {
        val crashed = Crashed()
        val claims = StubClaims(
            responses = mapOf(TICKET to Result.success(ClaimRelease.Released(commentPosted = false))),
        )

        val outcome = crashed.reconciler(claims = claims).reconcile()

        assertEquals(ClaimVerdict.ALREADY_RELEASED, outcome.dispositions.single().claim)
        assertTrue(outcome.isSettled)
    }

    @Test
    fun `a human who moved the ticket is deferred to and the journal stays open`() = runBlocking<Unit> {
        val crashed = Crashed()
        val claims = StubClaims(
            responses = mapOf(
                TICKET to Result.success(
                    ClaimRelease.Deferred(
                        observedState = "Done",
                        reason = "'Done' is not a state a dispatch sits in",
                        commentPosted = true,
                    ),
                ),
            ),
        )

        val outcome = crashed.reconciler(claims = claims).reconcile()

        val disposition = outcome.dispositions.single()
        assertEquals(ClaimVerdict.DEFERRED_TO_HUMAN, disposition.claim)
        assertTrue(disposition.notes.any { it.contains("deferred to the human state 'Done'") })
        assertFalse(outcome.isSettled)
        assertFalse(crashed.marked())
        // The local half still happened: deference is about the ticket, not the disk.
        assertEquals(WorktreeVerdict.REMOVED, disposition.worktree)
    }

    @Test
    fun `a claim another instance holds settles without moving the ticket`() = runBlocking<Unit> {
        val crashed = Crashed()
        val claims = StubClaims(
            responses = mapOf(TICKET to Result.success(ClaimRelease.NotHeld(liveHolder = "supervisor-live"))),
        )

        val outcome = crashed.reconciler(claims = claims).reconcile()

        val disposition = outcome.dispositions.single()
        assertEquals(ClaimVerdict.HELD_BY_ANOTHER_INSTANCE, disposition.claim)
        assertTrue(disposition.notes.any { it.contains("supervisor-live") })
        assertTrue(outcome.isSettled)
    }

    @Test
    fun `with no work source the claim is reported unreachable rather than settled`() = runBlocking<Unit> {
        val crashed = Crashed()

        val outcome = crashed.reconciler(claims = null).reconcile()

        assertEquals(ClaimVerdict.SOURCE_UNAVAILABLE, outcome.dispositions.single().claim)
        assertFalse(outcome.isSettled, "the claim is still held and no timeout will clear it")
        assertFalse(crashed.marked())
    }

    @Test
    fun `a work source that fails is reported and holds the journal open`() = runBlocking<Unit> {
        val crashed = Crashed()
        val claims = StubClaims(responses = mapOf(TICKET to Result.failure(IllegalStateException("mcp timeout"))))

        val outcome = crashed.reconciler(claims = claims).reconcile()

        val disposition = outcome.dispositions.single()
        assertEquals(ClaimVerdict.FAILED, disposition.claim)
        assertTrue(disposition.notes.any { it.contains("mcp timeout") })
        assertFalse(crashed.marked())
    }

    // -----------------------------------------------------------------
    // 7. Idempotency
    // -----------------------------------------------------------------

    @Test
    fun `a settled journal is not reconciled twice`() = runBlocking<Unit> {
        val crashed = Crashed()
        crashed.reconciler().reconcile()
        val claims = StubClaims()

        val second = crashed.reconciler(claims = claims).reconcile()

        assertTrue(second.dispositions.isEmpty(), "${second.dispositions}")
        assertTrue(claims.released.isEmpty(), "a marked journal costs no work-source reads")
        assertEquals(listOf(DEAD), second.journalsRead)
    }

    @Test
    fun `a pass interrupted before the work source finishes on the next run`() = runBlocking<Unit> {
        // The validation the specification asks for: kill the pass mid-way, run it
        // again, and the end state matches a run that was never interrupted.
        val uninterrupted = Crashed()
        uninterrupted.reconciler().reconcile()

        val interrupted = Crashed()
        interrupted.reconciler(
            claims = StubClaims(responses = mapOf(TICKET to Result.failure(IllegalStateException("killed")))),
        ).reconcile()
        assertFalse(interrupted.marked(), "an unfinished pass must not seal the journal")

        val resumed = interrupted.reconciler().reconcile()

        assertEquals(uninterrupted.endState(), interrupted.endState())
        assertTrue(resumed.isSettled)
        // The second run finds the local work already done rather than redoing it.
        assertEquals(WorktreeVerdict.ABSENT, resumed.dispositions.single().worktree)
        assertEquals(BranchVerdict.ABSENT, resumed.dispositions.single().branchVerdict)
        assertEquals(ClaimVerdict.RELEASED, resumed.dispositions.single().claim)
    }

    @Test
    fun `a held dispatch is reported again on every pass`() = runBlocking<Unit> {
        val crashed = Crashed(
            configure = { worktree -> Files.writeString(worktree.resolve("scratch.txt"), "work\n") },
        )

        val first = crashed.reconciler().reconcile()
        val second = crashed.reconciler().reconcile()

        assertEquals(first.dispositions.map { it.worktree }, second.dispositions.map { it.worktree })
        assertEquals(WorktreeVerdict.HELD_UNCOMMITTED, second.dispositions.single().worktree)
        assertTrue(Files.exists(crashed.worktree.resolve("scratch.txt")))
    }

    // -----------------------------------------------------------------
    // The cold-start fallback
    // -----------------------------------------------------------------

    @Test
    fun `no journal and no work source is reported as no evidence`() = runBlocking<Unit> {
        val outcome = StartupReconciler(
            journal = journal(tempDir(), RUNNER),
            now = { recordedAt },
            idGenerator = { "pass-${nextId++}" },
        ).reconcile()

        assertEquals(ReconciliationSource.NO_EVIDENCE, outcome.source)
        assertTrue(outcome.notes.any { it.contains("nothing to read") })
    }

    @Test
    fun `the cold start fallback reports claimed tickets without touching them`() = runBlocking<Unit> {
        // A claim comment proves a supervisor took a ticket, not that it died — and a
        // supervisor running right now on another host would have exactly this shape.
        val claims = StubClaims(
            scan = Result.success(
                ClaimScan(
                    tickets = listOf(ClaimedTicket("AMPR-400", listOf("supervisor-unknown"), "In Progress")),
                ),
            ),
        )

        val outcome = StartupReconciler(
            journal = journal(tempDir(), RUNNER),
            claims = claims,
            now = { recordedAt },
            idGenerator = { "pass-${nextId++}" },
        ).reconcile()

        assertEquals(ReconciliationSource.DEGRADED_CLAIM_SCAN, outcome.source)
        assertTrue(outcome.notes.any { it.contains("degraded") })
        val disposition = outcome.dispositions.single()
        assertEquals("AMPR-400", disposition.ticketId)
        assertEquals("supervisor-unknown", disposition.supervisorInstanceId)
        assertEquals(ClaimVerdict.REPORTED_ONLY, disposition.claim)
        assertTrue(claims.released.isEmpty(), "a scan may not license a write")
        assertFalse(outcome.isSettled)
    }

    @Test
    fun `the cold start fallback releases when the operator asks for it`() = runBlocking<Unit> {
        val claims = StubClaims(
            scan = Result.success(
                ClaimScan(tickets = listOf(ClaimedTicket("AMPR-400", listOf("supervisor-gone"), "In Progress"))),
            ),
        )

        val outcome = StartupReconciler(
            journal = journal(tempDir(), RUNNER),
            claims = claims,
            now = { recordedAt },
            idGenerator = { "pass-${nextId++}" },
            releaseUnjournaledClaims = true,
        ).reconcile()

        assertEquals(listOf("AMPR-400" to "supervisor-gone"), claims.released)
        assertEquals(ClaimVerdict.RELEASED, outcome.dispositions.single().claim)
    }

    @Test
    fun `an incomplete claim scan says so`() = runBlocking<Unit> {
        val claims = StubClaims(
            scan = Result.success(ClaimScan(incomplete = listOf("could not read the comments of AMPR-401"))),
        )

        val outcome = StartupReconciler(
            journal = journal(tempDir(), RUNNER),
            claims = claims,
            now = { recordedAt },
            idGenerator = { "pass-${nextId++}" },
        ).reconcile()

        assertTrue(outcome.notes.any { it.contains("may be incomplete") }, "${outcome.notes}")
    }

    // -----------------------------------------------------------------
    // Stubs for the two edges the pass does not own
    // -----------------------------------------------------------------

    /** A process-group stopper that answers from a script and records what it stopped. */
    private class StubStopper(
        private val outcomes: Map<Long?, TerminationOutcome> = emptyMap(),
        private val default: TerminationOutcome = TerminationOutcome.TERMINATED,
        private val log: MutableList<String>? = null,
    ) : ProcessGroupStopper {

        val stopped: MutableList<CancellationAddress> = mutableListOf()

        override fun stop(address: CancellationAddress, grace: Duration): TerminationOutcome {
            log?.add("stop:${address.processGroupId}")
            val repeat = address in stopped
            stopped += address
            // A group stopped once is gone the second time, which is what makes
            // re-running the pass free rather than a second round of signals.
            return if (repeat) {
                TerminationOutcome.ALREADY_GONE
            } else {
                outcomes[address.processGroupId] ?: default
            }
        }
    }

    /** A work source that answers from a script and records every release. */
    private class StubClaims(
        private val responses: Map<String, Result<ClaimRelease>> = emptyMap(),
        private val scan: Result<ClaimScan> = Result.success(ClaimScan()),
        private val log: MutableList<String>? = null,
    ) : ClaimRegistry {

        val released: MutableList<Pair<String, String>> = mutableListOf()

        override suspend fun releaseClaim(ticketId: String, heldBy: String): Result<ClaimRelease> {
            log?.add("release:$ticketId")
            val answer = responses[ticketId]
                ?: Result.success(ClaimRelease.Released(commentPosted = true, revertedTo = "Todo"))
            if (answer.isSuccess) released += ticketId to heldBy
            return answer
        }

        override suspend fun claimedTickets(): Result<ClaimScan> = scan
    }

    /** A forge that answers from a script and records every branch it was asked about. */
    private class StubLookup(
        private val byBranch: Map<String, Result<List<MergeRequestRef>>> = emptyMap(),
    ) : MergeRequestLookup {

        val asked: MutableList<String> = mutableListOf()

        override suspend fun forHeadBranch(branch: String): Result<List<MergeRequestRef>> {
            asked += branch
            return byBranch[branch] ?: Result.success(emptyList())
        }
    }

    private companion object {

        const val TICKET = "AMPR-310"
        const val DEAD = "supervisor-dead"
        const val OTHER = "supervisor-other"
        const val RUNNER = "reconcile-1"

        val POSIX: Boolean = File.separatorChar == '/'
    }
}
