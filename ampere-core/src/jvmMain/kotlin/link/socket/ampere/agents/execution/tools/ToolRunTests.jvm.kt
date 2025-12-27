package link.socket.ampere.agents.execution.tools

import java.io.File
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.concept.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.error.ExecutionError
import link.socket.ampere.agents.execution.request.ExecutionContext

actual suspend fun executeRunTests(
    context: ExecutionContext.Code.ReadCode,
): ExecutionOutcome.CodeReading {
    val executionStartTimestamp = Clock.System.now()

    // TODO: Use resolveFileSafely
    val rootDirectory = context.workspace.baseDirectory

    // TODO: Handle reading multiple files
    val filePath = context.filePathsToRead.first()

    return try {
        val args = mutableListOf("./gradlew")
        args.add("test")
        args.add("--tests")
        args.add(filePath)

        val process = ProcessBuilder()
            .directory(File(rootDirectory))
            .command(args)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode == 0) {
            ExecutionOutcome.CodeReading.Success(
                executorId = context.executorId,
                ticketId = context.ticket.id,
                taskId = context.task.id,
                executionStartTimestamp = executionStartTimestamp,
                executionEndTimestamp = Clock.System.now(),
                readFiles = listOf(filePath to output),
            )
        } else {
            ExecutionOutcome.CodeReading.Failure(
                executorId = context.executorId,
                ticketId = context.ticket.id,
                taskId = context.task.id,
                executionStartTimestamp = executionStartTimestamp,
                executionEndTimestamp = Clock.System.now(),
                error = ExecutionError(
                    type = ExecutionError.Type.WORKSPACE_ERROR,
                    message = "Tests failed",
                ),
            )
        }
    } catch (e: Exception) {
        ExecutionOutcome.CodeReading.Failure(
            executorId = context.executorId,
            ticketId = context.ticket.id,
            taskId = context.task.id,
            executionStartTimestamp = executionStartTimestamp,
            executionEndTimestamp = Clock.System.now(),
            error = ExecutionError(
                type = ExecutionError.Type.WORKSPACE_ERROR,
                message = "Failed to run tests: ${e.message}",
            ),
        )
    }
}
