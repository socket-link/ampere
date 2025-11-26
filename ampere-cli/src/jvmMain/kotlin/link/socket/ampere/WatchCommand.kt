package link.socket.ampere

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.EventSource
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.relay.EventRelayFilters
import link.socket.ampere.agents.events.relay.EventRelayServiceImpl
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database
import link.socket.ampere.renderer.EventRenderer
import link.socket.ampere.util.EventTypeParser

/**
 * Command to observe the substrate's electrical activity in real-time.
 *
 * This is the CLI's primary sensory interface - it lets you feel the pulse
 * of the organizational organism by streaming events as they occur.
 */
class WatchCommand : CliktCommand(
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

    private val terminal = Terminal()

    override fun run() = runBlocking {
        // Show startup banner
        terminal.println(bold(cyan("⚡ AMPERE")) + " - Connecting to AniMA Model Protocol virtual environment")
        terminal.println(dim("Connecting to event stream..."))
        terminal.println()

        // Set up the event infrastructure
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

        try {
            // Create database schema
            Database.Schema.create(driver)
            val database = Database(driver)

            // Initialize event infrastructure
            val eventRepository = EventRepository(DEFAULT_JSON, scope, database)
            val eventBus = EventSerialBus(scope)
            val eventRelayService = EventRelayServiceImpl(eventBus, eventRepository)

            // Create event renderer
            val renderer = EventRenderer(terminal)

            // Build filters from command options
            val filters = buildFilters()

            // Show active filters
            showActiveFilters(filters)

            // Subscribe to live events and render them
            terminal.println(bold("Watching events... (Ctrl+C to stop)"))
            terminal.println()

            eventRelayService.subscribeToLiveEvents(filters)
                .collect { event ->
                    renderer.render(event)
                }
        } catch (e: Exception) {
            terminal.println(red("Error: ${e.message}"))
            throw e
        } finally {
            driver.close()
            scope.cancel()
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
                terminal.println(yellow("Warning: No valid event types found in filters"))
                terminal.println(yellow("Available types: ${EventTypeParser.getAllEventTypeNames().sorted().joinToString(", ")}"))
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
     * Display active filters to the user.
     */
    private fun showActiveFilters(filters: EventRelayFilters) {
        if (filters.isEmpty()) {
            terminal.println(dim("Watching all events (no filters)"))
        } else {
            terminal.println(dim("Active filters:"))
            filters.eventTypes?.let { types ->
                terminal.println(dim("  Event types: ${types.map { it.second }.joinToString(", ")}"))
            }
            filters.eventSources?.let { sources ->
                terminal.println(dim("  Agents: ${sources.map { (it as EventSource.Agent).agentId }.joinToString(", ")}"))
            }
        }
        terminal.println()
    }
}
