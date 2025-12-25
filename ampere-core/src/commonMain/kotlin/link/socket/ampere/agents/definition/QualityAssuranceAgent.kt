package link.socket.ampere.agents.definition

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.qa.QualityAssuranceState
import link.socket.ampere.agents.domain.concept.Idea
import link.socket.ampere.agents.domain.concept.Perception
import link.socket.ampere.agents.domain.concept.Plan
import link.socket.ampere.agents.domain.concept.knowledge.Knowledge
import link.socket.ampere.agents.domain.concept.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.concept.outcome.Outcome
import link.socket.ampere.agents.domain.concept.status.TaskStatus
import link.socket.ampere.agents.domain.concept.task.Task
import link.socket.ampere.agents.domain.config.AgentConfiguration
import link.socket.ampere.agents.domain.memory.AgentMemoryService
import link.socket.ampere.agents.domain.memory.KnowledgeWithScore
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.domain.util.toClientModelId

/**
 * Quality Assurance Agent responsible for verifying code quality, correctness,
 * and adherence to standards.
 *
 * Enhanced with episodic memory—learns which validation approaches catch
 * the most issues and which types of problems are commonly missed.
 */
class QualityAssuranceAgent(
    override val agentConfiguration: AgentConfiguration,
    override val initialState: QualityAssuranceState = QualityAssuranceState.blank,
    memoryServiceFactory: ((AgentId) -> AgentMemoryService)? = null,
) : AutonomousAgent<QualityAssuranceState>() {

    override val id: AgentId = generateUUID("QualityAssuranceAgent")

    override val memoryService: AgentMemoryService? = memoryServiceFactory?.invoke(id)

    override val runLLMToEvaluatePerception: (perception: Perception<QualityAssuranceState>) -> Idea =
        { perception -> evaluatePerception(perception) }

    override val runLLMToPlan: (task: Task, ideas: List<Idea>) -> Plan =
        { task, ideas -> generateValidationPlan(task, ideas) }

    override val runLLMToExecuteTask: (task: Task) -> Outcome =
        { task -> executeValidationTask(task) }

    override val runLLMToExecuteTool: (tool: Tool<*>, request: ExecutionRequest<*>) -> ExecutionOutcome =
        { tool, request -> executeToolWithLLM(tool, request) }

    override val runLLMToEvaluateOutcomes: (outcomes: List<Outcome>) -> Idea =
        { outcomes -> evaluateValidationOutcomes(outcomes) }

    /**
     * UPDATED: Now accepts past knowledge to inform validation planning.
     *
     * The ValidationAgent learns patterns like:
     * - Which validation checks catch the most issues
     * - Which types of bugs are frequently missed
     * - Effective validation order and prioritization
     */
    override suspend fun determinePlanForTask(
        task: Task,
        vararg ideas: Idea,
        relevantKnowledge: List<KnowledgeWithScore>
    ): Plan {
        // Analyze past knowledge for validation insights
        val insights = analyzeValidationKnowledge(relevantKnowledge)

        // Build validation plan incorporating learned patterns
        val planTasks = mutableListOf<Task>()
        var estimatedComplexity = 5 // Base complexity

        when (task) {
            is Task.CodeChange -> {
                // Prioritize validation checks based on past effectiveness
                insights.effectiveChecks.toList().sortedByDescending { it.second }.forEach { (checkType, effectiveness) ->
                    planTasks.add(
                        Task.CodeChange(
                            id = generateUUID("${task.id}-validate-${checkType}"),
                            status = TaskStatus.Pending,
                            description = "Run $checkType validation (${(effectiveness * 100).toInt()}% " +
                                "effectiveness from past experience)",
                            assignedTo = task.assignedTo
                        )
                    )
                }

                // Add validation for commonly missed issues
                insights.commonlyMissedIssues.forEach { issueType ->
                    planTasks.add(
                        Task.CodeChange(
                            id = generateUUID("${task.id}-check-${issueType.hashCode()}"),
                            status = TaskStatus.Pending,
                            description = "Extra validation for commonly missed: $issueType",
                            assignedTo = task.assignedTo
                        )
                    )
                }

                // If no specific insights, add standard validation tasks
                if (planTasks.isEmpty()) {
                    planTasks.add(
                        Task.CodeChange(
                            id = generateUUID("${task.id}-syntax"),
                            status = TaskStatus.Pending,
                            description = "Syntax and compilation validation for ${task.description}",
                            assignedTo = task.assignedTo
                        )
                    )
                    planTasks.add(
                        Task.CodeChange(
                            id = generateUUID("${task.id}-style"),
                            status = TaskStatus.Pending,
                            description = "Code style and standards validation",
                            assignedTo = task.assignedTo
                        )
                    )
                    planTasks.add(
                        Task.CodeChange(
                            id = generateUUID("${task.id}-logic"),
                            status = TaskStatus.Pending,
                            description = "Logic and correctness validation",
                            assignedTo = task.assignedTo
                        )
                    )
                }

                // Adjust complexity based on insights
                estimatedComplexity = when {
                    insights.commonlyMissedIssues.size > 3 -> 8 // Many past misses = higher complexity
                    insights.effectiveChecks.values.average() > 0.8 -> 3 // High effectiveness = lower complexity
                    else -> 5
                }
            }
            else -> {
                // Generic task handling
                planTasks.add(task)
            }
        }

        return Plan.ForTask(
            task = task,
            tasks = planTasks,
            estimatedComplexity = estimatedComplexity,
        )
    }

    /**
     * Extract validation insights from past knowledge entries.
     *
     * Analyzes which validation approaches were most effective and what issues
     * were commonly missed.
     */
    private fun analyzeValidationKnowledge(knowledge: List<KnowledgeWithScore>): ValidationInsights {
        if (knowledge.isEmpty()) {
            return ValidationInsights() // No history, use defaults
        }

        // Filter to high-relevance knowledge (score > 0.5)
        val relevantKnowledge = knowledge.filter { it.relevanceScore > 0.5 }

        // Extract effective validation check types
        val checkTypes = listOf("syntax", "style", "logic", "security", "performance", "testing")
        val effectiveChecks = checkTypes.mapNotNull { checkType ->
            val relevantChecks = relevantKnowledge.filter { scored ->
                scored.knowledge.approach.contains(checkType, ignoreCase = true) ||
                    scored.knowledge.learnings.contains(checkType, ignoreCase = true)
            }
            if (relevantChecks.isNotEmpty()) {
                // Effectiveness = average relevance score of checks mentioning this type
                checkType to relevantChecks.map { it.relevanceScore }.average()
            } else null
        }.toMap()

        // Extract commonly missed issue types from failure learnings
        val missedIssues = relevantKnowledge
            .filter {
                it.knowledge.learnings.contains("missed", ignoreCase = true) ||
                    it.knowledge.learnings.contains("undetected", ignoreCase = true) ||
                    it.knowledge is Knowledge.FromOutcome // Focus on outcome knowledge
            }
            .mapNotNull { scored ->
                // Extract issue type from learnings
                val learnings = scored.knowledge.learnings.lowercase()
                when {
                    learnings.contains("null") || learnings.contains("npe") -> "null pointer issues"
                    learnings.contains("boundary") || learnings.contains("edge case") -> "boundary conditions"
                    learnings.contains("concurrency") || learnings.contains("race") -> "concurrency issues"
                    learnings.contains("security") -> "security vulnerabilities"
                    learnings.contains("memory") || learnings.contains("leak") -> "memory issues"
                    else -> null
                }
            }
            .distinct()

        return ValidationInsights(
            effectiveChecks = effectiveChecks,
            commonlyMissedIssues = missedIssues
        )
    }

    /**
     * Extract knowledge from validation outcome for future learning.
     *
     * Captures which validation checks were performed, what they caught,
     * and what might have been missed.
     */
    override fun extractKnowledgeFromOutcome(
        outcome: Outcome,
        task: Task,
        plan: Plan
    ): Knowledge {
        val taskDescription = when (task) {
            is Task.CodeChange -> task.description
            else -> "Generic validation task ${task.id}"
        }

        val approachDescription = buildString {
            append("Performed ${plan.tasks.size} validation checks for '$taskDescription'. ")

            // Extract check types from plan tasks
            val checkTypes = plan.tasks.mapNotNull { t ->
                when (t) {
                    is Task.CodeChange -> {
                        val desc = t.description.lowercase()
                        when {
                            desc.contains("syntax") -> "syntax"
                            desc.contains("style") -> "style"
                            desc.contains("logic") -> "logic"
                            desc.contains("security") -> "security"
                            desc.contains("performance") -> "performance"
                            desc.contains("testing") -> "testing"
                            else -> null
                        }
                    }
                    else -> null
                }
            }.distinct()

            if (checkTypes.isNotEmpty()) {
                append("Checks included: ${checkTypes.joinToString(", ")}. ")
            }
            append("Complexity: ${plan.estimatedComplexity}")
        }

        val learnings = buildString {
            when (outcome) {
                is Outcome.Success -> {
                    append("Success: All ${plan.tasks.size} validation checks passed. ")
                    append("This validation strategy was effective for this type of task. ")
                    append("Recommend similar validation approach for future tasks.")
                }
                is Outcome.Failure -> {
                    append("Failure: Validation checks detected issues. ")
                    append("The validation approach successfully caught problems. ")
                    append("Recommend continuing to prioritize these check types.")
                }
                else -> {
                    append("Partial validation completion. ")
                    append("Some checks passed, others may need refinement. ")
                    append("Consider adjusting validation granularity.")
                }
            }
        }

        return Knowledge.FromOutcome(
            outcomeId = outcome.id,
            approach = approachDescription,
            learnings = learnings,
            timestamp = Clock.System.now()
        )
    }

    /**
     * Calls the LLM with the given prompt and returns the response.
     *
     * This uses the agent's configured AI provider and model to generate a response.
     *
     * @param prompt The prompt to send to the LLM
     * @return The LLM's response text
     */
    override fun callLLM(prompt: String): String = runBlocking {
        val systemMessage = "You are a quality assurance agent analyzing code quality and identifying issues. Respond only with valid JSON."
        val temperature = 0.3
        val maxTokens = 500

        val client = agentConfiguration.aiConfiguration.provider.client
        val model = agentConfiguration.aiConfiguration.model

        val messages = listOf(
            ChatMessage(
                role = ChatRole.System,
                content = systemMessage
            ),
            ChatMessage(
                role = ChatRole.User,
                content = prompt
            )
        )

        val request = ChatCompletionRequest(
            model = model.toClientModelId(),
            messages = messages,
            temperature = temperature,
            maxTokens = maxTokens
        )

        val completion = client.chatCompletion(request)
        completion.choices.firstOrNull()?.message?.content
            ?: throw IllegalStateException("No response from LLM")
    }

    /**
     * Calls the LLM with custom parameters.
     */
    private fun callLLMWithParams(
        prompt: String,
        systemMessage: String = "You are a quality assurance agent analyzing code quality and identifying issues. Respond only with valid JSON.",
        temperature: Double = 0.3,
        maxTokens: Int = 500
    ): String = runBlocking {
        val client = agentConfiguration.aiConfiguration.provider.client
        val model = agentConfiguration.aiConfiguration.model

        val messages = listOf(
            ChatMessage(
                role = ChatRole.System,
                content = systemMessage
            ),
            ChatMessage(
                role = ChatRole.User,
                content = prompt
            )
        )

        val request = ChatCompletionRequest(
            model = model.toClientModelId(),
            messages = messages,
            temperature = temperature,
            maxTokens = maxTokens
        )

        val completion = client.chatCompletion(request)
        completion.choices.firstOrNull()?.message?.content
            ?: throw IllegalStateException("No response from LLM")
    }

    /**
     * Evaluates the current perception and generates validation insights.
     */
    private fun evaluatePerception(perception: Perception<QualityAssuranceState>): Idea {
        // TODO: Implement proper perception evaluation with LLM
        return Idea.blank
    }

    /**
     * Generates a validation plan for the given task incorporating ideas.
     */
    private fun generateValidationPlan(task: Task, ideas: List<Idea>): Plan {
        // TODO: Implement proper validation planning with LLM
        return Plan.ForTask(task)
    }

    /**
     * Executes a validation task using LLM guidance.
     */
    private fun executeValidationTask(task: Task): Outcome {
        // TODO: Implement proper validation task execution with LLM
        return Outcome.blank
    }

    /**
     * Executes a tool with LLM-generated parameters.
     */
    private fun executeToolWithLLM(tool: Tool<*>, request: ExecutionRequest<*>): ExecutionOutcome {
        // TODO: Implement proper tool execution with LLM
        return ExecutionOutcome.blank
    }

    /**
     * Evaluates validation outcomes and generates learnings.
     */
    private fun evaluateValidationOutcomes(outcomes: List<Outcome>): Idea {
        // TODO: Implement proper validation outcome evaluation with LLM
        return Idea.blank
    }
}

/**
 * Structured validation insights extracted from past knowledge.
 *
 * Captures which validation approaches work best and what issues are commonly missed.
 */
private data class ValidationInsights(
    val effectiveChecks: Map<String, Double> = emptyMap(), // check type -> effectiveness score
    val commonlyMissedIssues: List<String> = emptyList()
)
