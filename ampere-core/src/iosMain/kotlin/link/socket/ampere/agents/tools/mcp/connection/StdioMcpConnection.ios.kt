package link.socket.ampere.agents.tools.mcp.connection

/**
 * iOS implementation of StdioProcessHandler.
 *
 * iOS does not support spawning child processes with stdin/stdout communication.
 * This implementation returns errors for all operations.
 */
actual class StdioProcessHandler {

    actual suspend fun startProcess(executablePath: String): Result<Unit> {
        return Result.failure(
            McpConnectionException(
                "Stdio MCP connections are not supported on iOS. " +
                    "iOS does not allow spawning child processes.",
            ),
        )
    }

    actual suspend fun sendMessage(message: String): Result<Unit> {
        return Result.failure(
            McpConnectionException("Process not started - stdio not supported on iOS"),
        )
    }

    actual suspend fun receiveMessage(): String {
        throw McpConnectionException("Process not started - stdio not supported on iOS")
    }

    actual suspend fun stopProcess(): Result<Unit> {
        return Result.success(Unit)
    }
}
