package link.socket.ampere.agents.definition

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.execution.executor.NoOpExecutor
import link.socket.ampere.agents.execution.request.ExecutionConstraints
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.db.Database
import link.socket.ampere.plug.PlugId
import link.socket.ampere.plug.PlugManifest
import link.socket.ampere.plug.permission.PlugPermission
import link.socket.ampere.plug.permission.SqlDelightUserGrantStore

/**
 * Verifies the AMPR-348 fix: an agent built through [SparkAgentFactory] with a
 * `database` supplied gates plug-tool dispatch against the persisted
 * [link.socket.ampere.plug.permission.UserGrantStore] instead of the
 * `ExecutionSettingsBuilder` deny-all default.
 */
class SparkAgentFactoryUserGrantWiringTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        database = Database(driver)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `agent dispatches a plug tool once the store holds the required grant`() = runTest {
        val permission = PlugPermission.NativeAction("send-notification")
        val manifest = PlugManifest(
            id = PlugId("example-plug"),
            name = "Example Plug",
            version = "1.0.0",
            requiredPermissions = listOf(permission),
        )
        val tool = plugTool(manifest)
        val agent = createAgent()

        val beforeGrant = agent.runLLMToExecuteTool(tool, request())
        val deniedFailure = assertIs<ExecutionOutcome.NoChanges.Failure>(beforeGrant)
        assertTrue(deniedFailure.message.contains("Permission denied"))

        SqlDelightUserGrantStore(database).grant(
            plugId = manifest.id,
            permission = permission,
            grantedAt = Instant.fromEpochMilliseconds(1_000),
        ).getOrThrow()

        val afterGrant = agent.runLLMToExecuteTool(tool, request())
        assertIs<ExecutionOutcome.NoChanges.Success>(afterGrant)
    }

    private fun createAgent(): SparkBasedAgent<*> =
        SparkAgentFactory(
            scope = CoroutineScope(Dispatchers.Default),
            executor = NoOpExecutor(),
            database = database,
        ).createAgent(
            id = "test-agent",
            affinity = CognitiveAffinity.ANALYTICAL,
        )

    private fun plugTool(manifest: PlugManifest): FunctionTool<ExecutionContext> =
        FunctionTool(
            id = "notify",
            name = "Notify",
            description = "Sends a notification",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            plugManifest = manifest,
            executionFunction = {
                error("The executor is stubbed out — the tool's own function should never run")
            },
        )

    private fun request(): ExecutionRequest<ExecutionContext.NoChanges> {
        val now = Clock.System.now()
        val ticket = Ticket(
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
        )
        val task = Task.CodeChange(
            id = "task-1",
            status = TaskStatus.Pending,
            description = "Send a notification",
        )

        return ExecutionRequest(
            context = ExecutionContext.NoChanges(
                executorId = "test-agent",
                ticket = ticket,
                task = task,
                instructions = "Send a notification",
            ),
            constraints = ExecutionConstraints(
                requireTests = false,
                requireLinting = false,
            ),
        )
    }
}
