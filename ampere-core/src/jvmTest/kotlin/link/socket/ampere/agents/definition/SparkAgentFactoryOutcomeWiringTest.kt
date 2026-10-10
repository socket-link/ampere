package link.socket.ampere.agents.definition

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepository
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepositoryImpl
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.execution.executor.NoOpExecutor
import link.socket.ampere.agents.execution.request.ExecutionConstraints
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.api.internal.DefaultOutcomeService
import link.socket.ampere.db.Database

/**
 * AMPR-406 (F20), through the whole thread an Arc run uses: [SparkAgentFactory] → agent →
 * `AgentReasoning` → `ToolExecutionEngine` → a real SQL-backed
 * [OutcomeMemoryRepositoryImpl]. The engine-level facts are in
 * `ToolExecutionEngineOutcomeRecordingTest`; what is proved here is that the store and the
 * run id actually reach it from the factory, which is where a dropped parameter would hide.
 *
 * Note the pairing the factory's KDoc warns about: the executor is what makes a
 * tool-execution engine exist at all, so a store supplied without one records nothing.
 */
class SparkAgentFactoryOutcomeWiringTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var outcomes: OutcomeMemoryRepository

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        database = Database(driver)
        outcomes = OutcomeMemoryRepositoryImpl(database, driver)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `a factory-built agent's tool call lands in the store under the run id`() {
        val agent = createAgent(outcomeRepository = outcomes)

        val outcome = agent.runLLMToExecuteTool(tool(), request())
        assertIs<ExecutionOutcome.Success>(outcome, "NoOpExecutor reports success")

        runBlocking {
            val forTicket = DefaultOutcomeService(outcomes).forTicket("ticket-1").getOrThrow()
            val recorded = forTicket.single()
            assertEquals("notify: Send a notification", recorded.approach)
            assertTrue(recorded.success)

            // The run column is what `ArcTraceProjection` folds on, so assert it directly
            // rather than through `forTicket`, which cannot see it.
            val byRun = database.outcomeMemoryStoreQueries
                .getOutcomesByRunId(RUN_ID)
                .executeAsList()
            assertEquals(listOf(recorded.id), byRun.map { it.id })
        }
    }

    @Test
    fun `a factory-built agent with no store records nothing`() {
        val agent = createAgent(outcomeRepository = null)

        agent.runLLMToExecuteTool(tool(), request())

        runBlocking {
            assertTrue(DefaultOutcomeService(outcomes).forTicket("ticket-1").getOrThrow().isEmpty())
        }
    }

    private fun createAgent(outcomeRepository: OutcomeMemoryRepository?): SparkBasedAgent<*> =
        SparkAgentFactory(
            scope = CoroutineScope(Dispatchers.Default),
            workspace = ExecutionWorkspace(baseDirectory = "/tmp/ampr406-test-workspace"),
            executor = NoOpExecutor(),
            outcomeRepository = outcomeRepository,
            runId = RUN_ID,
        ).createAgent(
            id = "test-agent",
            affinity = CognitiveAffinity.ANALYTICAL,
        )

    /** No parameter strategy, so the engine dispatches generically and spends no LLM call. */
    private fun tool(): FunctionTool<ExecutionContext> =
        FunctionTool(
            id = "notify",
            name = "Notify",
            description = "Sends a notification",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = {
                error("The executor is stubbed out — the tool's own function should never run")
            },
        )

    private fun request(): ExecutionRequest<ExecutionContext.NoChanges> {
        val now = Clock.System.now()
        return ExecutionRequest(
            context = ExecutionContext.NoChanges(
                executorId = "test-agent",
                ticket = Ticket(
                    id = "ticket-1",
                    title = "Test ticket",
                    description = "Test ticket description",
                    type = TicketType.TASK,
                    priority = TicketPriority.MEDIUM,
                    status = TicketStatus.InProgress,
                    assignedAgentId = "test-agent",
                    createdByAgentId = "test-agent",
                    createdAt = now,
                    updatedAt = now,
                    dueDate = null,
                ),
                task = Task.CodeChange(
                    id = "task-1",
                    status = TaskStatus.Pending,
                    description = "Send a notification",
                ),
                instructions = "Send a notification",
            ),
            constraints = ExecutionConstraints(
                requireTests = false,
                requireLinting = false,
            ),
        )
    }

    private companion object {
        const val RUN_ID = "arc-run-ampr406"
    }
}
