package link.socket.ampere.repl

import kotlinx.coroutines.runBlocking
import link.socket.ampere.AmpereContext
import link.socket.ampere.agents.core.status.TicketStatus
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import org.jline.terminal.Terminal

/**
 * Registry of action commands that affect the substrate.
 *
 * These commands send impulses through the motor cortex - emitting
 * events that trigger agent reactions and state changes.
 */
class ActionCommandRegistry(
    private val context: AmpereContext,
    private val terminal: Terminal,
) {
    /**
     * Execute an action command from user input.
     * Returns null if the command is not an action command.
     */
    suspend fun executeIfMatches(input: String): CommandResult? {
        val parts = input.split(" ")
        val command = parts[0].lowercase()

        return when (command) {
            "ticket" -> executeTicketAction(parts.drop(1))
            "message" -> executeMessageAction(parts.drop(1))
            "agent" -> executeAgentAction(parts.drop(1))
            else -> null
        }
    }

    private suspend fun executeTicketAction(args: List<String>): CommandResult {
        if (args.isEmpty()) {
            terminal.writer().println("Usage: ticket <create|assign|status|list> ...")
            return CommandResult.ERROR
        }

        return when (args[0].lowercase()) {
            "create" -> createTicket(args.drop(1))
            "assign" -> assignTicket(args.drop(1))
            "status" -> updateTicketStatus(args.drop(1))
            "list" -> listTickets(args.drop(1))
            else -> {
                terminal.writer().println("Unknown ticket subcommand: ${args[0]}")
                CommandResult.ERROR
            }
        }
    }

    private suspend fun createTicket(args: List<String>): CommandResult {
        // Parse: ticket create "TITLE" --priority HIGH --description "DESC" --type FEATURE
        if (args.isEmpty()) {
            terminal.writer().println("Usage: ticket create \"TITLE\" [--priority PRIORITY] [--description \"DESC\"] [--type TYPE]")
            return CommandResult.ERROR
        }

        val title = args[0].trim('"')
        var priority = TicketPriority.MEDIUM
        var description = ""
        var type = TicketType.TASK

        // Parse flags
        var i = 1
        while (i < args.size) {
            when (args[i].lowercase()) {
                "--priority" -> {
                    if (i + 1 < args.size) {
                        priority = try {
                            TicketPriority.valueOf(args[i + 1].uppercase())
                        } catch (e: IllegalArgumentException) {
                            terminal.writer().println("Invalid priority: ${args[i + 1]}")
                            terminal.writer().println("Valid values: LOW, MEDIUM, HIGH, CRITICAL")
                            return CommandResult.ERROR
                        }
                        i += 2
                    } else {
                        terminal.writer().println("Missing value for --priority")
                        return CommandResult.ERROR
                    }
                }
                "--description" -> {
                    if (i + 1 < args.size) {
                        description = args[i + 1].trim('"')
                        i += 2
                    } else {
                        terminal.writer().println("Missing value for --description")
                        return CommandResult.ERROR
                    }
                }
                "--type" -> {
                    if (i + 1 < args.size) {
                        type = try {
                            TicketType.valueOf(args[i + 1].uppercase())
                        } catch (e: IllegalArgumentException) {
                            terminal.writer().println("Invalid type: ${args[i + 1]}")
                            terminal.writer().println("Valid values: FEATURE, BUG, TASK, SPIKE")
                            return CommandResult.ERROR
                        }
                        i += 2
                    } else {
                        terminal.writer().println("Missing value for --type")
                        return CommandResult.ERROR
                    }
                }
                else -> i++
            }
        }

        val result = context.ticketActionService.createTicket(
            title = title,
            description = description,
            priority = priority,
            type = type,
        )

        return when {
            result.isSuccess -> {
                val ticket = result.getOrThrow()
                terminal.writer().println(TerminalColors.success("Created ticket ${ticket.id}: $title [$priority]"))
                terminal.writer().println(TerminalColors.dim("  Event: TicketCreated emitted"))
                CommandResult.SUCCESS
            }
            else -> {
                terminal.writer().println(TerminalColors.error("Failed to create ticket: ${result.exceptionOrNull()?.message}"))
                CommandResult.ERROR
            }
        }
    }

    private suspend fun assignTicket(args: List<String>): CommandResult {
        // Parse: ticket assign TICKET_ID AGENT_ID
        if (args.size < 2) {
            terminal.writer().println("Usage: ticket assign TICKET_ID AGENT_ID")
            return CommandResult.ERROR
        }

        val ticketId = args[0]
        val agentId = args[1]

        val result = context.ticketActionService.assignTicket(ticketId, agentId)

        return when {
            result.isSuccess -> {
                terminal.writer().println(TerminalColors.success("Assigned ticket $ticketId to $agentId"))
                terminal.writer().println(TerminalColors.dim("  Event: TicketAssigned emitted"))
                CommandResult.SUCCESS
            }
            else -> {
                terminal.writer().println(TerminalColors.error("Failed to assign ticket: ${result.exceptionOrNull()?.message}"))
                CommandResult.ERROR
            }
        }
    }

    private suspend fun updateTicketStatus(args: List<String>): CommandResult {
        // Parse: ticket status TICKET_ID STATUS
        if (args.size < 2) {
            terminal.writer().println("Usage: ticket status TICKET_ID STATUS")
            return CommandResult.ERROR
        }

        val ticketId = args[0]
        val status = try {
            TicketStatus.fromString(args[1])
        } catch (e: IllegalArgumentException) {
            terminal.writer().println("Invalid status: ${args[1]}")
            terminal.writer().println("Valid values: Backlog, Ready, InProgress, Blocked, Done, Cancelled")
            return CommandResult.ERROR
        }

        val result = context.ticketActionService.updateStatus(ticketId, status)

        return when {
            result.isSuccess -> {
                terminal.writer().println(TerminalColors.success("Updated ticket $ticketId status to $status"))
                terminal.writer().println(TerminalColors.dim("  Event: TicketStatusChanged emitted"))
                CommandResult.SUCCESS
            }
            else -> {
                terminal.writer().println(TerminalColors.error("Failed to update status: ${result.exceptionOrNull()?.message}"))
                CommandResult.ERROR
            }
        }
    }

    private suspend fun listTickets(args: List<String>): CommandResult {
        // This is actually an observation command but fits thematically here
        terminal.writer().println("Ticket listing: use 'status' command to see system-wide ticket status")
        return CommandResult.SUCCESS
    }

    private suspend fun executeMessageAction(args: List<String>): CommandResult {
        if (args.isEmpty()) {
            terminal.writer().println("Usage: message <post|create-thread> ...")
            return CommandResult.ERROR
        }

        return when (args[0].lowercase()) {
            "post" -> postMessage(args.drop(1))
            "create-thread" -> createThread(args.drop(1))
            else -> {
                terminal.writer().println("Unknown message subcommand: ${args[0]}")
                CommandResult.ERROR
            }
        }
    }

    private suspend fun postMessage(args: List<String>): CommandResult {
        // Parse: message post THREAD_ID "CONTENT" --sender SENDER_ID
        if (args.size < 2) {
            terminal.writer().println("Usage: message post THREAD_ID \"CONTENT\" [--sender SENDER_ID]")
            return CommandResult.ERROR
        }

        val threadId = args[0]
        val content = args[1].trim('"')
        val senderId = if (args.size > 3 && args[2] == "--sender") {
            args[3]
        } else {
            "human"
        }

        val result = context.messageActionService.postMessage(
            threadId = threadId,
            content = content,
            senderId = senderId,
        )

        return when {
            result.isSuccess -> {
                terminal.writer().println(TerminalColors.success("Posted message to thread $threadId"))
                terminal.writer().println(TerminalColors.dim("  Event: MessagePosted emitted"))
                CommandResult.SUCCESS
            }
            else -> {
                terminal.writer().println(TerminalColors.error("Failed to post message: ${result.exceptionOrNull()?.message}"))
                CommandResult.ERROR
            }
        }
    }

    private suspend fun createThread(args: List<String>): CommandResult {
        // Parse: message create-thread --title "TITLE" --participants ID1,ID2
        var title: String? = null
        var participants: List<String>? = null

        var i = 0
        while (i < args.size) {
            when (args[i].lowercase()) {
                "--title" -> {
                    if (i + 1 < args.size) {
                        title = args[i + 1].trim('"')
                        i += 2
                    } else {
                        terminal.writer().println("Missing value for --title")
                        return CommandResult.ERROR
                    }
                }
                "--participants" -> {
                    if (i + 1 < args.size) {
                        participants = args[i + 1].split(",").map { it.trim() }
                        i += 2
                    } else {
                        terminal.writer().println("Missing value for --participants")
                        return CommandResult.ERROR
                    }
                }
                else -> i++
            }
        }

        if (title == null || participants == null) {
            terminal.writer().println("Usage: message create-thread --title \"TITLE\" --participants ID1,ID2")
            return CommandResult.ERROR
        }

        val result = context.messageActionService.createThread(
            title = title,
            participantIds = participants,
        )

        return when {
            result.isSuccess -> {
                val thread = result.getOrThrow()
                terminal.writer().println(TerminalColors.success("Created thread ${thread.id}: $title"))
                terminal.writer().println(TerminalColors.dim("  Participants: ${participants.joinToString(", ")}"))
                terminal.writer().println(TerminalColors.dim("  Event: ThreadCreated emitted"))
                CommandResult.SUCCESS
            }
            else -> {
                terminal.writer().println(TerminalColors.error("Failed to create thread: ${result.exceptionOrNull()?.message}"))
                CommandResult.ERROR
            }
        }
    }

    private suspend fun executeAgentAction(args: List<String>): CommandResult {
        if (args.isEmpty()) {
            terminal.writer().println("Usage: agent <wake|list> ...")
            return CommandResult.ERROR
        }

        return when (args[0].lowercase()) {
            "wake" -> wakeAgent(args.drop(1))
            "list" -> listAgents(args.drop(1))
            else -> {
                terminal.writer().println("Unknown agent subcommand: ${args[0]}")
                CommandResult.ERROR
            }
        }
    }

    private suspend fun wakeAgent(args: List<String>): CommandResult {
        if (args.isEmpty()) {
            terminal.writer().println("Usage: agent wake AGENT_ID")
            return CommandResult.ERROR
        }

        val agentId = args[0]
        val result = context.agentActionService.wakeAgent(agentId)

        return when {
            result.isSuccess -> {
                terminal.writer().println(TerminalColors.success("Sent wake signal to $agentId"))
                terminal.writer().println(TerminalColors.dim("  Event: TaskCreated emitted (assigned to $agentId)"))
                CommandResult.SUCCESS
            }
            else -> {
                terminal.writer().println(TerminalColors.error("Failed to wake agent: ${result.exceptionOrNull()?.message}"))
                CommandResult.ERROR
            }
        }
    }

    private suspend fun listAgents(args: List<String>): CommandResult {
        // Observation command - could delegate to service
        terminal.writer().println("Agent listing: use 'status' command to see active agents")
        return CommandResult.SUCCESS
    }
}
