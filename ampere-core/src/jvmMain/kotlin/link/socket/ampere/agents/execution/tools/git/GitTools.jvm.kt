package link.socket.ampere.agents.execution.tools.git

import java.io.File
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.error.ExecutionError
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.integrations.git.GitCliProvider

/**
 * JVM implementation for Git operations.
 *
 * Uses GitCliProvider to execute git and gh commands for repository operations.
 * Supports branching, committing, pushing, PR creation, and status queries.
 */
actual suspend fun executeGitOperation(
    context: ExecutionContext.GitOperation,
): ExecutionOutcome.GitOperation {
    val startTime = Clock.System.now()

    // Initialize provider with repository directory
    val workingDir = File(context.gitRequest.repository)
    val provider = GitCliProvider(workingDir)

    // Validate repository exists
    val validationResult = provider.validateRepository()
    if (validationResult.isFailure) {
        return ExecutionOutcome.GitOperation.Failure(
            executorId = context.executorId,
            ticketId = context.ticket.id,
            taskId = context.task.id,
            executionStartTimestamp = startTime,
            executionEndTimestamp = Clock.System.now(),
            error = ExecutionError(
                type = ExecutionError.Type.TOOL_UNAVAILABLE,
                message = "Invalid Git repository",
                details = "Failed to validate repository at ${context.gitRequest.repository}: " +
                    "${validationResult.exceptionOrNull()?.message}",
                isRetryable = false,
            ),
            partialResponse = null,
        )
    }

    // Execute the requested operation
    try {
        val response = when {
            context.gitRequest.createBranch != null -> {
                val branchRequest = context.gitRequest.createBranch
                val result = provider.createBranch(branchRequest).getOrThrow()
                GitOperationResponse(
                    success = true,
                    createdBranch = result,
                )
            }

            context.gitRequest.stageFiles != null -> {
                val files = context.gitRequest.stageFiles
                val stagedFiles = provider.stageFiles(files).getOrThrow()
                GitOperationResponse(
                    success = true,
                    stagedFiles = StagedFilesResult(stagedFiles),
                )
            }

            context.gitRequest.commit != null -> {
                val commitRequest = context.gitRequest.commit
                val result = provider.commit(commitRequest).getOrThrow()
                GitOperationResponse(
                    success = true,
                    createdCommit = result,
                )
            }

            context.gitRequest.push != null -> {
                val pushRequest = context.gitRequest.push
                provider.push(pushRequest).getOrThrow()
                GitOperationResponse(
                    success = true,
                    pushResult = PushResult(
                        branchName = pushRequest.branchName,
                        remoteName = "origin",
                        upstreamSet = pushRequest.setUpstream,
                    ),
                )
            }

            context.gitRequest.createPullRequest != null -> {
                val prRequest = context.gitRequest.createPullRequest
                val result = provider.createPullRequest(prRequest).getOrThrow()
                GitOperationResponse(
                    success = true,
                    createdPullRequest = result,
                )
            }

            context.gitRequest.getStatus -> {
                val result = provider.getStatus().getOrThrow()
                GitOperationResponse(
                    success = true,
                    statusResult = result,
                )
            }

            context.gitRequest.checkout != null -> {
                // Checkout is not yet implemented in GitCliProvider
                // For now, we return a failure indicating this
                return ExecutionOutcome.GitOperation.Failure(
                    executorId = context.executorId,
                    ticketId = context.ticket.id,
                    taskId = context.task.id,
                    executionStartTimestamp = startTime,
                    executionEndTimestamp = Clock.System.now(),
                    error = ExecutionError(
                        type = ExecutionError.Type.TOOL_UNAVAILABLE,
                        message = "Checkout operation not yet implemented",
                        details = "The checkout operation will be added in a future task.",
                        isRetryable = false,
                    ),
                    partialResponse = null,
                )
            }

            else -> {
                return ExecutionOutcome.GitOperation.Failure(
                    executorId = context.executorId,
                    ticketId = context.ticket.id,
                    taskId = context.task.id,
                    executionStartTimestamp = startTime,
                    executionEndTimestamp = Clock.System.now(),
                    error = ExecutionError(
                        type = ExecutionError.Type.UNEXPECTED,
                        message = "No Git operation specified",
                        details = "The GitOperationRequest must specify exactly one operation to perform.",
                        isRetryable = false,
                    ),
                    partialResponse = null,
                )
            }
        }

        return ExecutionOutcome.GitOperation.Success(
            executorId = context.executorId,
            ticketId = context.ticket.id,
            taskId = context.task.id,
            executionStartTimestamp = startTime,
            executionEndTimestamp = Clock.System.now(),
            response = response,
        )
    } catch (e: Exception) {
        // Handle any unexpected errors during Git operations
        return ExecutionOutcome.GitOperation.Failure(
            executorId = context.executorId,
            ticketId = context.ticket.id,
            taskId = context.task.id,
            executionStartTimestamp = startTime,
            executionEndTimestamp = Clock.System.now(),
            error = ExecutionError(
                type = ExecutionError.Type.UNEXPECTED,
                message = "Git operation failed: ${e.message}",
                details = e.stackTraceToString(),
                isRetryable = true,
            ),
            partialResponse = null,
        )
    }
}
