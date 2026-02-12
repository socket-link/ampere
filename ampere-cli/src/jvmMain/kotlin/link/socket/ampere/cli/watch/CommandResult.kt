package link.socket.ampere.cli.watch

/**
 * Represents the result of executing a command in command mode.
 */
sealed class CommandResult {
    /**
     * Command executed successfully with output to display.
     */
    data class Success(val output: String) : CommandResult()

    /**
     * Command failed with an error message.
     */
    data class Error(val message: String) : CommandResult()

    /**
     * Command requests to quit the dashboard.
     */
    data object Quit : CommandResult()
}
