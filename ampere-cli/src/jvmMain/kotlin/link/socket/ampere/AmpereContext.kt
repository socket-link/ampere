package link.socket.ampere

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.environment.EnvironmentService
import link.socket.ampere.agents.events.messages.DefaultThreadViewService
import link.socket.ampere.agents.events.messages.ThreadViewService
import link.socket.ampere.agents.events.relay.EventRelayService
import link.socket.ampere.agents.events.tickets.DefaultTicketViewService
import link.socket.ampere.agents.events.tickets.TicketViewService
import link.socket.ampere.agents.events.utils.ConsoleEventLogger
import link.socket.ampere.agents.events.utils.EventLogger
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database

/**
 * Context that provides dependencies for CLI commands.
 *
 * AmpereContext handles all the initialization and wiring of the environment,
 * so commands just receive the services they need without worrying about
 * how they're created.
 *
 * This class:
 * - Initializes the database connection
 * - Creates the database schema
 * - Sets up the coroutine scope for async operations
 * - Creates the EnvironmentService with all orchestrators and repositories
 * - Provides access to services for CLI commands
 *
 * Usage:
 * ```kotlin
 * val context = AmpereContext()
 * context.start()
 * try {
 *     // Use context.environmentService or context.eventRelayService
 * } finally {
 *     context.close()
 * }
 * ```
 */
class AmpereContext(
    /**
     * Path to the SQLite database file.
     * Defaults to "ampere.db" in the user's home directory.
     */
    databasePath: String = defaultDatabasePath(),

    /**
     * JSON configuration for serialization.
     * Defaults to the standard Ampere JSON configuration.
     */
    json: Json = DEFAULT_JSON,

    /**
     * Event logger for system operations.
     * Defaults to console logging.
     */
    logger: EventLogger = ConsoleEventLogger(),
) {
    /**
     * Database driver for SQLite operations.
     */
    private val driver: JdbcSqliteDriver = createDriver(databasePath)

    /**
     * Database instance with all queries.
     */
    private val database: Database = createDatabase(driver)

    /**
     * Coroutine scope for async operations.
     * Uses Dispatchers.Default with a SupervisorJob for fault tolerance.
     */
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * The environment service that provides access to all repositories,
     * orchestrators, and agent APIs.
     */
    val environmentService: EnvironmentService = EnvironmentService.create(
        database = database,
        scope = scope,
        json = json,
        logger = logger,
    )

    /**
     * Convenience accessor for the event relay service.
     * This is commonly needed by CLI commands that watch or query events.
     */
    val eventRelayService: EventRelayService
        get() = environmentService.eventRelayService

    /**
     * Thread view service for querying thread state.
     * Provides high-level views of thread data for CLI display.
     */
    val threadViewService: ThreadViewService = DefaultThreadViewService(
        messageRepository = environmentService.messageRepository
    )

    /**
     * Ticket view service for querying ticket state.
     * Provides high-level views of ticket data for CLI display.
     */
    val ticketViewService: TicketViewService = DefaultTicketViewService(
        ticketRepository = environmentService.ticketRepository
    )

    /**
     * Subscribe to all events for an agent.
     * Delegates to EnvironmentService for centralized event subscription.
     *
     * @param agentId The agent subscribing to the events
     * @param handler Handler to process events
     * @return List of subscriptions (one per event type)
     */
    fun subscribeToAll(
        agentId: String,
        handler: link.socket.ampere.agents.events.api.EventHandler<link.socket.ampere.agents.events.Event, link.socket.ampere.agents.events.subscription.Subscription>,
    ): List<link.socket.ampere.agents.events.subscription.Subscription> {
        return environmentService.subscribeToAll(agentId, handler)
    }

    /**
     * Start all orchestrator services.
     *
     * This must be called before using the context to ensure event routing
     * and other background operations are active.
     */
    fun start() {
        environmentService.start()
    }

    /**
     * Close all resources and stop background operations.
     *
     * This should be called when the CLI is shutting down to ensure
     * clean resource cleanup.
     */
    fun close() {
        scope.cancel()
        driver.close()
    }

    companion object {
        /**
         * Default database path in the user's home directory.
         * Falls back to current directory if user.home is not available.
         */
        private fun defaultDatabasePath(): String {
            val homeDir = System.getProperty("user.home") ?: System.getProperty("user.dir") ?: "."
            return File(homeDir, ".ampere/ampere.db").absolutePath
        }

        /**
         * Create a JDBC SQLite driver with proper configuration.
         */
        private fun createDriver(databasePath: String): JdbcSqliteDriver {
            // Ensure parent directory exists
            val dbFile = File(databasePath)
            dbFile.parentFile?.mkdirs()

            return JdbcSqliteDriver("jdbc:sqlite:$databasePath")
        }

        /**
         * Create the database instance and initialize the schema if needed.
         */
        private fun createDatabase(driver: JdbcSqliteDriver): Database {
            // Check if the main table exists to determine if we need to create the schema
            val schemaExists = try {
                driver.executeQuery<Boolean>(
                    identifier = null,
                    sql = "SELECT name FROM sqlite_master WHERE type='table' AND name='EventStore'",
                    mapper = { cursor ->
                        app.cash.sqldelight.db.QueryResult.Value(cursor.next().value)
                    },
                    parameters = 0,
                    binders = null
                ).value
            } catch (e: Exception) {
                false
            }

            if (!schemaExists) {
                // Schema doesn't exist, create it
                Database.Schema.create(driver)
            }

            return Database(driver)
        }
    }
}
