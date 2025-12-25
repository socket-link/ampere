package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.events.relay.EventRelayFilters
import link.socket.ampere.agents.events.relay.EventRelayService
import link.socket.ampere.cli.watch.presentation.CognitiveClusterer
import link.socket.ampere.cli.watch.presentation.EventCategorizer
import link.socket.ampere.cli.watch.presentation.EventSignificance
import link.socket.ampere.renderer.CLIRenderer
import link.socket.ampere.repl.TerminalFactory
import link.socket.ampere.util.EventTypeParser

/**
 * Command to observe the environment's electrical activity in real-time.
 *
 * This is the CLI's primary sensory interface - it lets you feel the pulse
 * of the organizational organism by streaming events as they occur.
 *
 * @param eventRelayService The service for subscribing to events (injected)
 * @param renderer CLI renderer for all output
 */
class WatchCommand(
    private val eventRelayService: EventRelayService,
    private val renderer: CLIRenderer = CLIRenderer(TerminalFactory.createTerminal()),
) : CliktCommand(
    name = "watch",
    help = """
        Watch events streaming from the AniMA environment in real-time.

        Connects to the EventBus and renders events to terminal with color-coding
        and formatting for human readability.

        By default, routine cognitive operations (knowledge recall/storage) are hidden
        to reduce noise. Use --verbose or --filter-significance all to see everything.

        Controls:
          Press Ctrl+C to stop watching

        Examples:
          ampere watch                                        # Watch significant events (default)
          ampere watch --verbose                              # Show all events including routine
          ampere watch --group-cognitive-cycles               # Group knowledge recall/store cycles
          ampere watch -g -v                                  # Group cycles + show all events
          ampere watch --filter-significance critical         # Only critical events
          ampere watch --filter TaskCreated                   # Filter by event type
          ampere watch --filter TaskCreated --filter QuestionRaised  # Multiple types
          ampere watch --agent agent-pm                       # Filter by agent ID
          ampere watch --agent ProductManagerAgent --verbose  # Agent filter + all events
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

    // Option for filtering by event significance
    private val filterSignificance by option(
        "--filter-significance", "-s",
        help = "Filter by event significance: all, significant, critical. Default: significant (hides routine events)."
    ).default("significant")

    // Flag for verbose mode (show all events including routine)
    private val verbose by option(
        "--verbose", "-v",
        help = "Show all events including routine cognitive operations (equivalent to --filter-significance all)."
    ).flag()

    // Flag for grouping cognitive cycles
    private val groupCognitiveCycles by option(
        "--group-cognitive-cycles", "-g",
        help = "Group related knowledge recall/store events into cognitive cycles for cleaner output."
    ).flag()

    override fun run() = runBlocking {
        // Show startup banner
        renderer.renderWatchBanner()

        try {
            // Build filters from command options
            val filters = buildFilters()

            // Show active filters
            renderer.renderActiveFilters(filters)

            // Create clusterer if grouping is enabled
            val clusterer = if (groupCognitiveCycles) CognitiveClusterer() else null

            // Track which events are part of clusters
            val clusteredEventIds = mutableSetOf<String>()

            // Subscribe to live events and render them
            renderer.renderWatchStart()

            eventRelayService.subscribeToLiveEvents(filters)
                .collect { event ->
                    // Check if coroutine is cancelled before processing
                    ensureActive()

                    // Try to cluster the event if grouping is enabled
                    if (clusterer != null) {
                        val cluster = clusterer.processEvent(event)
                        if (cluster != null) {
                            // Mark all events in the cluster as processed
                            cluster.events.forEach { clusteredEventIds.add(it.eventId) }

                            // Render the cluster
                            renderer.renderCognitiveCluster(cluster)
                        } else if (event.eventId !in clusteredEventIds) {
                            // Event not part of a cluster yet, render it normally
                            if (shouldDisplayEvent(event)) {
                                renderer.renderEvent(event)
                            }
                        }
                    } else {
                        // No clustering, just filter by significance
                        if (shouldDisplayEvent(event)) {
                            renderer.renderEvent(event)
                        }
                    }
                }
        } catch (e: CancellationException) {
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

    /**
     * Determine if an event should be displayed based on significance filtering.
     */
    private fun shouldDisplayEvent(event: Event): Boolean {
        // Verbose mode shows everything
        if (verbose) return true

        val significance = EventCategorizer.categorize(event)

        return when (filterSignificance.lowercase()) {
            "all" -> true
            "significant" -> significance == EventSignificance.SIGNIFICANT ||
                           significance == EventSignificance.CRITICAL
            "critical" -> significance == EventSignificance.CRITICAL
            else -> significance.shouldDisplayByDefault
        }
    }
}
