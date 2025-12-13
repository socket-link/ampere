package link.socket.ampere

import com.github.ajalt.clikt.core.subcommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import link.socket.ampere.agents.definition.AgentFactory
import link.socket.ampere.agents.definition.AgentType
import link.socket.ampere.agents.definition.CodeWriterAgent
import link.socket.ampere.agents.definition.ProductManagerAgent
import link.socket.ampere.agents.definition.QualityAssuranceAgent
import link.socket.ampere.agents.environment.workspace.defaultWorkspace
import link.socket.ampere.agents.events.utils.ConsoleEventLogger
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.data.RepositoryFactory
import link.socket.ampere.data.createJvmDriver
import link.socket.ampere.domain.ai.configuration.AIConfigurationFactory
import link.socket.ampere.domain.koog.KoogAgentFactory
import link.socket.ampere.help.HelpFormatter
import link.socket.ampere.logging.QuietEventLogger

/**
 * Main entry point for the Ampere CLI.
 *
 * This function:
 * 1. Creates an AmpereContext to initialize all dependencies
 * 2. Starts the environment orchestrator
 * 3. Runs the CLI command (defaults to interactive mode if no args)
 * 4. Cleans up resources on exit
 */
fun main(args: Array<String>) {
    // Check for verbose flag
    val verbose = args.contains("--verbose") || args.contains("-v")

    // Use quiet logger by default, verbose logger if requested
    val logger = if (verbose) ConsoleEventLogger() else QuietEventLogger()

    val databaseDriver = createJvmDriver()
    val ioScope = CoroutineScope(Dispatchers.IO)
    val jsonConfig = DEFAULT_JSON

    val koogAgentFactory = KoogAgentFactory()
    val aiConfigurationFactory = AIConfigurationFactory()
    val repositoryFactory = RepositoryFactory(ioScope, databaseDriver, jsonConfig)

    val context = AmpereContext(logger = logger)
    val environmentService = context.environmentService

    val agentFactory = AgentFactory(
        scope = ioScope,
        ticketOrchestrator = environmentService.ticketOrchestrator,
        aiConfigurationFactory = aiConfigurationFactory,
    )

    val codeAgent = agentFactory.create<CodeWriterAgent>(AgentType.CODE_WRITER)
    val productAgent = agentFactory.create<ProductManagerAgent>(AgentType.PRODUCT_MANAGER)
    val qualityAgent = agentFactory.create<QualityAssuranceAgent>(AgentType.QUALITY_ASSURANCE)

    codeAgent.initialize(ioScope)
    productAgent.initialize(ioScope)
    qualityAgent.initialize(ioScope)

    try {
        // Start all orchestrator services
        context.start()

        // Show clean startup status (unless in verbose mode where logger handles it)
        if (!verbose) {
            val workspace = defaultWorkspace()
            val workspacePath = workspace?.baseDirectory ?: "disabled"
            println(HelpFormatter.formatStartupStatus(3, workspacePath))
        }

        // If no arguments provided, launch interactive mode
        val effectiveArgs = if (args.isEmpty()) {
            arrayOf("interactive")
        } else {
            args
        }

        // Run the CLI with injected dependencies
        AmpereCommand()
            .subcommands(
                InteractiveCommand(context),
                WatchCommand(context.eventRelayService),
                ThreadCommand(context.threadViewService),
                StatusCommand(context.threadViewService, context.ticketViewService),
                OutcomesCommand(context.outcomeMemoryRepository)
            )
            .main(effectiveArgs)
    } finally {
        // Clean up resources
        context.close()
    }
}
