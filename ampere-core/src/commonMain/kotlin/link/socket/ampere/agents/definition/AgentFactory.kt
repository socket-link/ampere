package link.socket.ampere.agents.definition

import kotlinx.coroutines.CoroutineScope
import link.socket.ampere.agents.domain.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.config.AgentConfiguration
import link.socket.ampere.agents.events.tickets.TicketOrchestrator
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.agents.execution.tools.ToolWriteCodeFile
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfigurationFactory

enum class AgentType {
    CODE_WRITER,
    PRODUCT_MANAGER,
    QUALITY_ASSURANCE,
}

class AgentFactory(
    private val scope: CoroutineScope,
    private val ticketOrchestrator: TicketOrchestrator,
    private val aiConfigurationFactory: AIConfigurationFactory,
) {
    private val toolWriteCodeFile: Tool<ExecutionContext.Code.WriteCode> =
        ToolWriteCodeFile(AgentActionAutonomy.ASK_BEFORE_ACTION)

    private val aiConfiguration: AIConfiguration
        get() = aiConfigurationFactory.getDefaultConfiguration()

    private val agentConfiguration: AgentConfiguration
        get() = AgentConfiguration(
            agentDefinition = WriteCodeAgent,
            aiConfiguration = aiConfiguration,
        )

    fun <A : Agent<*>> create(
        agentType: AgentType,
    ): A = when (agentType) {
        AgentType.CODE_WRITER -> CodeWriterAgent(
            agentConfiguration = agentConfiguration,
            toolWriteCodeFile = toolWriteCodeFile,
            coroutineScope = scope,
        ) as A
        AgentType.PRODUCT_MANAGER -> ProductManagerAgent(
            agentConfiguration = agentConfiguration,
            ticketOrchestrator = ticketOrchestrator,
        ) as A
        AgentType.QUALITY_ASSURANCE -> QualityAssuranceAgent(
            agentConfiguration = agentConfiguration,
        ) as A
    }

}
