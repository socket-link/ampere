package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.events.utils.generateUUID

/**
 * Root command for task operations.
 *
 * Provides access to task creation and management commands.
 */
class TaskCommand(
    private val contextProvider: () -> AmpereContext,
) : CliktCommand(
    name = "task",
    help = "Create or manage task events",
) {
    init {
        subcommands(
            TaskCreateCommand(contextProvider),
        )
    }

    override fun run() = Unit
}

/**
 * Create a TaskCreated event from the CLI.
 */
class TaskCreateCommand(
    private val contextProvider: () -> AmpereContext,
) : CliktCommand(
    name = "create",
    help = "Publish a TaskCreated event",
) {
    private val description by argument()
        .help("Task description (quote if it contains spaces)")

    private val taskId by option("--id", help = "Optional task ID (auto-generated if omitted)")

    private val urgencyInput by option("--urgency", help = "Urgency level: low|medium|high")
        .default("medium")

    private val assignedTo by option("--assign", help = "Agent ID to assign the task to")

    override fun run() = runBlocking {
        val context = contextProvider()
        val eventApi = context.environmentService.createEventApi("human-cli")

        val effectiveTaskId = taskId ?: run {
            // Assumption: when no task ID is provided, a generated UUID is acceptable.
            generateUUID("task-cli")
        }

        val urgency = when (urgencyInput.lowercase()) {
            "low" -> Urgency.LOW
            "medium" -> Urgency.MEDIUM
            "high" -> Urgency.HIGH
            else -> {
                echo(
                    "Invalid --urgency value: $urgencyInput (expected low|medium|high)",
                    err = true,
                )
                return@runBlocking
            }
        }

        try {
            eventApi.publishTaskCreated(
                taskId = effectiveTaskId,
                urgency = urgency,
                description = description,
                assignedTo = assignedTo,
            )

            val assignmentSuffix = assignedTo?.let { " assigned to $it" } ?: ""
            echo("TaskCreated $effectiveTaskId (${urgency.name.lowercase()})$assignmentSuffix: $description")
        } catch (e: Exception) {
            echo("Failed to publish TaskCreated: ${e.message}", err = true)
        }
    }
}
