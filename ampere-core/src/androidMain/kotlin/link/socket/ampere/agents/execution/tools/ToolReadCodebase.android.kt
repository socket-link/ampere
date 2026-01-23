package link.socket.ampere.agents.execution.tools

import java.io.File
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.error.ExecutionError
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.execution.request.ExecutionContext

actual suspend fun executeReadCodebase(
    context: ExecutionContext.Code.ReadCode,
): ExecutionOutcome.CodeReading {
    val executionStartTimestamp = Clock.System.now()

    val rootDirectory = context.workspace.baseDirectory

    // TODO: Handle reading multiple files
    val filePath = context.filePathsToRead.first()

    return try {
        val file = resolveFileSafely(rootDirectory, filePath)

        if (!file.exists()) {
            return ExecutionOutcome.CodeReading.Failure(
                executorId = context.executorId,
                ticketId = context.ticket.id,
                taskId = context.task.id,
                executionStartTimestamp = executionStartTimestamp,
                executionEndTimestamp = Clock.System.now(),
                error = ExecutionError(
                    type = ExecutionError.Type.WORKSPACE_ERROR,
                    message = "Path does not exist: $filePath",
                ),
            )
        }

        val result: Any = if (file.isDirectory) {
            file.listFiles()?.joinToString("\n") { it.name } ?: ""
        } else {
            file.readText()
        }

        ExecutionOutcome.CodeReading.Success(
            executorId = context.executorId,
            ticketId = context.ticket.id,
            taskId = context.task.id,
            executionStartTimestamp = executionStartTimestamp,
            executionEndTimestamp = Clock.System.now(),
            readFiles = listOf(filePath to result.toString()),
        )
    } catch (e: SecurityException) {
        ExecutionOutcome.CodeReading.Failure(
            executorId = context.executorId,
            ticketId = context.ticket.id,
            taskId = context.task.id,
            executionStartTimestamp = executionStartTimestamp,
            executionEndTimestamp = Clock.System.now(),
            error = ExecutionError(
                type = ExecutionError.Type.WORKSPACE_ERROR,
                message = "Access outside root directory is not allowed: $filePath. \n ${e.message}",
            ),
        )
    }
}

private fun resolveFileSafely(
    rootDirectory: String,
    path: String,
): File {
    val base = File(rootDirectory).canonicalFile
    val target = File(base, path).canonicalFile

    // Ensure that the target path stays within the base directory of the workspace
    if (!target.path.startsWith(base.path + File.separator) && target != base) {
        throw SecurityException("Access outside root directory is not allowed: $path")
    }

    return target
}
