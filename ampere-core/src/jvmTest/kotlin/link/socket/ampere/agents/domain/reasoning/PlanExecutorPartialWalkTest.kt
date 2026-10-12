package link.socket.ampere.agents.domain.reasoning

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.PlanEvent
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.InMemoryEventDoor
import link.socket.ampere.data.DEFAULT_JSON

/**
 * AMPR-431: `PlanExecutor` can walk the rest of a plan whose first steps ran earlier.
 *
 * A hosted run resumed after a hold walks only the steps after the held one, possibly in
 * another process. The trace has to read as the plan the person approved — step 3 of 5,
 * not step 0 of 2 — and the walk has to settle to the outcome the whole plan would have,
 * so the steps before the break are folded in without being walked or published again.
 *
 * `runBlocking`, not `runTest`: the door's persist hops to an IO dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlanExecutorPartialWalkTest {

    private val scope = TestScope(UnconfinedTestDispatcher())
    private lateinit var door: InMemoryEventDoor.Handle

    @BeforeTest
    fun setUp() {
        door = InMemoryEventDoor.open(agentId = AGENT_ID, scope = scope)
    }

    @AfterTest
    fun tearDown() {
        door.close()
    }

    @Test
    fun `a walk started at index 3 of 5 publishes steps 3 and 4 of 5`() = runBlocking<Unit> {
        val walked = mutableListOf<String>()

        val result = PlanExecutor(eventApi = door.api, runId = RUN_ID).execute(
            plan = planOf("step-3", "step-4"),
            priorResults = emptyList(),
            firstStepIndex = 3,
            totalSteps = 5,
            earlierStepOutcomes = emptyList(),
        ) { step, _ ->
            walked += step.id
            StepResult.success(description = "ran ${step.id}", details = "done")
        }

        assertEquals(listOf("step-3", "step-4"), walked)
        assertEquals(2, result.stepOutcomes.size)

        val started = events().filterIsInstance<PlanEvent.PlanStepStarted>()
        assertEquals(listOf(3, 4), started.map { it.stepIndex })
        assertTrue(started.all { it.totalSteps == 5 }, "of the approved plan's five")
        val completed = events().filterIsInstance<PlanEvent.PlanStepCompleted>()
        assertEquals(listOf(3, 4), completed.map { it.stepIndex })
        assertTrue(completed.all { it.totalSteps == 5 })
    }

    @Test
    fun `a critical failure's skip names the step by its place in the approved plan`() = runBlocking<Unit> {
        val result = PlanExecutor().execute(
            plan = planOf("step-3", "step-4"),
            priorResults = emptyList(),
            firstStepIndex = 3,
            totalSteps = 5,
            earlierStepOutcomes = emptyList(),
        ) { step, _ ->
            StepResult.failure(description = "ran ${step.id}", error = "broke", isCritical = true)
        }

        val skipped = assertIs<StepOutcome.Skipped>(result.stepOutcomes[1])
        assertEquals("Skipped due to critical failure in step 4", skipped.reason)
    }

    @Test
    fun `earlier outcomes are folded and not walked or published`() = runBlocking<Unit> {
        val earlierFailure = StepOutcome.Failure(
            id = "step-0",
            stepDescription = "look it up",
            startTimestamp = Clock.System.now(),
            endTimestamp = Clock.System.now(),
            error = "the index is unreachable",
            isCritical = false,
        )

        val result = PlanExecutor(eventApi = door.api, runId = RUN_ID).execute(
            plan = planOf("step-1"),
            priorResults = listOf(earlierFailure),
            firstStepIndex = 1,
            totalSteps = 2,
            earlierStepOutcomes = listOf(earlierFailure),
        ) { step, _ ->
            StepResult.success(description = "ran ${step.id}", details = "done")
        }

        assertEquals(listOf("step-1"), result.stepOutcomes.map { it.id }, "only the steps walked here")
        val folded = assertIs<ExecutionOutcome.NoChanges.Failure>(result.outcome, "the whole plan's fold")
        assertTrue(folded.message.contains("the index is unreachable"))
        assertEquals(
            listOf("step-1"),
            events().filterIsInstance<PlanEvent.PlanStepStarted>().map { it.stepId },
            "the earlier step is not published again",
        )
    }

    @Test
    fun `a walk of no steps with earlier outcomes folds those alone`() = runBlocking<Unit> {
        val earlier = StepOutcome.Success(
            id = "step-0",
            stepDescription = "send it",
            startTimestamp = Clock.System.now(),
            endTimestamp = Clock.System.now(),
            details = "sent",
        )

        val result = PlanExecutor().execute(
            plan = planOf(),
            priorResults = listOf(earlier),
            firstStepIndex = 1,
            totalSteps = 1,
            earlierStepOutcomes = listOf(earlier),
        ) { _, _ -> error("no step to walk") }

        val folded = assertIs<ExecutionOutcome.NoChanges.Success>(result.outcome)
        assertTrue(folded.message.contains("Success: 1"), "the earlier success is counted: ${folded.message}")
    }

    private fun events(): List<Event> =
        door.database.eventStoreQueries.getEventsByRunId(RUN_ID).executeAsList()
            .map { DEFAULT_JSON.decodeFromString(Event.serializer(), it.payload) }

    private fun planOf(vararg ids: String): Plan.ForTask = Plan.ForTask(
        task = Task.Step(id = "goal", status = TaskStatus.Pending, description = "the goal"),
        tasks = ids.map { id -> Task.Step(id = id, status = TaskStatus.Pending, description = "do $id") },
    )

    private companion object {
        const val AGENT_ID = "plan-executor-partial-walk-test"
        const val RUN_ID = "run-partial-walk"
    }
}
