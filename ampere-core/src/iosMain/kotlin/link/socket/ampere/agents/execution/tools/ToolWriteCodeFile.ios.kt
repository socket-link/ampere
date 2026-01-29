package link.socket.ampere.agents.execution.tools

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.error.ExecutionError
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.results.ExecutionResult
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.writeToFile

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual suspend fun executeWriteCodeFile(
    context: ExecutionContext.Code.WriteCode,
): ExecutionOutcome.CodeChanged {
    val executionStartTimestamp = Clock.System.now()

    val rootDirectory = context.workspace.baseDirectory
    val (filePath, content) = context.instructionsPerFilePath.first()

    return try {
        val fullPath = if (filePath.startsWith("/")) {
            filePath
        } else {
            "$rootDirectory/$filePath"
        }

        // Ensure parent directory exists
        val parentPath = fullPath.substringBeforeLast("/")
        val fileManager = NSFileManager.defaultManager
        if (!fileManager.fileExistsAtPath(parentPath)) {
            fileManager.createDirectoryAtPath(
                parentPath,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            )
        }

        // Write the file
        val nsString = NSString.create(string = content)
        val success = nsString.writeToFile(
            fullPath,
            atomically = true,
            encoding = NSUTF8StringEncoding,
            error = null,
        )

        if (success) {
            ExecutionOutcome.CodeChanged.Success(
                executorId = context.executorId,
                ticketId = context.ticket.id,
                taskId = context.task.id,
                executionStartTimestamp = executionStartTimestamp,
                executionEndTimestamp = Clock.System.now(),
                changedFiles = listOf(filePath),
                validation = ExecutionResult(
                    codeChanges = null,
                    compilation = null,
                    linting = null,
                    tests = null,
                ),
            )
        } else {
            ExecutionOutcome.CodeChanged.Failure(
                executorId = context.executorId,
                ticketId = context.ticket.id,
                taskId = context.task.id,
                executionStartTimestamp = executionStartTimestamp,
                executionEndTimestamp = Clock.System.now(),
                partiallyChangedFiles = emptyList(),
                error = ExecutionError(
                    type = ExecutionError.Type.WORKSPACE_ERROR,
                    message = "Failed to write file: $filePath",
                ),
            )
        }
    } catch (e: Exception) {
        ExecutionOutcome.CodeChanged.Failure(
            executorId = context.executorId,
            ticketId = context.ticket.id,
            taskId = context.task.id,
            executionStartTimestamp = executionStartTimestamp,
            executionEndTimestamp = Clock.System.now(),
            partiallyChangedFiles = listOf(filePath),
            error = ExecutionError(
                type = ExecutionError.Type.WORKSPACE_ERROR,
                message = "Failed to write file: ${e.message}",
            ),
        )
    }
}
