package link.socket.ampere

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.definition.SparkBasedAgent
import link.socket.ampere.agents.definition.code.CodeState
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepository
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepository
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.execution.AutonomousWorkLoop
import link.socket.ampere.agents.execution.WorkLoopConfig
import link.socket.ampere.agents.execution.issue.CodeIssueWorkflow
import link.socket.ampere.integrations.issues.IssueTrackerProvider
import link.socket.ampere.agents.environment.EnvironmentService
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
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
import link.socket.ampere.api.Ampere
import link.socket.ampere.api.AmpereInstance
import link.socket.ampere.api.fromEnvironment
import link.socket.ampere.config.AmpereConfig
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.data.DatabaseSchemaManager
import link.socket.ampere.data.DatabaseSchemaManager.SchemaState
import link.socket.ampere.db.Database
import link.socket.ampere.db.fts.FtsSchema
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.arc.CompletionManifestSink
import link.socket.ampere.domain.llm.LlmProvider

/**
 * Context that provides dependencies for CLI commands.
 *
 * AmpereContext handles all the initialization and wiring of the environment,
 * so commands just receive the services they need without worrying about
 * how they're created.
 *
 * This class:
 * - Initializes the database connection
 * - Creates or migrates the database schema
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
    /**
     * The directory every agent this CLI builds is confined to (AMPR-300). Required and
     * explicit: `Main` resolves it from `--workspace`, then the `workspace:` key in
     * `ampere.yaml`, then the directory the CLI was started in — and says which. There is
     * no shared default any more; the old `~/.ampere/Workspaces/Ampere` let every agent in
     * every run write into one directory.
     */
    val workspace: ExecutionWorkspace,
    /**
     * Directory the markdown file receptor watches for `.md` changes, or null to disable
     * watching. Deliberately separate from [workspace]: the receptor registers every
     * subdirectory recursively, which is fine for a notes folder and not for a repository.
     */
    private val monitoredDirectory: String? = defaultMonitoredDirectory(),
    /** User configuration loaded from YAML file, if present */
    val userConfig: AmpereConfig? = null,
    /** AI configuration derived from user config or default */
    val aiConfiguration: AIConfiguration? = null,
    /** Custom LLM provider for bypassing built-in providers, useful for testing and custom integrations */
    val llmProvider: LlmProvider? = null,
) {
    /**
     * Database driver for SQLite operations.
     */
    private val driver: JdbcSqliteDriver = createDriver(databasePath)

    /**
     * Database instance with all queries.
     *
     * Exposed (not private) so callers constructing an [link.socket.ampere.agents.definition.AgentFactory]
     * or [link.socket.ampere.agents.definition.SparkAgentFactory] can wire a persisted
     * [link.socket.ampere.plug.permission.UserGrantStore] into agents built from this
     * context (AMPR-348).
     */
    val database: Database = createDatabase(logger, driver)

    /**
     * Coroutine scope for async operations.
     * Uses Dispatchers.Default with a SupervisorJob for fault tolerance.
     *
     * This scope is shared with AgentFactory and all agents to ensure
     * consistent event handling across the application.
     */
    val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * The environment service that provides access to all repositories,
     * orchestrators, and agent APIs.
     */
    val environmentService: EnvironmentService = EnvironmentService.create(
        database = database,
        scope = scope,
        json = json,
        logger = logger,
        driver = driver,
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
     * Event repository for querying persisted events.
     * Used by trace command to show event context.
     */
    val eventRepository: link.socket.ampere.agents.events.EventRepository
        get() = environmentService.eventRepository

    /**
     * Knowledge repository for persistent agent memory.
     * Shared across all agents to enable learning from each other's experiences.
     * Exposed for CLI commands that query agent knowledge.
     */
    val knowledgeRepository: KnowledgeRepository by lazy {
        KnowledgeRepositoryImpl(database, driver)
    }

    /**
     * Public API surface backed by this context's infrastructure.
     *
     * CLI commands use this to access the 7 service interfaces (AgentService,
     * TicketService, ThreadService, EventService, OutcomeService, KnowledgeService,
     * StatusService) instead of accessing internal repositories directly.
     */
    val ampereInstance: AmpereInstance by lazy {
        Ampere.fromEnvironment(
            environmentService = environmentService,
            knowledgeRepository = knowledgeRepository,
            workspace = workspace.baseDirectory,
            database = database,
        )
    }

    /**
     * Where every Arc run this CLI starts writes its completion manifest (AMPR-359): this
     * context's event store, under the run's id, so what a cancelled or failed run did and did not
     * do can be read back after the process is gone. Built on first use.
     */
    val completionManifestSink: CompletionManifestSink by lazy {
        CompletionManifestSink(
            eventApi = environmentService.createEventApi(CompletionManifestSink.DEFAULT_AGENT_ID),
            logger = logger,
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
     * The code agent instance for autonomous work. Set when
     * [createAutonomousWorkLoop] is called.
     */
    private var _codeAgent: SparkBasedAgent<CodeState>? = null

    /**
     * Access the code agent instance.
     * Throws an error if not initialized via [createAutonomousWorkLoop].
     */
    val codeAgent: SparkBasedAgent<CodeState>
        get() = _codeAgent ?: error("codeAgent not initialized. Call createAutonomousWorkLoop() first.")

    /**
     * The issue → task → PR workflow paired with the code agent. Owns the
     * claim/work-on/update lifecycle that used to live on the legacy
     * `CodeAgent`. Set when [createAutonomousWorkLoop] is called.
     */
    private var _codeIssueWorkflow: CodeIssueWorkflow? = null

    val codeIssueWorkflow: CodeIssueWorkflow
        get() = _codeIssueWorkflow ?: error(
            "codeIssueWorkflow not initialized. Call createAutonomousWorkLoop() first.",
        )

    /**
     * Autonomous work loop for the code agent. Manages continuous polling
     * and processing of GitHub issues.
     */
    private var _autonomousWorkLoop: AutonomousWorkLoop<CodeState>? = null

    /**
     * Access the autonomous work loop.
     * Throws an error if not initialized via [createAutonomousWorkLoop].
     */
    val autonomousWorkLoop: AutonomousWorkLoop<CodeState>
        get() = _autonomousWorkLoop ?: error("Autonomous work loop not initialized. Call createAutonomousWorkLoop() first.")

    /**
     * Workspace event mapper that transforms FileSystemEvents into ProductEvents.
     * Null if workspace monitoring is disabled.
     */
    private val workspaceEventMapper: WorkspaceEventMapper? =
        monitoredDirectory?.let {
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
        monitoredDirectory?.let { path ->
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

        // Said out loud on every start (AMPR-300): this is the only directory agents may write to.
        logger.logInfo("Agent workspace pinned to: ${workspace.baseDirectory}")

        // Start the markdown monitoring system if enabled
        workspaceEventMapper?.startWithEventBus(environmentService.eventBus)
        fileSystemReceptor?.start()

        if (fileSystemReceptor != null) {
            logger.logInfo("Workspace receptor system started, monitoring: ${monitoredDirectory ?: "disabled"}")
        }
    }

    /**
     * Create and initialize the autonomous work loop for a code agent.
     *
     * This must be called before attempting to start autonomous work.
     * The work loop is connected to the shared event bus so that work
     * events are visible in the dashboard.
     *
     * @param codeAgent The agent instance that will process issues
     * @param issueTrackerProvider Source of issues (typically GitHub)
     * @param repository Repository identifier the provider scopes its queries to
     * @param config Optional configuration for work loop behavior
     * @return The created AutonomousWorkLoop instance
     */
    fun createAutonomousWorkLoop(
        codeAgent: SparkBasedAgent<CodeState>,
        issueTrackerProvider: IssueTrackerProvider,
        repository: String,
        config: WorkLoopConfig = WorkLoopConfig(),
    ): AutonomousWorkLoop<CodeState> {
        val workflow = CodeIssueWorkflow(
            issueTrackerProvider = issueTrackerProvider,
            repository = repository,
            agentId = codeAgent.id,
        )
        _codeAgent = codeAgent
        _codeIssueWorkflow = workflow
        _autonomousWorkLoop = AutonomousWorkLoop(
            agent = codeAgent,
            workflow = workflow,
            config = config,
            scope = scope,
            eventApiFactory = environmentService::createEventApi,
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
     * Cancels the loop's job outright, including any in-progress issue — it does not let the
     * current issue complete first. The issue's terminal status write (e.g. `BLOCKED`) runs on
     * `Dispatchers.IO` and throws on entry because the Job is already cancelled, so the issue is
     * left labelled `IN_PROGRESS` indefinitely. Intake skips issues that already carry a workflow
     * label, so it is never picked up again (AMPR-343).
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
        // Stop autonomous work if running
        stopAutonomousWork()

        // Stop the workspace monitoring system if enabled
        fileSystemReceptor?.stop()

        // Wait for in-flight coroutines (e.g. WorkspaceStateStore's event replay)
        // to actually stop before closing the driver out from under them —
        // scope.cancel() alone only signals cancellation, it doesn't wait.
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        driver.close()
    }

    companion object {
        /**
         * Default directory the markdown receptor watches. This is a watch-only location for
         * the notes receptor; agents never write here (AMPR-300 removed it as a write target).
         */
        private fun defaultMonitoredDirectory(): String {
            val homeDir = System.getProperty("user.home") ?: System.getProperty("user.dir") ?: "."
            return File(homeDir, ".ampere/Workspaces/Ampere").absolutePath
        }

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
         * Create the database instance, creating or migrating the schema to the current version.
         *
         * @throws Exception if the schema can't be brought current. There's no partial fallback:
         * a CLI running against a half-built schema only fails later, on some unrelated query.
         */
        private fun createDatabase(
            logger: EventLogger,
            driver: JdbcSqliteDriver,
        ): Database {
            val state = DatabaseSchemaManager.ensure(driver).getOrElse { cause ->
                logger.logError("Error bringing the database schema up to date: ${cause.message}", cause)
                throw cause
            }
            when (state) {
                SchemaState.Created -> logger.logInfo("Database schema created")
                is SchemaState.Migrated ->
                    logger.logInfo("Database schema migrated from v${state.from} to v${state.to}")
                SchemaState.Current -> Unit
            }

            // FTS5 virtual tables are a separate, guarded step (see FtsSchema): a SQLite build
            // without the fts5 module degrades search instead of taking the whole schema down.
            // IF NOT EXISTS throughout makes this safe to call unconditionally on every open.
            FtsSchema.install(driver)

            return Database(driver)
        }
    }
}
