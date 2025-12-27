package link.socket.ampere.agents.execution.tools

import java.io.File
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.concept.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.error.ExecutionError
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.results.ExecutionResult

actual suspend fun executeWriteCodeFile(
    context: ExecutionContext.Code.WriteCode,
): ExecutionOutcome.CodeChanged {
    val executionStartTimestamp = Clock.System.now()

    // TODO: Use resolveFileSafely
    val rootDirectory = context.workspace.baseDirectory

    // TODO: Handle writing multiple files
    val (filePath, content) = context.instructionsPerFilePath.first()

    return try {
        val file = File(rootDirectory, filePath)

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
