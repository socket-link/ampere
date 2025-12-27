package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.events.relay.EventRelayService
import link.socket.ampere.cli.watch.KeyboardInputHandler
import link.socket.ampere.cli.watch.WatchMode
import link.socket.ampere.cli.watch.WatchViewConfig
import link.socket.ampere.cli.watch.presentation.WatchPresenter
import link.socket.ampere.cli.watch.presentation.WatchViewState
import link.socket.ampere.renderer.DashboardRenderer
import link.socket.ampere.renderer.HelpOverlayRenderer
import link.socket.ampere.repl.TerminalFactory
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Start the AMPERE environment with an interactive multi-modal dashboard.
 *
 * This is the main entry point for observing and interacting with the AMPERE system.
 * It provides multiple viewing modes for observing the environment:
 * - Dashboard mode (d): System vitals, agent status, recent events
 * - Event stream mode (e): Filtered event stream
 * - Memory ops mode (m): Knowledge operations view
 * - Agent focus mode (1-9): Focus on specific agents
 * - Verbose toggle (v): Show/hide routine events
 *
 * Controls:
 *   d - Dashboard mode
 *   e - Event stream mode
 *   m - Memory operations mode
 *   v - Toggle verbose mode
 *   1-9 - Focus on specific agent
 *   Ctrl+C - Exit
 */
class StartCommand(
    private val eventRelayService: EventRelayService,
) : CliktCommand(
    name = "start",
    help = """
        Start the AMPERE environment with an interactive dashboard.

        This command launches the multi-modal watch interface, starting in
        dashboard mode. You can switch between different viewing modes using
        keyboard shortcuts:

        Viewing Modes:
          d - Dashboard: System vitals, agent status, recent events
          e - Event Stream: Filtered stream of significant events
          m - Memory Operations: Knowledge recall/storage patterns
          1-9 - Agent Focus: Detailed view of a specific agent

        Options:
          v - Toggle verbose mode (show/hide routine events)
          Ctrl+C - Exit

        The interface updates automatically every second.

        Examples:
          ampere start           # Start interactive dashboard (default command)
          ampere                 # Same as 'ampere start'
    """.trimIndent()
) {

    override fun run() = runBlocking {
        val terminal = TerminalFactory.createTerminal()
        val presenter = WatchPresenter(eventRelayService)
        val renderer = DashboardRenderer(terminal)
        val helpRenderer = HelpOverlayRenderer(terminal)
        val inputHandler = KeyboardInputHandler(terminal)

        var config = WatchViewConfig(mode = WatchMode.DASHBOARD, verboseMode = false)
        var lastRenderedOutput: String? = null
        var lastViewState: WatchViewState? = null

        try {
            // Initialize terminal for full-screen rendering
            initializeTerminal()

            // Start the presenter
            presenter.start()

            // Wait a moment for initial events to be processed
            delay(500)

            // Launch input handling in background
            val inputJob = launch {
                while (isActive) {
                    val key = inputHandler.readKey()
                    if (key != null) {
                        val newConfig = inputHandler.processKey(key, config)
                        if (newConfig != null) {
                            config = newConfig
                            // Force re-render on config change
                            lastRenderedOutput = null
                        }
                    }
                    delay(50) // Short delay to prevent busy-waiting
                }
            }

            // Launch rendering in background
            val renderJob = launch(Dispatchers.IO) {
                while (isActive) {
                    val output = generateOutput(
                        config, presenter, renderer, helpRenderer, terminal, lastViewState
                    )

                    // Only flush to terminal if output changed
                    if (output != lastRenderedOutput) {
                        print(output)
                        System.out.flush()
                        lastRenderedOutput = output
                    }

                    delay(250) // Render at 4 FPS instead of 1 FPS for better responsiveness
                }
            }

            // Wait for jobs (they run until cancellation)
            inputJob.join()
            renderJob.join()

        } catch (e: CancellationException) {
            // Clean shutdown
            throw e
        } catch (e: Exception) {
            // Show error on screen
            print("\u001B[2J\u001B[H")  // Clear screen
            println("Error: ${e.message}")
            e.printStackTrace()
            System.out.flush()
            throw e
        } finally {
            presenter.stop()
            inputHandler.close()  // Restore terminal to normal mode
            restoreTerminal()
            println("\nAMPERE dashboard stopped")
        }
    }

    private fun initializeTerminal() {
        print("\u001B[?1049h")  // Enter alternate screen buffer
        print("\u001B[?25l")    // Hide cursor
        print("\u001B[2J")      // Clear screen
        print("\u001B[H")       // Move cursor to home
        System.out.flush()
    }

    private fun restoreTerminal() {
        print("\u001B[2J")       // Clear screen
        print("\u001B[H")        // Move cursor to home
        print("\u001B[?25h")     // Show cursor
        print("\u001B[?1049l")   // Exit alternate screen buffer
        System.out.flush()
    }

    private fun generateOutput(
        config: WatchViewConfig,
        presenter: WatchPresenter,
        renderer: DashboardRenderer,
        helpRenderer: HelpOverlayRenderer,
        terminal: com.github.ajalt.mordant.terminal.Terminal,
        lastViewState: WatchViewState?
    ): String {
        return if (config.showHelp) {
            helpRenderer.render()
        } else when (config.mode) {
            WatchMode.DASHBOARD -> {
                val viewState = presenter.getViewState()
                renderer.render(viewState)
            }
            WatchMode.EVENT_STREAM -> {
                buildString {
                    append("\u001B[2J\u001B[H")  // Clear screen and home
                    appendLine("Event Stream Mode")
                    appendLine("Press 'd' to return to dashboard, 'h' for help")
                    appendLine()
                    appendLine("(Event stream rendering not yet implemented)")
                }
            }
            WatchMode.MEMORY_OPS -> {
                buildString {
                    append("\u001B[2J\u001B[H")  // Clear screen and home
                    appendLine("Memory Operations Mode")
                    appendLine("Press 'd' to return to dashboard, 'h' for help")
                    appendLine()
                    appendLine("(Memory ops rendering not yet implemented)")
                }
            }
            WatchMode.AGENT_FOCUS -> {
                buildString {
                    append("\u001B[2J\u001B[H")  // Clear screen and home
                    appendLine("Agent Focus Mode")
                    appendLine("Press 'd' to return to dashboard, 'h' for help")
                    appendLine()
                    appendLine("(Agent focus rendering not yet implemented)")
                }
            }
            WatchMode.HELP -> {
                helpRenderer.render()
            }
            WatchMode.COMMAND -> {
                val viewState = presenter.getViewState()
                buildString {
                    append(renderer.render(viewState))
                    append("\u001B[${terminal.info.height};1H")  // Move to bottom line
                    append("\u001B[2K")  // Clear line
                    append(":${config.commandInput}")
                    append("\u001B[?25h")  // Show cursor for typing
                }
            }
        }
    }
}
