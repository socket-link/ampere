package link.socket.ampere.agents.execution

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.ToolEvent
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepositoryImpl
import link.socket.ampere.agents.domain.reasoning.AgentLLMService
import link.socket.ampere.agents.domain.status.ExecutionStatus
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.bus.EventSerialBusFactory
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.request.ExecutionConstraints
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic

/**
 * AMPR-425: a tool call that is waiting on a person, at the engine seam.
 *
 * Through the production [FunctionExecutor], because the executor protocol is where a
 * hold used to be lost: its terminal statuses could only say completed or failed, so a
 * held outcome came out of [FunctionExecutor] as "Unexpected outcome type". What the
 * engine owes a hold is three things — the outcome back unchanged, a completed event
 * that says held and names no error, and no row in the outcome store, whose `success`
 * column can only say succeeded or failed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ToolExecutionEngineHeldTest {

    private val scope = TestScope(UnconfinedTestDispatcher())

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var eventRepository: EventRepository
    private lateinit var eventApi: AgentEventApi

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        database = Database(driver)
        eventRepository = EventRepository(DEFAULT_JSON, scope, database)
        eventApi = AgentEventApi(
            agentId = "test-agent",
            eventRepository = eventRepository,
            eventSerialBus = EventSerialBusFactory(scope).create(),
        )
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `a tool that returns Held ends in a Held status, not a failed one`() = runTest {
        val statuses = FunctionExecutor.create().execute(request(), holdingTool()).toList()

        val last = assertIs<ExecutionStatus.Held>(statuses.last())
        assertTrue(last.isClosed, "Held is a terminal status")
        assertEquals(REASON, last.result.reason)
        assertTrue(
            statuses.none { it is ExecutionStatus.Completed || it is ExecutionStatus.Failed },
            "a hold is neither a completion nor a failure: $statuses",
        )
    }

    @Test
    fun `a held tool call comes back through the engine unchanged`() = runTest {
        val outcome = engine().execute(holdingTool(), request())

        val held = assertIs<ExecutionOutcome.Held>(outcome)
        assertEquals(REASON, held.reason)
        assertEquals(REFERENCE, held.reference)
    }

    @Test
    fun `a held tool call publishes held = true and no error message`() = runTest {
        engine().execute(holdingTool(), request(runId = "held-run"))

        val events = assertNotNull(eventRepository.getAllEvents().getOrNull())
        val completed = events.filterIsInstance<ToolEvent.ToolExecutionCompleted>().single()
        assertTrue(completed.held, "the completion says held")
        assertFalse(completed.success, "a call waiting on a person did not succeed")
        assertNull(completed.errorMessage, "and did not fail, so there is no error to name")
        assertEquals(Urgency.LOW, completed.urgency, "and no failure urgency")
        assertEquals("held-run", completed.runId)
    }

    @Test
    fun `a held tool call writes no outcome row`() = runTest {
        val store = OutcomeMemoryRepositoryImpl(database)
        val request = request(runId = "held-run")

        engine(store).execute(holdingTool(), request)

        assertTrue(
            store.getOutcomesByTicket(request.context.ticket.id).getOrThrow().isEmpty(),
            "a hold is neither a success nor a failure, and the store can only say one of those",
        )
    }

    @Test
    fun `the outcome store refuses a held outcome rather than writing it as a failure`() = runTest {
        val store = OutcomeMemoryRepositoryImpl(database)
        val now = Clock.System.now()

        val result = store.recordOutcome(
            ticketId = "ticket-held",
            actorId = "agent-1",
            executorId = "test-executor",
            approach = "send_reply: send it",
            outcome = ExecutionOutcome.Held(
                executorId = "test-executor",
                ticketId = "ticket-held",
                taskId = "task-held",
                executionStartTimestamp = now,
                executionEndTimestamp = now,
                reason = REASON,
            ),
            timestamp = now,
            runId = null,
        )

        assertTrue(result.isFailure)
        assertTrue(store.getOutcomesByTicket("ticket-held").getOrThrow().isEmpty())
    }

    private fun engine(store: OutcomeMemoryRepositoryImpl? = null): ToolExecutionEngine {
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
            actorId = "agent-1",
            eventApi = eventApi,
            outcomeRepository = store,
        )
    }

    private fun holdingTool() = FunctionTool<ExecutionContext.NoChanges>(
        id = "send_reply",
        name = "Send reply",
        description = "Sends on a person's behalf once they approve",
        requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
        executionFunction = { request ->
            val now = Clock.System.now()
            ExecutionOutcome.Held(
                executorId = request.context.executorId,
                ticketId = request.context.ticket.id,
                taskId = request.context.task.id,
                executionStartTimestamp = now,
                executionEndTimestamp = now,
                reason = REASON,
                reference = REFERENCE,
            )
        },
    )

    private fun request(runId: String? = null): ExecutionRequest<ExecutionContext.NoChanges> {
        val now = Clock.System.now()
        return ExecutionRequest(
            context = ExecutionContext.NoChanges(
                actorId = "test-agent",
                executorId = "test-executor",
                ticket = Ticket(
                    id = "ticket-1",
                    title = "Answer the thread",
                    description = "Answer the thread",
                    type = TicketType.TASK,
                    priority = TicketPriority.MEDIUM,
                    status = TicketStatus.InProgress,
                    assignedAgentId = "test-agent",
                    createdByAgentId = "test-agent",
                    createdAt = now,
                    updatedAt = now,
                ),
                task = Task.CodeChange(
                    id = "task-1",
                    status = TaskStatus.Pending,
                    description = "Send the reply",
                ),
                instructions = "Send the reply",
            ),
            constraints = ExecutionConstraints(requireTests = false, requireLinting = false),
            runId = runId,
        )
    }

    private companion object {
        const val REASON = "waiting for the account owner to approve sending"
        const val REFERENCE = "approval-request-17"
    }
}
