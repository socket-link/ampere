package link.socket.ampere.agents.definition

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import link.socket.ampere.agents.definition.code.CodeState
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.cognition.Spark
import link.socket.ampere.agents.domain.cognition.sparks.DefaultSparkCatalog
import link.socket.ampere.agents.domain.cognition.sparks.LanguageSparkIds
import link.socket.ampere.agents.domain.cognition.sparks.ProjectSpark
import link.socket.ampere.agents.domain.cognition.sparks.RoleSparkIds
import link.socket.ampere.agents.domain.cognition.sparks.SparkRegistry
import link.socket.ampere.agents.domain.memory.AgentMemoryService
import link.socket.ampere.agents.domain.routing.CapabilityRoutingDefaults
import link.socket.ampere.agents.domain.routing.CognitiveRelay
import link.socket.ampere.agents.domain.routing.CognitiveRelayImpl
import link.socket.ampere.agents.domain.routing.RelayConfig
import link.socket.ampere.agents.domain.routing.capability.CapabilityRung
import link.socket.ampere.agents.domain.routing.capability.InMemoryModelDescriptorRegistry
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.executor.Executor
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.llm.UpstreamLlmClient

/**
 * Factory for creating agents with Spark-based cognitive differentiation.
 *
 * This factory provides convenience methods for common agent configurations
 * while allowing full customization. It's the "happy path" for agent creation
 * using the Spark system.
 *
 * Each factory method:
 * 1. Creates an agent with the appropriate cognitive affinity
 * 2. Pre-sparks it with common Spark configurations
 * 3. Returns the agent ready for tasks
 *
 * @param scope CoroutineScope for agent async operations
 * @param eventApiFactory Creates per-agent event APIs for publishing events
 * @param memoryServiceFactory Creates per-agent memory services
 * @param defaultAiConfiguration Default AI configuration for agents
 * @param sparkRegistry Optional declarative spark registry; defaults to bundled fixtures
 * @param cognitiveRelay Optional relay injected into created agents (e.g. a `PlaybackRelay` for eval Bench runs)
 * @param executor Optional tool executor injected into created agents (e.g. a `NoOpExecutor` for effect-free Bench runs)
 * @param upstreamLlmClient Outbound LLM transport handed to created agents (AMPR-236). Null leaves them without a
 *   transport, so their first LLM call throws rather than calling a provider directly; pass
 *   [link.socket.ampere.llm.BundledUpstreamLlmClient] to opt into the direct call.
 * @param runId Ambient Arc-run identity (AMPR-240) handed to created agents so their LLM calls carry it as
 *   `RoutingContext.workflowId` and therefore reach `ProviderCallStartedEvent`/`ProviderCallCompletedEvent`.
 */
class SparkAgentFactory(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val eventApiFactory: ((AgentId) -> AgentEventApi)? = null,
    private val memoryServiceFactory: ((AgentId) -> AgentMemoryService)? = null,
    private val defaultAiConfiguration: AIConfiguration? = null,
    private val sparkRegistry: SparkRegistry? = null,
    private val cognitiveRelay: CognitiveRelay? = null,
    private val executor: Executor? = null,
    private val upstreamLlmClient: UpstreamLlmClient? = null,
    private val runId: RunId? = null,
) {
    private val effectiveSparkRegistry: SparkRegistry
        get() = sparkRegistry ?: DefaultSparkCatalog.registry

    /**
     * Fallback relay used only for agents that declare a rung floor (AMPR-232).
     *
     * A floor is enforced by the relay, so an agent carrying one but no relay
     * would have its floor silently ignored —
     * [AgentLLMService][link.socket.ampere.agents.domain.reasoning.AgentLLMService]
     * merges the floor into the `RoutingContext` and then, with no relay, resolves
     * straight to the agent's static configuration. Rather than accept that, a
     * declared floor gets a real [CognitiveRelayImpl] over the bundled catalog —
     * the same construction [AgentFactory] uses for the activated CODE path.
     *
     * Built lazily and shared across every agent this factory makes. Routing events
     * are not published here (no bus is available at this seam); inject
     * [cognitiveRelay] with a bus-backed relay when the Arc run needs them.
     *
     * Floorless agents keep the pre-AMPR-232 behavior (relay only if injected).
     */
    private val floorEnforcingRelay: CognitiveRelay by lazy {
        CognitiveRelayImpl(
            initialConfig = RelayConfig(rules = CapabilityRoutingDefaults.defaultCapabilityRules()),
            registry = InMemoryModelDescriptorRegistry(),
        )
    }

    /**
     * Creates a code-focused agent with ANALYTICAL affinity.
     *
     * Best for: code writing, code review, debugging, testing.
     *
     * Pre-sparked with:
     * - ProjectSpark for project context
     * - declarative role-code spark for code capabilities
     * - LanguageSpark (defaults to Kotlin)
     *
     * @param projectSpark The project context
     * @param language Language specialization (default: Kotlin)
     * @param id Optional custom agent ID
     * @return A code agent ready for tasks
     */
    fun createCodeAgent(
        projectSpark: ProjectSpark,
        language: Spark? = null,
        id: AgentId = generateUUID("CodeAgent-${projectSpark.projectId}"),
    ): SparkBasedAgent<CodeState> {
        val agent = createAgent(id, CognitiveAffinity.ANALYTICAL)
        agent.applySpark(projectSpark)
        agent.applySpark(requireRoleSpark(RoleSparkIds.CODE))
        agent.applySpark(language ?: requireLanguageSpark(LanguageSparkIds.KOTLIN))
        return agent
    }

    /**
     * Creates a research-focused agent with EXPLORATORY affinity.
     *
     * Best for: research, discovery, brainstorming, codebase exploration.
     *
     * Pre-sparked with:
     * - ProjectSpark for project context
     * - declarative role-research spark for research capabilities
     *
     * @param projectSpark The project context
     * @param id Optional custom agent ID
     * @return A research agent ready for tasks
     */
    fun createResearchAgent(
        projectSpark: ProjectSpark,
        id: AgentId = generateUUID("ResearchAgent-${projectSpark.projectId}"),
    ): SparkBasedAgent<CodeState> {
        val agent = createAgent(id, CognitiveAffinity.EXPLORATORY)
        agent.applySpark(projectSpark)
        agent.applySpark(requireRoleSpark(RoleSparkIds.RESEARCH))
        return agent
    }

    /**
     * Creates a planning-focused agent with INTEGRATIVE affinity.
     *
     * Best for: architecture, documentation, task breakdown, coordination.
     *
     * Pre-sparked with:
     * - ProjectSpark for project context
     * - declarative role-planning spark for planning capabilities
     *
     * @param projectSpark The project context
     * @param id Optional custom agent ID
     * @return A planning agent ready for tasks
     */
    fun createPlanningAgent(
        projectSpark: ProjectSpark,
        id: AgentId = generateUUID("PlanningAgent-${projectSpark.projectId}"),
    ): SparkBasedAgent<CodeState> {
        val agent = createAgent(id, CognitiveAffinity.INTEGRATIVE)
        agent.applySpark(projectSpark)
        agent.applySpark(requireRoleSpark(RoleSparkIds.PLANNING))
        return agent
    }

    /**
     * Creates an operations-focused agent with OPERATIONAL affinity.
     *
     * Best for: deployment, monitoring, incident response, routine tasks.
     *
     * Pre-sparked with:
     * - ProjectSpark for project context
     * - declarative role-operations spark for ops capabilities
     *
     * @param projectSpark The project context
     * @param id Optional custom agent ID
     * @return An ops agent ready for tasks
     */
    fun createOpsAgent(
        projectSpark: ProjectSpark,
        id: AgentId = generateUUID("OpsAgent-${projectSpark.projectId}"),
    ): SparkBasedAgent<CodeState> {
        val agent = createAgent(id, CognitiveAffinity.OPERATIONAL)
        agent.applySpark(projectSpark)
        agent.applySpark(requireRoleSpark(RoleSparkIds.OPERATIONS))
        return agent
    }

    private fun requireRoleSpark(id: String): Spark =
        effectiveSparkRegistry.roleSparkById(id)
            ?: error(
                "SparkAgentFactory requires role spark '$id', but the configured SparkRegistry " +
                    "did not provide it.",
            )

    private fun requireLanguageSpark(id: String): Spark =
        effectiveSparkRegistry.languageSparkById(id)
            ?: error(
                "SparkAgentFactory requires language spark '$id', but the configured SparkRegistry " +
                    "did not provide it.",
            )

    /**
     * Creates a base agent with the specified affinity and no pre-applied Sparks.
     *
     * Use this when you need full control over the Spark configuration.
     *
     * @param id Agent ID
     * @param affinity The cognitive affinity for this agent
     * @param minimumRung Capability-rung floor for this agent (AMPR-232). Threaded onto the agent's
     *   `AgentDefinition` so the relay refuses to route below it; an agent declaring one is given
     *   [floorEnforcingRelay] when no relay was injected, so the floor is enforced rather than ignored.
     *   Null declares no floor and leaves the agent unconstrained.
     * @return A bare agent ready for custom Spark configuration
     */
    fun createAgent(
        id: AgentId,
        affinity: CognitiveAffinity,
        minimumRung: CapabilityRung? = null,
    ): SparkBasedAgent<CodeState> {
        val eventApi = eventApiFactory?.invoke(id)
        val memoryService = memoryServiceFactory?.invoke(id)

        return SparkBasedAgent(
            agentId = id,
            cognitiveAffinity = affinity,
            initialState = CodeState.blank,
            _eventApi = eventApi,
            _memoryService = memoryService,
            _aiConfiguration = defaultAiConfiguration,
            _observabilityScope = scope,
            _cognitiveRelay = cognitiveRelay ?: minimumRung?.let { floorEnforcingRelay },
            _minimumRung = minimumRung,
            _executor = executor,
            _upstreamLlmClient = upstreamLlmClient,
            _runId = runId,
        )
    }

    /**
     * Builder for project-scoped agent creation.
     *
     * Provides a fluent API for creating agents within a specific project context.
     *
     * Example:
     * ```kotlin
     * factory.forProject("ampere", "AI agent library", "/path/to/repo")
     *     .codeAgent()
     * ```
     */
    fun forProject(
        projectId: String,
        description: String,
        repositoryRoot: String,
        conventions: String = "",
    ): ProjectContext {
        val projectSpark = ProjectSpark(
            projectId = projectId,
            projectDescription = description,
            repositoryRoot = repositoryRoot,
            conventions = conventions,
        )
        return ProjectContext(this, projectSpark)
    }

    /**
     * Context for creating agents within a specific project.
     */
    class ProjectContext(
        private val factory: SparkAgentFactory,
        private val projectSpark: ProjectSpark,
    ) {
        /** Creates a code agent for this project. */
        fun codeAgent(language: Spark? = null) =
            factory.createCodeAgent(projectSpark, language)

        /** Creates a research agent for this project. */
        fun researchAgent() = factory.createResearchAgent(projectSpark)

        /** Creates a planning agent for this project. */
        fun planningAgent() = factory.createPlanningAgent(projectSpark)

        /** Creates an ops agent for this project. */
        fun opsAgent() = factory.createOpsAgent(projectSpark)

        /** Gets the project spark for custom usage. */
        val spark: ProjectSpark get() = projectSpark
    }
}

/**
 * Helper extension to apply a spark with simpler syntax.
 */
private fun SparkBasedAgent<CodeState>.applySpark(spark: Spark) {
    this.spark<SparkBasedAgent<CodeState>>(spark)
}

/**
 * Extension function for fluent Spark chaining.
 *
 * Allows applying multiple Sparks in a single expression:
 * ```kotlin
 * agent.withSparks(projectSpark, roleSpark, languageSpark)
 * ```
 */
fun SparkBasedAgent<CodeState>.withSparks(vararg sparks: Spark): SparkBasedAgent<CodeState> {
    sparks.forEach { this.spark<SparkBasedAgent<CodeState>>(it) }
    return this
}
