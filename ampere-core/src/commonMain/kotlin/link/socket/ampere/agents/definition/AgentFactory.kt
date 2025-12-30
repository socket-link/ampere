package link.socket.ampere.agents.definition

import kotlinx.coroutines.CoroutineScope
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.memory.AgentMemoryService
import link.socket.ampere.agents.events.tickets.TicketOrchestrator
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.agents.execution.tools.ToolAskHuman
import link.socket.ampere.agents.execution.tools.ToolCreateIssues
import link.socket.ampere.agents.execution.tools.ToolWriteCodeFile
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfigurationFactory

enum class AgentType {
    CODE,
    PRODUCT,
    PROJECT,
    QUALITY,
}

class AgentFactory(
    private val scope: CoroutineScope,
    private val ticketOrchestrator: TicketOrchestrator,
    private val aiConfigurationFactory: AIConfigurationFactory,
    private val memoryServiceFactory: ((AgentId) -> AgentMemoryService)? = null,
) {
    private val toolWriteCodeFile: Tool<ExecutionContext.Code.WriteCode> =
        ToolWriteCodeFile(AgentActionAutonomy.ASK_BEFORE_ACTION)

    private val toolCreateIssues: Tool<ExecutionContext.IssueManagement> =
        ToolCreateIssues(AgentActionAutonomy.ACT_WITH_NOTIFICATION)

    private val toolAskHuman: Tool<ExecutionContext.NoChanges> =
        ToolAskHuman(AgentActionAutonomy.ASK_BEFORE_ACTION)

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
        AgentType.CODE -> CodeAgent(
            agentConfiguration = agentConfiguration,
            toolWriteCodeFile = toolWriteCodeFile,
            coroutineScope = scope,
            memoryServiceFactory = memoryServiceFactory,
        ) as A
        AgentType.PRODUCT -> ProductAgent(
            agentConfiguration = agentConfiguration,
            ticketOrchestrator = ticketOrchestrator,
            memoryServiceFactory = memoryServiceFactory,
        ) as A
        AgentType.PROJECT -> ProjectAgent(
            agentConfiguration = agentConfiguration,
            toolCreateIssues = toolCreateIssues,
            toolAskHuman = toolAskHuman,
            coroutineScope = scope,
            memoryServiceFactory = memoryServiceFactory,
        ) as A
        AgentType.QUALITY -> QualityAgent(
            agentConfiguration = agentConfiguration,
            memoryServiceFactory = memoryServiceFactory,
        ) as A
    }
}
