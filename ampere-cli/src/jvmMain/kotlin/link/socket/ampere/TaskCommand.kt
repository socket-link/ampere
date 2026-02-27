package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.api.service.TicketService

/**
 * Root command for task operations.
 *
 * Provides access to task creation and management commands.
 */
class TaskCommand(
    ticketService: TicketService,
) : CliktCommand(
    name = "task",
    help = "Create or manage tasks",
) {
    init {
        subcommands(
            TaskCreateCommand(ticketService),
        )
    }

    override fun run() = Unit
}

/**
 * Create a ticket from the CLI.
 */
class TaskCreateCommand(
    private val ticketService: TicketService,
) : CliktCommand(
    name = "create",
    help = "Create a new ticket",
) {
    private val description by argument()
        .help("Task description (quote if it contains spaces)")

    private val urgencyInput by option("--urgency", help = "Priority level: low|medium|high")
        .default("medium")

    private val assignedTo by option("--assign", help = "Agent ID to assign the task to")

    override fun run() = runBlocking {
        val priority = when (urgencyInput.lowercase()) {
            "low" -> TicketPriority.LOW
            "medium" -> TicketPriority.MEDIUM
            "high" -> TicketPriority.HIGH
            else -> {
                echo(
                    "Invalid --urgency value: $urgencyInput (expected low|medium|high)",
                    err = true,
                )
                return@runBlocking
            }
        }

        ticketService.create(description) {
            priority(priority)
        }.fold(
            onSuccess = { ticket ->
                // Assign if requested
                assignedTo?.let { agentId ->
                    ticketService.assign(ticket.id, agentId)
                }

                val assignmentSuffix = assignedTo?.let { " assigned to $it" } ?: ""
                echo("Created ticket ${ticket.id} (${priority.name.lowercase()})$assignmentSuffix: $description")
            },
            onFailure = { error ->
                echo("Failed to create ticket: ${error.message}", err = true)
            },
        )
    }
}
