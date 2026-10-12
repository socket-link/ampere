package link.socket.ampere.agents.definition

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.definition.code.CodeState
import link.socket.ampere.agents.domain.cognition.sparks.DefaultPhaseSparkLibrary
import link.socket.ampere.agents.domain.cognition.sparks.PhaseSparkLibrary
import link.socket.ampere.agents.domain.event.PlanEvent
import link.socket.ampere.agents.domain.event.ToolEvent
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.reasoning.AgentLLMService
import link.socket.ampere.agents.domain.reasoning.AgentReasoning
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.reasoning.PlanExecutionResult
import link.socket.ampere.agents.domain.reasoning.PlanExecutor
import link.socket.ampere.agents.domain.reasoning.StepResult
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.bus.EventSerialBusFactory
import link.socket.ampere.agents.execution.ToolExecutionEngine
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic

/**
 * AMPR-446: a plan step whose tool returns [ExecutionOutcome.Blank] is a skipped step.
 *
 * The tool ran and had nothing to do (AMPR-420). `executePlanStep` reported that as a
 * success through its `else`, while `AutonomousAgent.asPriorResult` called the same
 * outcome skipped, so a plan said a step was done that did nothing. The ruling (AMPR-328
 * Q11) is that it is skipped, and it is skipped on every reader of the step: the
 * [StepResult], the [PlanEvent.PlanStepCompleted] the walk publishes, and the chain the
 * steps after it are handed. The tool *call* did not fail, so its event pair still says
 * success.
 *
 * Through a real [ToolExecutionEngine] and [PlanExecutor], because the tool event pair and
 * the step event pair are both under test and only those two publish them. There are two
 * walks of a plan and both are covered: the one a roster-hosted run drives (`PlanExecutor`
 * over `executePlanStep`), and the Arc's (`AutonomousAgent.executePlan`), which hands each
 * step to `PlanExecutor` as its own one-step plan and so used to receive a skip folded into
 * a success.
 *
 * `runBlocking` rather than `runTest`: the agent's phase lambdas hop to the IO dispatcher
 * inside `withTimeout`, which a virtual clock walks straight past.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BlankStepIsSkippedTest {

    private val phaseSparkLibrary: PhaseSparkLibrary = runBlocking { DefaultPhaseSparkLibrary.load() }
    private val scope = TestScope(UnconfinedTestDispatcher())

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var eventRepository: EventRepository
    private lateinit var eventApi: AgentEventApi

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        eventRepository = EventRepository(DEFAULT_JSON, scope, Database(driver))
        eventApi = AgentEventApi(
            agentId = "blank-step-agent",
            eventRepository = eventRepository,
            eventSerialBus = EventSerialBusFactory(scope).create(),
        )
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `a tool that returns Blank ends its step Skipped naming the tool`() {
        val blank = RecordingTool(id = "git_stage", returns = blankOutcome)
        val agent = agentWith(blank)

        val result = runBlocking {
            agent.executePlanStep(
                step = step("step-1", "stage the changes", toolId = blank.id),
                parentTask = parent(),
                priorResults = emptyList(),
            )
        }

        val skipped = assertIs<StepResult.Skipped>(result)
        assertEquals("stage the changes", skipped.description)
        assertEquals("tool=git_stage returned nothing to execute", skipped.reason)
        assertEquals(1, blank.invocations.size, "the tool did run")
    }

    @Test
    fun `PlanStepCompleted carries the skip`() {
        val blank = RecordingTool(id = "git_stage", returns = blankOutcome)
        val after = RecordingTool(id = "git_commit", returns = ::committed)

        walk(agentWith(blank, after), blankThenCommit(blank, after))

        val completed = stepCompleted("step-1")
        val skipped = assertIs<StepOutcome.Skipped>(completed.outcome)
        assertEquals("tool=git_stage returned nothing to execute", skipped.reason)
        assertEquals("stage the changes", completed.stepDescription)
    }

    @Test
    fun `the next step is handed the blank step as Skipped`() {
        val blank = RecordingTool(id = "git_stage", returns = blankOutcome)
        val after = RecordingTool(id = "git_commit", returns = ::committed)

        val result = walk(agentWith(blank, after), blankThenCommit(blank, after))

        assertEquals(1, after.invocations.size, "a skip does not stop the walk")
        val handed = assertIs<StepOutcome.Skipped>(after.invocations.single().priorResults.single())
        assertEquals("step-1", handed.id)
        assertEquals("tool=git_stage returned nothing to execute", handed.reason)
        assertIs<StepOutcome.Success>(result.stepOutcomes[1])
        assertIs<ExecutionOutcome.NoChanges.Success>(result.outcome)
    }

    @Test
    fun `the blank tool call itself still completes as a success`() {
        val blank = RecordingTool(id = "git_stage", returns = blankOutcome)
        val after = RecordingTool(id = "git_commit", returns = ::committed)

        walk(agentWith(blank, after), blankThenCommit(blank, after))

        val completed = toolEvents().single { it.toolId == blank.id }
        assertTrue(completed.success, "the tool ran and did not fail; only the step is skipped")
        assertNull(completed.errorMessage)
    }

    /**
     * Out of scope here and pinned as it is: `PlanExecutor` folds a walk with no failure and
     * no hold to a success, skipped steps included. A plan of nothing but blank steps is
     * therefore a `NoChanges.Success` whose summary counts no successes and every skip.
     */
    @Test
    fun `a plan of only blank steps folds to PlanExecutor's all-skipped answer`() {
        val first = RecordingTool(id = "git_stage", returns = blankOutcome)
        val second = RecordingTool(id = "git_status", returns = blankOutcome)
        val plan = Plan.ForTask(
            task = parent(),
            tasks = listOf(
                step("step-1", "stage the changes", toolId = first.id),
                step("step-2", "check the working tree", toolId = second.id),
            ),
            estimatedComplexity = 2,
        )

        val result = walk(agentWith(first, second), plan)

        assertEquals(1, first.invocations.size)
        assertEquals(1, second.invocations.size, "a skip does not stop the walk")
        assertTrue(result.stepOutcomes.all { it is StepOutcome.Skipped }, "got ${result.stepOutcomes}")
        val folded = assertIs<ExecutionOutcome.NoChanges.Success>(result.outcome)
        assertContains(folded.message, "✓ Success: 0")
        assertContains(folded.message, "⊘ Skipped: 2")
        assertEquals(0, result.completedSteps)
    }

    @Test
    fun `on the Arc walk the next step is handed the blank step as Skipped`() {
        val blank = RecordingTool(id = "git_stage", returns = blankOutcome)
        val after = RecordingTool(id = "git_commit", returns = ::committed)

        val outcome = runBlocking { agentWith(blank, after).executePlan(blankThenCommit(blank, after)) }

        assertEquals(1, after.invocations.size, "a skip does not stop the walk")
        val handed = assertIs<StepOutcome.Skipped>(after.invocations.single().priorResults.single())
        assertEquals("tool=git_stage returned nothing to execute", handed.reason)
        val folded = assertIs<ExecutionOutcome.NoChanges.Success>(outcome)
        assertContains(folded.message, "committed", message = "the walk's outcome is the step that did something")
    }

    @Test
    fun `on the Arc walk a skip after a success leaves the success as the outcome`() {
        val first = RecordingTool(id = "git_commit", returns = ::committed)
        val blank = RecordingTool(id = "git_stage", returns = blankOutcome)
        val plan = Plan.ForTask(
            task = parent(),
            tasks = listOf(
                step("step-1", "commit the changes", toolId = first.id),
                step("step-2", "stage what is left", toolId = blank.id),
            ),
            estimatedComplexity = 2,
        )

        val outcome = runBlocking { agentWith(first, blank).executePlan(plan) }

        assertEquals(1, blank.invocations.size)
        val folded = assertIs<ExecutionOutcome.NoChanges.Success>(outcome)
        assertContains(folded.message, "committed", message = "a skip neither succeeds nor fails the walk")
    }

    /**
     * The completion answer the Arc reads does not change: a walk of nothing but skips
     * settles to the success that did nothing `PlanExecutor` makes of the same steps, with
     * the skip's reason as its message.
     */
    @Test
    fun `on the Arc walk a plan of only blank steps settles to a success carrying the skip reason`() {
        val blank = RecordingTool(id = "git_stage", returns = blankOutcome)
        val plan = Plan.ForTask(
            task = parent(),
            tasks = listOf(step("step-1", "stage the changes", toolId = blank.id)),
            estimatedComplexity = 1,
        )

        val outcome = runBlocking { agentWith(blank).executePlan(plan) }

        val folded = assertIs<ExecutionOutcome.NoChanges.Success>(outcome)
        assertIs<Outcome.Success>(outcome)
        assertEquals("tool=git_stage returned nothing to execute", folded.message)
        assertEquals("parent", folded.taskId)
        assertEquals(1, blank.invocations.size)
    }

    /** `runTask` is read as a verdict (`CodeIssueWorkflow`), so a lone skip settles there too. */
    @Test
    fun `runTask on a blank step settles to a success and not an unexpected outcome`() {
        val blank = RecordingTool(id = "git_stage", returns = blankOutcome)

        val outcome = runBlocking {
            agentWith(blank).runTask(step("step-1", "stage the changes", toolId = blank.id))
        }

        val folded = assertIs<ExecutionOutcome.NoChanges.Success>(outcome)
        assertEquals("tool=git_stage returned nothing to execute", folded.message)
        assertEquals("step-1", folded.taskId)
    }

    // ========================================================================
    // Fixture
    // ========================================================================

    /**
     * A tool that records each request it is dispatched with and answers with [returns].
     * Named with ids the Code role's spark allows, because dispatch is against the narrowed
     * set.
     */
    private class RecordingTool(
        val id: String,
        returns: (ExecutionRequest<*>) -> ExecutionOutcome,
    ) {
        val invocations: MutableList<ExecutionRequest<*>> = CopyOnWriteArrayList()

        val tool: FunctionTool<ExecutionContext.NoChanges> = FunctionTool(
            id = id,
            name = "Recording $id",
            description = "records its requests",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { request ->
                invocations += request
                returns(request)
            },
        )
    }

    /** What a tool that ran and had nothing to do returns (AMPR-420). */
    private val blankOutcome: (ExecutionRequest<*>) -> ExecutionOutcome = { ExecutionOutcome.blank }

    private fun committed(request: ExecutionRequest<*>): ExecutionOutcome {
        val now = Clock.System.now()
        return ExecutionOutcome.NoChanges.Success(
            executorId = request.context.executorId,
            ticketId = request.context.ticket.id,
            taskId = request.context.task.id,
            executionStartTimestamp = now,
            executionEndTimestamp = now,
            message = "committed",
        )
    }

    /** The plan walk a roster-hosted run drives: [PlanExecutor] over `executePlanStep`. */
    private fun walk(agent: SparkBasedAgent<CodeState>, plan: Plan.ForTask): PlanExecutionResult =
        runBlocking {
            PlanExecutor(executorId = "blank-step-test", eventApi = eventApi)
                .execute(plan, priorResults = emptyList()) { step, context ->
                    agent.executePlanStep(step, parentTask = plan.task, priorResults = context.priorResults)
                }
        }

    private fun blankThenCommit(blank: RecordingTool, after: RecordingTool): Plan.ForTask = Plan.ForTask(
        task = parent(),
        tasks = listOf(
            step("step-1", "stage the changes", toolId = blank.id),
            step("step-2", "commit the changes", toolId = after.id),
        ),
        estimatedComplexity = 2,
    )

    private fun stepCompleted(stepId: String): PlanEvent.PlanStepCompleted =
        storedEvents()
            .filterIsInstance<PlanEvent.PlanStepCompleted>()
            .single { it.stepId == stepId }

    private fun toolEvents(): List<ToolEvent.ToolExecutionCompleted> =
        storedEvents().filterIsInstance<ToolEvent.ToolExecutionCompleted>()

    private fun storedEvents() = assertNotNull(runBlocking { eventRepository.getAllEvents() }.getOrNull())

    private fun parent(): Task.Step = Task.Step(
        id = "parent",
        status = TaskStatus.Pending,
        description = "commit the work",
    )

    private fun step(id: String, description: String, toolId: String): Task.Step = Task.Step(
        id = id,
        status = TaskStatus.Pending,
        description = description,
        toolId = toolId,
    )

    /**
     * An agent whose tool calls go through a real [ToolExecutionEngine] publishing through
     * the test's door, so the tool event pair is the production one.
     */
    private fun agentWith(vararg tools: RecordingTool): SparkBasedAgent<CodeState> {
        val engine = engine()
        val reasoning = AgentReasoning.createForTesting(actorId = "blank-step-test") {
            onPlanning { _, _ -> error("executing a step must not re-plan it (AMPR-396)") }
            onToolExecution { tool, request -> runBlocking { engine.execute(tool, request) } }
        }
        return SparkBasedAgent.Code(
            sparkRegistry = phaseSparkLibrary,
            agentId = "blank-step-agent",
            tools = tools.map { it.tool }.toSet<Tool<*>>(),
            reasoningOverride = reasoning,
        )
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
            actorId = "blank-step-agent",
            eventApi = eventApi,
        )
    }
}
