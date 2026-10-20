package link.socket.ampere.propel

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.definition.SparkBasedAgent
import link.socket.ampere.agents.definition.code.CodeState
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.event.AdmissionRecord
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.PermissionDeniedEvent
import link.socket.ampere.agents.domain.event.PlanEvent
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.outcome.TrippedRule
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.reasoning.PlanExecutionResult
import link.socket.ampere.agents.domain.reasoning.PlanExecutor
import link.socket.ampere.agents.domain.reasoning.StepResult
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.InMemoryEventDoor
import link.socket.ampere.agents.events.StoredEvent
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.field.Hardness
import link.socket.ampere.field.Rule
import link.socket.ampere.plug.PlugId
import link.socket.ampere.plug.PlugManifest
import link.socket.ampere.plug.permission.PlugPermission
import link.socket.ampere.plug.permission.UserGrants

/**
 * AMPR-472 (F33): every step `dispatchPlanStep` admits leaves a `PlanEvent.StepAdmitted`
 * before it runs, and a step a rule stopped says so on its outcome.
 *
 * Drives the walk both drivers use — `PlanExecutor` over `dispatchPlanStep` — with a real
 * door, so the step quota is read from the `PlanStepCompleted`s the walk itself persists.
 * A hosted run can only put Hard rules on a step, so the Soft half of the record is shown
 * here, with the rules handed to `dispatchPlanStep` directly: a Soft step cap of one and a
 * Hard step cap of two over three steps pass the first, resist the second and trip the
 * third. `HostedRunAdmissionTest` drives the same record through a whole hosted run.
 *
 * `runBlocking`, not `runTest`: a door's publish hops to the IO dispatcher and a virtual
 * clock walks straight past it.
 */
class StepAdmittedTest {

    private lateinit var scope: CoroutineScope
    private lateinit var door: InMemoryEventDoor.Handle

    /** Every request a tool's own function was dispatched with. */
    private val dispatched = CopyOnWriteArrayList<ExecutionRequest<*>>()

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        door = InMemoryEventDoor.open(agentId = AGENT_ID, scope = scope)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        door.close()
    }

    @Test
    fun `one Soft and one Hard rule over three steps record Pass then Resist then Trip in step order`() =
        runBlocking<Unit> {
            val result = walk(agent(), STEPS, rules = listOf(SOFT_CAP to Hardness.SOFT, HARD_CAP to Hardness.HARD))

            assertIs<StepOutcome.Success>(result.stepOutcomes[0])
            assertIs<StepOutcome.Success>(result.stepOutcomes[1], "a resisted step still runs")
            val stopped = assertIs<StepOutcome.Failure>(result.stepOutcomes[2])
            assertEquals(2, dispatched.size, "the tripped step's tool never ran")

            val record = record()
            val admitted = record.events<PlanEvent.StepAdmitted>()
            assertEquals(STEPS.map { it.id }, admitted.map { it.stepId }, "one per step, in step order")
            assertEquals(AdmissionRecord.Pass, admitted[0].admission)
            assertEquals(AdmissionRecord.Resist(degree = 1.0, rules = listOf(STEP_CAP_NAME)), admitted[1].admission)
            val trip = assertIs<AdmissionRecord.Trip>(admitted[2].admission)
            assertEquals(STEP_CAP_NAME, trip.rule)
            assertTrue(trip.reason.contains("step quota reached"), trip.reason)

            // The trip is typed on the outcome the walk returned and on the one it recorded,
            // and the error a reader of text had is what it was.
            assertEquals(TrippedRule(rule = trip.rule, reason = trip.reason), stopped.tripped)
            assertEquals(trip.reason, stopped.error)
            val completed = record.events<PlanEvent.PlanStepCompleted>()
            assertEquals(trip.rule, assertIs<StepOutcome.Failure>(completed[2].outcome).tripped?.rule)

            // Each admission sits inside its own step's pair, names the same plan, and is
            // the seat's, under the run.
            val started = record.events<PlanEvent.PlanStepStarted>()
            STEPS.forEach { step ->
                val startedAt = record.indexOfFirst { (it.event as? PlanEvent.PlanStepStarted)?.stepId == step.id }
                val admittedAt = record.indexOfFirst { (it.event as? PlanEvent.StepAdmitted)?.stepId == step.id }
                val completedAt = record.indexOfFirst { (it.event as? PlanEvent.PlanStepCompleted)?.stepId == step.id }
                assertTrue(
                    startedAt < admittedAt && admittedAt < completedAt,
                    "${step.id}: started at $startedAt, admitted at $admittedAt, completed at $completedAt",
                )
            }
            assertEquals(started.map { it.planId }, admitted.map { it.planId })
            admitted.forEach { event ->
                assertEquals(RUN_ID, event.runId)
                assertEquals(AGENT_ID, event.eventSource.getIdentifier())
            }
        }

    @Test
    fun `an access trip is recorded under the plug and its denial is still published once`() = runBlocking<Unit> {
        val result = walk(agent(grants = null), listOf(PLUG_STEP), rules = emptyList())

        val stopped = assertIs<StepOutcome.Failure>(result.stepOutcomes.single())
        val trip = assertIs<AdmissionRecord.Trip>(record().events<PlanEvent.StepAdmitted>().single().admission)
        assertEquals("AccessPolicy:${MANIFEST.id.value}", trip.rule)
        assertEquals(stopped.error, trip.reason)
        assertEquals(TrippedRule(rule = trip.rule, reason = trip.reason), stopped.tripped)

        assertEquals(1, allEvents().count { it is PermissionDeniedEvent }, "the denial is as it was")
        assertTrue(dispatched.isEmpty())
    }

    @Test
    fun `a step that failed on its own was admitted as a pass and carries no trip`() = runBlocking<Unit> {
        val result = walk(agent(), listOf(FAILING_STEP), rules = emptyList())

        val failed = assertIs<StepOutcome.Failure>(result.stepOutcomes.single())
        assertNull(failed.tripped, "a tool's own failure is not a rule's")
        assertEquals(AdmissionRecord.Pass, record().events<PlanEvent.StepAdmitted>().single().admission)
    }

    @Test
    fun `an admission that could not be decided records nothing and carries no trip`() = runBlocking<Unit> {
        val unevaluable = Rule.Deadline(due = Instant.fromEpochMilliseconds(0)) to Hardness.HARD

        val result = walk(agent(), STEPS.take(1), rules = listOf(unevaluable))

        val failed = assertIs<StepOutcome.Failure>(result.stepOutcomes.single())
        assertTrue(failed.error.contains("was not admitted"), failed.error)
        assertTrue(failed.isCritical, "it still fails closed")
        assertNull(failed.tripped, "nothing was decided, so no rule tripped")
        assertTrue(record().events<PlanEvent.StepAdmitted>().isEmpty())
        assertTrue(dispatched.isEmpty())
    }

    @Test
    fun `a step dispatched outside a plan walk records no admission and still types its trip`() =
        runBlocking<Unit> {
            val result = dispatchPlanStep(
                agent = agent(grants = null),
                step = PLUG_STEP,
                parentTask = GOAL,
                priorResults = emptyList(),
            )

            val stopped = assertIs<StepResult.Failure>(result)
            assertEquals("AccessPolicy:${MANIFEST.id.value}", stopped.tripped?.rule)
            assertEquals(stopped.error, stopped.tripped?.reason)
            assertTrue(allEvents().none { it is PlanEvent.StepAdmitted }, "no plan, so no record to sit in")
        }

    // ========================================================================
    // Fixture
    // ========================================================================

    private suspend fun walk(
        agent: SparkBasedAgent<CodeState>,
        steps: List<Task>,
        rules: List<Pair<Rule, Hardness>>,
    ): PlanExecutionResult {
        val plan = Plan.ForTask(task = GOAL, tasks = steps, estimatedComplexity = steps.size)
        val walker = PlanExecutor(eventApi = door.api, runId = RUN_ID)
        return walker.execute(plan, priorResults = emptyList()) { step, context ->
            dispatchPlanStep(agent, step, parentTask = GOAL, priorResults = context.priorResults, runRules = rules)
        }
    }

    private fun agent(grants: (suspend (PlugManifest) -> UserGrants)? = { UserGrants() }): SparkBasedAgent<CodeState> =
        SparkBasedAgent(
            agentId = AGENT_ID,
            cognitiveAffinity = CognitiveAffinity.ANALYTICAL,
            initialState = CodeState.blank,
            _additionalTools = setOf(
                recordingTool(TOOL_ID, result = "recorded", dispatched = dispatched),
                failingTool(FAILING_TOOL_ID, error = "the tool could not reach its service", dispatched = dispatched),
                plugTool,
            ),
            _eventApi = door.api,
            _aiConfiguration = AIConfiguration_Default(
                provider = AIProvider_Anthropic,
                model = AIModel_Claude.Sonnet_5,
            ),
            _llmProvider = { "{}" },
            _observabilityScope = scope,
            _executor = FunctionExecutor.create(),
            _userGrantProvider = grants,
            _runId = RUN_ID,
        )

    /** A Plug-backed tool with no parameter strategy: the Breaker is all that stands before it. */
    private val plugTool: Tool<*> = FunctionTool<ExecutionContext.NoChanges>(
        id = PLUG_TOOL_ID,
        name = "Notify",
        description = "sends a notification through a Plug",
        requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
        plugManifest = MANIFEST,
        executionFunction = { request ->
            dispatched += request
            error("a step the Breaker tripped never reaches its tool")
        },
    )

    /** The run's record, in sequence order. */
    private suspend fun record(): List<StoredEvent> = door.api.getRunEvents(RUN_ID).getOrThrow().toList()

    private suspend fun allEvents(): List<Event> = door.repository.getAllEvents().getOrThrow()

    private inline fun <reified T : Event> List<StoredEvent>.events(): List<T> = mapNotNull { it.event as? T }

    private companion object {
        const val AGENT_ID = "step-admitted-test-agent"
        const val RUN_ID = "run-step-admitted"
        const val TOOL_ID = "record"
        const val FAILING_TOOL_ID = "unreachable"
        const val PLUG_TOOL_ID = "notify"
        const val STEP_CAP_NAME = "Quota.Count:steps"

        /** Breached from the second step on: one step is on the record by then. */
        val SOFT_CAP = Rule.Quota.Count(limit = 1, of = RunQuotas.STEPS)

        /** Breached from the third step on. */
        val HARD_CAP = Rule.Quota.Count(limit = 2, of = RunQuotas.STEPS)

        val PERMISSION: PlugPermission = PlugPermission.NativeAction("send-notification")
        val MANIFEST = PlugManifest(
            id = PlugId("example-notifier"),
            name = "Example Notifier",
            version = "1.0.0",
            requiredPermissions = listOf(PERMISSION),
        )

        val GOAL = goal("Keep three notes")

        val STEPS: List<Task> = listOf("Write the first note", "Write the second note", "Write the third note")
            .mapIndexed { index, description ->
                Task.Step(
                    id = "step-${index + 1}",
                    status = TaskStatus.Pending,
                    description = description,
                    toolId = TOOL_ID,
                )
            }

        val PLUG_STEP = Task.Step(
            id = "step-notify",
            status = TaskStatus.Pending,
            description = "Notify the owner",
            toolId = PLUG_TOOL_ID,
        )

        val FAILING_STEP = Task.Step(
            id = "step-unreachable",
            status = TaskStatus.Pending,
            description = "Call the unreachable service",
            toolId = FAILING_TOOL_ID,
        )
    }
}
