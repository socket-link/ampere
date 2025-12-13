package link.socket.ampere.repl

import link.socket.ampere.help.CommandHelpContent

/**
 * Manages display of help information for the REPL.
 * Centralizes all help text and provides methods for displaying different help sections.
 *
 * Now delegates to CommandHelpContent for consistent, demo-ready help text.
 */
class HelpDisplayManager(
    private val terminalOps: TerminalOperations
) {
    /**
     * Display main help based on terminal width.
     */
    fun displayHelp() {
        val width = terminalOps.getWidth()
        val compact = width < 90

        if (compact) {
            terminalOps.println(CommandHelpContent.getCompactHelp())
        } else {
            terminalOps.println(CommandHelpContent.getMainHelp())
        }
    }

    /**
     * Display help for a specific command.
     */
    fun displayCommandHelp(commandName: String) {
        val helpText = when (commandName.lowercase()) {
            "watch", "w" -> CommandHelpContent.getWatchHelp()
            "ticket" -> CommandHelpContent.getTicketHelp()
            "message" -> CommandHelpContent.getMessageHelp()
            "agent" -> CommandHelpContent.getAgentHelp()
            "status", "s" -> CommandHelpContent.getStatusHelp()
            "thread", "t" -> CommandHelpContent.getThreadHelp()
            "outcomes", "o" -> CommandHelpContent.getOutcomesHelp()
            else -> {
                terminalOps.println(TerminalColors.error("Unknown command: $commandName"))
                terminalOps.println(TerminalColors.info("Type 'help' for all commands"))
                return
            }
        }
        terminalOps.println(helpText)
    }

    /**
     * Display quick start information.
     */
    fun displayQuickStart() {
        terminalOps.println(TerminalColors.info("  Type 'help' for commands, 'exit' to quit"))
        terminalOps.println(TerminalColors.info("  Press Ctrl+C to interrupt running observations"))
        terminalOps.println()
    }
}
