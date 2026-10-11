package link.socket.ampere.agents.domain.reasoning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.execution.priorResultsSection

/**
 * AMPR-425: a held step is a full stop of the plan walk.
 *
 * A tool that has to wait for a person reports it as held — neither a success, which
 * would hand the next step a result that does not exist, nor a failure, which would
 * record a fault that did not happen. [PlanExecutor] is the one place the stop lives,
 * so every walk of a plan obeys it: the held step keeps its outcome, every later step is
 * skipped naming it, and the plan settles to an [ExecutionOutcome.Held].
 */
class PlanExecutorHeldTest {

    private val executor = PlanExecutor(executorId = "plan-executor-held-test")

    @Test
    fun `a held step stops the walk and the remaining steps are skipped naming it`() {
        val ran = mutableListOf<String>()

        val result = runBlocking {
            executor.execute(
                plan = planOf("draft the reply", "send the reply", "file the thread"),
                priorResults = emptyList(),
            ) { step, _ ->
                val description = (step as Task.CodeChange).description
                ran += description
                if (description == "send the reply") {
                    StepResult.held(description, reason = REASON, reference = REFERENCE)
                } else {
                    StepResult.success(description, details = "done: $description")
                }
            }
        }

        assertEquals(
            listOf("draft the reply", "send the reply"),
            ran,
            "nothing after the held step is dispatched",
        )
        assertEquals(3, result.stepOutcomes.size, "every step is accounted for")
        val skipped = assertIs<StepOutcome.Skipped>(result.stepOutcomes[2])
        assertEquals("step-3", skipped.id)
        assertEquals("Skipped: step 2 is held ($REASON)", skipped.reason)
    }

    @Test
    fun `the overall outcome of a held walk is Held with the step's reason and reference`() {
        val result = runBlocking {
            executor.execute(
                plan = planOf("send the reply", "file the thread"),
                priorResults = emptyList(),
            ) { step, _ ->
                StepResult.held((step as Task.CodeChange).description, reason = REASON, reference = REFERENCE)
            }
        }

        val held = assertIs<ExecutionOutcome.Held>(result.outcome)
        assertEquals(REASON, held.reason)
        assertEquals(REFERENCE, held.reference)
        assertEquals("parent-task", held.taskId)
        assertFalse(result.outcome is Outcome.Success, "a hold is not a success")
        assertFalse(result.outcome is Outcome.Failure, "and not a failure")
        assertFalse(result.isSuccess)
        assertFalse(result.hasFailures, "no step failed")

        val step = assertIs<StepOutcome.Held>(result.stepOutcomes.first())
        assertEquals(REASON, step.reason)
        assertEquals(REFERENCE, step.reference)
        assertEquals("step-1", step.id)
    }

    @Test
    fun `steps before the hold keep their results`() {
        val result = runBlocking {
            executor.execute(
                plan = planOf("look up the address", "draft the reply", "send the reply"),
                priorResults = emptyList(),
            ) { step, _ ->
                when (val description = (step as Task.CodeChange).description) {
                    "look up the address" -> StepResult.success(description, details = "found it")
                    // Non-critical, so the walk goes on to the hold.
                    "draft the reply" -> StepResult.failure(description, error = "the template was missing")
                    else -> StepResult.held(description, reason = REASON)
                }
            }
        }

        val first = assertIs<StepOutcome.Success>(result.stepOutcomes[0])
        assertEquals("found it", first.details)
        val second = assertIs<StepOutcome.Failure>(result.stepOutcomes[1])
        assertEquals("the template was missing", second.error)
        assertIs<StepOutcome.Held>(result.stepOutcomes[2])
        assertIs<ExecutionOutcome.Held>(
            result.outcome,
            "a hold outranks an earlier non-critical failure: it is why the walk ended",
        )
    }

    @Test
    fun `a critical failure still skips the rest as it did`() {
        val result = runBlocking {
            executor.execute(plan = planOf("try", "after"), priorResults = emptyList()) { step, _ ->
                StepResult.failure((step as Task.CodeChange).description, error = "broke", isCritical = true)
            }
        }

        val skipped = assertIs<StepOutcome.Skipped>(result.stepOutcomes[1])
        assertEquals("Skipped due to critical failure in step 1", skipped.reason)
        assertIs<ExecutionOutcome.NoChanges.Failure>(result.outcome)
    }

    @Test
    fun `a later step is told the earlier one is held`() {
        val held = StepOutcome.Held(
            id = "step-1",
            stepDescription = "send the reply",
            startTimestamp = Instant.fromEpochSeconds(0),
            endTimestamp = Instant.fromEpochSeconds(0),
            reason = REASON,
            reference = REFERENCE,
        )

        val section = priorResultsSection(listOf(held))

        assertTrue(section.contains("[held] send the reply"), "the verdict tag says held: $section")
        assertTrue(section.contains(REASON), "with what it waits on")
        assertFalse(section.contains(REFERENCE), "the reference is carried, never rendered")
        assertFalse(section.contains("[failed]") || section.contains("[succeeded]"))
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
                description = "answer the thread",
            ),
            tasks = steps,
            estimatedComplexity = steps.size,
        )
    }

    private companion object {
        const val REASON = "waiting for the account owner to approve sending"
        const val REFERENCE = "approval-request-17"
    }
}
