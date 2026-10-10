package link.socket.ampere.agents.domain.reasoning

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task

/**
 * AMPR-408: [PlanExecutor] carries the running results to each step.
 *
 * The executor already passed a [StepContext] to every step and already built one
 * [StepOutcome] per executed step — but the two never met, so the only thing a step
 * could learn about its predecessors was whatever that predecessor had thought to
 * name in `StepResult.contextUpdates`. A step executor cannot invent that for a tool
 * it is about to dispatch, which is why "search, then summarise what you found"
 * could not run.
 */
class PlanExecutorPriorResultsTest {

    private val executor = PlanExecutor(executorId = "plan-executor-test")
    private val epoch = Instant.fromEpochSeconds(0)

    @Test
    fun `each step sees what the steps before it produced`() {
        val seen = mutableListOf<List<String>>()

        runBlocking {
            executor.execute(
                plan = planOf("find the file", "read the file", "summarise the file"),
                priorResults = emptyList(),
            ) { step, context ->
                seen += context.priorResults.map { it.stepDescription }
                StepResult.success(
                    description = (step as Task.CodeChange).description,
                    details = "did ${step.description}",
                )
            }
        }

        assertEquals(
            listOf(
                emptyList(),
                listOf("find the file"),
                listOf("find the file", "read the file"),
            ),
            seen,
            "the chain grows by one per executed step, oldest first",
        )
    }

    @Test
    fun `a step is handed the detail of the step before it`() {
        val seen = mutableListOf<String>()

        runBlocking {
            executor.execute(
                plan = planOf("find the file", "summarise the file"),
                priorResults = emptyList(),
            ) { step, context ->
                context.priorResults.forEach { seen += (it as StepOutcome.Success).details }
                StepResult.success(
                    description = (step as Task.CodeChange).description,
                    details = "found src/Thing.kt",
                )
            }
        }

        assertEquals(listOf("found src/Thing.kt"), seen, "the detail travels, not just the description")
    }

    @Test
    fun `the caller's own results seed the chain`() {
        val seen = mutableListOf<List<String>>()
        val upstream = listOf(
            StepOutcome.Success(
                id = "upstream-step",
                stepDescription = "search the notes",
                startTimestamp = epoch,
                endTimestamp = epoch,
                details = "found: the launch slipped to Thursday",
            ),
        )

        runBlocking {
            executor.execute(plan = planOf("summarise the notes"), priorResults = upstream) { step, context ->
                seen += context.priorResults.map { it.stepDescription }
                StepResult.success(description = (step as Task.CodeChange).description)
            }
        }

        assertEquals(
            listOf(listOf("search the notes")),
            seen,
            "the live Execute path wraps each step in a one-step plan, so without the seed " +
                "every step would see an empty chain (AMPR-396)",
        )
    }

    @Test
    fun `a failed step travels forward as a failure`() {
        val seen = mutableListOf<StepOutcome>()

        runBlocking {
            executor.execute(plan = planOf("try the thing", "recover"), priorResults = emptyList()) { step, context ->
                seen += context.priorResults
                val description = (step as Task.CodeChange).description
                if (description == "try the thing") {
                    // Non-critical: the plan goes on, which is the only case where a
                    // later step can read the failure at all.
                    StepResult.failure(description = description, error = "the thing was not there")
                } else {
                    StepResult.success(description = description)
                }
            }
        }

        val carried = assertNotNull(
            seen.single() as? StepOutcome.Failure,
            "the second step should have been handed the first step's failure",
        )
        assertContains(carried.error, "the thing was not there")
    }

    private fun planOf(vararg descriptions: String): Plan.ForTask {
        val steps = descriptions.mapIndexed { index, description ->
            Task.CodeChange(
                id = "step-${index + 1}",
                status = TaskStatus.Pending,
                description = description,
            )
        }
        return Plan.ForTask(
            task = Task.CodeChange(
                id = "parent-task",
                status = TaskStatus.Pending,
                description = "do the work",
            ),
            tasks = steps,
            estimatedComplexity = steps.size,
        )
    }
}
