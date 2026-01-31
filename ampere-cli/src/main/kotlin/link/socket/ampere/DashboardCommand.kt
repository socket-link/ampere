package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.events.relay.EventRelayService
import link.socket.ampere.cli.watch.presentation.WatchPresenter
import link.socket.ampere.renderer.DashboardRenderer
import link.socket.ampere.repl.TerminalFactory

/**
 * Command to display a live-updating dashboard of the AMPERE environment.
 *
 * Shows system vitals, agent activity, and recent significant events
 * in a condensed, easy-to-scan format.
 */
class DashboardCommand(
    private val eventRelayService: EventRelayService,
) : CliktCommand(
    name = "dashboard",
    help = """
        Display a live-updating dashboard of the AMPERE environment.

        Shows system vitals (active agents, system state), agent activity
        with visual indicators, and a feed of recent significant events.

        The dashboard updates automatically every second and filters out
        routine cognitive operations by default for a cleaner view.

        Controls:
          Press Ctrl+C to stop

        Examples:
          ampere dashboard                    # Start live dashboard
          ampere dashboard --refresh-interval 2  # Update every 2 seconds
    """.trimIndent()
) {
    private val refreshInterval by option(
        "--refresh-interval", "-r",
        help = "Refresh interval in seconds (default: 1)"
    ).default("1")

    override fun run() = runBlocking {
        val terminal = TerminalFactory.createTerminal()
        val presenter = WatchPresenter(eventRelayService)
        val renderer = DashboardRenderer(terminal)

        try {
            // Start the presenter
            presenter.start()

            // Parse refresh interval
            val intervalSeconds = refreshInterval.toLongOrNull() ?: 1L
            val intervalMs = intervalSeconds * 1000

            // Render loop
            while (true) {
                // Get current view state
                val viewState = presenter.getViewState()

                // Render to terminal
                val output = renderer.render(viewState)
                print(output)

                // Wait for next refresh
                delay(intervalMs)
            }
        } catch (e: CancellationException) {
            // Clean shutdown
            throw e
        } catch (e: Exception) {
            terminal.println("Error: ${e.message}")
            throw e
        } finally {
            presenter.stop()
            // Show cursor and clear formatting
            print("\u001B[?25h") // Show cursor
            println("\nDashboard stopped")
        }
    }
}
