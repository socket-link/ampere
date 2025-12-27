package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.events.relay.EventRelayService
import link.socket.ampere.cli.watch.KeyboardInputHandler
import link.socket.ampere.cli.watch.WatchMode
import link.socket.ampere.cli.watch.WatchViewConfig
import link.socket.ampere.cli.watch.presentation.WatchPresenter
import link.socket.ampere.renderer.DashboardRenderer
import link.socket.ampere.renderer.HelpOverlayRenderer
import link.socket.ampere.repl.TerminalFactory

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

        try {
            // Initialize terminal for full-screen rendering
            print("\u001B[?1049h")  // Enter alternate screen buffer
            print("\u001B[?25l")    // Hide cursor
            print("\u001B[2J")      // Clear screen
            print("\u001B[H")       // Move cursor to home
            System.out.flush()

            // Start the presenter
            presenter.start()

            // Wait a moment for initial events to be processed
            delay(500)

            // Render loop
            while (true) {
                // Check for keyboard input
                val key = inputHandler.readKey()
                if (key != null) {
                    val newConfig = inputHandler.processKey(key, config)
                    if (newConfig != null) {
                        config = newConfig
                    }
                }

                // Render based on current mode and state
                if (config.showHelp) {
                    // Help overlay takes precedence
                    val output = helpRenderer.render()
                    print(output)
                    System.out.flush()
                } else when (config.mode) {
                    WatchMode.DASHBOARD -> {
                        val viewState = presenter.getViewState()
                        val output = renderer.render(viewState)
                        print(output)
                        System.out.flush()
                    }
                    WatchMode.EVENT_STREAM -> {
                        // TODO: Implement event stream rendering
                        print("\u001B[2J\u001B[H")  // Clear screen and home
                        println("Event Stream Mode")
                        println("Press 'd' to return to dashboard, 'h' for help")
                        println()
                        println("(Event stream rendering not yet implemented)")
                        System.out.flush()
                    }
                    WatchMode.MEMORY_OPS -> {
                        // TODO: Implement memory ops rendering
                        print("\u001B[2J\u001B[H")  // Clear screen and home
                        println("Memory Operations Mode")
                        println("Press 'd' to return to dashboard, 'h' for help")
                        println()
                        println("(Memory ops rendering not yet implemented)")
                        System.out.flush()
                    }
                    WatchMode.AGENT_FOCUS -> {
                        // TODO: Implement agent focus rendering
                        print("\u001B[2J\u001B[H")  // Clear screen and home
                        println("Agent Focus Mode")
                        println("Press 'd' to return to dashboard, 'h' for help")
                        println()
                        println("(Agent focus rendering not yet implemented)")
                        System.out.flush()
                    }
                    WatchMode.HELP -> {
                        // This shouldn't happen (use showHelp instead), but handle it
                        val output = helpRenderer.render()
                        print(output)
                        System.out.flush()
                    }
                    WatchMode.COMMAND -> {
                        // Render current view with command prompt at bottom
                        val viewState = presenter.getViewState()
                        val output = renderer.render(viewState)
                        print(output)

                        // Add command prompt at bottom
                        print("\u001B[${terminal.info.height};1H")  // Move to bottom line
                        print("\u001B[2K")  // Clear line
                        print(":${config.commandInput}")
                        print("\u001B[?25h")  // Show cursor for typing
                        System.out.flush()
                    }
                }

                // Wait before next refresh
                delay(1000)
            }
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

            // Restore terminal state
            print("\u001B[2J")       // Clear screen
            print("\u001B[H")        // Move cursor to home
            print("\u001B[?25h")     // Show cursor
            print("\u001B[?1049l")   // Exit alternate screen buffer
            System.out.flush()

            println("\nAMPERE dashboard stopped")
        }
    }
}
