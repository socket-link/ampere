package link.socket.ampere.agents.execution.dispatch

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import link.socket.ampere.agents.execution.dispatch.FakeDispatchWorkSource.Companion.GATE_AWAITING_VERDICT
import link.socket.ampere.agents.execution.dispatch.FakeDispatchWorkSource.Companion.RESEARCH
import link.socket.ampere.agents.execution.dispatch.FakeDispatchWorkSource.Companion.ticket
import link.socket.ampere.agents.execution.dispatch.FakeDispatchWorkSource.Write
import link.socket.ampere.agents.execution.process.CancellationAddress
import link.socket.ampere.agents.execution.process.ProcessGroups
import link.socket.ampere.agents.execution.verify.Baseline
import link.socket.ampere.agents.execution.verify.GateOutcome
import link.socket.ampere.agents.execution.verify.GateRunner
import link.socket.ampere.agents.execution.verify.GateStep
import link.socket.ampere.agents.execution.verify.ManifestGate
import link.socket.ampere.agents.execution.verify.ManifestSource
import link.socket.ampere.agents.execution.verify.Precondition
import link.socket.ampere.agents.execution.verify.PreconditionCheck
import link.socket.ampere.agents.execution.verify.PreconditionChecker
import link.socket.ampere.agents.execution.verify.PreconditionPredicate
import link.socket.ampere.agents.execution.verify.SideEffects
import link.socket.ampere.agents.execution.verify.VerifyManifest

/**
 * The dispatch pipeline end to end, short of a real work source and a real agent
 * (AMPR-308, tasks 2–5): a real repository, a real journal, real process groups, and
 * a shell script standing in for the agent.
 *
 * Each test drives one way a dispatch can end and checks the three records that have
 * to agree about it: the journal's phase sequence, the writes the work source
 * received, and what is left running on the machine.
 */
class SwitchboardTest {

    private val posix = File.separatorChar == '/'
    private val temporary = mutableListOf<Path>()
    private val addresses = CopyOnWriteArrayList<CancellationAddress>()

    @AfterTest
    fun tearDown() {
        addresses.forEach { ProcessGroups.terminate(it, grace = 200.milliseconds) }
        temporary.forEach { it.toFile().deleteRecursively() }
    }

    /** One dispatch environment: a repository, the Switchboard's directories, and a probe directory for the agent. */
    private inner class Harness {
        val root: Path = Files.createTempDirectory("ampere-switchboard").toRealPath().also { temporary.add(it) }
        val repo: Path = GitFixture.repository(root)
        val journalDir: Path = root.resolve("journal")
        val worktreeRoot: Path = root.resolve("worktrees")
        val logs: Path = root.resolve("logs")
        val probe: Path = Files.createDirectories(root.resolve("probe"))
        val source = FakeDispatchWorkSource()
        val git = RecordingGit()
        val notes = CopyOnWriteArrayList<String>()
        val dispatched = CompletableDeferred<CancellationAddress>()

        val journalFile: Path get() = journalDir.resolve("$INSTANCE.${DispatchJournal.EXTENSION}")

        fun switchboard(
            agent: String,
            mergeRequests: MergeRequestLookup? = RecordingLookup.open(),
            silenceLimit: Duration = 10.minutes,
            verification: SwitchboardVerification? = verification(gate("trivial", "true")),
            maxRepairAttempts: Int = SwitchboardConfig.DEFAULT_MAX_REPAIR_ATTEMPTS,
        ): Switchboard = Switchboard(
            config = SwitchboardConfig(
                wave = "w1",
                logDirectory = logs,
                repositorySlug = FakeDispatchWorkSource.REPOSITORY,
                shutdownGrace = 2.seconds,
                maxRepairAttempts = maxRepairAttempts,
            ),
            workSource = source,
            journal = DispatchJournal.open(journalDir, INSTANCE),
            worktrees = WorktreeProvisioner(repository = repo, root = worktreeRoot, base = "main", git = git),
            launcher = AgentLauncher(
                AgentLaunchSpec(
                    command = listOf("/bin/sh", "-c", agent, "agent", "{model}", "{effort}", "{context}", "{ticket}"),
                    environment = mapOf("PROBE" to probe.toString(), "JOURNAL" to journalFile.toString()),
                ),
            ),
            supervisor = RunSupervisor(silenceLimit = silenceLimit, pollInterval = 50.milliseconds, grace = 2.seconds),
            mergeRequests = mergeRequests,
            verification = verification,
            listener = object : SwitchboardListener {
                override fun onNote(message: String) {
                    notes += message
                }

                override fun onDispatched(
                    ticketId: String,
                    plan: WorktreePlan,
                    model: ModelChoice,
                    address: CancellationAddress,
                ) {
                    addresses += address
                    dispatched.complete(address)
                }
            },
        )

        fun journal(): JournalRead = DispatchJournal.parse(INSTANCE, Files.readAllLines(journalFile))

        fun phases(ticketId: String): List<DispatchPhase> =
            journal().entries.filter { it.ticketId == ticketId }.map { it.phase }

        fun worktree(ticketId: String): Path = worktreeRoot.resolve(ticketId.lowercase())

        /** How many times the agent ran, when its script appends to `$PROBE/runs`. */
        fun agentRuns(): Int = probe.resolve("runs").let { if (Files.exists(it)) Files.readAllLines(it).size else 0 }
    }

    /** Verification over [gates], with every precondition present unless [absent] says otherwise. */
    private fun verification(
        vararg gates: ManifestGate,
        absent: Set<String> = emptySet(),
        manifest: ManifestSource? = null,
    ): SwitchboardVerification = SwitchboardVerification(
        manifests = manifest ?: object : ManifestSource {
            override suspend fun load(worktree: Path): Result<VerifyManifest> = Result.success(
                VerifyManifest(
                    version = 1,
                    requires = listOf(Precondition("jdk-21", PreconditionPredicate.Toolchain("21"))),
                    optional = emptyList(),
                    gates = gates.toList(),
                ),
            )
        },
        runner = GateRunner(
            preconditions = object : PreconditionChecker {
                override suspend fun check(precondition: Precondition, root: Path): PreconditionCheck =
                    if (precondition.id in absent) {
                        PreconditionCheck.Absent("${precondition.id} is not on this host")
                    } else {
                        PreconditionCheck.Satisfied
                    }
            },
            environment = System.getenv(),
            grace = 500.milliseconds,
            pollInterval = 20.milliseconds,
        ),
    )

    private fun gate(id: String, run: String) = ManifestGate(
        id = id,
        steps = listOf(GateStep(id = null, run = run)),
        sequenced = false,
        fresh = false,
        timeout = 1.minutes,
        retrySafe = true,
        sideEffects = SideEffects.BUILD_DIR,
        parallelSafe = false,
        requires = listOf("jdk-21"),
        needs = emptyList(),
        baseline = Baseline.CI,
    )

    /** A gate that passes once the agent has written `ok` into its worktree, printing a Gradle anchor otherwise. */
    private val okGate = gate(
        "app-test",
        "sh -c 'if [ -f ok ]; then exit 0; fi; echo \"> Task :app:test FAILED\"; exit 1'",
    )

    private fun runOnce(switchboard: Switchboard): SwitchboardReport = runBlocking {
        withTimeout(60.seconds) { switchboard.run(watch = false) }
    }

    @Test
    fun `a ready ticket runs from claim to a merge request, journaled at every phase`() {
        if (!posix) return
        val h = Harness()
        h.source.add(ticket("AMPR-1"))
        val lookup = RecordingLookup.open()

        val report = runOnce(h.switchboard(agent = "echo '{\"type\":\"result\"}'", mergeRequests = lookup))

        assertEquals(
            listOf(
                DispatchPhase.CLAIMED,
                DispatchPhase.WORKTREE_CREATED,
                DispatchPhase.AGENT_RUNNING,
                DispatchPhase.VERIFYING,
                DispatchPhase.MR_OPENED,
                DispatchPhase.DONE,
            ),
            h.phases("AMPR-1").distinct(),
        )
        assertEquals(RecordingLookup.URL, h.journal().entries.last().mergeRequestUrl)
        assertEquals(Write.Claim("AMPR-1"), h.source.writes.first())
        val surfaced = assertIs<Write.Surface>(h.source.writes.last())
        assertEquals(RecordingLookup.URL, surfaced.mergeRequestUrl)
        assertEquals(2, h.source.writes.size, "a verified run with a PR writes only the claim and the surfacing")
        assertEquals(listOf("miley/ampr-1-fixture"), lookup.asked, "settled by asking the forge about the branch")

        val context = h.worktree("AMPR-1").resolve(".context/attachments/linear-uuid-AMPR-1/[LINEAR]-AMPR-1.md")
        assertTrue(Files.isRegularFile(context), "the ticket must be where the operator's tooling puts it")
        assertTrue("```ampere-scope" in Files.readString(context))

        assertTrue(h.journal().cleanShutdown)
        assertTrue(report.cleanShutdown)
        assertEquals(mapOf("AMPR-1" to DispatchPhase.DONE), report.settled)
        assertTrue("result" in Files.readString(h.logs.resolve(INSTANCE).resolve("ampr-1.log")))

        // Merge is human-only, and so is every shared code write.
        val gitVerbs = h.git.commands.map { it.first() }.toSet()
        assertTrue(gitVerbs.none { it in setOf("push", "merge", "commit", "rebase", "reset") }, "git run: $gitVerbs")
    }

    @Test
    fun `the agent runs pinned to its worktree with the ticket's model and its group journaled before it starts`() {
        if (!posix) return
        val h = Harness()
        h.source.add(ticket("AMPR-1"))

        runOnce(
            h.switchboard(
                agent = """
                    pwd -P > "${'$'}PROBE/pwd"
                    printf '%s' "${'$'}PWD" > "${'$'}PROBE/env-pwd"
                    printf '%s|%s|%s|%s' "${'$'}1" "${'$'}2" "${'$'}3" "${'$'}4" > "${'$'}PROBE/args"
                    ps -o pgid= -p ${'$'}${'$'} | tr -d ' ' > "${'$'}PROBE/pgid"
                    cat "${'$'}JOURNAL" > "${'$'}PROBE/journal-at-start"
                """.trimIndent(),
            ),
        )

        val worktree = h.worktree("AMPR-1")
        assertEquals(worktree.toRealPath().toString(), Files.readString(h.probe.resolve("pwd")).trim())
        assertEquals(worktree.toString(), Files.readString(h.probe.resolve("env-pwd")))
        assertEquals(
            "sonnet|low|.context/attachments/linear-uuid-AMPR-1/[LINEAR]-AMPR-1.md|AMPR-1",
            Files.readString(h.probe.resolve("args")),
        )

        // The agent copied the journal as its first act: the record of its own process
        // group was already on disk before it could do anything.
        val atStart = DispatchJournal.parse(INSTANCE, Files.readAllLines(h.probe.resolve("journal-at-start")))
        val running = atStart.entries.last()
        assertEquals(DispatchPhase.AGENT_RUNNING, running.phase)
        assertEquals(Files.readString(h.probe.resolve("pgid")).trim().toLong(), running.agentAddress?.processGroupId)
    }

    @Test
    fun `a run silent past the limit is group-killed and escalated`() {
        if (!posix) return
        val h = Harness()
        h.source.add(ticket("AMPR-1"))

        val report = runOnce(h.switchboard(agent = "echo started; exec sleep 60", silenceLimit = 600.milliseconds))

        val address = assertNotNull(h.journal().entries.last { it.agentAddress != null }.agentAddress)
        assertFalse(ProcessGroups.isAlive(address), "the hung run's whole group must be gone")
        assertEquals(DispatchPhase.ESCALATED, h.phases("AMPR-1").last())

        val escalation = assertIs<Write.Escalate>(h.source.writes.last())
        assertTrue("no output for 600ms" in escalation.reason, escalation.reason)
        assertTrue("Needs:" in escalation.reason)
        assertFalse(h.root.toString() in escalation.reason, "an escalation is public and must not name a local path")
        assertEquals(mapOf("AMPR-1" to DispatchPhase.ESCALATED), report.settled)
        assertTrue(report.cleanShutdown)
    }

    @Test
    fun `a research ticket ends at the verdict gate and an escalation, never at a merge request`() {
        if (!posix) return
        val h = Harness()
        h.source.add(ticket("AMPR-1", labels = setOf("wave:w1", RESEARCH)))
        val lookup = RecordingLookup.open()

        val report = runOnce(h.switchboard(agent = "echo findings posted", mergeRequests = lookup))

        assertEquals(
            listOf(Write.Claim("AMPR-1"), Write.RequestVerdict("AMPR-1")),
            h.source.writes.take(2),
        )
        val escalation = assertIs<Write.Escalate>(h.source.writes.last())
        assertTrue("human verdict" in escalation.reason)
        assertEquals(3, h.source.writes.size)
        assertTrue(lookup.asked.isEmpty(), "a research ticket is never settled by a merge request")
        assertEquals(DispatchPhase.ESCALATED, h.phases("AMPR-1").last())
        assertFalse(DispatchPhase.DONE in h.phases("AMPR-1"))
        assertEquals(mapOf("AMPR-1" to DispatchPhase.ESCALATED), report.settled)
    }

    @Test
    fun `a gated ticket is never claimed`() {
        if (!posix) return
        val h = Harness()
        h.source.add(ticket("AMPR-1", labels = setOf("wave:w1", GATE_AWAITING_VERDICT)))

        val report = runOnce(h.switchboard(agent = "echo should not run"))

        assertTrue(h.source.writes.isEmpty(), "nothing may be written for a gated ticket")
        assertEquals(RefusalReason.GATED, report.refused.single().reason)
        assertTrue(report.dispatched.isEmpty())
        assertFalse(Files.exists(h.worktree("AMPR-1")))
    }

    @Test
    fun `a lost claim leaves no record and no worktree`() {
        if (!posix) return
        val h = Harness()
        h.source.add(ticket("AMPR-1"))
        h.source.claimOutcome = { DispatchClaim.Lost(winner = "switchboard-other") }

        val report = runOnce(h.switchboard(agent = "echo should not run"))

        assertEquals(listOf<Write>(Write.Claim("AMPR-1")), h.source.writes)
        assertTrue(h.journal().entries.isEmpty())
        assertFalse(Files.exists(h.worktree("AMPR-1")))
        assertTrue(report.cleanShutdown)
    }

    @Test
    fun `a clean exit without a merge request is escalated, not marked done`() {
        if (!posix) return
        val h = Harness()
        h.source.add(ticket("AMPR-1"))

        runOnce(h.switchboard(agent = "true", mergeRequests = RecordingLookup { Result.success(emptyList()) }))

        val escalation = assertIs<Write.Escalate>(h.source.writes.last())
        assertTrue("no open pull request" in escalation.reason, escalation.reason)
        assertEquals(DispatchPhase.ESCALATED, h.phases("AMPR-1").last())
    }

    @Test
    fun `a lookup that does not answer escalates rather than guessing`() {
        if (!posix) return
        val h = Harness()
        h.source.add(ticket("AMPR-1"))

        runOnce(
            h.switchboard(
                agent = "true",
                mergeRequests = RecordingLookup { Result.failure(IllegalStateException("gh is not authenticated")) },
            ),
        )

        val escalation = assertIs<Write.Escalate>(h.source.writes.last())
        assertTrue("could not confirm" in escalation.reason, escalation.reason)
    }

    @Test
    fun `a failed run is escalated with its exit status`() {
        if (!posix) return
        val h = Harness()
        h.source.add(ticket("AMPR-1"))

        runOnce(h.switchboard(agent = "exit 3"))

        val escalation = assertIs<Write.Escalate>(h.source.writes.last())
        assertTrue("status 3" in escalation.reason, escalation.reason)
    }

    @Test
    fun `a worktree that cannot be created is escalated and no agent is launched`() {
        if (!posix) return
        val h = Harness()
        h.source.add(ticket("AMPR-1"))
        GitFixture.git(h.repo, "branch", "miley/ampr-1-fixture")

        runOnce(h.switchboard(agent = "touch \"\$PROBE/ran\""))

        assertEquals(listOf(DispatchPhase.CLAIMED, DispatchPhase.ESCALATED), h.phases("AMPR-1"))
        assertFalse(Files.exists(h.probe.resolve("ran")))
        val escalation = assertIs<Write.Escalate>(h.source.writes.last())
        assertTrue("miley/ampr-1-fixture" in escalation.reason)
    }

    @Test
    fun `shutdown during a run terminates its group, escalates it, and marks the journal clean`() {
        if (!posix) return
        val h = Harness()
        h.source.add(ticket("AMPR-1"))
        val switchboard = h.switchboard(agent = "echo started; exec sleep 60")

        val report = runBlocking {
            val running = async(Dispatchers.Default) { switchboard.run(watch = true) }
            val address = withTimeout(30.seconds) { h.dispatched.await() }
            assertTrue(ProcessGroups.isAlive(address))

            withContext(Dispatchers.IO) { switchboard.requestShutdown() }
            val report = withTimeout(30.seconds) { running.await() }

            assertFalse(ProcessGroups.isAlive(address), "shutdown must leave no live process group")
            report
        }

        assertTrue(report.cleanShutdown)
        assertTrue(h.journal().cleanShutdown)
        val escalation = assertIs<Write.Escalate>(h.source.writes.last())
        assertTrue("shut down while the agent was running" in escalation.reason)
        assertEquals(DispatchPhase.ESCALATED, h.phases("AMPR-1").last())
    }

    @Test
    fun `shutdown before an agent starts gives the claim back and removes what it created`() {
        if (!posix) return
        val h = Harness()
        h.source.add(ticket("AMPR-1"))
        val reached = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        h.git.before = { args ->
            if (args.take(2) == listOf("worktree", "add")) {
                reached.complete(Unit)
                proceed.await()
            }
        }
        val switchboard = h.switchboard(agent = "touch \"\$PROBE/ran\"")

        val report = runBlocking {
            val running = async(Dispatchers.Default) { switchboard.run(watch = true) }
            withTimeout(30.seconds) { reached.await() }
            withContext(Dispatchers.IO) { switchboard.requestShutdown() }
            proceed.complete(Unit)
            withTimeout(30.seconds) { running.await() }
        }

        assertEquals(listOf<Write>(Write.Claim("AMPR-1"), Write.Release("AMPR-1")), h.source.writes)
        assertEquals(listOf("AMPR-1"), report.released)
        assertFalse(DispatchPhase.AGENT_RUNNING in h.phases("AMPR-1"))
        assertFalse(Files.exists(h.probe.resolve("ran")), "the agent must never have started")
        assertFalse(Files.exists(h.worktree("AMPR-1")), "the unused worktree is removed")
        val branch = GitFixture.gitExit(h.repo, "rev-parse", "--verify", "--quiet", "refs/heads/miley/ampr-1-fixture")
        assertNotEquals(0, branch, "the unused branch is deleted")
        assertTrue(report.cleanShutdown)
        assertTrue(h.journal().cleanShutdown)
    }

    @Test
    fun `a green verification surfaces the merge request, with every gate on the journal`() {
        if (!posix) return
        val h = Harness()
        h.source.add(ticket("AMPR-1"))

        val report = runOnce(
            h.switchboard(agent = "echo started >> \"\$PROBE/runs\"; touch ok", verification = verification(okGate)),
        )

        val surfaced = assertIs<Write.Surface>(h.source.writes.last())
        assertTrue("All 1 verification gates passed" in surfaced.summary, surfaced.summary)
        assertEquals(DispatchPhase.DONE, h.phases("AMPR-1").last())
        val gates = h.journal().entries.mapNotNull { it.gate }
        assertEquals(listOf("app-test"), gates.map { it.gateId })
        assertEquals(GateOutcome.PASSED, gates.single().outcome)
        assertEquals(0, gates.single().exitCode)
        val verifying = h.journal().entries.filter { it.phase == DispatchPhase.VERIFYING }
        assertTrue(verifying.any { it.gate == null && it.agentAddress != null }, "a running gate's group is journaled")
        assertEquals(mapOf("AMPR-1" to DispatchPhase.DONE), report.settled)
        assertTrue(report.cleanShutdown)
    }

    /**
     * Task 4 and task 5's broken half: an agent whose change never passes is fed the
     * failure exactly the bounded number of times, then escalated with the failing
     * gate, its exit code and its anchored excerpt — and its merge request is never
     * surfaced.
     */
    @Test
    fun `a broken change is repaired exactly the bounded number of times, then escalated, and never surfaces`() {
        if (!posix) return
        val h = Harness()
        h.source.add(ticket("AMPR-1"))

        val report = runOnce(
            h.switchboard(agent = "echo started >> \"\$PROBE/runs\"", verification = verification(okGate)),
        )

        assertEquals(1 + SwitchboardConfig.DEFAULT_MAX_REPAIR_ATTEMPTS, h.agentRuns())
        assertTrue(h.source.writes.none { it is Write.Surface }, "a red run must never surface")
        val escalation = assertIs<Write.Escalate>(h.source.writes.last())
        assertTrue("`app-test`" in escalation.reason, escalation.reason)
        assertTrue("exited 1" in escalation.reason, escalation.reason)
        assertTrue("> Task :app:test FAILED" in escalation.reason, escalation.reason)
        assertTrue("after 2 repair attempts" in escalation.reason, escalation.reason)
        assertFalse(h.root.toString() in escalation.reason, "an escalation is public and must not name a local path")

        val phases = h.phases("AMPR-1")
        assertEquals(3, phases.count { it == DispatchPhase.AGENT_RUNNING })
        assertFalse(DispatchPhase.DONE in phases)
        assertEquals(DispatchPhase.ESCALATED, phases.last())
        assertEquals(listOf(0, 1, 2), h.journal().entries.mapNotNull { it.gate }.map { it.attempt })

        val reportFile = h.worktree("AMPR-1").resolve(".context/verification/attempt-2.md")
        assertTrue(Files.isRegularFile(reportFile), "the repair run reads the verification report")
        assertTrue("app-test" in Files.readString(reportFile))
        assertEquals(mapOf("AMPR-1" to DispatchPhase.ESCALATED), report.settled)
    }

    @Test
    fun `a change repaired on its first attempt surfaces`() {
        if (!posix) return
        val h = Harness()
        h.source.add(ticket("AMPR-1"))
        val agent = """
            echo started >> "${'$'}PROBE/runs"
            if [ -f "${'$'}PROBE/first-done" ]; then touch ok; else touch "${'$'}PROBE/first-done"; fi
        """.trimIndent()

        runOnce(h.switchboard(agent = agent, verification = verification(okGate)))

        assertEquals(2, h.agentRuns())
        val surfaced = assertIs<Write.Surface>(h.source.writes.last())
        assertTrue("after 1 repair attempt." in surfaced.summary, surfaced.summary)
        assertEquals(DispatchPhase.DONE, h.phases("AMPR-1").last())
    }

    @Test
    fun `a host failure escalates about the host at once, spending no repair attempt`() {
        if (!posix) return
        val h = Harness()
        h.source.add(ticket("AMPR-1"))

        runOnce(
            h.switchboard(
                agent = "echo started >> \"\$PROBE/runs\"",
                verification = verification(okGate, gate("bridge", "touch \${env.AMPERE_SWITCHBOARD_TEST_UNSET}")),
            ),
        )

        assertEquals(1, h.agentRuns(), "an infrastructure failure must not relaunch the agent")
        val escalation = assertIs<Write.Escalate>(h.source.writes.last())
        assertTrue("about the machine, not the agent" in escalation.reason, escalation.reason)
        assertTrue("`bridge`" in escalation.reason, escalation.reason)
        assertTrue("AMPERE_SWITCHBOARD_TEST_UNSET" in escalation.reason, escalation.reason)
        assertTrue("`app-test`" in escalation.reason, "the agent's own failure is still named")
        assertTrue(h.source.writes.none { it is Write.Surface })
    }

    @Test
    fun `a refused run escalates naming the precondition, and nothing is surfaced`() {
        if (!posix) return
        val h = Harness()
        h.source.add(ticket("AMPR-1"))

        runOnce(
            h.switchboard(
                agent = "echo started >> \"\$PROBE/runs\"; touch ok",
                verification = verification(okGate, absent = setOf("jdk-21")),
            ),
        )

        assertEquals(1, h.agentRuns())
        val escalation = assertIs<Write.Escalate>(h.source.writes.last())
        assertTrue("refused" in escalation.reason && "`jdk-21`" in escalation.reason, escalation.reason)
        assertTrue(h.journal().entries.mapNotNull { it.gate }.all { it.outcome == GateOutcome.NOT_EXECUTED })
    }

    @Test
    fun `a missing manifest or no verification at all escalates instead of surfacing`() {
        if (!posix) return
        val missing = Harness()
        missing.source.add(ticket("AMPR-1"))
        runOnce(
            missing.switchboard(
                agent = "true",
                verification = verification(
                    manifest = object : ManifestSource {
                        override suspend fun load(worktree: Path): Result<VerifyManifest> =
                            Result.failure(link.socket.ampere.agents.execution.verify.ManifestMissing("origin/main"))
                    },
                ),
            ),
        )
        val noManifest = assertIs<Write.Escalate>(missing.source.writes.last())
        assertTrue("declares no `.ampere/verify.yml`" in noManifest.reason, noManifest.reason)

        val unverified = Harness()
        unverified.source.add(ticket("AMPR-1"))
        runOnce(unverified.switchboard(agent = "true", verification = null))
        val none = assertIs<Write.Escalate>(unverified.source.writes.last())
        assertTrue("no verification configured" in none.reason, none.reason)
        assertTrue(unverified.source.writes.none { it is Write.Surface })
    }

    /** Real git, with every command recorded and an optional hook before each. */
    private class RecordingGit : GitRunner {
        val commands = CopyOnWriteArrayList<List<String>>()

        @Volatile
        var before: suspend (List<String>) -> Unit = {}

        override suspend fun run(directory: Path, args: List<String>): GitInvocation {
            commands += args
            before(args)
            return ProcessGroupGitRunner.run(directory, args)
        }
    }

    private class RecordingLookup(
        private val answer: (String) -> Result<List<MergeRequestRef>>,
    ) : MergeRequestLookup {
        val asked = CopyOnWriteArrayList<String>()

        override suspend fun forHeadBranch(branch: String): Result<List<MergeRequestRef>> {
            asked += branch
            return answer(branch)
        }

        companion object {
            const val URL = "https://example.invalid/pull/1"

            fun open() = RecordingLookup {
                Result.success(listOf(MergeRequestRef(url = URL, number = 1, state = MergeRequestState.OPEN)))
            }
        }
    }

    private companion object {
        const val INSTANCE = "switchboard-test"
    }
}
