package link.socket.ampere

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.definition.CodeAgent
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepository
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepository
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.memory.AgentMemoryService
import link.socket.ampere.agents.execution.AutonomousWorkLoop
import link.socket.ampere.agents.execution.WorkLoopConfig
import link.socket.ampere.agents.environment.EnvironmentService
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.environment.workspace.defaultWorkspace
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.messages.DefaultThreadViewService
import link.socket.ampere.agents.events.messages.ThreadViewService
import link.socket.ampere.agents.events.relay.EventRelayService
import link.socket.ampere.agents.events.subscription.Subscription
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
import link.socket.ampere.data.DatabaseMaintenanceConfig
import link.socket.ampere.data.DatabaseMaintenanceService
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
    /** Path to the SQLite database file, defaults to "ampere.db" in the user's home directory*/
    databasePath: String = defaultDatabasePath(),
    /** JSON configuration for serialization, defaults to the standard Ampere JSON configuration */
    json: Json = DEFAULT_JSON,
    /** Event logger for system operations, defaults to console logging */
    private val logger: EventLogger = ConsoleEventLogger(),
    /** The workspace to monitor for file changes, can be set to null to disable workspace monitoring */
    private val workspace: ExecutionWorkspace? = defaultWorkspace(),
) {
    /**
     * Database driver for SQLite operations.
     */
    private val driver: JdbcSqliteDriver = createDriver(databasePath)

    /**
     * Database instance with all queries.
     */
    private val database: Database = createDatabase(logger, driver)

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
     * Outcome memory repository for querying execution outcomes.
     * Provides access to the environment's accumulated experience.
     */
    val outcomeMemoryRepository: OutcomeMemoryRepository
        get() = environmentService.outcomeMemoryRepository

    /**
     * Database maintenance service for cleanup operations.
     * Handles retention policies and disk space reclamation.
     */
    val databaseMaintenanceService: DatabaseMaintenanceService by lazy {
        DatabaseMaintenanceService(
            eventRepository = environmentService.eventRepository,
            messageRepository = environmentService.messageRepository,
            outcomeMemoryRepository = environmentService.outcomeMemoryRepository,
            config = DatabaseMaintenanceConfig(
                eventRetentionDays = 30,
                messageRetentionDays = 90,
                outcomeRetentionDays = 180,
                maxEventsToKeep = 50000,
                maxOutcomesToKeep = 10000,
                runVacuum = false, // Disabled by default due to performance cost
            ),
            scope = scope,
            logger = logger,
        )
    }

    /**
     * Knowledge repository for persistent agent memory.
     * Shared across all agents to enable learning from each other's experiences.
     */
    private val knowledgeRepository: KnowledgeRepository by lazy {
        KnowledgeRepositoryImpl(database)
    }

    /**
     * Create an agent memory service for the specified agent.
     *
     * This factory method creates per-agent memory service instances that share
     * the same underlying repository. This allows agents to learn from each other's
     * experiences while maintaining agent-specific filtering and event tracking.
     *
     * @param agentId The ID of the agent that will use this memory service
     * @return A configured AgentMemoryService instance
     */
    fun createMemoryService(agentId: AgentId): AgentMemoryService {
        return AgentMemoryService(
            agentId = agentId,
            knowledgeRepository = knowledgeRepository,
            eventBus = environmentService.eventBus
        )
    }

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
     * CodeAgent instance for autonomous work.
     * Set when createAutonomousWorkLoop() is called.
     */
    private var _codeAgent: CodeAgent? = null

    /**
     * Access the CodeAgent instance.
     * Throws an error if not initialized via createAutonomousWorkLoop().
     */
    val codeAgent: CodeAgent
        get() = _codeAgent ?: error("CodeAgent not initialized. Call createAutonomousWorkLoop() first.")

    /**
     * Autonomous work loop for CodeAgent.
     * Manages continuous polling and processing of GitHub issues.
     */
    private var _autonomousWorkLoop: AutonomousWorkLoop? = null

    /**
     * Access the autonomous work loop.
     * Throws an error if not initialized via createAutonomousWorkLoop().
     */
    val autonomousWorkLoop: AutonomousWorkLoop
        get() = _autonomousWorkLoop ?: error("Autonomous work loop not initialized. Call createAutonomousWorkLoop() first.")

    /**
     * Workspace event mapper that transforms FileSystemEvents into ProductEvents.
     * Null if workspace monitoring is disabled.
     */
    private val workspaceEventMapper: WorkspaceEventMapper? =
        workspace?.baseDirectory?.let {
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
    private val fileSystemReceptor: FileSystemReceptor? =
        workspace?.baseDirectory?.let { path ->
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
        handler: EventHandler<Event, Subscription>,
    ): List<Subscription> =
        environmentService.subscribeToAll(agentId, handler)

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
            logger.logInfo("Workspace receptor system started, monitoring: ${workspace?.baseDirectory ?: "disabled"}")
        }

        // Run database cleanup in background on startup
        logger.logInfo("Starting database maintenance...")
        databaseMaintenanceService.runCleanupAsync()
    }

    /**
     * Create and initialize the autonomous work loop for a CodeAgent.
     *
     * This must be called before attempting to start autonomous work.
     *
     * @param codeAgent The CodeAgent instance that will process issues
     * @param config Optional configuration for work loop behavior
     * @return The created AutonomousWorkLoop instance
     */
    fun createAutonomousWorkLoop(
        codeAgent: CodeAgent,
        config: WorkLoopConfig = WorkLoopConfig(),
    ): AutonomousWorkLoop {
        _codeAgent = codeAgent
        _autonomousWorkLoop = AutonomousWorkLoop(
            agent = codeAgent,
            config = config,
            scope = scope,
        )
        return autonomousWorkLoop
    }

    /**
     * Start the autonomous work loop.
     *
     * The loop will begin polling for available issues and processing them
     * according to the configured strategy.
     *
     * @throws IllegalStateException if the work loop has not been initialized
     */
    fun startAutonomousWork() {
        autonomousWorkLoop.start()
        logger.logInfo("Autonomous work loop started")
    }

    /**
     * Stop the autonomous work loop.
     *
     * Gracefully stops the polling loop. Any in-progress issue will complete,
     * but no new issues will be claimed.
     */
    fun stopAutonomousWork() {
        _autonomousWorkLoop?.stop()
        logger.logInfo("Autonomous work loop stopped")
    }

    /**
     * Close all resources and stop background operations.
     *
     * This should be called when the CLI is shutting down to ensure
     * clean resource cleanup.
     */
    fun close() {
        logger.logInfo("Shutting down Ampere context...")

        // Stop autonomous work if running
        stopAutonomousWork()

        // Stop the workspace monitoring system if enabled
        fileSystemReceptor?.stop()

        // Cancel the coroutine scope (this will cancel the cleanup job if still running)
        scope.cancel()

        // Close the database driver
        logger.logInfo("Closing database connection...")
        driver.close()

        logger.logInfo("Ampere context shutdown complete")
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

            val driver = JdbcSqliteDriver("jdbc:sqlite:$databasePath")

            // Configure SQLite for better concurrency and performance
            driver.execute(null, "PRAGMA journal_mode=WAL", 0) // Enable Write-Ahead Logging
            driver.execute(null, "PRAGMA synchronous=NORMAL", 0) // Balanced durability/performance
            driver.execute(null, "PRAGMA busy_timeout=5000", 0) // Wait up to 5s on locks
            driver.execute(null, "PRAGMA cache_size=-64000", 0) // 64MB cache

            return driver
        }

        /**
         * Create the database instance and initialize the schema if needed.
         */
        private fun createDatabase(
            logger: EventLogger,
            driver: JdbcSqliteDriver,
        ): Database {
            // Check if all required tables exist
            val eventStoreExists = tableExists(driver, "EventStore")
            val knowledgeStoreExists = tableExists(driver, "KnowledgeStore")

            if (!eventStoreExists) {
                // Full schema doesn't exist, create it
                logger.logInfo("Database schema doesn't exist, creating all tables...")
                try {
                    Database.Schema.create(driver)
                    logger.logInfo("Database schema created successfully")
                } catch (e: Exception) {
                    logger.logError("Error creating database schema: ${e.message}")
                }
            } else if (!knowledgeStoreExists) {
                // EventStore exists but KnowledgeStore doesn't - need to add new tables
                logger.logInfo("Adding KnowledgeStore tables to existing database...")
                try {
                    createKnowledgeStoreTables(driver)
                    logger.logInfo("KnowledgeStore tables created successfully")
                } catch (e: Exception) {
                    logger.logError("Error creating KnowledgeStore tables: ${e.message}")
                }
            }

            return Database(driver)
        }

        /**
         * Manually create KnowledgeStore tables for existing databases.
         */
        private fun createKnowledgeStoreTables(driver: JdbcSqliteDriver) {
            // Create KnowledgeStore table
            driver.execute(
                null,
                """
                CREATE TABLE IF NOT EXISTS KnowledgeStore (
                    id TEXT PRIMARY KEY NOT NULL,
                    knowledge_type TEXT NOT NULL,
                    approach TEXT NOT NULL,
                    learnings TEXT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    idea_id TEXT,
                    outcome_id TEXT,
                    perception_id TEXT,
                    plan_id TEXT,
                    task_id TEXT,
                    task_type TEXT,
                    complexity_level TEXT
                )
                """.trimIndent(),
                0
            )

            // Create indexes
            driver.execute(null, "CREATE INDEX IF NOT EXISTS idx_knowledge_type ON KnowledgeStore(knowledge_type)", 0)
            driver.execute(null, "CREATE INDEX IF NOT EXISTS idx_knowledge_timestamp ON KnowledgeStore(timestamp DESC)", 0)
            driver.execute(null, "CREATE INDEX IF NOT EXISTS idx_knowledge_task_type ON KnowledgeStore(task_type)", 0)
            driver.execute(null, "CREATE INDEX IF NOT EXISTS idx_knowledge_complexity ON KnowledgeStore(complexity_level)", 0)

            // Create KnowledgeTag table
            driver.execute(
                null,
                """
                CREATE TABLE IF NOT EXISTS KnowledgeTag (
                    knowledge_id TEXT NOT NULL,
                    tag TEXT NOT NULL,
                    PRIMARY KEY (knowledge_id, tag),
                    FOREIGN KEY (knowledge_id) REFERENCES KnowledgeStore(id) ON DELETE CASCADE
                )
                """.trimIndent(),
                0
            )

            // Create tag indexes
            driver.execute(null, "CREATE INDEX IF NOT EXISTS idx_knowledge_tag_tag ON KnowledgeTag(tag)", 0)
            driver.execute(null, "CREATE INDEX IF NOT EXISTS idx_knowledge_tag_knowledge_id ON KnowledgeTag(knowledge_id)", 0)

            // Create FTS table
            driver.execute(
                null,
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS KnowledgeFts USING fts5(
                    knowledge_id UNINDEXED,
                    approach,
                    learnings,
                    content=KnowledgeStore,
                    content_rowid=rowid
                )
                """.trimIndent(),
                0
            )

            // Create triggers
            driver.execute(
                null,
                """
                CREATE TRIGGER IF NOT EXISTS knowledge_fts_insert AFTER INSERT ON KnowledgeStore BEGIN
                    INSERT INTO KnowledgeFts(rowid, knowledge_id, approach, learnings)
                    VALUES (new.rowid, new.id, new.approach, new.learnings);
                END
                """.trimIndent(),
                0
            )

            driver.execute(
                null,
                """
                CREATE TRIGGER IF NOT EXISTS knowledge_fts_delete AFTER DELETE ON KnowledgeStore BEGIN
                    DELETE FROM KnowledgeFts WHERE rowid = old.rowid;
                END
                """.trimIndent(),
                0
            )

            driver.execute(
                null,
                """
                CREATE TRIGGER IF NOT EXISTS knowledge_fts_update AFTER UPDATE ON KnowledgeStore BEGIN
                    DELETE FROM KnowledgeFts WHERE rowid = old.rowid;
                    INSERT INTO KnowledgeFts(rowid, knowledge_id, approach, learnings)
                    VALUES (new.rowid, new.id, new.approach, new.learnings);
                END
                """.trimIndent(),
                0
            )
        }

        /**
         * Check if a table exists in the database.
         */
        private fun tableExists(driver: JdbcSqliteDriver, tableName: String): Boolean {
            return try {
                driver.executeQuery(
                    identifier = null,
                    sql = "SELECT name FROM sqlite_master WHERE type='table' AND name='$tableName'",
                    mapper = { cursor ->
                        app.cash.sqldelight.db.QueryResult.Value(cursor.next().value)
                    },
                    parameters = 0,
                    binders = null
                ).value
            } catch (e: Exception) {
                false
            }
        }
    }
}
