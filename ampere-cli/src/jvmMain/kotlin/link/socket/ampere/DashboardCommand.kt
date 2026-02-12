package link.socket.ampere

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.jakewharton.mosaic.runMosaicBlocking
import kotlinx.coroutines.delay
import link.socket.ampere.agents.events.relay.EventRelayService
import link.socket.ampere.cli.mosaic.DashboardScreen
import link.socket.ampere.cli.watch.presentation.WatchPresenter
import link.socket.ampere.cli.watch.presentation.WatchViewState
import link.socket.ampere.cli.watch.presentation.SystemVitals

/**
 * Command to display a live-updating dashboard of the AMPERE environment.
 *
 * Shows system vitals, agent activity, and recent significant events
 * in a condensed, easy-to-scan format. Rendered with Mosaic (Compose for terminal).
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

    override fun run() {
        val presenter = WatchPresenter(eventRelayService)
        val intervalSeconds = refreshInterval.toLongOrNull() ?: 1L
        val intervalMs = intervalSeconds * 1000

        runMosaicBlocking {
            var viewState by remember {
                mutableStateOf(
                    WatchViewState(
                        systemVitals = SystemVitals(),
                        agentStates = emptyMap(),
                        recentSignificantEvents = emptyList(),
                    )
                )
            }
            var frameTick by remember { mutableLongStateOf(0L) }

            LaunchedEffect(Unit) {
                presenter.start()
                try {
                    while (true) {
                        viewState = presenter.getViewState()
                        frameTick++
                        delay(intervalMs)
                    }
                } finally {
                    presenter.stop()
                }
            }

            DashboardScreen(viewState, frameTick)
        }
    }
}
