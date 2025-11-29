package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.events.EventSource
import link.socket.ampere.agents.events.relay.EventRelayFilters
import link.socket.ampere.agents.events.relay.EventRelayService
import link.socket.ampere.renderer.CLIRenderer
import link.socket.ampere.util.EventTypeParser

/**
 * Command to observe the substrate's electrical activity in real-time.
 *
 * This is the CLI's primary sensory interface - it lets you feel the pulse
 * of the organizational organism by streaming events as they occur.
 *
 * @param eventRelayService The service for subscribing to events (injected)
 * @param renderer CLI renderer for all output
 */
class WatchCommand(
    private val eventRelayService: EventRelayService,
    private val renderer: CLIRenderer = CLIRenderer(Terminal()),
) : CliktCommand(
    name = "watch",
    help = """
        Watch events streaming from the AniMA substrate in real-time.

        Connects to the EventBus and renders events to terminal with color-coding
        and formatting for human readability.

        Examples:
          ampere watch                         # Watch all events
          ampere watch --filter TaskCreated    # Filter by event type
          ampere watch --filter TaskCreated --filter QuestionRaised  # Multiple types
          ampere watch --agent agent-pm        # Filter by agent ID
    """.trimIndent()
) {
    // Repeatable option for filtering by event types
    private val filterTypes by option(
        "--filter", "-f",
        help = "Filter by event type (e.g., TaskCreated, QuestionRaised). Repeatable."
    ).multiple()

    // Repeatable option for filtering by agent IDs
    private val filterAgents by option(
        "--agent", "-a",
        help = "Filter by agent ID (e.g., agent-pm, agent-dev). Repeatable."
    ).multiple()

    override fun run() = runBlocking {
        // Show startup banner
        renderer.renderWatchBanner()

        try {
            // Build filters from command options
            val filters = buildFilters()

            // Show active filters
            renderer.renderActiveFilters(filters)

            // Subscribe to live events and render them
            renderer.renderWatchStart()

            eventRelayService.subscribeToLiveEvents(filters)
                .collect { event ->
                    // Check if coroutine is cancelled before processing
                    kotlinx.coroutines.ensureActive()
                    renderer.renderEvent(event)
                }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Gracefully handle cancellation - just stop collecting
            // The CommandExecutor will display the interrupt message
            throw e
        } catch (e: Exception) {
            renderer.renderError(e.message ?: "Unknown error")
            throw e
        }
    }

    /**
     * Build EventRelayFilters from command-line options.
     */
    private fun buildFilters(): EventRelayFilters {
        // Parse event types
        val eventTypes = if (filterTypes.isNotEmpty()) {
            val parsed = EventTypeParser.parseMultiple(filterTypes)
            if (parsed.isEmpty()) {
                renderer.renderWarning("No valid event types found in filters")
                renderer.renderAvailableEventTypes(EventTypeParser.getAllEventTypeNames())
            }
            parsed
        } else {
            null
        }

        // Parse agent IDs to EventSource.Agent objects
        val eventSources = if (filterAgents.isNotEmpty()) {
            filterAgents.map { EventSource.Agent(it) }.toSet()
        } else {
            null
        }

        return EventRelayFilters(
            eventTypes = eventTypes,
            eventSources = eventSources
        )
    }
}
