package link.socket.ampere.agents.execution.tools

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.error.ExecutionError
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.execution.request.ExecutionContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

@OptIn(ExperimentalForeignApi::class)
actual suspend fun executeReadCodebase(
    context: ExecutionContext.Code.ReadCode,
): ExecutionOutcome.CodeReading {
    val executionStartTimestamp = Clock.System.now()

    val rootDirectory = context.workspace.baseDirectory
    val filePath = context.filePathsToRead.first()

    return try {
        val fullPath = if (filePath.startsWith("/")) {
            filePath
        } else {
            "$rootDirectory/$filePath"
        }

        val fileManager = NSFileManager.defaultManager
        val isDirectory = booleanArrayOf(false)

        if (!fileManager.fileExistsAtPath(fullPath)) {
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

        @Suppress("UNCHECKED_CAST")
        val content = NSString.stringWithContentsOfFile(
            fullPath,
            NSUTF8StringEncoding,
            null,
        ) as? String ?: ""

        ExecutionOutcome.CodeReading.Success(
            executorId = context.executorId,
            ticketId = context.ticket.id,
            taskId = context.task.id,
            executionStartTimestamp = executionStartTimestamp,
            executionEndTimestamp = Clock.System.now(),
            readFiles = listOf(filePath to content),
        )
    } catch (e: Exception) {
        ExecutionOutcome.CodeReading.Failure(
            executorId = context.executorId,
            ticketId = context.ticket.id,
            taskId = context.task.id,
            executionStartTimestamp = executionStartTimestamp,
            executionEndTimestamp = Clock.System.now(),
            error = ExecutionError(
                type = ExecutionError.Type.WORKSPACE_ERROR,
                message = "Failed to read file: ${e.message}",
            ),
        )
    }
}
