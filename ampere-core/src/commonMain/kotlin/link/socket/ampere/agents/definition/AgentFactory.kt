package link.socket.ampere.agents.definition

import kotlinx.coroutines.CoroutineScope
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.cognition.sparks.AmpereProjectSpark
import link.socket.ampere.agents.domain.cognition.sparks.LanguageSpark
import link.socket.ampere.agents.domain.cognition.sparks.ProjectSpark
import link.socket.ampere.agents.domain.cognition.sparks.RoleSpark
import link.socket.ampere.agents.domain.memory.AgentMemoryService
import link.socket.ampere.agents.domain.state.AgentState
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
 * Factory for creating agents with proper dependency injection and Spark initialization.
 *
 * All agents created through this factory share the same scope and have access
 * to the event bus through the eventApiFactory, ensuring consistent event
 * handling across the application.
 *
 * **Spark Integration (Ticket #226)**:
 * Each agent is initialized with a proper Spark stack based on their type:
 * - CognitiveAffinity: Set by the agent class (e.g., ANALYTICAL for CodeAgent)
 * - ProjectSpark: Applied if provided (defaults to AmpereProjectSpark)
 * - RoleSpark: Applied based on agent type
 * - LanguageSpark: Applied for code agents (defaults to Kotlin)
 *
 * @param scope Coroutine scope for agent async operations (should be shared with EnvironmentService)
 * @param ticketOrchestrator For ticket management
 * @param memoryServiceFactory Creates per-agent memory services connected to the shared event bus
 * @param eventApiFactory Creates per-agent event APIs for publishing events to the shared bus
 * @param issueTrackerProvider Optional GitHub/issue tracker integration
 * @param repository Optional repository name for issue tracking
 * @param aiConfiguration Optional AI configuration for model selection
 * @param projectSpark Optional project spark (defaults to AmpereProjectSpark)
 */
class AgentFactory(
    private val scope: CoroutineScope,
    private val ticketOrchestrator: TicketOrchestrator,
    private val memoryServiceFactory: ((AgentId) -> AgentMemoryService)? = null,
    private val eventApiFactory: ((AgentId) -> AgentEventApi)? = null,
    private val issueTrackerProvider: IssueTrackerProvider? = null,
    private val repository: String? = null,
    private val aiConfiguration: AIConfiguration? = null,
    private val projectSpark: ProjectSpark? = null,
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
     * The effective ProjectSpark to use for agent initialization.
     * Defaults to AmpereProjectSpark if not explicitly provided.
     */
    private val effectiveProjectSpark: ProjectSpark
        get() = projectSpark ?: AmpereProjectSpark.spark

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

    /**
     * Creates an agent of the specified type with appropriate Sparks applied.
     *
     * The agent is created and then initialized with the following Spark stack:
     * - ProjectSpark (from factory configuration or AmpereProjectSpark default)
     * - RoleSpark appropriate for the agent type
     * - LanguageSpark for code agents (Kotlin by default)
     *
     * @param agentType The type of agent to create
     * @return The created agent with Sparks applied
     */
    fun <A : Agent<*>> create(
        agentType: AgentType,
    ): A {
        val agent = createAgent(agentType)
        applySparkStack(agent, agentType)
        @Suppress("UNCHECKED_CAST")
        return agent as A
    }

    /**
     * Creates an agent of the specified type without applying Sparks.
     * Use this only when you need to manually configure the Spark stack.
     *
     * @param agentType The type of agent to create
     * @return The created agent without Sparks applied
     */
    fun <A : Agent<*>> createWithoutSparks(
        agentType: AgentType,
    ): A {
        @Suppress("UNCHECKED_CAST")
        return createAgent(agentType) as A
    }

    private fun createAgent(agentType: AgentType): AutonomousAgent<out AgentState> = when (agentType) {
        AgentType.CODE -> CodeAgent(
            agentConfiguration = agentConfiguration,
            toolWriteCodeFile = toolWriteCodeFile,
            coroutineScope = scope,
            memoryServiceFactory = memoryServiceFactory,
            issueTrackerProvider = issueTrackerProvider,
            repository = repository,
        )
        AgentType.PRODUCT -> ProductAgent(
            agentConfiguration = agentConfiguration,
            ticketOrchestrator = ticketOrchestrator,
            memoryServiceFactory = memoryServiceFactory,
        )
        AgentType.PROJECT -> ProjectAgent(
            agentConfiguration = agentConfiguration,
            toolCreateIssues = toolCreateIssues,
            toolAskHuman = toolAskHuman,
            coroutineScope = scope,
            memoryServiceFactory = memoryServiceFactory,
        )
        AgentType.QUALITY -> QualityAgent(
            agentConfiguration = agentConfiguration,
            memoryServiceFactory = memoryServiceFactory,
        )
    }

    /**
     * Applies the appropriate Spark stack to an agent based on its type.
     *
     * The layering follows the cellular differentiation model:
     * 1. ProjectSpark - Establishes project context
     * 2. RoleSpark - Defines agent's role and capabilities
     * 3. LanguageSpark - For code agents, adds language-specific context
     */
    private fun applySparkStack(agent: AutonomousAgent<*>, agentType: AgentType) {
        // Apply ProjectSpark first
        agent.spark<AutonomousAgent<*>>(effectiveProjectSpark)

        // Apply appropriate RoleSpark based on agent type
        when (agentType) {
            AgentType.CODE -> {
                agent.spark<AutonomousAgent<*>>(RoleSpark.Code)
                agent.spark<AutonomousAgent<*>>(LanguageSpark.Kotlin)
            }
            AgentType.PRODUCT -> {
                agent.spark<AutonomousAgent<*>>(RoleSpark.Planning)
            }
            AgentType.PROJECT -> {
                agent.spark<AutonomousAgent<*>>(RoleSpark.Planning)
            }
            AgentType.QUALITY -> {
                agent.spark<AutonomousAgent<*>>(RoleSpark.Code)
            }
        }
    }
}
