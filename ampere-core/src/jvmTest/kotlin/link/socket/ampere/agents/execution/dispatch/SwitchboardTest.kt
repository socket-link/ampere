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
        ): Switchboard = Switchboard(
            config = SwitchboardConfig(
                wave = "w1",
                logDirectory = logs,
                repositorySlug = FakeDispatchWorkSource.REPOSITORY,
                shutdownGrace = 2.seconds,
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
    }

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
                DispatchPhase.MR_OPENED,
                DispatchPhase.DONE,
            ),
            h.phases("AMPR-1"),
        )
        assertEquals(RecordingLookup.URL, h.journal().entries.last().mergeRequestUrl)
        assertEquals(listOf<Write>(Write.Claim("AMPR-1")), h.source.writes, "a run with a PR writes nothing else")
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
