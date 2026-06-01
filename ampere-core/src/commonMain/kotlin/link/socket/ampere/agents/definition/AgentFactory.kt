package link.socket.ampere.agents.definition

import kotlinx.coroutines.CoroutineScope
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.config.CognitiveConfig
import link.socket.ampere.agents.domain.cognition.Spark
import link.socket.ampere.agents.domain.cognition.sparks.AmpereProjectSpark
import link.socket.ampere.agents.domain.cognition.sparks.AmpereSpikeFlags
import link.socket.ampere.agents.domain.cognition.sparks.DefaultPhaseSparkLibrary
import link.socket.ampere.agents.domain.cognition.sparks.LanguageSparkIds
import link.socket.ampere.agents.domain.cognition.sparks.PhaseSparkLibrary
import link.socket.ampere.agents.domain.cognition.sparks.ProjectSpark
import link.socket.ampere.agents.domain.memory.AgentMemoryService
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.tickets.TicketOrchestrator
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.agents.execution.tools.ToolAskHuman
import link.socket.ampere.agents.execution.tools.ToolCreateIssues
import link.socket.ampere.agents.execution.tools.ToolReadCodeFile
import link.socket.ampere.agents.execution.tools.ToolWriteCodeFile
import link.socket.ampere.agents.execution.tools.git.ToolCommit
import link.socket.ampere.agents.execution.tools.git.ToolCreateBranch
import link.socket.ampere.agents.execution.tools.git.ToolCreatePullRequest
import link.socket.ampere.agents.execution.tools.git.ToolGitStatus
import link.socket.ampere.agents.execution.tools.git.ToolPush
import link.socket.ampere.agents.execution.tools.git.ToolStageFiles
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfigurationFactory
import link.socket.ampere.domain.llm.LlmProvider
import link.socket.ampere.integrations.issues.IssueTrackerProvider
import link.socket.ampere.util.runBlockingCompat

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
 * - CognitiveAffinity: Set by the SparkBasedAgent factory (e.g. ANALYTICAL for the Code factory)
 * - ProjectSpark: Applied if provided (defaults to AmpereProjectSpark)
 * - Role spark: Applied based on agent type
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
 * @param toolWriteCodeFileOverride Optional override for write_code_file tool
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
    private val toolWriteCodeFileOverride: Tool<ExecutionContext.Code.WriteCode>? = null,
    private val cognitiveConfig: CognitiveConfig = CognitiveConfig(),
    private val llmProvider: LlmProvider? = null,
    private val eventSerialBus: EventSerialBus? = null,
) {
    private val toolWriteCodeFile: Tool<ExecutionContext.Code.WriteCode> =
        toolWriteCodeFileOverride ?: ToolWriteCodeFile(AgentActionAutonomy.ASK_BEFORE_ACTION)

    private val toolCreateIssues: Tool<ExecutionContext.IssueManagement> =
        ToolCreateIssues(
            requiredAgentAutonomy = AgentActionAutonomy.ACT_WITH_NOTIFICATION,
            parameterStrategy = link.socket.ampere.agents.definition.project.ProjectParams.IssueCreation(
                repository = repository ?: ".",
                availableAgents = emptyList(),
                existingIssues = emptyList(),
            ),
        )

    private val toolAskHuman: Tool<ExecutionContext.NoChanges>? =
        eventSerialBus?.let {
            ToolAskHuman(
                requiredAgentAutonomy = AgentActionAutonomy.ASK_BEFORE_ACTION,
                eventSerialBus = it,
                parameterStrategy = link.socket.ampere.agents.definition.project.ProjectParams.HumanEscalation(
                    agentRole = "Project Manager",
                ),
            )
        }

    private val effectiveAiConfiguration: AIConfiguration
        get() = aiConfiguration ?: AIConfigurationFactory.getDefaultConfiguration()

    private val agentConfiguration: AgentConfiguration
        get() = AgentConfiguration(
            agentDefinition = WriteCodeAgent,
            aiConfiguration = effectiveAiConfiguration,
            cognitiveConfig = cognitiveConfig,
            llmProvider = llmProvider,
        )

    /**
     * The effective ProjectSpark to use for agent initialization.
     * Defaults to AmpereProjectSpark if not explicitly provided.
     */
    private val effectiveProjectSpark: ProjectSpark
        get() = projectSpark ?: AmpereProjectSpark.spark

    /**
     * Bundled `.spark.md` library, loaded lazily on first agent creation.
     * The factory's [createAgent] path attaches this library to every
     * spark-based agent it builds so the declarative per-phase guidance
     * (`code-agent.spark.md`, `product-agent.spark.md`, etc.) activates
     * during the cognitive loop.
     *
     * Loading is suspend but the existing public `create` API is
     * synchronous, so we resolve the library via `runBlocking` once and
     * cache it. The load reads bundled resources and parses markdown —
     * cheap enough at startup that the single blocking call is fine.
     */
    private val phaseSparkLibrary: PhaseSparkLibrary by lazy {
        runBlockingCompat { DefaultPhaseSparkLibrary.load() }
    }

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
     * - Role spark appropriate for the agent type
     * - LanguageSpark for code agents (Kotlin by default)
     *
     * @param agentType The type of agent to create
     * @return The created agent with Sparks applied
     */
    fun <A : Agent<*>> create(
        agentType: AgentType,
    ): A {
        val agent = createAgent(agentType)
        attachPhaseSparkLibrary(agent)
        applySparkStack(agent, agentType)
        if (agent is ObservableAgent<*>) {
            agent.emitCognitiveSnapshot()
        }
        @Suppress("UNCHECKED_CAST")
        return agent as A
    }

    /**
     * Wires the bundled declarative spark library into the agent so its
     * `.spark.md` per-phase contributions activate during the cognitive
     * loop. Only spark-based agents have the setter (the legacy typed
     * agents are gone after Waves 1–2). Also flips on the runtime gate
     * for the declarative-spark path; the flag defaults to off for
     * backward-compat callers that wire agents by hand without a
     * library.
     */
    private fun attachPhaseSparkLibrary(agent: AutonomousAgent<*>) {
        if (agent !is SparkBasedAgent<*>) return
        agent.setPhaseSparkLibrary(phaseSparkLibrary)
        AmpereSpikeFlags.declarativeSparksEnabled = true
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
        AgentType.CODE -> {
            val agentId = generateUUID("SparkBasedAgent-Code")
            val eventApi = eventApiFactory?.invoke(agentId)
            val memoryService = memoryServiceFactory?.invoke(agentId)
            SparkBasedAgent.Code(
                sparkRegistry = phaseSparkLibrary,
                agentId = agentId,
                aiConfiguration = effectiveAiConfiguration,
                eventApi = eventApi,
                memoryService = memoryService,
                llmProvider = llmProvider,
                observabilityScope = scope,
                tools = buildSet {
                    add(toolWriteCodeFile)
                    add(ToolReadCodeFile(AgentActionAutonomy.FULLY_AUTONOMOUS))
                    add(ToolCreateBranch())
                    add(ToolStageFiles())
                    add(ToolCommit())
                    add(ToolPush())
                    add(ToolCreatePullRequest())
                    add(ToolGitStatus())
                },
            )
        }
        AgentType.PRODUCT -> {
            val agentId = generateUUID("SparkBasedAgent-Product")
            val eventApi = eventApiFactory?.invoke(agentId)
            val memoryService = memoryServiceFactory?.invoke(agentId)
            SparkBasedAgent.Product(
                sparkRegistry = phaseSparkLibrary,
                agentId = agentId,
                aiConfiguration = effectiveAiConfiguration,
                eventApi = eventApi,
                memoryService = memoryService,
                llmProvider = llmProvider,
                observabilityScope = scope,
            )
        }
        AgentType.PROJECT -> {
            val agentId = generateUUID("SparkBasedAgent-Project")
            val eventApi = eventApiFactory?.invoke(agentId)
            val memoryService = memoryServiceFactory?.invoke(agentId)
            SparkBasedAgent.Project(
                sparkRegistry = phaseSparkLibrary,
                agentId = agentId,
                aiConfiguration = effectiveAiConfiguration,
                eventApi = eventApi,
                memoryService = memoryService,
                llmProvider = llmProvider,
                observabilityScope = scope,
                tools = setOfNotNull(toolCreateIssues, toolAskHuman),
            )
        }
        AgentType.QUALITY -> {
            val agentId = generateUUID("SparkBasedAgent-Quality")
            val eventApi = eventApiFactory?.invoke(agentId)
            val memoryService = memoryServiceFactory?.invoke(agentId)
            SparkBasedAgent.Quality(
                sparkRegistry = phaseSparkLibrary,
                agentId = agentId,
                aiConfiguration = effectiveAiConfiguration,
                eventApi = eventApi,
                memoryService = memoryService,
                llmProvider = llmProvider,
                observabilityScope = scope,
            )
        }
    }

    /**
     * Applies the appropriate Spark stack to an agent based on its type.
     *
     * The layering follows the cellular differentiation model:
     * 1. ProjectSpark - Establishes project context
     * 2. Role spark - Defines agent's role and capabilities
     * 3. LanguageSpark - For code agents, adds language-specific context
     */
    private fun applySparkStack(agent: AutonomousAgent<*>, agentType: AgentType) {
        // Apply ProjectSpark first
        applySpark(agent, effectiveProjectSpark)

        // Apply appropriate role spark based on agent type.
        // Note: the `SparkBasedAgent.<Role>(...)` factories each apply
        // their own role spark at construction time, so the branches here
        // only layer additional sparks on top of what the factory already
        // stacked. The CODE branch adds a language spark; the PRODUCT,
        // PROJECT, and QUALITY branches add nothing further (their role
        // sparks are already applied by their respective factories).
        when (agentType) {
            AgentType.CODE -> {
                applySpark(agent, requireLanguageSpark(LanguageSparkIds.KOTLIN))
            }
            AgentType.PRODUCT,
            AgentType.PROJECT,
            AgentType.QUALITY,
            -> Unit
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun applySpark(agent: AutonomousAgent<*>, spark: Spark) {
        (agent as AutonomousAgent<AgentState>).spark<AutonomousAgent<AgentState>>(spark)
    }

    private fun requireLanguageSpark(id: String): Spark =
        phaseSparkLibrary.languageSparkById(id)
            ?: error("Bundled language spark '$id' is missing from DefaultPhaseSparkLibrary")
}
