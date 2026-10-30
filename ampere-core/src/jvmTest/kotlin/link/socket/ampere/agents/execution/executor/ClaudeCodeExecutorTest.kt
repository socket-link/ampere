package link.socket.ampere.agents.execution.executor

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
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
import link.socket.ampere.agents.execution.executor.acp.HarnessPolicy
import link.socket.ampere.agents.execution.process.CancellationAddress
import link.socket.ampere.agents.execution.process.ProcessGroups
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.ToolHarnessCodeChange

/**
 * The Claude Code factory (AMPR-499): the id the record carries, a command that is not there
 * reported as unavailable with its reason, the fence holding against the same `sh` fake the
 * OpenCode factory is proven against, the key reaching the agent through its environment, and
 * an `npx`-shaped wrapper ended with everything it started.
 */
class ClaudeCodeExecutorTest {

    private val posix = File.separatorChar == '/'
    private val root: File = Files.createTempDirectory("claude-code-executor-test").toFile()
    private val scriptDirectory = File(root, ".scripts").also { it.mkdirs() }
    private val scripts = FakeAcpAgentScript(scriptDirectory.toPath())
    private val executors = mutableListOf<AcpCodeExecutor>()
    private val stragglers = mutableListOf<Long>()

    private val srcOnly = FileAccessScope(
        readPatterns = setOf(FileAccessScope.AnyPath),
        writePatterns = setOf("src/**"),
    )

    @AfterTest
    fun cleanUp() = runBlocking<Unit> {
        executors.forEach { runCatching { it.close() } }
        stragglers.forEach { ProcessGroups.terminate(address(it), grace = 200.milliseconds) }
        root.deleteRecursively()
    }

    @Test
    fun `the factory binds the claude-code id and a missing command is an unavailable health`() = runBlocking<Unit> {
        if (!posix) return@runBlocking
        val executor = executor(listOf(File(root, "no-such-adapter").absolutePath))

        val health = executor.performHealthCheck().getOrThrow()
        executor.close()

        assertEquals(CLAUDE_CODE_EXECUTOR_ID, executor.id)
        assertEquals("claude-code", executor.id)
        assertEquals("Claude Code (ACP)", executor.displayName)
        assertFalse(health.isAvailable)
        assertTrue(health.issues.isNotEmpty(), "the reason is reported: ${health.status}")
        assertTrue(executor.capabilities.supportsLanguages.isEmpty(), "language-agnostic")
    }

    @Test
    fun `the default command is Zed's adapter through npx and an empty one is refused`() {
        assertEquals(listOf("npx", "@zed-industries/claude-code-acp"), CLAUDE_CODE_ACP_COMMAND)
        assertFailsWith<IllegalArgumentException> { AcpCodeExecutor.claudeCode(command = emptyList()) }
    }

    @Test
    fun `an edit inside the scope written straight to disk is a success naming the file`() = runBlocking<Unit> {
        if (!posix) return@runBlocking
        checkout()
        val agent = scripts.agent(onPrompt = scripts.editWithPermission("$root/src/Greeting.kt", "fun greet() = 1"))
        val executor = executor(listOf(agent.absolutePath))

        val statuses = executor.execute(request(), ToolHarnessCodeChange()).toList()

        val completed = assertIs<ExecutionStatus.Completed>(statuses.last())
        val success = assertIs<ExecutionOutcome.CodeChanged.Success>(completed.result)
        assertEquals(CLAUDE_CODE_EXECUTOR_ID, success.executorId)
        assertEquals(listOf("src/Greeting.kt"), success.changedFiles)
        assertTrue(scripts.logLines().any { it.contains("\"optionId\":\"once\"") }, "the fence allowed it once")
        assertEquals("fun greet() = 1\n", File(root, "src/Greeting.kt").readText())
    }

    @Test
    fun `a write outside the scope made despite a rejection fails the step and names the path`() = runBlocking<Unit> {
        if (!posix) return@runBlocking
        checkout()
        val agent = scripts.agent(
            onPrompt = scripts.editWithPermission("$root/README.md", "rewritten anyway", writeEvenIfRejected = true),
        )
        val executor = executor(listOf(agent.absolutePath))

        val statuses = executor.execute(request(), ToolHarnessCodeChange()).toList()

        val failed = assertIs<ExecutionStatus.Failed>(statuses.last())

        val failure = assertIs<ExecutionOutcome.CodeChanged.Failure>(failed.result)
        assertEquals(CLAUDE_CODE_EXECUTOR_ID, failure.executorId)
        assertEquals(ExecutionError.Type.WORKSPACE_ERROR, failure.error.type)
        assertTrue(failure.error.message.contains("\"README.md\""), failure.error.message)
        assertEquals(listOf("README.md"), failure.partiallyChangedFiles)
        assertTrue(scripts.logLines().any { it.contains("\"optionId\":\"reject\"") }, "the fence refused it")
        assertEquals("rewritten anyway\n", File(root, "README.md").readText(), "reported, not reverted")
    }

    @Test
    fun `the key reaches the agent through its environment and not its arguments`() = runBlocking<Unit> {
        if (!posix) return@runBlocking
        val dump = File(root, "spawn.txt")
        val d = "$"
        val agent = scripts.agent(
            preamble = "printf '%s\\n%s\\n' \"${d}ANTHROPIC_API_KEY\" \"$d*\" > \"${dump.absolutePath}\"",
        )
        val executor = executor(
            command = listOf(agent.absolutePath, "--flag"),
            environment = mapOf("ANTHROPIC_API_KEY" to "test-key-not-real"),
        )

        val health = executor.performHealthCheck().getOrThrow()
        executor.close()

        assertTrue(health.isAvailable, "the fake agent answered initialize: ${health.issues}")
        val (key, arguments) = dump.readLines()
        assertEquals("test-key-not-real", key)
        assertEquals("--flag", arguments, "the arguments are the caller's command line, no key added")
    }

    @Test
    fun `closing ends an npx-shaped wrapper and everything under it`() = runBlocking<Unit> {
        if (!posix) return@runBlocking
        val wrapperPid = File(root, "wrapper.pid")
        val sleeperPid = File(root, "sleeper.pid")
        // Like npx: the wrapper starts the adapter as its child and waits, rather than exec'ing it.
        val wrapper = File(scriptDirectory, "npx-like.sh").apply {
            writeText("#!/bin/sh\necho $$ > \"${wrapperPid.absolutePath}\"\n\"$@\"\n")
            Files.setPosixFilePermissions(toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        }
        // Like the adapter starting Claude Code: a child that does not exit when stdin closes.
        val agent = scripts.agent(
            preamble = "sleep 600 </dev/null >/dev/null 2>&1 & echo $! > \"${sleeperPid.absolutePath}\"",
        )
        val executor = executor(listOf(wrapper.absolutePath, agent.absolutePath))

        val health = executor.performHealthCheck().getOrThrow()
        assertTrue(health.isAvailable, "the wrapped agent answered initialize: ${health.issues}")
        val group = wrapperPid.readText().trim().toLong().also { stragglers += it }
        val sleeper = sleeperPid.readText().trim().toLong()
        assertTrue(ProcessGroups.isAlive(address(group)), "the group is running before close")

        executor.close()

        waitUntil(10.seconds) { !ProcessGroups.isAlive(address(group)) }
        assertFalse(ProcessGroups.isAlive(address(group)), "nothing is left at the address")
        waitUntil(10.seconds) { ProcessHandle.of(sleeper).map { !it.isAlive }.orElse(true) }
    }

    // ========================================================================
    // Fixture
    // ========================================================================

    private fun executor(
        command: List<String>,
        environment: Map<String, String> = emptyMap(),
    ): AcpCodeExecutor =
        AcpCodeExecutor.claudeCode(
            command = command,
            workspace = ExecutionWorkspace(baseDirectory = root.absolutePath),
            policy = HarnessPolicy(startTimeout = 10.seconds),
            environment = environment,
        ).also { executors += it }

    /** The group a wrapper that led it reported; its start time is not recorded, so nothing is refused. */
    private fun address(group: Long) =
        CancellationAddress(leaderPid = group, processGroupId = group, leaderStartedAt = null)

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
                executorId = CLAUDE_CODE_EXECUTOR_ID,
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
