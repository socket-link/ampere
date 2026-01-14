package link.socket.ampere

import com.github.ajalt.clikt.core.subcommands
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.definition.AgentFactory
import link.socket.ampere.agents.definition.AgentType
import link.socket.ampere.agents.definition.CodeAgent
import link.socket.ampere.agents.definition.ProductAgent
import link.socket.ampere.agents.definition.QualityAgent
import link.socket.ampere.config.AmpereConfig
import link.socket.ampere.config.ConfigConverter
import link.socket.ampere.config.ConfigParser
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.data.RepositoryFactory
import link.socket.ampere.data.createJvmDriver
import link.socket.ampere.domain.koog.KoogAgentFactory
import link.socket.ampere.integrations.git.RepositoryDetector
import link.socket.ampere.integrations.issues.github.GitHubCliProvider
import link.socket.ampere.util.LoggingConfiguration
import link.socket.ampere.util.configureLogging

/**
 * Main entry point for the Ampere CLI.
 *
 * This function:
 * 1. Loads configuration from YAML file (if present)
 * 2. Configures logging based on environment variables
 * 3. Creates an AmpereContext to initialize all dependencies
 * 4. Starts the environment orchestrator
 * 5. Runs the CLI command (defaults to interactive mode if no args)
 * 6. Cleans up resources on exit
 */
fun main(args: Array<String>) {
    // Load configuration from file (--config flag or default locations)
    val config = loadConfiguration(args)

    // Configure logging from environment variable (AMPERE_LOG_LEVEL)
    // CLI options (--verbose, --log-level, etc.) can override this per-command if needed
    val loggingConfig = LoggingConfiguration.fromEnvironment()
    configureLogging(loggingConfig)

    // Log config info if loaded
    if (config != null) {
        println("Loaded configuration:")
        println("  AI Provider: ${config.ai.provider} (${config.ai.model})")
        println("  Team: ${config.team.joinToString { it.role }}")
        config.goal?.let { println("  Goal: $it") }
        println()
    }

    val databaseDriver = createJvmDriver()
    val ioScope = CoroutineScope(Dispatchers.IO)
    val jsonConfig = DEFAULT_JSON

    val koogAgentFactory = KoogAgentFactory()
    val repositoryFactory = RepositoryFactory(ioScope, databaseDriver, jsonConfig)

    // Create EventLogger based on logging configuration
    val eventLogger = loggingConfig.createEventLogger()

    val context = AmpereContext(logger = eventLogger)
    val environmentService = context.environmentService

    // Initialize GitHub integration
    val issueTrackerProvider = GitHubCliProvider()
    val repository = runBlocking { RepositoryDetector.detectRepository() }

    val agentFactory = AgentFactory(
        scope = ioScope,
        ticketOrchestrator = environmentService.ticketOrchestrator,
        memoryServiceFactory = { agentId -> context.createMemoryService(agentId) },
        issueTrackerProvider = issueTrackerProvider,
        repository = repository,
    )

    val codeAgent = agentFactory.create<CodeAgent>(AgentType.CODE)
    val productAgent = agentFactory.create<ProductAgent>(AgentType.PRODUCT)
    val qualityAgent = agentFactory.create<QualityAgent>(AgentType.QUALITY)

    codeAgent.initialize(ioScope)
    productAgent.initialize(ioScope)
    qualityAgent.initialize(ioScope)

    // Initialize autonomous work loop for CodeAgent
    context.createAutonomousWorkLoop(codeAgent)

    try {
        // Start all orchestrator services
        context.start()

        // Filter out --config/-c flag (already processed)
        val filteredArgs = filterConfigArgs(args)

        // If no arguments provided, or if --goal is first arg, launch start mode
        val effectiveArgs = when {
            filteredArgs.isEmpty() -> arrayOf("start")
            filteredArgs.firstOrNull()?.startsWith("--goal") == true ||
            filteredArgs.firstOrNull()?.startsWith("-g") == true -> arrayOf("start") + filteredArgs
            else -> filteredArgs
        }

        // Run the CLI with injected dependencies
        AmpereCommand()
            .subcommands(
                StartCommand { context },
                RunCommand { context },
                HelpCommand(),
                InteractiveCommand(context),
                WatchCommand(context.eventRelayService),
                DashboardCommand(context.eventRelayService),
                ThreadCommand(context.threadViewService),
                StatusCommand(context.threadViewService, context.ticketViewService),
                OutcomesCommand(context.outcomeMemoryRepository),
                KnowledgeCommand(context.knowledgeRepository),
                TraceCommand(context.eventRepository),
                IssuesCommand(),
                RespondCommand(),
                WorkCommand { context },
                TestCommand(),
            )
            .main(effectiveArgs)
    } finally {
        // Clean up resources
        context.close()
    }
}

/**
 * Load configuration from a YAML file.
 *
 * Configuration is loaded from (in order of precedence):
 * 1. --config <path> argument
 * 2. ampere.yaml in current directory
 * 3. ampere.yml in current directory
 * 4. .ampere/config.yaml in current directory
 *
 * @return Parsed configuration or null if no config file found
 */
private fun loadConfiguration(args: Array<String>): AmpereConfig? {
    // Check for --config flag
    val configIndex = args.indexOfFirst { it == "--config" || it == "-c" }
    val configPath = if (configIndex >= 0 && configIndex < args.size - 1) {
        args[configIndex + 1]
    } else null

    // Find config file
    val configFile = when {
        configPath != null -> {
            val file = File(configPath)
            if (!file.exists()) {
                System.err.println("Error: Config file not found: $configPath")
                return null
            }
            file
        }
        else -> findDefaultConfigFile()
    }

    if (configFile == null) {
        return null
    }

    return try {
        val config = ConfigParser.parse(configFile)

        // Validate the configuration
        val errors = ConfigParser.validate(configFile)
        if (errors.isNotEmpty()) {
            System.err.println("Configuration warnings:")
            errors.forEach { System.err.println("  - $it") }
        }

        config
    } catch (e: Exception) {
        System.err.println("Error loading configuration from ${configFile.path}: ${e.message}")
        null
    }
}

/**
 * Find a default configuration file in standard locations.
 */
private fun findDefaultConfigFile(): File? {
    val candidates = listOf(
        "ampere.yaml",
        "ampere.yml",
        ".ampere/config.yaml",
        ".ampere/config.yml",
    )

    for (candidate in candidates) {
        val file = File(candidate)
        if (file.exists() && file.canRead()) {
            return file
        }
    }

    return null
}

/**
 * Filter out --config/-c flag and its argument from args.
 * These are processed separately before Clikt takes over.
 */
private fun filterConfigArgs(args: Array<String>): Array<String> {
    val result = mutableListOf<String>()
    var skipNext = false

    for (arg in args) {
        when {
            skipNext -> skipNext = false
            arg == "--config" || arg == "-c" -> skipNext = true
            else -> result.add(arg)
        }
    }

    return result.toTypedArray()
}
