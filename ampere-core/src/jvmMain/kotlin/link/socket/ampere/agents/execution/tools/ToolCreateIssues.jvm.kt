package link.socket.ampere.agents.execution.tools

import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.error.ExecutionError
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.integrations.issues.BatchIssueCreator
import link.socket.ampere.integrations.issues.github.GitHubCliProvider

/**
 * JVM implementation for creating issues in a project management system.
 *
 * This implementation uses:
 * - GitHubCliProvider for GitHub API interaction via gh CLI
 * - BatchIssueCreator for dependency resolution and ordered creation
 *
 * The execution flow:
 * 1. Validate provider connection
 * 2. Create batch of issues using BatchIssueCreator
 * 3. BatchIssueCreator handles topological sorting
 * 4. BatchIssueCreator calls provider for each issue
 * 5. Parent relationships are documented in issue bodies and comments
 * 6. Return success or partial success outcome
 */
actual suspend fun executeCreateIssues(
    context: ExecutionContext.IssueManagement,
): ExecutionOutcome.IssueManagement {
    val startTime = Clock.System.now()

    // Initialize provider and batch creator
    val provider = GitHubCliProvider()
    val batchCreator = BatchIssueCreator(provider)

    // Validate provider connection
    val connectionResult = provider.validateConnection()
    if (connectionResult.isFailure) {
        return ExecutionOutcome.IssueManagement.Failure(
            executorId = context.executorId,
            ticketId = context.ticket.id,
            taskId = context.task.id,
            executionStartTimestamp = startTime,
            executionEndTimestamp = Clock.System.now(),
            error = ExecutionError(
                type = ExecutionError.Type.TOOL_UNAVAILABLE,
                message = "GitHub CLI not authenticated",
                details = "Run: gh auth login\nError: ${connectionResult.exceptionOrNull()?.message}",
                isRetryable = true,
            ),
            partialResponse = null,
        )
    }

    // Execute batch creation
    try {
        val response = batchCreator.createBatch(context.issueRequest)

        return if (response.success) {
            // All issues created successfully
            ExecutionOutcome.IssueManagement.Success(
                executorId = context.executorId,
                ticketId = context.ticket.id,
                taskId = context.task.id,
                executionStartTimestamp = startTime,
                executionEndTimestamp = Clock.System.now(),
                response = response,
            )
        } else {
            // Some issues failed, but some succeeded (partial success)
            // This is still a failure outcome, but we include the partial response
            ExecutionOutcome.IssueManagement.Failure(
                executorId = context.executorId,
                ticketId = context.ticket.id,
                taskId = context.task.id,
                executionStartTimestamp = startTime,
                executionEndTimestamp = Clock.System.now(),
                error = ExecutionError(
                    type = ExecutionError.Type.UNEXPECTED,
                    message = "Some issues failed to create: ${response.errors.size} errors",
                    details = response.errors.joinToString("\n") { "${it.localId}: ${it.message}" },
                    isRetryable = true,
                ),
                partialResponse = response,
            )
        }
    } catch (e: Exception) {
        // Unexpected error during batch creation
        return ExecutionOutcome.IssueManagement.Failure(
            executorId = context.executorId,
            ticketId = context.ticket.id,
            taskId = context.task.id,
            executionStartTimestamp = startTime,
            executionEndTimestamp = Clock.System.now(),
            error = ExecutionError(
                type = ExecutionError.Type.UNEXPECTED,
                message = "Unexpected error during issue creation: ${e.message}",
                details = e.stackTraceToString(),
                isRetryable = false,
            ),
            partialResponse = null,
        )
    }
}
