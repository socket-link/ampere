package link.socket.ampere

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.environment.EnvironmentService
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.concept.outcome.OutcomeMemoryRepository
import link.socket.ampere.agents.events.messages.DefaultThreadViewService
import link.socket.ampere.agents.events.messages.ThreadViewService
import link.socket.ampere.agents.events.relay.EventRelayService
import link.socket.ampere.agents.events.tickets.DefaultTicketViewService
import link.socket.ampere.agents.events.tickets.TicketViewService
import link.socket.ampere.agents.events.utils.ConsoleEventLogger
import link.socket.ampere.agents.events.utils.EventLogger
import link.socket.ampere.agents.receptors.FileSystemReceptor
import link.socket.ampere.agents.receptors.WorkspaceEventMapper
import link.socket.ampere.agents.service.AgentActionService
import link.socket.ampere.agents.service.MessageActionService
import link.socket.ampere.agents.service.TicketActionService
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

    /**
     * Path to the workspace directory to monitor for file changes.
     * Defaults to "~/.ampere/Workspaces/Ampere".
     * Set to null to disable workspace monitoring.
     */
    workspacePath: String? = defaultWorkspacePath(),
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
     * Event logger for system operations.
     */
    private val eventLogger: EventLogger = logger

    /**
     * Workspace path to monitor, or null if monitoring is disabled.
     */
    private val workspace: String? = workspacePath

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
     * Outcome memory repository for querying execution outcomes.
     * Provides access to the environment's accumulated experience.
     */
    val outcomeMemoryRepository: OutcomeMemoryRepository
        get() = environmentService.outcomeMemoryRepository

    /**
     * Ticket action service for creating and managing tickets from CLI.
     */
    val ticketActionService: TicketActionService by lazy {
        val eventApi = environmentService.createEventApi("human-cli")
        TicketActionService(
            ticketRepository = environmentService.ticketRepository,
            eventApi = eventApi,
        )
    }

    /**
     * Message action service for posting messages and creating threads from CLI.
     */
    val messageActionService: MessageActionService by lazy {
        val eventApi = environmentService.createEventApi("human-cli")
        MessageActionService(
            messageRepository = environmentService.messageRepository,
            eventApi = eventApi,
        )
    }

    /**
     * Agent action service for triggering agent actions from CLI.
     */
    val agentActionService: AgentActionService by lazy {
        val eventApi = environmentService.createEventApi("human-cli")
        AgentActionService(
            eventApi = eventApi,
        )
    }

    /**
     * Workspace event mapper that transforms FileSystemEvents into ProductEvents.
     * Null if workspace monitoring is disabled.
     */
    private val workspaceEventMapper: WorkspaceEventMapper? = workspacePath?.let {
        val eventApi = environmentService.createEventApi("workspace-receptor-system")
        WorkspaceEventMapper(
            agentEventApi = eventApi,
            mapperId = "mapper-workspace",
            scope = scope
        )
    }

    /**
     * File system receptor that monitors the workspace directory for file changes.
     * Null if workspace monitoring is disabled.
     */
    private val fileSystemReceptor: FileSystemReceptor? = workspacePath?.let { path ->
        val eventApi = environmentService.createEventApi("workspace-receptor-system")
        FileSystemReceptor(
            workspacePath = path,
            agentEventApi = eventApi,
            receptorId = "receptor-filesystem",
            scope = scope,
            fileFilter = { file ->
                // Only monitor markdown files
                file.extension.lowercase() == "md"
            }
        )
    }

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
        handler: link.socket.ampere.agents.events.api.EventHandler<Event, link.socket.ampere.agents.events.subscription.Subscription>,
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

        // Start the workspace monitoring system if enabled
        workspaceEventMapper?.startWithEventBus(environmentService.eventBus)
        fileSystemReceptor?.start()

        if (fileSystemReceptor != null) {
            eventLogger.logInfo("Workspace receptor system started, monitoring: ${workspace ?: "disabled"}")
        }
    }

    /**
     * Close all resources and stop background operations.
     *
     * This should be called when the CLI is shutting down to ensure
     * clean resource cleanup.
     */
    fun close() {
        // Stop the workspace monitoring system if enabled
        fileSystemReceptor?.stop()

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
         * Default workspace path in the user's home directory.
         * Returns "~/.ampere/Workspaces/Ampere" expanded to absolute path.
         */
        private fun defaultWorkspacePath(): String {
            val homeDir = System.getProperty("user.home") ?: System.getProperty("user.dir") ?: "."
            return File(homeDir, ".ampere/Workspaces/Ampere").absolutePath
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
