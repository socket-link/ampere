package link.socket.ampere.propel

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
import link.socket.ampere.agents.domain.event.AdmissionRecord
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.PlanEvent
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.events.InMemoryEventDoor
import link.socket.ampere.agents.events.StoredEvent
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.roster.PromptRef
import link.socket.ampere.roster.RoleConfig
import link.socket.ampere.roster.RoleId
import link.socket.ampere.roster.RosterConfig

/**
 * AMPR-472 (F33): a hosted run's steps are each admitted on the record, and the step a run
 * quota stopped says which rule stopped it.
 *
 * Drives the production wiring — `rosterRunHost` over an in-memory door — with one seat
 * and three reasoning steps, so each step is admitted against the run's rules before its
 * model call. A run's rules are all Hard, which is why the Soft half of the record is
 * `StepAdmittedTest`'s.
 *
 * `runBlocking`, not `runTest`: a door's publish hops to the IO dispatcher and a virtual
 * clock walks straight past it.
 */
class HostedRunAdmissionTest {

    private lateinit var scope: CoroutineScope
    private lateinit var door: InMemoryEventDoor.Handle

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        door = InMemoryEventDoor.open(agentId = SDK_ID, scope = scope)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        door.close()
    }

    @Test
    fun `a step quota of two passes two steps and trips the third by name`() = runBlocking<Unit> {
        val run = openRun(RunPolicy(quotas = RunQuotas(steps = 2)))
        val result = run.execute()
        run.close()

        assertEquals(RunStatus.Partial, result.status)
        val stopped = assertIs<StepOutcome.Failure>(result.stepOutcomes[2])
        assertEquals(STEP_CAP_NAME, stopped.tripped?.rule)
        assertTrue(stopped.error.contains("step quota reached"), "the error is what it was; got '${stopped.error}'")

        val record = record(result.runId)
        val admitted = record.events<PlanEvent.StepAdmitted>()
        // Three, not four: the run asks its quotas again once the step has failed, and that
        // question is not a step's admission.
        assertEquals(3, admitted.size, "one per step; got ${admitted.map { it.admission }}")
        assertEquals(listOf(AdmissionRecord.Pass, AdmissionRecord.Pass), admitted.take(2).map { it.admission })
        val trip = assertIs<AdmissionRecord.Trip>(admitted[2].admission)
        assertEquals(STEP_CAP_NAME, trip.rule)
        assertEquals(stopped.error, trip.reason)

        val completed = record.events<PlanEvent.PlanStepCompleted>()
        assertEquals(trip.rule, assertIs<StepOutcome.Failure>(completed[2].outcome).tripped?.rule)

        // In step order, naming the plan its pairs name, from the seat that ran each step.
        val started = record.events<PlanEvent.PlanStepStarted>()
        assertEquals(started.map { it.stepId }, admitted.map { it.stepId })
        assertEquals(started.map { it.planId }, admitted.map { it.planId })
        admitted.forEach { event ->
            assertEquals(HOST_ID, event.eventSource.getIdentifier())
            assertEquals(result.runId, event.runId)
        }
        assertTrue(
            record.filter { it.event is PlanEvent.StepAdmitted }.all { it.runId == result.runId },
            "every admission is on the run's envelope",
        )
    }

    @Test
    fun `a run with no quotas records a pass for every step and no trip`() = runBlocking<Unit> {
        val run = openRun(RunPolicy())
        val result = run.execute()
        run.close()

        assertEquals(RunStatus.Succeeded, result.status)
        val admitted = record(result.runId).events<PlanEvent.StepAdmitted>()
        assertEquals(List(STEPS.size) { AdmissionRecord.Pass }, admitted.map { it.admission })
        result.stepOutcomes.forEach { outcome -> assertNull((outcome as? StepOutcome.Failure)?.tripped) }
    }

    // ========================================================================
    // Fixture
    // ========================================================================

    private suspend fun openRun(policy: RunPolicy): HostedRun =
        rosterRunHost(createEventApi = door::doorFor, agentScope = scope).open(
            roster = RosterConfig(
                host = HOST_ROLE,
                roles = listOf(RoleConfig(id = HOST_ROLE, title = "Host", instructions = PROMPT)),
            ),
            seats = mapOf(
                HOST_ROLE to HostedAgent(
                    id = HOST_ID,
                    role = HOST_ROLE,
                    charter = "You are the host of this run.",
                    aiConfiguration = AI_CONFIGURATION,
                    upstreamLlmClient = CountingUpstreamLlmClient(::answer),
                ),
            ),
            goal = goal(GOAL_TEXT),
            tools = emptySet(),
            policy = policy,
        )

    /** PERCEIVE's insight, a step's conclusion, and otherwise the three-step plan. */
    private fun answer(prompt: String): String = when {
        prompt.contains("perception module") -> PERCEPTION_JSON
        prompt.contains(REASONING_STEP_MARKER) -> "step concluded"
        else -> planJson(*STEPS.map { planStep(it, toolToUse = null, seat = HOST_ROLE.value) }.toTypedArray())
    }

    /** The run's record, in sequence order. */
    private suspend fun record(runId: String): List<StoredEvent> =
        door.repository.getEventsByRunId(runId).getOrThrow()

    private inline fun <reified T : Event> List<StoredEvent>.events(): List<T> = mapNotNull { it.event as? T }

    private companion object {
        const val SDK_ID = "hosted-run-admission-test-sdk"
        const val HOST_ID = "seat-host"
        const val GOAL_TEXT = "Summarise the retry policy"
        const val STEP_CAP_NAME = "Quota.Count:steps"

        /** How `SparkBasedAgent` opens the prompt for a step that nominates no tool. */
        const val REASONING_STEP_MARKER = "You are carrying out one step of a plan"

        val STEPS = listOf("Read the retry policy", "Compare it with the spec", "Write the summary")

        val HOST_ROLE = RoleId("planner")
        val PROMPT = PromptRef(id = "test-prompt", version = 1)
        val AI_CONFIGURATION = AIConfiguration_Default(
            provider = AIProvider_Anthropic,
            model = AIModel_Claude.Sonnet_5,
        )
    }
}
