package link.socket.ampere

import com.github.ajalt.clikt.core.subcommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import link.socket.ampere.agents.definition.AgentFactory
import link.socket.ampere.agents.definition.AgentType
import link.socket.ampere.agents.definition.CodeWriterAgent
import link.socket.ampere.agents.definition.ProductManagerAgent
import link.socket.ampere.agents.definition.QualityAssuranceAgent
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.data.RepositoryFactory
import link.socket.ampere.data.createJvmDriver
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfigurationFactory
import link.socket.ampere.domain.koog.KoogAgentFactory

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
    val databaseDriver = createJvmDriver()
    val ioScope = CoroutineScope(Dispatchers.IO)
    val jsonConfig = DEFAULT_JSON

    val aiConfigurationFactory = AIConfigurationFactory()
    val koogAgentFactory = KoogAgentFactory()
    val repositoryFactory = RepositoryFactory(ioScope, databaseDriver, jsonConfig)

    val context = AmpereContext()
    val environmentService = context.environmentService

    val agentFactory = AgentFactory(
        scope = ioScope,
        ticketOrchestrator = environmentService.ticketOrchestrator,
        aiConfigurationFactory = aiConfigurationFactory,
        defaultAIConfiguration = WriteCodeAgent.suggestedAIConfigurationBuilder(aiConfigurationFactory),
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
