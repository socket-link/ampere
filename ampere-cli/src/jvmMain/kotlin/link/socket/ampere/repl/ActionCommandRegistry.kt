package link.socket.ampere.repl

import link.socket.ampere.AmpereContext
import link.socket.ampere.agents.domain.concept.status.TicketStatus
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import org.jline.terminal.Terminal

/**
 * Registry of action commands that affect the environment.
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
        val parser = ArgParser(args)
        val positional = parser.getPositional()

        if (positional.isEmpty() || parser.has("help")) {
            displayTicketCreateHelp()
            return CommandResult.ERROR
        }

        val title = positional[0].trim('"')
        val priority = parser.get("priority")?.let {
            try {
                TicketPriority.valueOf(it.uppercase())
            } catch (e: IllegalArgumentException) {
                terminal.writer().println(TerminalColors.error("Invalid priority: $it"))
                terminal.writer().println(TerminalColors.dim("Valid values: LOW, MEDIUM, HIGH, CRITICAL"))
                return CommandResult.ERROR
            }
        } ?: TicketPriority.MEDIUM

        val description = parser.get("description")?.trim('"') ?: ""
        val type = parser.get("type")?.let {
            try {
                TicketType.valueOf(it.uppercase())
            } catch (e: IllegalArgumentException) {
                terminal.writer().println(TerminalColors.error("Invalid type: $it"))
                terminal.writer().println(TerminalColors.dim("Valid values: FEATURE, BUG, TASK, SPIKE"))
                return CommandResult.ERROR
            }
        } ?: TicketType.TASK

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
        val parser = ArgParser(args)
        val positional = parser.getPositional()

        if (positional.size < 2 || parser.has("help")) {
            displayMessagePostHelp()
            return CommandResult.ERROR
        }

        val threadId = positional[0]
        val content = positional[1].trim('"')
        val senderId = parser.get("sender") ?: "human"

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
        val parser = ArgParser(args)

        if (parser.has("help") || !parser.has("title") || !parser.has("participants")) {
            displayThreadCreateHelp()
            return CommandResult.ERROR
        }

        val title = parser.get("title")?.trim('"') ?: run {
            terminal.writer().println(TerminalColors.error("Missing --title"))
            return CommandResult.ERROR
        }

        val participants = parser.get("participants")?.split(",")?.map { it.trim() } ?: run {
            terminal.writer().println(TerminalColors.error("Missing --participants"))
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

    // ═══ Help Functions ═══

    private fun displayTicketCreateHelp() {
        val help = """
            ticket create - Create a new ticket

            Usage: ticket create "TITLE" [-p PRIORITY] [-d "DESC"]

            Flags:
              -p, --priority PRIORITY    HIGH, MEDIUM, LOW, CRITICAL (default: MEDIUM)
              -d, --description DESC     Ticket description
              -h, --help                 Show this help

            Examples:
              ticket create "Add authentication" -p HIGH
              ticket create "Fix bug" -d "User login fails on retry"
        """.trimIndent()

        terminal.writer().println(help)
    }

    private fun displayMessagePostHelp() {
        val help = """
            message post - Post a message to a thread

            Usage: message post THREAD_ID "CONTENT" [-s SENDER_ID]

            Flags:
              -s, --sender SENDER_ID     Message sender (default: human)
              -h, --help                 Show this help

            Examples:
              message post thread-123 "Hello team"
              message post thread-123 "Status update" -s agent-pm
        """.trimIndent()

        terminal.writer().println(help)
    }

    private fun displayThreadCreateHelp() {
        val help = """
            message create-thread - Create a new conversation thread

            Usage: message create-thread -t "TITLE" --participants ID1,ID2

            Flags:
              -t, --title TITLE          Thread title
              --participants IDS         Comma-separated participant IDs
              -h, --help                 Show this help

            Examples:
              message create-thread -t "Sprint Planning" --participants agent-pm,agent-dev
        """.trimIndent()

        terminal.writer().println(help)
    }
}
