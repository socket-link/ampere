package link.socket.ampere.agents.execution

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.error.ExecutionError
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.StepOutcome

/**
 * AMPR-408: what a plan's earlier steps produced, as prompt text.
 *
 * Two things are pinned here. First, that [describeResult] reads an outcome's
 * *payload* — the message, the files, the issue numbers — because the thing a later
 * step needs is what the earlier one found, and `"outcome=Success"` is not that.
 * Second, that [priorResultsSection] renders nothing at all for an empty chain, so
 * the first step of a plan asks exactly the question it asked before AMPR-408.
 */
class PriorStepResultsTest {

    private val epoch = Instant.fromEpochSeconds(0)

    @Test
    fun `an empty chain renders no section`() {
        assertEquals("", priorResultsSection(emptyList()))
    }

    @Test
    fun `each result names its step and what that step produced`() {
        val section = priorResultsSection(
            listOf(
                success("search the notes", "found: the launch slipped to Thursday"),
                success("rank the findings", "ranked 3 findings"),
            ),
        )

        assertContains(section, "1. [succeeded] search the notes")
        assertContains(section, "found: the launch slipped to Thursday")
        assertContains(section, "2. [succeeded] rank the findings")
        assertTrue(
            section.indexOf("search the notes") < section.indexOf("rank the findings"),
            "oldest first, so a model can refer to 'the result of step 2'",
        )
    }

    @Test
    fun `a failed step renders its error rather than being dropped`() {
        val section = priorResultsSection(
            listOf(
                StepOutcome.Failure(
                    id = "step-1",
                    stepDescription = "read the config",
                    startTimestamp = epoch,
                    endTimestamp = epoch,
                    error = "WORKSPACE_ERROR: config.yml is missing",
                    isCritical = false,
                ),
            ),
        )

        assertContains(section, "[failed] read the config")
        assertContains(section, "config.yml is missing")
    }

    @Test
    fun `a skipped step says so`() {
        val section = priorResultsSection(
            listOf(
                StepOutcome.Skipped(
                    id = "step-1",
                    stepDescription = "publish the release",
                    timestamp = epoch,
                    reason = "nothing to publish",
                ),
            ),
        )

        assertContains(section, "[skipped] publish the release")
        assertContains(section, "nothing to publish")
    }

    @Test
    fun `an oversized result is truncated rather than pasted whole`() {
        val wholeFile = (1..500).joinToString("\n") { "line $it of a file nobody needs in full" }

        val section = priorResultsSection(listOf(success("read the sources", wholeFile)))

        assertContains(section, "line 1 of a file nobody needs in full")
        assertContains(section, "… (truncated)")
        assertTrue(
            section.length < wholeFile.length,
            "the point of the cap is that the prompt does not grow with the file",
        )
    }

    @Test
    fun `a tool message is the result a later step reads`() {
        val outcome = ExecutionOutcome.NoChanges.Success(
            executorId = "executor",
            ticketId = "ticket",
            taskId = "task",
            executionStartTimestamp = epoch,
            executionEndTimestamp = epoch,
            message = "the launch slipped to Thursday",
        )

        assertEquals("the launch slipped to Thursday", outcome.describeResult())
    }

    @Test
    fun `a code read renders the files it read`() {
        val outcome = ExecutionOutcome.CodeReading.Success(
            executorId = "executor",
            ticketId = "ticket",
            taskId = "task",
            executionStartTimestamp = epoch,
            executionEndTimestamp = epoch,
            readFiles = listOf("src/Thing.kt" to "class Thing"),
        )

        val described = outcome.describeResult()

        assertContains(described, "src/Thing.kt")
        assertContains(described, "class Thing")
    }

    @Test
    fun `a failure renders the error a next step may have to work around`() {
        val outcome = ExecutionOutcome.CodeChanged.Failure(
            executorId = "executor",
            ticketId = "ticket",
            taskId = "task",
            executionStartTimestamp = epoch,
            executionEndTimestamp = epoch,
            error = ExecutionError(
                type = ExecutionError.Type.COMPILATION_FAILED,
                message = "Unresolved reference: frobnicate",
            ),
            partiallyChangedFiles = listOf("src/Thing.kt"),
        )

        val described = outcome.describeResult()

        assertContains(described, "COMPILATION_FAILED")
        assertContains(described, "Unresolved reference: frobnicate")
        assertContains(described, "src/Thing.kt")
    }

    private fun success(description: String, details: String) = StepOutcome.Success(
        id = description,
        stepDescription = description,
        startTimestamp = epoch,
        endTimestamp = epoch,
        details = details,
    )
}
