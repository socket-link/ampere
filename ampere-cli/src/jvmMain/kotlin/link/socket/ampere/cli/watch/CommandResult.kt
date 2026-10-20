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
     * A command still running (AMPR-468). The dashboard draws [message] until the command returns
     * or ESC cancels it; [notice] says why a command typed meanwhile was not run.
     */
    data class Pending(val message: String, val notice: String? = null) : CommandResult()

    /**
     * Command requests to quit the dashboard.
     */
    data object Quit : CommandResult()
}
