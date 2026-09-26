package link.socket.ampere.agents.execution

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.error.ExecutionError
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.reasoning.AgentLLMService
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.request.ExecutionConstraints
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.ToolWriteCodeFile
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic

/**
 * AMPR-300: an agent's writes cannot leave the workspace it was pinned to.
 *
 * This drives the real dispatch chain — the plan-step request an agent builds
 * (generic `NoChanges` context plus the request-level workspace pin), the
 * engine, the tool's own `CodeParams.CodeWriting` strategy fed by a scripted
 * "LLM", and the real JVM `write_code_file` actual — and checks where the
 * bytes actually land.
 */
class PinnedWorkspaceContainmentTest {

    private lateinit var sandbox: File
    private lateinit var workspace: File

    @BeforeTest
    fun setup() {
        sandbox = Files.createTempDirectory("ampr300").toFile()
        workspace = File(sandbox, "worktree").also { it.mkdirs() }
    }

    @AfterTest
    fun tearDown() {
        sandbox.deleteRecursively()
    }

    @Test
    fun `a pinned agent writes inside its workspace`() = runTest {
        val llmCalls = mutableListOf<String>()
        val engine = engine(llmCalls) { filesJson("src/main/Hello.kt", "fun main() {}") }

        val outcome = engine.execute(ToolWriteCodeFile(AgentActionAutonomy.FULLY_AUTONOMOUS), request(pinned()))

        assertIs<ExecutionOutcome.CodeChanged.Success>(outcome)
        assertEquals("fun main() {}", File(workspace, "src/main/Hello.kt").readText())
        assertEquals(1, llmCalls.size)
    }

    @Test
    fun `a traversal path from the LLM is rejected and the file outside the workspace is untouched`() = runTest {
        val escaped = File(sandbox, "escaped.txt").also { it.writeText("original") }
        val engine = engine(mutableListOf()) { filesJson("../escaped.txt", "overwritten") }

        val outcome = engine.execute(ToolWriteCodeFile(AgentActionAutonomy.FULLY_AUTONOMOUS), request(pinned()))

        val failure = assertIs<ExecutionOutcome.CodeChanged.Failure>(outcome)
        assertEquals(ExecutionError.Type.WORKSPACE_ERROR, failure.error.type)
        assertEquals("original", escaped.readText())
        assertTrue(failure.partiallyChangedFiles.isNullOrEmpty())
    }

    @Test
    fun `a nested traversal path is rejected`() = runTest {
        val engine = engine(mutableListOf()) { filesJson("src/../../escaped.txt", "x") }

        val outcome = engine.execute(ToolWriteCodeFile(AgentActionAutonomy.FULLY_AUTONOMOUS), request(pinned()))

        assertIs<ExecutionOutcome.CodeChanged.Failure>(outcome)
        assertFalse(File(sandbox, "escaped.txt").exists())
    }

    @Test
    fun `a symlink out of the workspace is rejected`() = runTest {
        val outside = File(sandbox, "outside").also { it.mkdirs() }
        try {
            Files.createSymbolicLink(File(workspace, "link").toPath(), outside.toPath())
        } catch (e: UnsupportedOperationException) {
            return@runTest
        }
        val engine = engine(mutableListOf()) { filesJson("link/escaped.txt", "x") }

        val outcome = engine.execute(ToolWriteCodeFile(AgentActionAutonomy.FULLY_AUTONOMOUS), request(pinned()))

        assertIs<ExecutionOutcome.CodeChanged.Failure>(outcome)
        assertFalse(File(outside, "escaped.txt").exists())
    }

    @Test
    fun `an unpinned agent cannot write at all and no LLM call is spent finding that out`() = runTest {
        val llmCalls = mutableListOf<String>()
        val engine = engine(llmCalls) { filesJson("anywhere.txt", "x") }

        val outcome = engine.execute(ToolWriteCodeFile(AgentActionAutonomy.FULLY_AUTONOMOUS), request(workspace = null))

        val failure = assertIs<ExecutionOutcome.NoChanges.Failure>(outcome)
        assertTrue(failure.message.contains("No workspace pinned"), failure.message)
        assertEquals(0, llmCalls.size, "the refusal must happen before the parameter-generation call")
        // Nothing landed in the working directory, which is where "." used to resolve.
        assertFalse(File("anywhere.txt").exists())
        assertFalse(File(workspace, "anywhere.txt").exists())
    }

    private fun pinned() = ExecutionWorkspace(baseDirectory = workspace.path)

    private fun filesJson(path: String, content: String): String =
        """{"files":[{"path":"$path","content":"$content"}]}"""

    private fun engine(llmCalls: MutableList<String>, respond: () -> String): ToolExecutionEngine {
        val executor = FunctionExecutor.create()
        val llmService = AgentLLMService(
            AgentConfiguration(
                agentDefinition = WriteCodeAgent,
                aiConfiguration = AIConfiguration_Default(
                    provider = AIProvider_Anthropic,
                    model = AIModel_Claude.Sonnet_5,
                ),
                llmProvider = { prompt ->
                    llmCalls += prompt
                    respond()
                },
            ),
        )
        return ToolExecutionEngine(
            llmService = llmService,
            executor = executor,
            executorId = executor.id,
        )
    }

    /** The shape `SparkBasedAgent.buildPlanStepRequest` produces: generic context, request-level pin. */
    private fun request(workspace: ExecutionWorkspace?): ExecutionRequest<ExecutionContext.NoChanges> {
        val now = Clock.System.now()
        return ExecutionRequest(
            context = ExecutionContext.NoChanges(
                executorId = "agent-1",
                ticket = Ticket(
                    id = "ticket-1",
                    title = "Test ticket",
                    description = "Test ticket description",
                    type = TicketType.TASK,
                    priority = TicketPriority.MEDIUM,
                    status = TicketStatus.InProgress,
                    assignedAgentId = "agent-1",
                    createdByAgentId = "agent-1",
                    createdAt = now,
                    updatedAt = now,
                ),
                task = Task.CodeChange(
                    id = "task-1",
                    status = TaskStatus.Pending,
                    description = "Write the file",
                ),
                instructions = "Write the file",
            ),
            constraints = ExecutionConstraints(requireTests = false, requireLinting = false),
            workspace = workspace,
        )
    }
}
