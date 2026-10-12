package link.socket.ampere.agents.domain.reasoning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.domain.outcome.ContentOrigin
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.outcome.contentOrigin
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task

/**
 * AMPR-453: where `PlanExecutor`'s results say their text came from.
 *
 * Each step keeps the origin its step executor gave it. A step skipped after a hold or a
 * critical failure takes the origin of the step that caused it, because the skip quotes
 * that step's reason. And the plan's folded outcome is `Own` only when every step it folds
 * was — its message quotes them, and on the Arc path the message *is* the one step's report,
 * which is how that step's origin survives to the next one.
 */
class PlanExecutorOriginTest {

    private val executor = PlanExecutor(executorId = "plan-executor-origin-test")

    @Test
    fun `a plan of Own steps folds to an Own outcome`() {
        val result = walk("search", "summarise") { description ->
            StepResult.success(description, details = "done").withOrigin(ContentOrigin.Own)
        }

        assertEquals(listOf(ContentOrigin.Own, ContentOrigin.Own), result.stepOutcomes.map { it.origin })
        val folded = assertIs<ExecutionOutcome.NoChanges.Success>(result.outcome)
        assertEquals(ContentOrigin.Own, folded.contentOrigin())
    }

    @Test
    fun `one External step makes the fold External`() {
        val result = walk("search", "summarise") { description ->
            val step = StepResult.success(description, details = "done")
            if (description == "summarise") step.withOrigin(ContentOrigin.Own) else step
        }

        assertEquals(ContentOrigin.External, result.stepOutcomes.first().origin, "the default")
        assertEquals(ContentOrigin.External, result.outcome.contentOrigin())
    }

    @Test
    fun `a failed fold carries the origin too`() {
        val result = walk("search") { description ->
            StepResult.failure(description, error = "boom").withOrigin(ContentOrigin.Own)
        }

        val folded = assertIs<ExecutionOutcome.NoChanges.Failure>(result.outcome)
        assertEquals(ContentOrigin.Own, folded.origin)
    }

    @Test
    fun `steps skipped after a hold take the held step's origin and so does the held fold`() {
        val result = walk("draft", "send", "file") { description ->
            when (description) {
                "send" -> StepResult.held(description, reason = "waiting for approval")
                else -> StepResult.success(description, details = "done").withOrigin(ContentOrigin.Own)
            }
        }

        val skipped = assertIs<StepOutcome.Skipped>(result.stepOutcomes[2])
        assertEquals(ContentOrigin.External, skipped.origin, "the skip quotes the held step's reason")
        val folded = assertIs<ExecutionOutcome.Held>(result.outcome)
        assertEquals(ContentOrigin.External, folded.origin)
    }

    @Test
    fun `an empty plan's fold is Own`() {
        val result = runBlocking {
            executor.execute(Plan.ForTask(task = task("goal"), tasks = emptyList()), emptyList()) { _, _ ->
                error("no step to run")
            }
        }

        assertEquals(ContentOrigin.Own, result.outcome.contentOrigin(), "the message is the executor's own")
    }

    private fun walk(vararg descriptions: String, step: (String) -> StepResult): PlanExecutionResult =
        runBlocking {
            executor.execute(
                plan = Plan.ForTask(
                    task = task("goal"),
                    tasks = descriptions.mapIndexed { index, description ->
                        Task.CodeChange(
                            id = "step-${index + 1}",
                            status = TaskStatus.Pending,
                            description = description,
                        )
                    },
                ),
                priorResults = emptyList(),
            ) { planStep, _ -> step((planStep as Task.CodeChange).description) }
        }

    private fun task(description: String) = Task.CodeChange(
        id = description,
        status = TaskStatus.Pending,
        description = description,
    )
}
