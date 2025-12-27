package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.help
import link.socket.ampere.agents.execution.tools.human.GlobalHumanResponseRegistry

/**
 * CLI command to respond to human input requests from agents.
 *
 * When an agent invokes the AskHumanTool, it generates a request ID and
 * blocks execution waiting for a human response. This command allows humans
 * to provide that response through the CLI.
 *
 * Usage:
 *   ampere respond <request-id> "<response text>"
 *
 * Example:
 *   ampere respond abc-123 "Yes, proceed with the changes"
 */
class RespondCommand : CliktCommand(
    name = "respond",
    help = """
        Provide a response to an agent's human input request.

        When an agent asks for human input, it displays a request ID.
        Use this command to provide your response and unblock the agent.

        Examples:
          ampere respond abc-123 "Approved"
          ampere respond def-456 "Use the staging environment instead"
          ampere respond ghi-789 "No, please revise the approach"

        To see pending requests:
          ampere status  # Shows all active human interaction requests
    """.trimIndent()
) {

    private val requestId by argument()
        .help("The request ID displayed by the agent")

    private val response by argument()
        .help("Your response text (quote if it contains spaces)")

    override fun run() {
        val registry = GlobalHumanResponseRegistry.instance

        // Check if this request ID exists
        val pendingIds = registry.getPendingRequestIds()

        if (requestId !in pendingIds) {
            echo("❌ Error: Request ID '$requestId' not found", err = true)
            echo("")
            echo("Pending requests:")
            if (pendingIds.isEmpty()) {
                echo("  (none)")
            } else {
                pendingIds.forEach { id ->
                    echo("  - $id")
                }
            }
            return
        }

        // Provide the response
        val success = registry.provideResponse(requestId, response)

        if (success) {
            echo("✅ Response delivered successfully!")
            echo("")
            echo("Request ID: $requestId")
            echo("Response: $response")
            echo("")
            echo("The agent will now continue execution with your input.")
        } else {
            echo("❌ Error: Failed to deliver response (request may have timed out)", err = true)
        }
    }
}
