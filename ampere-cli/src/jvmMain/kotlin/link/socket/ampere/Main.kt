package link.socket.ampere

import com.github.ajalt.clikt.core.subcommands
import kotlin.system.exitProcess
import java.io.File
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.definition.AgentFactory
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.definition.AgentType
import link.socket.ampere.agents.definition.SparkBasedAgent
import link.socket.ampere.agents.definition.code.CodeState
import link.socket.ampere.agents.definition.product.ProductState
import link.socket.ampere.agents.definition.qa.QualityState
import link.socket.ampere.config.AmpereConfig
import link.socket.ampere.config.ConfigConverter
import link.socket.ampere.config.ConfigParser
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.data.createJvmDriver
import link.socket.ampere.domain.koog.KoogAgentFactory
import link.socket.ampere.integrations.git.RepositoryDetector
import link.socket.ampere.integrations.issues.github.GitHubCliProvider
import link.socket.ampere.llm.BundledUpstreamLlmClient
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

    // Resolve the one directory agents may write to (AMPR-300): --workspace, then the
    // config file's `workspace:` key, then the directory the CLI was started in.
    val workspace = resolveWorkspace(args, config)

    // Configure logging from environment variable (AMPERE_LOG_LEVEL)
    // CLI options (--verbose, --log-level, etc.) can override this per-command if needed
    val loggingConfig = LoggingConfiguration.fromEnvironment()
    configureLogging(loggingConfig)

    // Convert config to AIConfiguration if present
    val aiConfiguration = config?.let {
        try {
            ConfigConverter.toAIConfiguration(it.ai)
        } catch (e: Exception) {
            System.err.println("Warning: Could not convert AI config: ${e.message}")
            null
        }
    }

    // Only log config info if running a non-TUI subcommand
    val isSubcommand = args.firstOrNull()?.let { first ->
        !first.startsWith("-") && first != "ampere"
    } == true

    if (config != null && isSubcommand) {
        println("Loaded configuration:")
        println("  AI Provider: ${config.ai.provider} (${config.ai.model})")
        println("  Team: ${config.team.joinToString { it.role }}")
        config.goal?.let { println("  Goal: $it") }
        println("  Workspace: ${workspace.baseDirectory}")
        println()
    }

    val databaseDriver = createJvmDriver()
    val jsonConfig = DEFAULT_JSON

    val koogAgentFactory = KoogAgentFactory()

    // Create EventLogger based on logging configuration
    val eventLogger = loggingConfig.createEventLogger()

    val context = AmpereContext(
        logger = eventLogger,
        workspace = workspace,
        userConfig = config,
        aiConfiguration = aiConfiguration,
    )
    val environmentService = context.environmentService

    // Initialize GitHub integration
    val issueTrackerProvider = GitHubCliProvider()
    val repository = runBlocking { RepositoryDetector.detectRepository() }

    val agentFactory = AgentFactory(
        scope = context.scope,
        ticketOrchestrator = environmentService.ticketOrchestrator,
        workspace = context.workspace,
        knowledgeRepository = context.knowledgeRepository,
        createEventApi = environmentService::createEventApi,
        issueTrackerProvider = issueTrackerProvider,
        repository = repository,
        aiConfiguration = aiConfiguration,
        llmProvider = context.llmProvider,
        // The CLI is a first-party tool talking to providers with the
        // operator's own keys, so it opts into the direct-provider call.
        upstreamLlmClient = BundledUpstreamLlmClient,
        // Gates plug-tool dispatch against the persisted grant store instead
        // of the deny-all default (AMPR-348).
        database = context.database,
    )

    // Create agents based on team configuration (or defaults if no config)
    val teamRoles = config?.team?.map { it.role } ?: listOf("engineer", "product-manager", "qa-tester")

    val codeAgent: SparkBasedAgent<CodeState>? = if (teamRoles.any { it == "engineer" || it == "code" }) {
        agentFactory.create<SparkBasedAgent<CodeState>>(AgentType.CODE).also { it.initialize(context.scope) }
    } else null

    val productAgent: SparkBasedAgent<ProductState>? = if (teamRoles.any { it == "product-manager" || it == "product" }) {
        agentFactory.create<SparkBasedAgent<ProductState>>(AgentType.PRODUCT).also { it.initialize(context.scope) }
    } else null

    val qualityAgent: SparkBasedAgent<QualityState>? = if (teamRoles.any { it == "qa-tester" || it == "quality" }) {
        agentFactory.create<SparkBasedAgent<QualityState>>(AgentType.QUALITY).also { it.initialize(context.scope) }
    } else null

    // Initialize autonomous work loop for the code agent (if present in team
    // and a repository was detected for the issue tracker).
    if (codeAgent != null && repository != null) {
        context.createAutonomousWorkLoop(
            codeAgent = codeAgent,
            issueTrackerProvider = issueTrackerProvider,
            repository = repository,
        )
    }

    try {
        // Start all orchestrator services
        context.start()

        // Filter out --config/-c and --workspace/-w flags (already processed)
        val filteredArgs = filterPreParsedArgs(args)

        // Run the CLI
        val api = context.ampereInstance
        AmpereCommand { context }
            .subcommands(
                ThreadCommand(api.threads),
                StatusCommand(api),
                OutcomesCommand(api.outcomes),
                KnowledgeCommand(api.knowledge),
                TraceCommand(api.events),
                TaskCommand(api.tickets),
                IssuesCommand(),
                RespondCommand(),
                WorkCommand { context },
                TestCommand(),
                DemoCommand(),
            )
            .main(filteredArgs)
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
 * Resolve the directory every agent this process builds is confined to (AMPR-300).
 *
 * Precedence:
 * 1. `--workspace <dir>` / `-w <dir>` on the command line
 * 2. the `workspace:` key of the loaded configuration file
 * 3. the directory the CLI was started in
 *
 * The directory must already exist: a sandbox that has to be created on the fly is usually a
 * typo, and silently making one is how writes end up somewhere nobody meant.
 */
internal fun resolveWorkspace(args: Array<String>, config: AmpereConfig?): ExecutionWorkspace {
    val flagIndex = args.indexOfFirst { it == "--workspace" || it == "-w" }
    val fromFlag = if (flagIndex >= 0 && flagIndex < args.size - 1) args[flagIndex + 1] else null
    if (flagIndex >= 0 && fromFlag == null) {
        System.err.println("Error: --workspace requires a directory argument")
        exitProcess(2)
    }

    val requested = fromFlag ?: config?.workspace ?: System.getProperty("user.dir")
    val directory = File(requested).absoluteFile
    if (!directory.isDirectory) {
        System.err.println("Error: workspace is not a directory: ${directory.path}")
        exitProcess(2)
    }
    return ExecutionWorkspace(baseDirectory = directory.path)
}

/**
 * Filter out the flags that are processed before Clikt takes over — `--config`/`-c` and
 * `--workspace`/`-w` — together with their arguments.
 */
private fun filterPreParsedArgs(args: Array<String>): Array<String> {
    val preParsed = setOf("--config", "-c", "--workspace", "-w")
    val result = mutableListOf<String>()
    var skipNext = false

    for (arg in args) {
        when {
            skipNext -> skipNext = false
            arg in preParsed -> skipNext = true
            else -> result.add(arg)
        }
    }

    return result.toTypedArray()
}
