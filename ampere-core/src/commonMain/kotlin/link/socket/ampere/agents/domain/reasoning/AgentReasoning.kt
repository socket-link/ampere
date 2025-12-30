package link.socket.ampere.agents.domain.reasoning

import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.memory.AgentMemoryService
import link.socket.ampere.agents.domain.memory.KnowledgeWithScore
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.execution.ParameterStrategy
import link.socket.ampere.agents.execution.ToolExecutionEngine
import link.socket.ampere.agents.execution.executor.Executor
import link.socket.ampere.agents.execution.executor.ExecutorId
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool

/**
 * Unified reasoning facade that composes all cognitive services.
 *
 * This is the primary interface agents use to access reasoning capabilities.
 * It combines all the individual services (LLM, perception, planning, execution,
 * evaluation, knowledge extraction) into a cohesive API.
 *
 * Agents configure this facade with their specific customizations:
 * - Context builders for perception
 * - Custom prompts for planning
 * - Task factories for plan generation
 * - Parameter strategies for tool execution
 * - Knowledge extraction customizations
 *
 * Usage:
 * ```kotlin
 * val reasoning = AgentReasoning.create(config) {
 *     agentRole = "Project Manager"
 *     availableTools = setOf(toolCreateIssues, toolAskHuman)
 *
 *     perception {
 *         contextBuilder = { state -> buildPMContext(state) }
 *     }
 *
 *     planning {
 *         taskFactory = PMTaskFactory
 *         customPrompt = { task, ideas, tools, knowledge -> buildPMPrompt(...) }
 *     }
 *
 *     execution {
 *         registerStrategy("create_issues", ProjectParams.IssueCreation(...))
 *     }
 * }
 *
 * // Use in agent
 * val idea = reasoning.evaluatePerception(perception)
 * val plan = reasoning.generatePlan(task, ideas)
 * val outcome = reasoning.executeTool(tool, request)
 * val knowledge = reasoning.extractKnowledge(outcome, task, plan)
 * ```
 *
 * @property config The agent configuration
 * @property settings The reasoning settings
 */
class AgentReasoning private constructor(
    private val config: AgentConfiguration?,
    private val settings: ReasoningSettings,
    private val mockResponses: MockReasoningResponses? = null,
) {
    private val llmService: AgentLLMService? = config?.let { AgentLLMService(it) }
    private val perceptionEvaluator: PerceptionEvaluator? = llmService?.let { PerceptionEvaluator(it) }
    private val planGenerator: PlanGenerator? = llmService?.let { PlanGenerator(it) }
    private val outcomeEvaluator: OutcomeEvaluator? = llmService?.let { OutcomeEvaluator(it) }
    private val planExecutor = PlanExecutor(settings.executorId)

    private val toolExecutionEngine: ToolExecutionEngine? = if (llmService != null && settings.executor != null) {
        ToolExecutionEngine(llmService, settings.executor, settings.executorId).also { engine ->
            settings.parameterStrategies.forEach { (toolId, strategy) ->
                engine.registerStrategy(toolId, strategy)
            }
        }
    } else {
        null
    }

    // ========================================================================
    // Perception
    // ========================================================================

    /**
     * Evaluates a perception and generates insights.
     */
    suspend fun <S : AgentState> evaluatePerception(perception: Perception<S>): Idea {
        // Use mock response if available
        mockResponses?.perceptionEvaluator?.let { evaluator ->
            @Suppress("UNCHECKED_CAST")
            return evaluator(perception as Perception<AgentState>)
        }

        return perceptionEvaluator?.evaluate(
            perception = perception,
            contextBuilder = { state ->
                @Suppress("UNCHECKED_CAST")
                settings.perceptionContextBuilder?.invoke(state as AgentState)
                    ?: "State: $state"
            },
            agentRole = settings.agentRole,
            availableTools = settings.availableTools,
        ) ?: throw IllegalStateException("No perception evaluator configured")
    }

    // ========================================================================
    // Planning
    // ========================================================================

    /**
     * Generates a plan for accomplishing a task.
     */
    suspend fun generatePlan(
        task: Task,
        ideas: List<Idea>,
        relevantKnowledge: List<KnowledgeWithScore> = emptyList(),
    ): Plan {
        // Use mock response if available
        mockResponses?.planGenerator?.let { generator ->
            return generator(task, ideas)
        }

        return planGenerator?.generate(
            task = task,
            ideas = ideas,
            agentRole = settings.agentRole,
            availableTools = settings.availableTools,
            relevantKnowledge = relevantKnowledge,
            taskFactory = settings.taskFactory,
            customPromptBuilder = settings.planningPromptBuilder,
        ) ?: throw IllegalStateException("No plan generator configured")
    }

    // ========================================================================
    // Execution
    // ========================================================================

    /**
     * Executes a plan step by step.
     */
    suspend fun executePlan(
        plan: Plan,
        stepExecutor: suspend (Task, StepContext) -> StepResult,
    ): PlanExecutionResult {
        return planExecutor.execute(plan, stepExecutor)
    }

    /**
     * Executes a tool with LLM-generated parameters.
     */
    suspend fun executeTool(
        tool: Tool<*>,
        request: ExecutionRequest<*>,
    ): ExecutionOutcome {
        // Use mock response if available
        mockResponses?.toolExecutor?.let { executor ->
            return executor(tool, request)
        }

        return toolExecutionEngine?.execute(tool, request)
            ?: ExecutionOutcome.NoChanges.Failure(
                executorId = settings.executorId,
                ticketId = request.context.ticket.id,
                taskId = request.context.task.id,
                executionStartTimestamp = Clock.System.now(),
                executionEndTimestamp = Clock.System.now(),
                message = "Tool execution engine not configured",
            )
    }

    // ========================================================================
    // Evaluation & Learning
    // ========================================================================

    /**
     * Evaluates outcomes and generates learnings.
     */
    suspend fun evaluateOutcomes(
        outcomes: List<Outcome>,
        memoryService: AgentMemoryService? = null,
    ): EvaluationResult {
        // Use mock response if available
        mockResponses?.outcomeEvaluator?.let { evaluator ->
            return evaluator(outcomes)
        }

        val result = outcomeEvaluator?.evaluate(
            outcomes = outcomes,
            agentRole = settings.agentRole,
            contextBuilder = settings.outcomeContextBuilder,
        ) ?: throw IllegalStateException("No outcome evaluator configured")

        // Store learnings in memory if available
        if (result.knowledge.isNotEmpty() && memoryService != null) {
            result.knowledge.forEach { knowledge ->
                memoryService.storeKnowledge(knowledge)
            }
        }

        return result
    }

    /**
     * Extracts knowledge from a single outcome.
     */
    fun extractKnowledge(
        outcome: Outcome,
        task: Task,
        plan: Plan,
    ): Knowledge.FromOutcome {
        return settings.knowledgeExtractor?.invoke(outcome, task, plan)
            ?: KnowledgeExtractor.extractDefault(outcome, task, plan, settings.agentRole)
    }

    // ========================================================================
    // Direct LLM Access
    // ========================================================================

    /**
     * Calls the LLM directly with a prompt.
     */
    suspend fun callLLM(
        prompt: String,
        systemMessage: String? = null,
    ): String {
        // Use mock response if available
        mockResponses?.llmCall?.let { call ->
            return call(prompt)
        }

        return llmService?.call(
            prompt = prompt,
            systemMessage = systemMessage ?: "You are a ${settings.agentRole} agent.",
        ) ?: throw IllegalStateException("No LLM service configured")
    }

    /**
     * Calls the LLM expecting a JSON response.
     */
    suspend fun callLLMForJson(prompt: String): LLMJsonResponse {
        return llmService?.callForJson(prompt)
            ?: throw IllegalStateException("No LLM service configured")
    }

    companion object {
        /**
         * Creates an AgentReasoning instance with the given configuration.
         */
        fun create(
            config: AgentConfiguration,
            executorId: ExecutorId,
            configure: ReasoningSettingsBuilder.() -> Unit,
        ): AgentReasoning {
            val builder = ReasoningSettingsBuilder(executorId)
            builder.configure()
            return AgentReasoning(config, builder.build())
        }

        /**
         * Creates an AgentReasoning instance for testing with mock responses.
         *
         * This factory allows tests to inject mock behaviors for all cognitive operations
         * without requiring a real LLM connection.
         *
         * Usage:
         * ```kotlin
         * val mockReasoning = AgentReasoning.createForTesting(executorId) {
         *     onPerception { perception -> Idea("Mock insight", "Analysis") }
         *     onPlanning { task, ideas -> Plan.ForTask(task, listOf(task), 1) }
         *     onToolExecution { tool, request -> ExecutionOutcome.Success(...) }
         *     onOutcomeEvaluation { outcomes -> EvaluationResult(...) }
         * }
         * ```
         */
        fun createForTesting(
            executorId: ExecutorId,
            configure: MockReasoningBuilder.() -> Unit,
        ): AgentReasoning {
            val builder = MockReasoningBuilder()
            builder.configure()
            val settings = ReasoningSettings(
                executorId = executorId,
                agentRole = "Test Agent",
                availableTools = emptySet(),
                executor = null,
                perceptionContextBuilder = null,
                planningPromptBuilder = null,
                taskFactory = DefaultTaskFactory,
                parameterStrategies = emptyMap(),
                outcomeContextBuilder = null,
                knowledgeExtractor = null,
            )
            return AgentReasoning(null, settings, builder.build())
        }
    }
}

/**
 * Mock responses container for testing AgentReasoning.
 */
data class MockReasoningResponses(
    val perceptionEvaluator: ((Perception<AgentState>) -> Idea)? = null,
    val planGenerator: ((Task, List<Idea>) -> Plan)? = null,
    val toolExecutor: ((Tool<*>, ExecutionRequest<*>) -> ExecutionOutcome)? = null,
    val outcomeEvaluator: ((List<Outcome>) -> EvaluationResult)? = null,
    val llmCall: ((String) -> String)? = null,
)

/**
 * Builder for configuring mock reasoning responses.
 */
class MockReasoningBuilder {
    private var perceptionEvaluator: ((Perception<AgentState>) -> Idea)? = null
    private var planGenerator: ((Task, List<Idea>) -> Plan)? = null
    private var toolExecutor: ((Tool<*>, ExecutionRequest<*>) -> ExecutionOutcome)? = null
    private var outcomeEvaluator: ((List<Outcome>) -> EvaluationResult)? = null
    private var llmCall: ((String) -> String)? = null

    fun onPerception(handler: (Perception<AgentState>) -> Idea) {
        perceptionEvaluator = handler
    }

    fun onPlanning(handler: (Task, List<Idea>) -> Plan) {
        planGenerator = handler
    }

    fun onToolExecution(handler: (Tool<*>, ExecutionRequest<*>) -> ExecutionOutcome) {
        toolExecutor = handler
    }

    fun onOutcomeEvaluation(handler: (List<Outcome>) -> EvaluationResult) {
        outcomeEvaluator = handler
    }

    fun onLLMCall(handler: (String) -> String) {
        llmCall = handler
    }

    fun build(): MockReasoningResponses = MockReasoningResponses(
        perceptionEvaluator = perceptionEvaluator,
        planGenerator = planGenerator,
        toolExecutor = toolExecutor,
        outcomeEvaluator = outcomeEvaluator,
        llmCall = llmCall,
    )
}

/**
 * Settings for configuring agent reasoning behavior.
 */
data class ReasoningSettings(
    val executorId: ExecutorId,
    val agentRole: String,
    val availableTools: Set<Tool<*>>,
    val executor: Executor?,
    val perceptionContextBuilder: ((AgentState) -> String)?,
    val planningPromptBuilder: ((Task, List<Idea>, Set<Tool<*>>, List<KnowledgeWithScore>) -> String)?,
    val taskFactory: TaskFactory,
    val parameterStrategies: Map<String, ParameterStrategy>,
    val outcomeContextBuilder: ((List<Outcome>) -> String)?,
    val knowledgeExtractor: ((Outcome, Task, Plan) -> Knowledge.FromOutcome)?,
)

/**
 * Builder for ReasoningSettings.
 */
class ReasoningSettingsBuilder(private val executorId: ExecutorId) {
    var agentRole: String = "Agent"
    var availableTools: Set<Tool<*>> = emptySet()
    var executor: Executor? = null

    private var perceptionContextBuilder: ((AgentState) -> String)? = null
    private var planningPromptBuilder: ((Task, List<Idea>, Set<Tool<*>>, List<KnowledgeWithScore>) -> String)? = null
    private var taskFactory: TaskFactory = DefaultTaskFactory
    private val parameterStrategies = mutableMapOf<String, ParameterStrategy>()
    private var outcomeContextBuilder: ((List<Outcome>) -> String)? = null
    private var knowledgeExtractor: ((Outcome, Task, Plan) -> Knowledge.FromOutcome)? = null

    /**
     * Configure perception settings.
     */
    fun perception(configure: PerceptionSettingsBuilder.() -> Unit) {
        val builder = PerceptionSettingsBuilder()
        builder.configure()
        perceptionContextBuilder = builder.contextBuilder
    }

    /**
     * Configure planning settings.
     */
    fun planning(configure: PlanningSettingsBuilder.() -> Unit) {
        val builder = PlanningSettingsBuilder()
        builder.configure()
        planningPromptBuilder = builder.customPrompt
        builder.taskFactory?.let { taskFactory = it }
    }

    /**
     * Configure execution settings.
     */
    fun execution(configure: ExecutionSettingsBuilder.() -> Unit) {
        val builder = ExecutionSettingsBuilder()
        builder.configure()
        parameterStrategies.putAll(builder.strategies)
    }

    /**
     * Configure outcome evaluation settings.
     */
    fun evaluation(configure: EvaluationSettingsBuilder.() -> Unit) {
        val builder = EvaluationSettingsBuilder()
        builder.configure()
        outcomeContextBuilder = builder.contextBuilder
    }

    /**
     * Configure knowledge extraction settings.
     */
    fun knowledge(configure: KnowledgeSettingsBuilder.() -> Unit) {
        val builder = KnowledgeSettingsBuilder()
        builder.configure()
        knowledgeExtractor = builder.extractor
    }

    fun build(): ReasoningSettings {
        return ReasoningSettings(
            executorId = executorId,
            agentRole = agentRole,
            availableTools = availableTools,
            executor = executor,
            perceptionContextBuilder = perceptionContextBuilder,
            planningPromptBuilder = planningPromptBuilder,
            taskFactory = taskFactory,
            parameterStrategies = parameterStrategies.toMap(),
            outcomeContextBuilder = outcomeContextBuilder,
            knowledgeExtractor = knowledgeExtractor,
        )
    }
}

class PerceptionSettingsBuilder {
    var contextBuilder: ((AgentState) -> String)? = null
}

class PlanningSettingsBuilder {
    var customPrompt: ((Task, List<Idea>, Set<Tool<*>>, List<KnowledgeWithScore>) -> String)? = null
    var taskFactory: TaskFactory? = null
}

class ExecutionSettingsBuilder {
    internal val strategies = mutableMapOf<String, ParameterStrategy>()

    fun registerStrategy(toolId: String, strategy: ParameterStrategy) {
        strategies[toolId] = strategy
    }
}

class EvaluationSettingsBuilder {
    var contextBuilder: ((List<Outcome>) -> String)? = null
}

class KnowledgeSettingsBuilder {
    var extractor: ((Outcome, Task, Plan) -> Knowledge.FromOutcome)? = null
}
