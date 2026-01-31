package link.socket.ampere.agents.tools.mcp.connection

/**
 * JS implementation of StdioProcessHandler.
 *
 * Browser environments do not support spawning child processes with stdin/stdout communication.
 * This implementation returns errors for all operations.
 */
actual class StdioProcessHandler {

    actual suspend fun startProcess(executablePath: String): Result<Unit> {
        return Result.failure(
            McpConnectionException(
                "Stdio MCP connections are not supported in browser environments. " +
                    "JavaScript does not allow spawning child processes.",
            ),
        )
    }

    actual suspend fun sendMessage(message: String): Result<Unit> {
        return Result.failure(
            McpConnectionException("Process not started - stdio not supported in browser environments"),
        )
    }

    actual suspend fun receiveMessage(): String {
        throw McpConnectionException("Process not started - stdio not supported in browser environments")
    }

    actual suspend fun stopProcess(): Result<Unit> {
        return Result.success(Unit)
    }
}
