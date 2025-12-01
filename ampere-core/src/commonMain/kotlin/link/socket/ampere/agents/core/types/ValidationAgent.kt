package link.socket.ampere.agents.core.types

import kotlinx.datetime.Clock
import link.socket.ampere.agents.core.AgentConfiguration
import link.socket.ampere.agents.core.AgentId
import link.socket.ampere.agents.core.AutonomousAgent
import link.socket.ampere.agents.core.memory.AgentMemoryService
import link.socket.ampere.agents.core.memory.Knowledge
import link.socket.ampere.agents.core.memory.KnowledgeWithScore
import link.socket.ampere.agents.core.outcomes.ExecutionOutcome
import link.socket.ampere.agents.core.outcomes.Outcome
import link.socket.ampere.agents.core.reasoning.Idea
import link.socket.ampere.agents.core.reasoning.Perception
import link.socket.ampere.agents.core.reasoning.Plan
import link.socket.ampere.agents.core.states.AgentState
import link.socket.ampere.agents.core.tasks.Task
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool

/**
 * Validation Agent responsible for verifying code quality, correctness,
 * and adherence to standards.
 *
 * Enhanced with episodic memory—learns which validation approaches catch
 * the most issues and which types of problems are commonly missed.
 */
class ValidationAgent(
    override val id: AgentId,
    override val initialState: AgentState,
    override val agentConfiguration: AgentConfiguration,
    override val memoryService: AgentMemoryService? = null,
) : AutonomousAgent<AgentState>() {

    // LLM execution placeholders - would be implemented with actual LLM calls
    override val runLLMToEvaluatePerception: (perception: Perception<AgentState>) -> Idea =
        { _ -> Idea.blank }

    override val runLLMToPlan: (task: Task, ideas: List<Idea>) -> Plan =
        { task, _ -> Plan.ForTask(task) }

    override val runLLMToExecuteTask: (task: Task) -> Outcome =
        { _ -> Outcome.blank }

    override val runLLMToExecuteTool: (tool: Tool<*>, request: ExecutionRequest<*>) -> ExecutionOutcome =
        { _, _ -> ExecutionOutcome.blank }

    override val runLLMToEvaluateOutcomes: (outcomes: List<Outcome>) -> Idea =
        { _ -> Idea.blank }

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
                            status = link.socket.ampere.agents.core.status.TaskStatus.Pending,
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
                            status = link.socket.ampere.agents.core.status.TaskStatus.Pending,
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
                            status = link.socket.ampere.agents.core.status.TaskStatus.Pending,
                            description = "Syntax and compilation validation for ${task.description}",
                            assignedTo = task.assignedTo
                        )
                    )
                    planTasks.add(
                        Task.CodeChange(
                            id = generateUUID("${task.id}-style"),
                            status = link.socket.ampere.agents.core.status.TaskStatus.Pending,
                            description = "Code style and standards validation",
                            assignedTo = task.assignedTo
                        )
                    )
                    planTasks.add(
                        Task.CodeChange(
                            id = generateUUID("${task.id}-logic"),
                            status = link.socket.ampere.agents.core.status.TaskStatus.Pending,
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
