package link.socket.ampere.repl

import link.socket.ampere.AmpereContext
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder

/**
 * Manages an interactive REPL session for the AMPERE CLI.
 *
 * The REPL session maintains a persistent environment where multiple
 * commands can be executed sequentially without restarting the substrate.
 * This is the "brainstem interface" connecting human input to agent activity.
 */
class ReplSession(
    private val context: AmpereContext
) {
    private val terminal: Terminal = TerminalBuilder.builder()
        .system(true)
        .build()

    private val reader: LineReader = LineReaderBuilder.builder()
        .terminal(terminal)
        .build()

    /**
     * Start the REPL loop.
     * Displays welcome banner, then enters command loop until exit.
     */
    fun start() {
        displayWelcomeBanner()
        runCommandLoop()
    }

    private fun displayWelcomeBanner() {
        val banner = """
        ╔═══════════════════════════════════════════════════════╗
        ║  AMPERE Interactive Shell v0.1.0                      ║
        ║  Autonomous Multi-Process Execution & Relay Env       ║
        ║                                                       ║
        ║  Type 'help' for commands, 'exit' to quit            ║
        ║  Press Ctrl+C to interrupt running observations       ║
        ╚═══════════════════════════════════════════════════════╝
        """.trimIndent()

        terminal.writer().println(banner)
        terminal.writer().println()

        // Display initial system status
        displaySystemStatus()
        terminal.writer().println()
    }

    private fun displaySystemStatus() {
        // TODO: Pull actual metrics from context services
        terminal.writer().println("System Status: ● Running")
    }

    private fun runCommandLoop() {
        while (true) {
            try {
                val line = reader.readLine("ampere> ")

                if (line.isNullOrBlank()) {
                    continue
                }

                val result = executeCommand(line.trim())

                if (result == CommandResult.EXIT) {
                    terminal.writer().println("Goodbye! Shutting down environment...")
                    break
                }

            } catch (e: Exception) {
                terminal.writer().println("Error: ${e.message}")
            }
        }
    }

    private fun executeCommand(input: String): CommandResult {
        val parts = input.split(" ", limit = 2)
        val command = parts[0].lowercase()
        val args = parts.getOrNull(1) ?: ""

        return when (command) {
            "exit", "quit" -> CommandResult.EXIT
            "help" -> {
                displayHelp()
                CommandResult.SUCCESS
            }
            else -> {
                terminal.writer().println("Unknown command: $command")
                terminal.writer().println("Type 'help' for available commands")
                CommandResult.ERROR
            }
        }
    }

    private fun displayHelp() {
        val help = """
        Available commands:
          help             Show this help message
          exit, quit       Exit the interactive session

        More commands coming soon...
        """.trimIndent()

        terminal.writer().println(help)
    }

    fun close() {
        terminal.close()
    }
}

enum class CommandResult {
    SUCCESS,
    ERROR,
    EXIT
}
