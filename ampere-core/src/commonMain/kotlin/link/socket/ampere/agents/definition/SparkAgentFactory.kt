package link.socket.ampere.agents.definition

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.cognition.Spark
import link.socket.ampere.agents.domain.cognition.sparks.LanguageSpark
import link.socket.ampere.agents.domain.cognition.sparks.ProjectSpark
import link.socket.ampere.agents.domain.cognition.sparks.RoleSpark
import link.socket.ampere.agents.domain.memory.AgentMemoryService
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.domain.ai.configuration.AIConfiguration

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
 */
class SparkAgentFactory(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val eventApiFactory: ((AgentId) -> AgentEventApi)? = null,
    private val memoryServiceFactory: ((AgentId) -> AgentMemoryService)? = null,
    private val defaultAiConfiguration: AIConfiguration? = null,
) {
    /**
     * Creates a code-focused agent with ANALYTICAL affinity.
     *
     * Best for: code writing, code review, debugging, testing.
     *
     * Pre-sparked with:
     * - ProjectSpark for project context
     * - RoleSpark.Code for code capabilities
     * - LanguageSpark (defaults to Kotlin)
     *
     * @param projectSpark The project context
     * @param language Language specialization (default: Kotlin)
     * @param id Optional custom agent ID
     * @return A code agent ready for tasks
     */
    fun createCodeAgent(
        projectSpark: ProjectSpark,
        language: LanguageSpark = LanguageSpark.Kotlin,
        id: AgentId = generateUUID("CodeAgent-${projectSpark.projectId}"),
    ): SparkBasedAgent {
        val agent = createAgent(id, CognitiveAffinity.ANALYTICAL)
        agent.applySpark(projectSpark)
        agent.applySpark(RoleSpark.Code)
        agent.applySpark(language)
        return agent
    }

    /**
     * Creates a research-focused agent with EXPLORATORY affinity.
     *
     * Best for: research, discovery, brainstorming, codebase exploration.
     *
     * Pre-sparked with:
     * - ProjectSpark for project context
     * - RoleSpark.Research for research capabilities
     *
     * @param projectSpark The project context
     * @param id Optional custom agent ID
     * @return A research agent ready for tasks
     */
    fun createResearchAgent(
        projectSpark: ProjectSpark,
        id: AgentId = generateUUID("ResearchAgent-${projectSpark.projectId}"),
    ): SparkBasedAgent {
        val agent = createAgent(id, CognitiveAffinity.EXPLORATORY)
        agent.applySpark(projectSpark)
        agent.applySpark(RoleSpark.Research)
        return agent
    }

    /**
     * Creates a planning-focused agent with INTEGRATIVE affinity.
     *
     * Best for: architecture, documentation, task breakdown, coordination.
     *
     * Pre-sparked with:
     * - ProjectSpark for project context
     * - RoleSpark.Planning for planning capabilities
     *
     * @param projectSpark The project context
     * @param id Optional custom agent ID
     * @return A planning agent ready for tasks
     */
    fun createPlanningAgent(
        projectSpark: ProjectSpark,
        id: AgentId = generateUUID("PlanningAgent-${projectSpark.projectId}"),
    ): SparkBasedAgent {
        val agent = createAgent(id, CognitiveAffinity.INTEGRATIVE)
        agent.applySpark(projectSpark)
        agent.applySpark(RoleSpark.Planning)
        return agent
    }

    /**
     * Creates an operations-focused agent with OPERATIONAL affinity.
     *
     * Best for: deployment, monitoring, incident response, routine tasks.
     *
     * Pre-sparked with:
     * - ProjectSpark for project context
     * - RoleSpark.Operations for ops capabilities
     *
     * @param projectSpark The project context
     * @param id Optional custom agent ID
     * @return An ops agent ready for tasks
     */
    fun createOpsAgent(
        projectSpark: ProjectSpark,
        id: AgentId = generateUUID("OpsAgent-${projectSpark.projectId}"),
    ): SparkBasedAgent {
        val agent = createAgent(id, CognitiveAffinity.OPERATIONAL)
        agent.applySpark(projectSpark)
        agent.applySpark(RoleSpark.Operations)
        return agent
    }

    /**
     * Creates a base agent with the specified affinity and no pre-applied Sparks.
     *
     * Use this when you need full control over the Spark configuration.
     *
     * @param id Agent ID
     * @param affinity The cognitive affinity for this agent
     * @return A bare agent ready for custom Spark configuration
     */
    fun createAgent(
        id: AgentId,
        affinity: CognitiveAffinity,
    ): SparkBasedAgent {
        val eventApi = eventApiFactory?.invoke(id)
        val memoryService = memoryServiceFactory?.invoke(id)

        return SparkBasedAgent(
            agentId = id,
            cognitiveAffinity = affinity,
            _eventApi = eventApi,
            _memoryService = memoryService,
            _aiConfiguration = defaultAiConfiguration,
            _observabilityScope = scope,
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
        fun codeAgent(language: LanguageSpark = LanguageSpark.Kotlin) =
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
private fun SparkBasedAgent.applySpark(spark: Spark) {
    this.spark<SparkBasedAgent>(spark)
}

/**
 * Extension function for fluent Spark chaining.
 *
 * Allows applying multiple Sparks in a single expression:
 * ```kotlin
 * agent.withSparks(projectSpark, roleSpark, languageSpark)
 * ```
 */
fun SparkBasedAgent.withSparks(vararg sparks: Spark): SparkBasedAgent {
    sparks.forEach { this.spark<SparkBasedAgent>(it) }
    return this
}
