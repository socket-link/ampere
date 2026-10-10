package link.socket.ampere.agents.definition

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.definition.code.CodeState
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.environment.EnvironmentService
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.execution.describeResult
import link.socket.ampere.agents.execution.executor.Executor
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.api.Ampere
import link.socket.ampere.api.fromEnvironment
import link.socket.ampere.db.Database

/**
 * AMPR-405 (B11): a factory-built agent can actually run a tool.
 *
 * No shipped construction path supplied an `Executor` before this: `AgentFactory` had no
 * parameter for one, `Ampere.fromEnvironment`'s bound factory passed none, and
 * `AmpereRuntime.create` left it null. With no executor
 * [AgentReasoning][link.socket.ampere.agents.domain.reasoning.AgentReasoning] builds no
 * `ToolExecutionEngine` at all, so every plan step naming a tool came back
 * "Tool execution engine not configured" — no tool had ever run through the engine in a
 * shipped path.
 *
 * These tests dispatch through the real engine and the real [FunctionExecutor], so the
 * assertion that a tool "ran" is the tool's own recorded invocation rather than the shape of
 * the returned outcome. No model is involved: a [FunctionTool] carrying no `ParameterStrategy`
 * takes the engine's generic path, which makes no LLM call.
 *
 * `jvmTest` because the in-memory SQLDelight driver behind [EnvironmentService] is JVM-only,
 * and `runBlocking` rather than `runTest` because the agent's phase lambdas hop to the IO
 * dispatcher inside a `withTimeout` a virtual clock would skip straight past.
 */
class FactoryExecutorWiringTest {

    private lateinit var scope: CoroutineScope
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var environmentService: EnvironmentService

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        database = Database(driver)
        environmentService = EnvironmentService.create(database = database, scope = scope)
    }

    @AfterTest
    fun tearDown() {
        // Cancel before closing: an agent's observability scope is this scope, so an event
        // still in flight would otherwise write to a closed driver.
        scope.cancel()
        driver.close()
    }

    @Test
    fun `a factory-built agent executes a FunctionTool it was given`() = runBlocking<Unit> {
        val probe = RecordingTool(PROBE_TOOL_ID)
        val agent = codeAgent(factory(additionalTools = setOf(probe.tool)))

        assertTrue(
            agent.canExecuteTools,
            "the factory supplies a FunctionExecutor by default, so its agents can dispatch",
        )

        val outcome = agent.runLLMToExecuteTask(stepNaming(PROBE_TOOL_ID), emptyList())

        assertEquals(
            1,
            probe.invocations.size,
            "the step named the tool and the agent has an executor, so the tool should have " +
                "run; before AMPR-405 it was refused as 'Tool execution engine not configured'",
        )
        assertTrue(
            outcome is Outcome.Success,
            "a dispatched tool that succeeded should settle the step as a success; " +
                "got ${outcome::class.simpleName} — ${outcome.reason()}",
        )
    }

    @Test
    fun `the bound factory from fromEnvironment supplies one too`() = runBlocking<Unit> {
        val probe = RecordingTool(PROBE_TOOL_ID)
        val instance = Ampere.fromEnvironment(
            environmentService = environmentService,
            knowledgeRepository = KnowledgeRepositoryImpl(database),
            workspace = WORKSPACE,
            agentScope = scope,
            database = database,
            tools = setOf(probe.tool),
        )

        val agent = codeAgent(assertNotNull(instance.agentFactory))

        assertTrue(agent.canExecuteTools, "the bound factory must not hand out inert agents")

        agent.runLLMToExecuteTask(stepNaming(PROBE_TOOL_ID), emptyList())

        assertEquals(
            1,
            probe.invocations.size,
            "an agent built off a composed AmpereInstance should run the tools the instance " +
                "was composed with",
        )
    }

    @Test
    fun `an explicitly executor-less factory builds agents that refuse and say why`() = runBlocking<Unit> {
        val probe = RecordingTool(PROBE_TOOL_ID)
        val agent = codeAgent(
            factory(additionalTools = setOf(probe.tool), executor = null),
        )

        assertFalse(
            agent.canExecuteTools,
            "`executor = null` is a declaration that these agents must not act",
        )

        val outcome = agent.runLLMToExecuteTask(stepNaming(PROBE_TOOL_ID), emptyList())

        assertEquals(0, probe.invocations.size, "nothing should dispatch without an executor")
        assertTrue(
            outcome is Outcome.Failure,
            "a tool step an agent cannot run is a failure; got ${outcome::class.simpleName}",
        )
        assertTrue(
            outcome.reason().contains("No executor configured"),
            "the refusal must name what is missing — the step used to report the bare " +
                "variant name, `tool=$PROBE_TOOL_ID failed: Failure`, which named nothing; " +
                "got: ${outcome.reason()}",
        )
    }

    /**
     * The spark stack narrows what the factory hands over (AMPR-400), and that still holds for
     * tools passed through `additionalTools`: [PROBE_TOOL_ID] works above because the bundled
     * `role-code` spark admits it. Pinned here so the tests above cannot be read as evidence
     * that an executor bypasses narrowing.
     */
    @Test
    fun `a supplied tool the role spark does not admit stays undispatchable`() = runBlocking<Unit> {
        val probe = RecordingTool("tool_no_spark_admits")
        val agent = codeAgent(factory(additionalTools = setOf(probe.tool)))

        val outcome = agent.runLLMToExecuteTask(stepNaming("tool_no_spark_admits"), emptyList())

        assertEquals(0, probe.invocations.size, "a withdrawn tool is unreachable, executor or not")
        assertTrue(
            outcome is Outcome.Failure,
            "the step should fail on the tool lookup; got ${outcome::class.simpleName}",
        )
    }

    private fun factory(
        additionalTools: Set<Tool<*>> = emptySet(),
        executor: Executor? = FunctionExecutor.create(),
    ): AgentFactory = AgentFactory(
        scope = scope,
        ticketOrchestrator = environmentService.ticketOrchestrator,
        workspace = ExecutionWorkspace(baseDirectory = WORKSPACE),
        createEventApi = environmentService::createEventApi,
        executor = executor,
        additionalTools = additionalTools,
    )

    private fun codeAgent(factory: AgentFactory): SparkBasedAgent<CodeState> =
        factory.create(AgentType.CODE)

    private fun stepNaming(toolId: String): Task.CodeChange =
        Task.CodeChange(
            id = "ampr-405-step",
            status = TaskStatus.Pending,
            description = "Run the probe tool",
            toolId = toolId,
        )

    /** A tool that records every invocation, so "it ran" is observable rather than inferred. */
    private class RecordingTool(id: String) {
        val invocations: MutableList<ExecutionRequest<*>> = CopyOnWriteArrayList()

        val tool: FunctionTool<ExecutionContext.NoChanges> = FunctionTool(
            id = id,
            name = "Recording $id",
            description = "test recording tool",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { request ->
                invocations += request
                ExecutionOutcome.NoChanges.Success(
                    executorId = request.context.executorId,
                    ticketId = request.context.ticket.id,
                    taskId = request.context.task.id,
                    executionStartTimestamp = Clock.System.now(),
                    executionEndTimestamp = Clock.System.now(),
                    message = "recorded by $id",
                )
            },
        )
    }

    private companion object {
        const val WORKSPACE: String = "/tmp/ampr405-factory-executor-workspace"

        /**
         * A tool id the bundled `role-code` spark's `allowedTools` admits, so the spark stack
         * does not withdraw the probe before dispatch can be reached.
         */
        const val PROBE_TOOL_ID: String = "run_tests"
    }
}

/**
 * What a plan's outcome says it did, through AMPR-412's one renderer. Plan execution folds its
 * steps into `ExecutionOutcome.NoChanges.*`, so a step's own error only reaches a caller
 * through the folded summary.
 */
private fun Outcome.reason(): String = (this as? ExecutionOutcome)?.describeResult() ?: ""
