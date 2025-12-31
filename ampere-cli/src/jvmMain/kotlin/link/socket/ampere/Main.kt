package link.socket.ampere

import com.github.ajalt.clikt.core.subcommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import link.socket.ampere.agents.definition.AgentFactory
import link.socket.ampere.agents.definition.AgentType
import link.socket.ampere.agents.definition.CodeAgent
import link.socket.ampere.agents.definition.ProductAgent
import link.socket.ampere.agents.definition.QualityAgent
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.data.RepositoryFactory
import link.socket.ampere.data.createJvmDriver
import link.socket.ampere.domain.ai.configuration.AIConfigurationFactory
import link.socket.ampere.domain.koog.KoogAgentFactory
import link.socket.ampere.util.LoggingConfiguration
import link.socket.ampere.util.configureLogging

/**
 * Main entry point for the Ampere CLI.
 *
 * This function:
 * 1. Configures logging based on environment variables
 * 2. Creates an AmpereContext to initialize all dependencies
 * 3. Starts the environment orchestrator
 * 4. Runs the CLI command (defaults to interactive mode if no args)
 * 5. Cleans up resources on exit
 */
fun main(args: Array<String>) {
    // Configure logging from environment variable (AMPERE_LOG_LEVEL)
    // CLI options (--verbose, --log-level, etc.) can override this per-command if needed
    val loggingConfig = LoggingConfiguration.fromEnvironment()
    configureLogging(loggingConfig)

    val databaseDriver = createJvmDriver()
    val ioScope = CoroutineScope(Dispatchers.IO)
    val jsonConfig = DEFAULT_JSON

    val koogAgentFactory = KoogAgentFactory()
    val aiConfigurationFactory = AIConfigurationFactory()
    val repositoryFactory = RepositoryFactory(ioScope, databaseDriver, jsonConfig)

    // Create EventLogger based on logging configuration
    val eventLogger = loggingConfig.createEventLogger()

    val context = AmpereContext(logger = eventLogger)
    val environmentService = context.environmentService

    val agentFactory = AgentFactory(
        scope = ioScope,
        ticketOrchestrator = environmentService.ticketOrchestrator,
        aiConfigurationFactory = aiConfigurationFactory,
        memoryServiceFactory = { agentId -> context.createMemoryService(agentId) }
    )

    val codeAgent = agentFactory.create<CodeAgent>(AgentType.CODE)
    val productAgent = agentFactory.create<ProductAgent>(AgentType.PRODUCT)
    val qualityAgent = agentFactory.create<QualityAgent>(AgentType.QUALITY)

    codeAgent.initialize(ioScope)
    productAgent.initialize(ioScope)
    qualityAgent.initialize(ioScope)

    try {
        // Start all orchestrator services
        context.start()

        // If no arguments provided, launch start (dashboard) mode
        val effectiveArgs = if (args.isEmpty()) {
            arrayOf("start")
        } else {
            args
        }

        // Run the CLI with injected dependencies
        AmpereCommand()
            .subcommands(
                StartCommand(context.eventRelayService),
                HelpCommand(),
                InteractiveCommand(context),
                WatchCommand(context.eventRelayService),
                DashboardCommand(context.eventRelayService),
                ThreadCommand(context.threadViewService),
                StatusCommand(context.threadViewService, context.ticketViewService),
                OutcomesCommand(context.outcomeMemoryRepository),
                IssuesCommand(),
                RespondCommand(),
                TestCommand(),
                DemoCommand().subcommands(
                    JazzDemoCommand { context }
                ),
            )
            .main(effectiveArgs)
    } finally {
        // Clean up resources
        context.close()
    }
}
