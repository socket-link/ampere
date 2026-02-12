package link.socket.ampere.help

import link.socket.ampere.util.EventTypeParser

/**
 * Centralized repository of detailed help content for all CLI commands.
 *
 * Each command has extended help text with:
 * - Purpose (action-oriented description)
 * - Usage syntax
 * - Flags and options
 * - Valid values for constrained parameters
 * - Concrete examples
 * - When/why to use the command
 */
object CommandHelpContent {

    /**
     * Get detailed help for the watch command.
     */
    fun getWatchHelp(): String {
        val eventTypes = EventTypeParser.getAllEventTypeNames().sorted()

        return HelpFormatter.buildDetailedHelp(
            commandName = "watch",
            purpose = "Stream real-time events from the agent system",
            usage = "watch [--filter TYPE] [--agent ID]",
            flags = listOf(
                "-f, --filter TYPE" to "Show only events of this type (repeatable)",
                "-a, --agent ID" to "Show only events from this agent (repeatable)"
            ),
            examples = listOf(
                "watch" to "Stream all events",
                "watch -f TicketCreated" to "Only new tickets",
                "watch -f TaskCreated -f MessagePosted" to "Multiple event types",
                "watch -a agent-pm" to "Only ProductManager events",
                "watch -f TicketStatusChanged -a agent-dev" to "Combine filters"
            ),
            additionalSections = mapOf(
                "Event Types" to HelpFormatter.formatValidValues(eventTypes, perLine = 3),
                "Controls" to HelpFormatter.formatKeyBinding("Enter", "Stop watching (or Ctrl+C in command mode)")
            )
        )
    }

    /**
     * Get detailed help for the status command.
     */
    fun getStatusHelp(): String {
        return HelpFormatter.buildDetailedHelp(
            commandName = "status",
            purpose = "Show comprehensive system dashboard with current state",
            usage = "status",
            examples = listOf(
                "status" to "View current system state"
            ),
            additionalSections = mapOf(
                "Displays" to buildString {
                    appendLine("${HelpFormatter.DOUBLE_INDENT}• Active threads with escalations and stale indicators")
                    appendLine("${HelpFormatter.DOUBLE_INDENT}• Ticket breakdown by status (InProgress, Todo, Blocked, etc.)")
                    appendLine("${HelpFormatter.DOUBLE_INDENT}• Unassigned and high-priority ticket counts")
                    append("${HelpFormatter.DOUBLE_INDENT}• Summary of urgent items needing human attention")
                },
                "When to Use" to "${HelpFormatter.DOUBLE_INDENT}Quick pulse check on system health and pending work"
            )
        )
    }

    /**
     * Get detailed help for the thread command.
     */
    fun getThreadHelp(): String {
        return HelpFormatter.buildDetailedHelp(
            commandName = "thread",
            purpose = "View and manage conversation threads",
            usage = "thread <subcommand> [args]",
            examples = listOf(
                "thread list" to "List all active threads",
                "thread show thread-abc123" to "Show complete thread conversation"
            ),
            additionalSections = mapOf(
                "Subcommands" to buildString {
                    appendLine(HelpFormatter.formatKeyValue("list", "List all active threads with summary info"))
                    append(HelpFormatter.formatKeyValue("show <id>", "Display full conversation for a thread"))
                },
                "When to Use" to buildString {
                    appendLine("${HelpFormatter.DOUBLE_INDENT}• Review agent-to-agent conversations")
                    appendLine("${HelpFormatter.DOUBLE_INDENT}• Investigate escalated threads needing human input")
                    append("${HelpFormatter.DOUBLE_INDENT}• Understand communication patterns and decision-making")
                }
            )
        )
    }

    /**
     * Get detailed help for the outcomes command.
     */
    fun getOutcomesHelp(): String {
        return HelpFormatter.buildDetailedHelp(
            commandName = "outcomes",
            purpose = "Query execution outcome memory and system learning",
            usage = "outcomes <subcommand> [args]",
            examples = listOf(
                "outcomes ticket TKT-123" to "View all execution attempts for a ticket",
                "outcomes search \"authentication\"" to "Find similar past outcomes",
                "outcomes executor agent-dev" to "Analyze executor performance",
                "outcomes stats" to "View aggregate statistics"
            ),
            additionalSections = mapOf(
                "Subcommands" to buildString {
                    appendLine(HelpFormatter.formatKeyValue("ticket <id>", "Show execution history for a ticket"))
                    appendLine(HelpFormatter.formatKeyValue("search <query>", "Find outcomes similar to description"))
                    appendLine(HelpFormatter.formatKeyValue("executor <id>", "Show outcomes for specific executor"))
                    append(HelpFormatter.formatKeyValue("stats", "Aggregate outcome statistics"))
                },
                "When to Use" to buildString {
                    appendLine("${HelpFormatter.DOUBLE_INDENT}• Debug repeated ticket failures")
                    appendLine("${HelpFormatter.DOUBLE_INDENT}• Learn from past similar tasks")
                    appendLine("${HelpFormatter.DOUBLE_INDENT}• Evaluate agent/executor effectiveness")
                    append("${HelpFormatter.DOUBLE_INDENT}• Track system learning progress")
                }
            )
        )
    }

    /**
     * Get detailed help for the ticket command (REPL mode).
     */
    fun getTicketHelp(): String {
        return HelpFormatter.buildDetailedHelp(
            commandName = "ticket",
            purpose = "Create and manage tickets",
            usage = "ticket <subcommand> [args]",
            examples = listOf(
                "ticket create \"Add auth\" -p HIGH" to "Create high-priority ticket",
                "ticket assign TKT-123 agent-dev" to "Assign to developer agent",
                "ticket status TKT-123 InProgress" to "Update ticket status"
            ),
            additionalSections = mapOf(
                "Subcommands" to buildString {
                    appendLine(HelpFormatter.formatKeyValue(
                        "create \"TITLE\"",
                        "Create ticket (--priority, --description optional)"
                    ))
                    appendLine(HelpFormatter.formatKeyValue("assign <id> <agent>", "Assign ticket to agent"))
                    append(HelpFormatter.formatKeyValue("status <id> <STATUS>", "Update ticket status"))
                },
                "Valid Priorities" to HelpFormatter.formatValidValues(
                    listOf("LOW", "MEDIUM", "HIGH", "CRITICAL"),
                    perLine = 4
                ),
                "Valid Statuses" to HelpFormatter.formatValidValues(
                    listOf("Backlog", "Ready", "InProgress", "Blocked", "InReview", "Done"),
                    perLine = 3
                ),
                "Status Transitions" to buildString {
                    appendLine("${HelpFormatter.DOUBLE_INDENT}Backlog → Ready, Done")
                    appendLine("${HelpFormatter.DOUBLE_INDENT}Ready → InProgress")
                    appendLine("${HelpFormatter.DOUBLE_INDENT}InProgress → Blocked, InReview, Done")
                    appendLine("${HelpFormatter.DOUBLE_INDENT}Blocked → InProgress")
                    appendLine("${HelpFormatter.DOUBLE_INDENT}InReview → InProgress, Done")
                    append("${HelpFormatter.DOUBLE_INDENT}Done → (terminal)")
                }
            )
        )
    }

    /**
     * Get detailed help for the message command (REPL mode).
     */
    fun getMessageHelp(): String {
        return HelpFormatter.buildDetailedHelp(
            commandName = "message",
            purpose = "Post messages and create conversation threads",
            usage = "message <subcommand> [args]",
            examples = listOf(
                "message post thread-123 \"Status update\"" to "Post to existing thread",
                "message post thread-123 \"Hello\" --sender agent-pm" to "Post as specific agent",
                "message create-thread -t \"Planning\" --participants agent-pm,agent-dev" to "Create new thread"
            ),
            additionalSections = mapOf(
                "Subcommands" to buildString {
                    appendLine(HelpFormatter.formatKeyValue(
                        "post <thread> \"TEXT\"",
                        "Post message to thread (--sender optional)"
                    ))
                    append(HelpFormatter.formatKeyValue(
                        "create-thread",
                        "Create thread (--title, --participants required)"
                    ))
                }
            )
        )
    }

    /**
     * Get detailed help for the agent command (REPL mode).
     */
    fun getAgentHelp(): String {
        return HelpFormatter.buildDetailedHelp(
            commandName = "agent",
            purpose = "Interact with and control agents",
            usage = "agent <subcommand> [args]",
            examples = listOf(
                "agent wake agent-pm" to "Wake dormant ProductManager agent"
            ),
            additionalSections = mapOf(
                "Subcommands" to HelpFormatter.formatKeyValue("wake <id>", "Send wake signal to agent")
            )
        )
    }

    /**
     * Get the main help overview with all commands organized by category.
     */
    fun getMainHelp(): String {
        val sections = linkedMapOf(
            "OBSERVE|Stream events and inspect system state" to listOf(
                Triple("watch [--filter TYPE] [--agent ID]", "Stream live events (Enter to stop)", null),
                Triple("status", "Show agents, threads, active tickets", null),
                Triple("thread list", "List conversation threads", null),
                Triple("thread show <id>", "Show thread messages", null),
                Triple("outcomes <subcommand>", "Query decision history", null)
            ),
            "ACT|Create work and coordinate agents" to listOf(
                Triple("ticket create \"TITLE\" [OPTIONS]", "Create new ticket", null),
                Triple("ticket assign <id> <agent>", "Assign ticket to agent", null),
                Triple("ticket status <id> <STATUS>", "Update ticket status", null),
                Triple("message post <thread> \"TEXT\"", "Post to thread", null),
                Triple("message create-thread [OPTIONS]", "Create conversation thread", null),
                Triple("agent wake <id>", "Activate dormant agent", null)
            ),
            "HELP|Learn and explore" to listOf(
                Triple("help", "This overview", null),
                Triple("help <command>", "Detailed command help with examples", null)
            )
        )

        val footer = buildString {
            appendLine(HelpFormatter.formatSection("KEYS", null))
            appendLine(HelpFormatter.INDENT + HelpFormatter.formatKeyBinding("Enter", "interrupt  ↑↓=history  Tab=complete  Ctrl+D=exit"))
        }

        return HelpFormatter.buildMainHelp(sections, footer)
    }

    /**
     * Get compact help for narrow terminals (< 90 chars).
     */
    fun getCompactHelp(): String {
        return buildString {
            appendLine("AMPERE - Command Reference")
            appendLine()
            appendLine("─── OBSERVE (Ctrl+C to stop) ───")
            appendLine("  w [-f TYPE] [-a ID]    Stream events")
            appendLine("  s                      System status")
            appendLine("  t list | show <id>     Threads")
            appendLine("  o ticket|search|stats  Outcomes")
            appendLine()
            appendLine("─── ACT ───")
            appendLine("  ticket create \"TITLE\" [-p PRI] [-d \"DESC\"]")
            appendLine("  ticket assign <id> <agent>")
            appendLine("  ticket status <id> <STATUS>")
            appendLine("  message post <thread> \"TEXT\" [-s ID]")
            appendLine("  agent wake <id>")
            appendLine()
            appendLine("─── HELP ───")
            appendLine("  help               This overview")
            appendLine("  help <command>     Detailed help")
            appendLine()
            appendLine("Keys: Enter=stop  ↑↓=history  Tab=complete  Ctrl+D=exit")
        }
    }
}
