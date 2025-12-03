package link.socket.ampere.repl

import java.nio.file.Paths
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import link.socket.ampere.AmpereContext
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder

/**
 * Manages an interactive REPL session for the AMPERE CLI.
 *
 * The REPL session maintains a persistent environment where multiple
 * commands can be executed sequentially without restarting the environment.
 * This is the "brainstem interface" connecting human input to agent activity.
 */
class ReplSession(
    private val context: AmpereContext
) {
    private val terminal: Terminal = TerminalBuilder.builder()
        .system(true)
        .build()

    private val historyFile = Paths.get(
        System.getProperty("user.home"),
        ".ampere",
        "history"
    )

    private val reader: LineReader = LineReaderBuilder.builder()
        .terminal(terminal)
        .variable(LineReader.HISTORY_FILE, historyFile)
        .completer(AmpereCompleter(context))
        .build()

    private val executor = CommandExecutor(terminal)
    private val statusBar = StatusBar(terminal)
    private val modeManager = ModeManager()
    private val filterCycler = EventFilterCycler()

    // Abstracted components for better separation of concerns
    private val terminalOps = TerminalOperations(terminal)
    private val aliasExpander = AliasExpander()
    private val helpDisplayManager = HelpDisplayManager(terminalOps)
    private val keyBindingManager = KeyBindingManager(reader)
    private val signalHandlerManager = SignalHandlerManager()
    private val inputHandler = InputHandler(
        terminal,
        reader,
        modeManager,
        executor,
        filterCycler,
        terminalOps
    )

    // Add registry for observation commands
    private val observationCommands = ObservationCommandRegistry(
        context,
        terminal,
        executor,
        statusBar,
        filterCycler
    )

    // Add registry for action commands
    private val actionCommands = ActionCommandRegistry(
        context,
        terminal
    )

    init {
        // Ensure history directory exists
        historyFile.parent.toFile().mkdirs()

        // Install custom key bindings and signal handlers
        keyBindingManager.installClearScreenBindings { terminalOps.clearScreen() }
        signalHandlerManager.installEmergencyExitHandler(
            TerminalColors.dim("Emergency exit!")
        )
    }

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

                  ⚡──○──⚡
               ⚡──○──⚡──○──⚡          AMPERE Interactive Shell v0.1.0
            ⚡──○──⚡──○──⚡──○──⚡     Autonomous multi-agent coordination
               ⚡──○──⚡──○──⚡
                  ⚡──○──⚡
        """.trimIndent()

        terminalOps.println(banner)
        terminalOps.println()

        // Display initial system status
        displaySystemStatus()
        terminalOps.println()
        helpDisplayManager.displayQuickStart()
    }

    private fun displaySystemStatus() {
        // TODO: Pull actual metrics from context services
        terminalOps.println("System Status: ● Running")
    }

    private fun runCommandLoop() {
        while (true) {
            try {
                statusBar.render(
                    modeManager.getCurrentMode(),
                    if (modeManager.getCurrentMode() == Mode.OBSERVING) filterCycler.current() else null
                )

                val line = try {
                    inputHandler.readInput()
                } catch (e: EndOfFileException) {
                    // Ctrl+D handling
                    inputHandler.handleCtrlD()
                    continue
                } catch (e: EmergencyExitException) {
                    // Double-Esc
                    terminalOps.println("\n${TerminalColors.warning("Emergency exit!")}")
                    break
                }

                if (line == null) continue

                // Execute command in cancellable context
                val result = runBlocking {
                    executeCommand(line)
                }

                if (result == CommandResult.EXIT) {
                    terminalOps.println("Goodbye! Shutting down environment...")
                    break
                }

            } catch (e: Exception) {
                terminalOps.println(TerminalColors.error(e.message ?: "Unknown error"))
            }
        }
    }

    private suspend fun executeCommand(input: String): CommandResult {
        // Handle special commands
        when (input.lowercase()) {
            "clear", ".clear" -> {
                terminalOps.clearScreen()
                return CommandResult.SUCCESS
            }
        }

        // Expand aliases
        val expandedInput = aliasExpander.expand(input)

        // Try observation commands first
        val observationResult = observationCommands.executeIfMatches(expandedInput)
        if (observationResult != null) {
            if (observationResult == CommandResult.SUCCESS) {
                modeManager.setMode(Mode.OBSERVING)
            }
            return observationResult
        }

        // Try action commands
        val actionResult = actionCommands.executeIfMatches(expandedInput)
        if (actionResult != null) {
            return actionResult
        }

        // Check built-in commands
        val parts = expandedInput.split(" ", limit = 2)
        val command = parts[0].lowercase()
        val args = parts.getOrNull(1) ?: ""

        return when (command) {
            "exit", "quit" -> CommandResult.EXIT
            "help", "?" -> {
                if (args.isNotBlank()) {
                    helpDisplayManager.displayCommandHelp(args.trim())
                } else {
                    helpDisplayManager.displayHelp()
                }
                CommandResult.SUCCESS
            }
            "test-interrupt" -> {
                // Test command for verifying interruption works
                executor.execute {
                    terminalOps.println("Running for 30 seconds... Press Enter to interrupt")
                    delay(30000)
                    terminalOps.println("Completed!")
                    CommandResult.SUCCESS
                }
            }
            else -> {
                terminalOps.println(TerminalColors.error("Unknown command: $command"))
                terminalOps.println(TerminalColors.info("Type 'help' for available commands"))
                CommandResult.ERROR
            }
        }
    }

    fun close() {
        // JLine3 automatically saves history when the reader is closed
        executor.close()
        signalHandlerManager.clearAllHandlers()
        terminal.close()
    }
}

enum class CommandResult {
    SUCCESS,
    ERROR,
    EXIT,
    INTERRUPTED  // Added for Ctrl+C handling
}

/**
 * Exception thrown when user triggers emergency exit (double-tap Esc).
 */
class EmergencyExitException : Exception()
