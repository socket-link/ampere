package link.socket.ampere.agents.execution.executor

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.cognition.FileAccessScope
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
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.ToolHarnessCodeChange

/**
 * The Junie factory (AMPR-500): the id the record carries, a binary that is not there reported
 * as unavailable with its reason, never thrown, the registry's `--acp=true` launch with the
 * caller's arguments and environment after it, and a whole turn through the factory recorded
 * under `junie`.
 */
class JunieExecutorTest {

    private val posix = File.separatorChar == '/'
    private val root: File = Files.createTempDirectory("junie-executor-test").toFile()
    private val scripts = FakeAcpAgentScript(File(root, ".scripts").also { it.mkdirs() }.toPath())
    private val executors = mutableListOf<AcpCodeExecutor>()

    @AfterTest
    fun cleanUp() = runBlocking<Unit> {
        executors.forEach { runCatching { it.close() } }
        root.deleteRecursively()
    }

    @Test
    fun `the factory binds the junie id and a missing binary is an unavailable health`() = runBlocking<Unit> {
        if (!posix) return@runBlocking
        val executor = junie(binary = File(root, "no-such-junie").absolutePath)

        val health = executor.performHealthCheck().getOrThrow()

        assertEquals(JUNIE_EXECUTOR_ID, executor.id)
        assertEquals("junie", executor.id)
        assertEquals("Junie (ACP)", executor.displayName)
        assertFalse(health.isAvailable)
        assertTrue(health.issues.isNotEmpty(), "the reason is reported: ${health.status}")
        assertTrue(executor.capabilities.supportsLanguages.isEmpty(), "language-agnostic")
    }

    @Test
    fun `the agent is launched in ACP mode with the caller's arguments and environment`() = runBlocking<Unit> {
        if (!posix) return@runBlocking
        val arguments = File(root, "spawn-args.txt")
        val variables = File(root, "spawn-env.txt")
        val d = "$"
        val agent = scripts.agent(
            preamble = "printf '%s\\n' \"$d@\" > \"${arguments.absolutePath}\"; " +
                "printf '%s\\n' \"${d}JUNIE_API_KEY\" > \"${variables.absolutePath}\"",
        )
        val executor = junie(
            binary = agent.absolutePath,
            environment = mapOf("JUNIE_API_KEY" to "k"),
            extraArguments = listOf("--model", "sonnet"),
        )

        val health = executor.performHealthCheck().getOrThrow()

        assertTrue(health.isAvailable, "the fake agent answered initialize: ${health.issues}")
        assertEquals(listOf("--acp=true", "--model", "sonnet"), arguments.readLines())
        assertEquals(listOf("k"), variables.readLines())
    }

    @Test
    fun `an edit the agent writes inside the scope is a success recorded under junie`() = runBlocking<Unit> {
        if (!posix) return@runBlocking
        checkout()
        val agent = scripts.agent(onPrompt = scripts.editWithPermission("$root/src/Greeting.kt", "fun greet() = 1"))
        val executor = junie(binary = agent.absolutePath)

        val statuses = executor.execute(request(), ToolHarnessCodeChange()).toList()

        val completed = assertIs<ExecutionStatus.Completed>(statuses.last())
        val success = assertIs<ExecutionOutcome.CodeChanged.Success>(completed.result)
        assertEquals(listOf("src/Greeting.kt"), success.changedFiles)
        assertTrue(statuses.all { it.executorId == JUNIE_EXECUTOR_ID }, "every status names junie")
        assertEquals(JUNIE_EXECUTOR_ID, success.executorId)
        assertTrue(scripts.logLines().any { it.contains("\"optionId\":\"once\"") }, "the fence allowed it once")
    }

    // ========================================================================
    // Fixture
    // ========================================================================

    private fun junie(
        binary: String,
        environment: Map<String, String> = emptyMap(),
        extraArguments: List<String> = emptyList(),
    ): AcpCodeExecutor =
        AcpCodeExecutor.junie(
            binary = binary,
            workspace = ExecutionWorkspace(baseDirectory = root.absolutePath),
            policy = HarnessPolicy(startTimeout = 10.seconds),
            environment = environment,
            extraArguments = extraArguments,
        ).also { executors += it }

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
                executorId = JUNIE_EXECUTOR_ID,
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
            fileAccessScope = FileAccessScope(
                readPatterns = setOf(FileAccessScope.AnyPath),
                writePatterns = setOf("src/**"),
            ),
        )
    }
}
