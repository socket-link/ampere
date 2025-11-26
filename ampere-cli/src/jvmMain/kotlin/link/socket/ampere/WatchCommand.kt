package link.socket.ampere

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.events.EnvironmentService
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.db.Database
import link.socket.ampere.renderer.EventRenderer

class WatchCommand : CliktCommand(
    name = "watch",
    help = """
        Watch events streaming from the AniMA substrate in real-time.

        Connects to the EventBus and renders events to terminal with color-coding
        and formatting for human readability.

        Examples:
          ampere watch                    # Watch all events
          ampere watch --type TaskCreated # Filter by event type
          ampere watch --since 1h         # Events from last hour
    """.trimIndent()
) {
    private val eventType by option(
        "--type", "-t",
        help = "Filter events by type (TaskCreated, QuestionRaised, CodeSubmitted)"
    )

    private val agent by option(
        "--agent", "-a",
        help = "Filter events by agent ID"
    )

    private val since by option(
        "--since", "-s",
        help = "Show events since timestamp (e.g., '1h', '30m', '2024-01-01')"
    )

    private val limit by option(
        "--limit", "-n",
        help = "Limit number of events to display"
    ).int().default(100)

    private val replay by option(
        "--replay", "-r",
        help = "Replay historical events from database before watching"
    )

    private val terminal = Terminal()

    override fun run() = runBlocking {
        terminal.println(
            bold(cyan("AMPERE")) + " - AniMA Model Protocol Example Runtime Environment"
        )
        terminal.println(dim("Connecting to event stream..."))
        terminal.println()

        // Create database (in-memory for now)
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(driver)

        // Create coroutine scope for the environment
        val scope = CoroutineScope(Dispatchers.Default)

        // Create environment service using the factory method
        val environment = EnvironmentService.create(
            database = database,
            scope = scope,
        )

        // Create event renderer
        val renderer = EventRenderer(terminal)

        // Subscribe to all events
        val watcherId = "cli-watcher"
        terminal.println(dim("Subscribing to events..."))
        environment.subscribeToAll(
            agentId = watcherId,
            handler = EventHandler { event, _ ->
                // Filter by event type if specified
                if (eventType != null && !event.eventClassType.second.equals(eventType, ignoreCase = true)) {
                    return@EventHandler
                }

                // Filter by agent if specified
                if (agent != null && event.eventSource.getIdentifier() != agent) {
                    return@EventHandler
                }

                // Render the event
                renderer.render(event)
            }
        )

        // Start the environment
        environment.start()

        terminal.println(bold(cyan("✓")) + " Watching events (Press Ctrl+C to stop)")
        terminal.println()

        // Keep the command running
        // TODO: Add graceful shutdown handling
        Thread.currentThread().join()
    }
}
