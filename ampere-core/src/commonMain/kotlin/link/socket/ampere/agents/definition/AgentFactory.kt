package link.socket.ampere.agents.definition

import kotlinx.coroutines.CoroutineScope
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.memory.AgentMemoryService
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.tickets.TicketOrchestrator
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.agents.execution.tools.ToolAskHuman
import link.socket.ampere.agents.execution.tools.ToolCreateIssues
import link.socket.ampere.agents.execution.tools.ToolWriteCodeFile
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfigurationFactory
import link.socket.ampere.integrations.issues.IssueTrackerProvider

enum class AgentType {
    CODE,
    PRODUCT,
    PROJECT,
    QUALITY,
}

/**
 * Factory for creating agents with proper dependency injection.
 *
 * All agents created through this factory share the same scope and have access
 * to the event bus through the eventApiFactory, ensuring consistent event
 * handling across the application.
 *
 * @param scope Coroutine scope for agent async operations (should be shared with EnvironmentService)
 * @param ticketOrchestrator For ticket management
 * @param memoryServiceFactory Creates per-agent memory services connected to the shared event bus
 * @param eventApiFactory Creates per-agent event APIs for publishing events to the shared bus
 * @param issueTrackerProvider Optional GitHub/issue tracker integration
 * @param repository Optional repository name for issue tracking
 * @param aiConfiguration Optional AI configuration for model selection
 */
class AgentFactory(
    private val scope: CoroutineScope,
    private val ticketOrchestrator: TicketOrchestrator,
    private val memoryServiceFactory: ((AgentId) -> AgentMemoryService)? = null,
    private val eventApiFactory: ((AgentId) -> AgentEventApi)? = null,
    private val issueTrackerProvider: IssueTrackerProvider? = null,
    private val repository: String? = null,
    private val aiConfiguration: AIConfiguration? = null,
) {
    private val toolWriteCodeFile: Tool<ExecutionContext.Code.WriteCode> =
        ToolWriteCodeFile(AgentActionAutonomy.ASK_BEFORE_ACTION)

    private val toolCreateIssues: Tool<ExecutionContext.IssueManagement> =
        ToolCreateIssues(AgentActionAutonomy.ACT_WITH_NOTIFICATION)

    private val toolAskHuman: Tool<ExecutionContext.NoChanges> =
        ToolAskHuman(AgentActionAutonomy.ASK_BEFORE_ACTION)

    private val effectiveAiConfiguration: AIConfiguration
        get() = aiConfiguration ?: AIConfigurationFactory.getDefaultConfiguration()

    private val agentConfiguration: AgentConfiguration
        get() = AgentConfiguration(
            agentDefinition = WriteCodeAgent,
            aiConfiguration = effectiveAiConfiguration,
        )

    /**
     * Get an event API for publishing events on behalf of an agent.
     *
     * This allows external code (like AutonomousWorkLoop) to publish events
     * to the shared event bus using the agent's identity.
     *
     * @param agentId The ID of the agent
     * @return AgentEventApi or null if no eventApiFactory was provided
     */
    fun getEventApiFor(agentId: AgentId): AgentEventApi? = eventApiFactory?.invoke(agentId)

    fun <A : Agent<*>> create(
        agentType: AgentType,
    ): A = when (agentType) {
        AgentType.CODE -> CodeAgent(
            agentConfiguration = agentConfiguration,
            toolWriteCodeFile = toolWriteCodeFile,
            coroutineScope = scope,
            memoryServiceFactory = memoryServiceFactory,
            issueTrackerProvider = issueTrackerProvider,
            repository = repository,
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
