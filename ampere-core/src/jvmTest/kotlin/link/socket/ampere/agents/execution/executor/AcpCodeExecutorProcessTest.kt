package link.socket.ampere.agents.execution.executor

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.cognition.FileAccessScope
import link.socket.ampere.agents.domain.error.ExecutionError
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.status.ExecutionStatus
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.execution.executor.acp.GitWorkspaceObserver
import link.socket.ampere.agents.execution.executor.acp.HarnessPolicy
import link.socket.ampere.agents.execution.executor.acp.StdioAcpTransport
import link.socket.ampere.agents.execution.process.CancellationAddress
import link.socket.ampere.agents.execution.process.ProcessGroups
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.ToolHarnessCodeChange

/**
 * The whole JVM path (AMPR-493): [AcpCodeExecutor] over a [StdioAcpTransport] to a real `sh`
 * agent, with a [GitWorkspaceObserver] on a real checkout. The agent writes to disk from its
 * own process, which is what makes the audit the fence that holds.
 */
class AcpCodeExecutorProcessTest {

    private val posix = File.separatorChar == '/'
    private val root: File = Files.createTempDirectory("acp-process-test").toFile()
    private val scripts = FakeAcpAgentScript(root.toPath().resolve(".scripts").also { Files.createDirectories(it) })
    private val spawned = mutableListOf<CancellationAddress>()
    private val executors = mutableListOf<AcpCodeExecutor>()
    private var transport: StdioAcpTransport? = null

    private val srcOnly = FileAccessScope(
        readPatterns = setOf(FileAccessScope.AnyPath),
        writePatterns = setOf("src/**"),
    )

    @AfterTest
    fun cleanUp() = runBlocking<Unit> {
        executors.forEach { runCatching { it.close() } }
        spawned.forEach { ProcessGroups.terminate(it, grace = 200.milliseconds) }
        root.deleteRecursively()
    }

    @Test
    fun `an edit inside the scope written straight to disk is a success naming the file`() = runBlocking<Unit> {
        if (!posix) return@runBlocking
        checkout()
        val script = scripts.agent(onPrompt = scripts.editWithPermission("$root/src/Greeting.kt", "fun greet() = 1"))
        val executor = executor(script)

        val statuses = executor.execute(request(), ToolHarnessCodeChange()).toList()

        val completed = assertIs<ExecutionStatus.Completed>(statuses.last())
        val success = assertIs<ExecutionOutcome.CodeChanged.Success>(completed.result)
        assertEquals(listOf("src/Greeting.kt"), success.changedFiles)
        assertEquals("Edited the file.", success.validation.codeChanges?.diffSummary)
        assertTrue(statuses.any { it is ExecutionStatus.Writing && it.filePath == "src/Greeting.kt" })
        assertTrue(scripts.logLines().any { it.contains("\"optionId\":\"once\"") }, "the fence allowed it once")
        assertEquals("fun greet() = 1\n", File(root, "src/Greeting.kt").readText())
    }

    @Test
    fun `a write outside the scope made despite a rejection fails the step and names the path`() = runBlocking<Unit> {
        if (!posix) return@runBlocking
        checkout()
        val script = scripts.agent(
            onPrompt = scripts.editWithPermission("$root/README.md", "rewritten anyway", writeEvenIfRejected = true),
        )
        val executor = executor(script)

        val failed = assertIs<ExecutionStatus.Failed>(executor.run())

        val failure = assertIs<ExecutionOutcome.CodeChanged.Failure>(failed.result)
        assertEquals(ExecutionError.Type.WORKSPACE_ERROR, failure.error.type)
        assertTrue(failure.error.message.contains("\"README.md\""), failure.error.message)
        assertEquals(listOf("README.md"), failure.partiallyChangedFiles)
        assertTrue(scripts.logLines().any { it.contains("\"optionId\":\"reject\"") }, "the fence refused it")
        assertEquals("rewritten anyway\n", File(root, "README.md").readText(), "reported, not reverted")
    }

    @Test
    fun `an agent that exits mid turn is TOOL_UNAVAILABLE and retryable`() = runBlocking<Unit> {
        if (!posix) return@runBlocking
        checkout()
        val executor = executor(scripts.agent(onPrompt = "exit 0"))

        val failed = assertIs<ExecutionStatus.Failed>(executor.run())

        val failure = assertIs<ExecutionOutcome.CodeChanged.Failure>(failed.result)
        assertEquals(ExecutionError.Type.TOOL_UNAVAILABLE, failure.error.type)
        assertTrue(failure.error.isRetryable)
    }

    @Test
    fun `cancelling a turn the agent will not end terminates the agent's group`() = runBlocking<Unit> {
        if (!posix) return@runBlocking
        checkout()
        val executor = executor(
            scripts.agent(onPrompt = "sleep 600"),
            policy = HarnessPolicy(cancelGrace = 300.milliseconds),
        )

        val turn = async(Dispatchers.Default) { executor.execute(request(), ToolHarnessCodeChange()).toList() }
        waitUntil(10.seconds) { scripts.logLines().any { it.contains("session/prompt") } }
        val address = assertNotNull(transport?.address, "the transport spawned")
        spawned += address
        turn.cancelAndJoin()

        waitUntil(10.seconds) { !ProcessGroups.isAlive(address) }
        assertFalse(ProcessGroups.isAlive(address))
    }

    // ========================================================================
    // Fixture
    // ========================================================================

    private fun executor(script: File, policy: HarnessPolicy = HarnessPolicy()): AcpCodeExecutor =
        AcpCodeExecutor(
            id = "fake-acp",
            displayName = "Fake ACP process",
            transportFactory = {
                StdioAcpTransport(listOf(script.absolutePath)).also { transport = it }
            },
            observerFactory = { GitWorkspaceObserver(File(it.baseDirectory)) },
            policy = policy,
            workspace = ExecutionWorkspace(baseDirectory = root.absolutePath),
        ).also { executors += it }

    /** The terminal status of one call. */
    private suspend fun AcpCodeExecutor.run(): ExecutionStatus =
        execute(request(), ToolHarnessCodeChange()).toList().last()

    /** A checkout with a committed README and an empty `src/`. */
    private fun checkout() {
        git("init", "-q")
        git("config", "user.email", "test@example.com")
        git("config", "user.name", "Test")
        git("config", "commit.gpgsign", "false")
        File(root, "README.md").writeText("hello\n")
        File(root, "src").mkdirs()
        File(root, ".gitignore").writeText(".scripts/\n")
        git("add", "-A")
        git("commit", "-q", "-m", "init")
    }

    private fun git(vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args).directory(root).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
    }

    private fun request(): ExecutionRequest<ExecutionContext.NoChanges> {
        val now = Clock.System.now()
        return ExecutionRequest(
            context = ExecutionContext.NoChanges(
                actorId = "agent-1",
                executorId = "fake-acp",
                ticket = Ticket(
                    id = "ticket-1",
                    title = "Greet",
                    description = "Greet the user",
                    type = TicketType.TASK,
                    priority = TicketPriority.MEDIUM,
                    status = TicketStatus.InProgress,
                    assignedAgentId = "agent-1",
                    createdByAgentId = "agent-1",
                    createdAt = now,
                    updatedAt = now,
                ),
                task = Task.CodeChange(id = "task-1", status = TaskStatus.Pending, description = "Add a greeting"),
                instructions = "Add a greeting",
            ),
            runId = "run-1",
            fileAccessScope = srcOnly,
        )
    }

    private fun waitUntil(timeout: Duration, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met within $timeout" }
            Thread.sleep(25)
        }
    }
}
