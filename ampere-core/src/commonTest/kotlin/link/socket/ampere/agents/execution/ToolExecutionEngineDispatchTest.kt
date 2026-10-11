package link.socket.ampere.agents.execution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.outcome.StepOutcome
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
import link.socket.ampere.agents.execution.results.ExecutionResult
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic

/**
 * What [ToolExecutionEngine] hands back for each shape of result a tool can produce, with no
 * door and no store wired: the dispatch alone.
 *
 * AMPR-420: the engine is the only path a tool call takes, and these are the assertions the
 * deleted single-tool wrapper's own suite made, moved onto it. The event half of that suite
 * is `ToolExecutionWithEventsTest`, which needs a database and so lives in jvmTest.
 *
 * Not carried over is the wrapper's `validate`, which returned `Valid` for every request and
 * so asserted a constant. The engine's own refusals (no intent, permission denied) are
 * pinned in [ToolExecutionEngineOutcomeRecordingTest] and `ToolExecutionPermissionGateTest`.
 */
class ToolExecutionEngineDispatchTest {

    @Test
    fun `a tool's own success outcome comes back unchanged`() = runTest {
        val tool = noChangesTool("hello") { executionRequest ->
            successOutcome(executionRequest, "hello world")
        }

        val outcome = engine().execute(tool, request())

        val returned = assertIs<ExecutionOutcome.NoChanges.Success>(outcome)
        assertEquals("hello world", returned.message)
    }

    @Test
    fun `a tool's own failure outcome comes back unchanged`() = runTest {
        val tool = noChangesTool("fails") { executionRequest ->
            failureOutcome(executionRequest, "Something went wrong")
        }

        val outcome = engine().execute(tool, request())

        val returned = assertIs<ExecutionOutcome.NoChanges.Failure>(outcome)
        assertEquals("Something went wrong", returned.message)
    }

    @Test
    fun `a tool that throws comes back as a failure rather than an exception`() = runTest {
        val tool = noChangesTool("throws") { throw IllegalStateException("Tool failure simulation") }

        val outcome = engine().execute(tool, request())

        val failure = assertIs<ExecutionOutcome.NoChanges.Failure>(outcome)
        assertTrue(
            failure.message.contains("Tool failure simulation"),
            "the cause is kept so the step's failure says what went wrong: ${failure.message}",
        )
    }

    /**
     * A tool that ran and had nothing to hand back is neither a success nor a failure, and
     * the engine says so with an explicit `Blank` (AMPR-420). The executor protocol had no
     * terminal status for it, so it used to come back as a failed dispatch, where the
     * deleted single-tool wrapper had reported it as a blank result.
     */
    @Test
    fun `a tool that returns a blank outcome comes back as an explicit Blank`() = runTest {
        val tool = noChangesTool("blank") { Outcome.blank }

        val outcome = engine().execute(tool, request())

        assertIs<ExecutionOutcome.Blank>(outcome)
    }

    @Test
    fun `a tool that returns a blank execution outcome comes back as the same Blank`() = runTest {
        val tool = noChangesTool("blank-execution") { ExecutionOutcome.blank }

        val outcome = engine().execute(tool, request())

        assertIs<ExecutionOutcome.Blank>(outcome)
    }

    /** Only a blank is a no-op: any other result the executor cannot read is still a fault. */
    @Test
    fun `a tool that returns an outcome nothing recognises still fails the dispatch`() = runTest {
        val tool = noChangesTool("unrecognised") { executionRequest ->
            StepOutcome.Skipped(
                id = executionRequest.context.task.id,
                stepDescription = "not a tool result",
                timestamp = Clock.System.now(),
                reason = "a step outcome is no kind of tool result",
            )
        }

        val outcome = engine().execute(tool, request())

        val failure = assertIs<ExecutionOutcome.NoChanges.Failure>(outcome)
        assertTrue(
            failure.message.contains("Unexpected outcome type"),
            "an unrecognised result is named as one: ${failure.message}",
        )
    }

    @Test
    fun `two dispatches in flight through one engine keep their own outcomes`() = runTest {
        val engine = engine()
        val order = mutableListOf<String>()
        val slow = noChangesTool("tool-a") { executionRequest ->
            order += "a-started"
            yield()
            order += "a-finished"
            successOutcome(executionRequest, "result-a")
        }
        val quick = noChangesTool("tool-b") { executionRequest ->
            order += "b"
            successOutcome(executionRequest, "result-b")
        }

        val (first, second) = listOf(
            async { engine.execute(slow, request()) },
            async { engine.execute(quick, request()) },
        ).awaitAll()

        assertEquals(
            listOf("a-started", "b", "a-finished"),
            order,
            "the second dispatch ran while the first was still in flight",
        )
        assertEquals("result-a", assertIs<ExecutionOutcome.NoChanges.Success>(first).message)
        assertEquals("result-b", assertIs<ExecutionOutcome.NoChanges.Success>(second).message)
    }

    @Test
    fun `a tool typed to a code context receives that context through the engine`() = runTest {
        val tool = FunctionTool<ExecutionContext.Code.WriteCode>(
            id = "write-code",
            name = "Write Code Tool",
            description = "Writes code",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { executionRequest ->
                // Reading the code-specific field is the check: a context the engine had
                // rebuilt as anything else would not cast, and the tool would fail instead.
                val filePaths = executionRequest.context.instructionsPerFilePath.map { it.first }
                ExecutionOutcome.CodeChanged.Success(
                    executorId = executionRequest.context.executorId,
                    ticketId = executionRequest.context.ticket.id,
                    taskId = executionRequest.context.task.id,
                    executionStartTimestamp = Clock.System.now(),
                    executionEndTimestamp = Clock.System.now(),
                    changedFiles = filePaths,
                    validation = ExecutionResult(
                        codeChanges = null,
                        compilation = null,
                        linting = null,
                        tests = null,
                    ),
                )
            },
        )
        val context = ExecutionContext.Code.WriteCode(
            executorId = "agent-1",
            ticket = ticket(),
            task = task(),
            instructions = "Write some code",
            workspace = ExecutionWorkspace(baseDirectory = "/tmp/test"),
            instructionsPerFilePath = listOf(
                "/tmp/test/file1.kt" to "fun main() {}",
                "/tmp/test/file2.kt" to "class Test {}",
            ),
        )

        val outcome = engine().execute(tool, requestFor(context))

        val changed = assertIs<ExecutionOutcome.CodeChanged.Success>(outcome)
        assertEquals(listOf("/tmp/test/file1.kt", "/tmp/test/file2.kt"), changed.changedFiles)
    }

    private fun engine(): ToolExecutionEngine {
        val executor = FunctionExecutor.create()
        return ToolExecutionEngine(
            llmService = AgentLLMService(
                AgentConfiguration(
                    agentDefinition = WriteCodeAgent,
                    aiConfiguration = AIConfiguration_Default(
                        provider = AIProvider_Anthropic,
                        model = AIModel_Claude.Sonnet_5,
                    ),
                    llmProvider = { "{}" },
                ),
            ),
            executor = executor,
            executorId = executor.id,
        )
    }

    private fun noChangesTool(
        id: String,
        run: suspend (ExecutionRequest<ExecutionContext.NoChanges>) -> Outcome,
    ) = FunctionTool<ExecutionContext.NoChanges>(
        id = id,
        name = "Tool $id",
        description = "Test tool $id",
        requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
        executionFunction = run,
    )

    private fun successOutcome(
        executionRequest: ExecutionRequest<ExecutionContext.NoChanges>,
        message: String,
    ) = ExecutionOutcome.NoChanges.Success(
        executorId = executionRequest.context.executorId,
        ticketId = executionRequest.context.ticket.id,
        taskId = executionRequest.context.task.id,
        executionStartTimestamp = Clock.System.now(),
        executionEndTimestamp = Clock.System.now(),
        message = message,
    )

    private fun failureOutcome(
        executionRequest: ExecutionRequest<ExecutionContext.NoChanges>,
        message: String,
    ) = ExecutionOutcome.NoChanges.Failure(
        executorId = executionRequest.context.executorId,
        ticketId = executionRequest.context.ticket.id,
        taskId = executionRequest.context.task.id,
        executionStartTimestamp = Clock.System.now(),
        executionEndTimestamp = Clock.System.now(),
        message = message,
    )

    private fun request(): ExecutionRequest<ExecutionContext.NoChanges> =
        requestFor(
            ExecutionContext.NoChanges(
                executorId = "agent-1",
                ticket = ticket(),
                task = task(),
                instructions = "Do the thing",
            ),
        )

    private fun <C : ExecutionContext> requestFor(context: C): ExecutionRequest<C> =
        ExecutionRequest(
            context = context,
            constraints = ExecutionConstraints(
                requireTests = false,
                requireLinting = false,
            ),
        )

    private fun ticket(): Ticket {
        val now = Clock.System.now()
        return Ticket(
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
            dueDate = null,
        )
    }

    private fun task(): Task.CodeChange = Task.CodeChange(
        id = "task-1",
        status = TaskStatus.Pending,
        description = "Do the thing",
    )
}
