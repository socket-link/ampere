package link.socket.ampere.agents.definition

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.definition.code.CodeState
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.cognition.sparks.DefaultPhaseSparkLibrary
import link.socket.ampere.agents.domain.cognition.sparks.PhaseSparkLibrary
import link.socket.ampere.agents.domain.event.ProviderCallStartedEvent
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database
import link.socket.ampere.domain.llm.LlmProvider

/**
 * AMPR-396: executing a plan costs one PLAN call — the one the Plan phase
 * already made — not one more per step.
 *
 * [SparkBasedAgent.runLLMToExecuteTask] used to call `generatePlan` again for
 * every step [AutonomousAgent.executePlan] handed it, so a three-step plan
 * billed four PLAN calls and dispatched a sub-plan's steps instead of the ones
 * the loop had just planned. The count here is read off the persisted
 * `ProviderCallStartedEvent` rows — the same telemetry `ArcTraceProjection`
 * bills a run from — rather than off a counter in a test double.
 *
 * Lives in `jvmTest` because the in-memory SQLDelight driver backing the real
 * [EventRepository] is JVM-only, and uses `runBlocking` rather than `runTest`
 * because the agent's phase lambdas hop to the IO dispatcher inside
 * `withTimeout`, which a virtual clock skips straight past.
 */
class SparkBasedAgentPlanStepDispatchTest {

    private val phaseSparkLibrary: PhaseSparkLibrary = runBlocking { DefaultPhaseSparkLibrary.load() }

    private lateinit var scope: CoroutineScope
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var eventRepository: EventRepository
    private lateinit var eventApi: AgentEventApi

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        eventRepository = EventRepository(DEFAULT_JSON, scope, Database(driver))
        eventApi = AgentEventApi(
            agentId = "plan-step-dispatch-agent",
            eventRepository = eventRepository,
            eventSerialBus = EventSerialBus(scope),
        )
    }

    @AfterTest
    fun tearDown() {
        // Cancel before closing: the agent's observability scope is this scope, so a
        // spark event still in flight would otherwise write to a closed driver.
        scope.cancel()
        driver.close()
    }

    @Test
    fun `a three-step plan costs one PLAN call not four`() = runBlocking<Unit> {
        val prompts = mutableListOf<String>()
        val agent = agentAnswering(THREE_STEP_PLAN_JSON, prompts)

        val plan = agent.determinePlanForTask(task = goalTask())
        assertEquals(3, plan.tasks.size, "the fixture plans three steps")

        val outcome = agent.executePlan(plan)

        assertEquals(
            1,
            planCalls().size,
            "executing a three-step plan re-plans nothing, so only the Plan phase's " +
                "own PLAN call should be billed (the AMPR-396 bug billed one more per " +
                "step, so four)",
        )
        assertEquals(
            3,
            callsUnder(CognitivePhase.EXECUTE).size,
            "each of the three tool-less steps is carried out by one EXECUTE call " +
                "(AMPR-407), and none of them is a PLAN call",
        )
        assertEquals(
            4,
            prompts.size,
            "one planning prompt plus one per reasoning step",
        )
        assertTrue(
            outcome is Outcome.Success,
            "every step reached a conclusion, so the plan should succeed; " +
                "got ${outcome::class.simpleName}",
        )
    }

    @Test
    fun `runSubPlanForTask is the one path that spends a further PLAN call`() = runBlocking<Unit> {
        val agent = agentAnswering(THREE_STEP_PLAN_JSON, mutableListOf())

        agent.runSubPlanForTask(
            Task.CodeChange(
                id = "coarse-step",
                status = TaskStatus.Pending,
                description = "Too coarse to dispatch directly",
            ),
        )

        assertEquals(
            1,
            planCalls().size,
            "the opt-in sub-cycle makes the PLAN call the default path no longer makes",
        )
    }

    /**
     * The planning prompt asks for `"toolToUse": "tool ID or null"`, and a step
     * answered with JSON `null` has to arrive as `toolId == null`. `JsonNull` is
     * itself a `JsonPrimitive` whose `content` is the string `"null"`, so reading
     * the field as `jsonPrimitive.content` nominated a tool named `null` — which
     * now matters, because the step's own `toolId` is what gets dispatched.
     */
    @Test
    fun `a step the planner marked tool-less parses as a null toolId`() = runBlocking<Unit> {
        val agent = agentAnswering(MIXED_TOOL_PLAN_JSON, mutableListOf())

        val plan = agent.determinePlanForTask(task = goalTask())

        assertEquals(
            listOf(null, "write_code_file", null),
            plan.tasks.map { (it as Task.CodeChange).toolId },
            "JSON null, a real tool id, and the string \"null\" should read as " +
                "null / the id / null",
        )
    }

    private fun agentAnswering(response: String, prompts: MutableList<String>): SparkBasedAgent<CodeState> {
        val provider: LlmProvider = { prompt ->
            prompts += prompt
            response
        }
        return SparkBasedAgent.Code(
            sparkRegistry = phaseSparkLibrary,
            agentId = eventApi.agentId,
            eventApi = eventApi,
            llmProvider = provider,
            observabilityScope = scope,
        )
    }

    private fun goalTask(): Task.CodeChange =
        Task.CodeChange(
            id = "ampr-396-task",
            status = TaskStatus.Pending,
            description = "Ship the feature",
        )

    private suspend fun planCalls(): List<ProviderCallStartedEvent> =
        callsUnder(CognitivePhase.PLAN)

    private suspend fun callsUnder(phase: CognitivePhase): List<ProviderCallStartedEvent> =
        eventRepository.getAllEvents()
            .getOrThrow()
            .filterIsInstance<ProviderCallStartedEvent>()
            .filter { it.cognitivePhase == phase }

    private companion object {
        /**
         * Three steps nominating no tool, so each is carried out by one EXECUTE model call
         * and none of them dispatches a tool (AMPR-407).
         */
        const val THREE_STEP_PLAN_JSON: String = """
            {
              "steps": [
                { "description": "Read the surrounding code", "toolToUse": null, "requiresPreviousStep": false },
                { "description": "Write the change", "toolToUse": null, "requiresPreviousStep": true },
                { "description": "Verify the change", "toolToUse": null, "requiresPreviousStep": true }
              ],
              "estimatedComplexity": 3,
              "requiresHumanInput": false
            }
        """

        /** The three shapes a model answers `toolToUse` with: JSON null, a tool id, the string "null". */
        const val MIXED_TOOL_PLAN_JSON: String = """
            {
              "steps": [
                { "description": "Read the surrounding code", "toolToUse": null },
                { "description": "Write the change", "toolToUse": "write_code_file" },
                { "description": "Think about it", "toolToUse": "null" }
              ],
              "estimatedComplexity": 2,
              "requiresHumanInput": false
            }
        """
    }
}
