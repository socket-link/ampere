package link.socket.ampere.agents.execution.tools

import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.error.ExecutionError
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.results.ExecutionResult

actual suspend fun executeWriteCodeFile(
    context: ExecutionContext.Code.WriteCode,
): ExecutionOutcome.CodeChanged {
    val executionStartTimestamp = Clock.System.now()

    val rootDirectory = context.workspace.baseDirectory

    // TODO: Handle writing multiple files
    val (filePath, content) = context.instructionsPerFilePath.first()

    return try {
        // Reject any path that resolves outside the workspace root (e.g. `../`).
        val file = resolveFileSafely(rootDirectory, filePath)

        file.parentFile?.let { parent ->
            if (!parent.exists()) {
                parent.mkdirs()
            }
        }
        file.writeText(content)

        ExecutionOutcome.CodeChanged.Success(
            executorId = context.executorId,
            ticketId = context.ticket.id,
            taskId = context.task.id,
            executionStartTimestamp = executionStartTimestamp,
            executionEndTimestamp = Clock.System.now(),
            changedFiles = listOf(filePath),
            // TODO: Execute validation
            validation = ExecutionResult(
                codeChanges = null,
                compilation = null,
                linting = null,
                tests = null,
            ),
        )
    } catch (e: SecurityException) {
        // Nothing was written: the path was rejected before any filesystem mutation.
        ExecutionOutcome.CodeChanged.Failure(
            executorId = context.executorId,
            ticketId = context.ticket.id,
            taskId = context.task.id,
            executionStartTimestamp = executionStartTimestamp,
            executionEndTimestamp = Clock.System.now(),
            partiallyChangedFiles = emptyList(),
            error = ExecutionError(
                type = ExecutionError.Type.WORKSPACE_ERROR,
                message = "Access outside root directory is not allowed: $filePath. \n ${e.message}",
            ),
        )
    } catch (e: Exception) {
        ExecutionOutcome.CodeChanged.Failure(
            executorId = context.executorId,
            ticketId = context.ticket.id,
            taskId = context.task.id,
            executionStartTimestamp = executionStartTimestamp,
            executionEndTimestamp = Clock.System.now(),
            // TODO: Track partial set of files
            partiallyChangedFiles = listOf(filePath),
            error = ExecutionError(
                type = ExecutionError.Type.WORKSPACE_ERROR,
                message = "Failed to write file: ${e.message}",
            ),
        )
    }
}
