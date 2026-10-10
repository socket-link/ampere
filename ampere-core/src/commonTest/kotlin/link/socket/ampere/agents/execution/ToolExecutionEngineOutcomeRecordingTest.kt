package link.socket.ampere.agents.execution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.OutcomeMemory
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepository
import link.socket.ampere.agents.domain.reasoning.AgentLLMService
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketId
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.execution.executor.ExecutorId
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.request.ExecutionConstraints
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic

/**
 * AMPR-406 (F20): `OutcomeMemoryRepository.recordOutcome` had no caller, so `OutcomeService`
 * and the CLI `outcomes` command read a store nothing wrote. The engine is the persisting
 * path — these are the facts about what it writes.
 */
class ToolExecutionEngineOutcomeRecordingTest {

    @Test
    fun `a dispatched tool call is recorded against its ticket and its run`() = runTest {
        val store = RecordingOutcomeRepository()
        val engine = engineWith(runId = "arc-run-1", store = store)

        engine.execute(succeedingTool(), request())

        val recorded = store.calls.single()
        assertEquals("ticket-1", recorded.ticketId)
        assertEquals("arc-run-1", recorded.runId, "the row must be joinable to the run's trace")
        assertTrue(
            recorded.outcome is ExecutionOutcome.Success,
            "a successful dispatch is recorded as a success",
        )
    }

    @Test
    fun `the recorded approach names the tool and the intent`() = runTest {
        val store = RecordingOutcomeRepository()
        val engine = engineWith(runId = null, store = store)

        engine.execute(succeedingTool(), request())

        val approach = store.calls.single().approach
        assertEquals("do-the-thing: Do the thing", approach)
    }

    @Test
    fun `the recorded timestamp is the outcome's own end time rather than the write's`() = runTest {
        val store = RecordingOutcomeRepository()
        val engine = engineWith(runId = null, store = store)
        val toolEndedAt = Instant.fromEpochMilliseconds(1_700_000_000_000)

        engine.execute(succeedingTool(endedAt = toolEndedAt), request())

        assertEquals(toolEndedAt, store.calls.single().timestamp)
    }

    @Test
    fun `a refusal before dispatch is still recorded`() = runTest {
        val store = RecordingOutcomeRepository()
        val engine = engineWith(runId = "arc-run-1", store = store)

        // A blank intent is refused before any executor is reached — but it is an attempt, and
        // a failure is the half of the learning signal worth keeping.
        engine.execute(succeedingTool(), request(instructions = ""))

        val recorded = store.calls.single()
        assertTrue(recorded.outcome is ExecutionOutcome.Failure)
        assertEquals("arc-run-1", recorded.runId)
    }

    @Test
    fun `the run named by the request wins over the engine's own`() = runTest {
        val store = RecordingOutcomeRepository()
        val engine = engineWith(runId = "engine-run", store = store)

        engine.execute(succeedingTool(), request(runId = "caller-run"))

        assertEquals("caller-run", store.calls.single().runId)
    }

    @Test
    fun `an engine with no store records nothing`() = runTest {
        val store = RecordingOutcomeRepository()
        val engine = engineWith(runId = "arc-run-1", store = null)

        engine.execute(succeedingTool(), request())

        assertTrue(store.calls.isEmpty())
    }

    @Test
    fun `a store that throws does not change what the call reports`() = runTest {
        val engine = engineWith(runId = "arc-run-1", store = ThrowingOutcomeRepository)

        val outcome = engine.execute(succeedingTool(), request())

        assertTrue(
            outcome is ExecutionOutcome.NoChanges.Success,
            "a failed memory write must not turn a successful tool call into a failure",
        )
    }

    private fun succeedingTool(endedAt: Instant? = null) =
        FunctionTool<ExecutionContext>(
            id = "do-the-thing",
            name = "Do The Thing",
            description = "Succeeds",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { executionRequest ->
                val now = Clock.System.now()
                ExecutionOutcome.NoChanges.Success(
                    executorId = executionRequest.context.executorId,
                    ticketId = executionRequest.context.ticket.id,
                    taskId = executionRequest.context.task.id,
                    executionStartTimestamp = now,
                    executionEndTimestamp = endedAt ?: now,
                    message = "done",
                )
            },
        )

    private fun engineWith(
        runId: RunId?,
        store: OutcomeMemoryRepository?,
    ): ToolExecutionEngine {
        val executor = FunctionExecutor.create()
        val llmService = AgentLLMService(
            AgentConfiguration(
                agentDefinition = WriteCodeAgent,
                aiConfiguration = AIConfiguration_Default(
                    provider = AIProvider_Anthropic,
                    model = AIModel_Claude.Sonnet_5,
                ),
                llmProvider = { "{}" },
            ),
        )
        return ToolExecutionEngine(
            llmService = llmService,
            executor = executor,
            executorId = executor.id,
            runId = runId,
            outcomeRepository = store,
        )
    }

    private fun request(
        runId: RunId? = null,
        instructions: String = "Do the thing",
    ): ExecutionRequest<ExecutionContext.NoChanges> {
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
                    dueDate = null,
                ),
                task = Task.CodeChange(
                    id = "task-1",
                    status = TaskStatus.Pending,
                    description = "Do the thing",
                ),
                instructions = instructions,
            ),
            constraints = ExecutionConstraints(
                requireTests = false,
                requireLinting = false,
            ),
            runId = runId,
        )
    }
}

/** One [OutcomeMemoryRepository.recordOutcome] call, kept whole so every argument is assertable. */
private data class RecordedCall(
    val ticketId: TicketId,
    val executorId: ExecutorId,
    val approach: String,
    val outcome: ExecutionOutcome,
    val timestamp: Instant,
    val runId: RunId?,
)

private class RecordingOutcomeRepository : OutcomeMemoryRepository {

    val calls = mutableListOf<RecordedCall>()

    override suspend fun recordOutcome(
        ticketId: TicketId,
        executorId: ExecutorId,
        approach: String,
        outcome: ExecutionOutcome,
        timestamp: Instant,
        runId: RunId?,
    ): Result<OutcomeMemory> {
        calls += RecordedCall(ticketId, executorId, approach, outcome, timestamp, runId)
        return Result.success(
            OutcomeMemory(
                id = "memory-${calls.size}",
                ticketId = ticketId,
                executorId = executorId,
                approach = approach,
                success = outcome is ExecutionOutcome.Success,
                executionDurationMs = 0L,
                filesChanged = 0,
                errorMessage = null,
                timestamp = timestamp,
            ),
        )
    }

    override suspend fun findSimilarOutcomes(description: String, limit: Int): Result<List<OutcomeMemory>> =
        Result.success(emptyList())

    override suspend fun getOutcomesByTicket(ticketId: TicketId): Result<List<OutcomeMemory>> =
        Result.success(emptyList())

    override suspend fun getOutcomesByExecutor(executorId: ExecutorId, limit: Int): Result<List<OutcomeMemory>> =
        Result.success(emptyList())
}

/** A store whose write blows up, to prove the tool call's own result survives it. */
private object ThrowingOutcomeRepository : OutcomeMemoryRepository {

    override suspend fun recordOutcome(
        ticketId: TicketId,
        executorId: ExecutorId,
        approach: String,
        outcome: ExecutionOutcome,
        timestamp: Instant,
        runId: RunId?,
    ): Result<OutcomeMemory> = throw IllegalStateException("the store is down")

    override suspend fun findSimilarOutcomes(description: String, limit: Int): Result<List<OutcomeMemory>> =
        Result.success(emptyList())

    override suspend fun getOutcomesByTicket(ticketId: TicketId): Result<List<OutcomeMemory>> =
        Result.success(emptyList())

    override suspend fun getOutcomesByExecutor(executorId: ExecutorId, limit: Int): Result<List<OutcomeMemory>> =
        Result.success(emptyList())
}
