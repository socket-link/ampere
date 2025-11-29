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
 * Product Manager Agent responsible for breaking down features into tasks
 * and coordinating implementation efforts.
 *
 * Enhanced with episodic memory—learns which decomposition strategies
 * lead to successful implementations versus which create confusion or rework.
 */
class ProductManagerAgent(
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
     * UPDATED: Now accepts past knowledge to inform planning.
     *
     * The ProductManager learns patterns like:
     * - Features with comprehensive test plans succeed more often
     * - Breaking features into fewer than 5 tasks reduces overhead
     * - Similar features that worked well (reusable decomposition patterns)
     */
    override suspend fun determinePlanForTask(
        task: Task,
        vararg ideas: Idea,
        relevantKnowledge: List<KnowledgeWithScore>
    ): Plan {
        // Analyze past knowledge for actionable patterns
        val insights = analyzeKnowledge(relevantKnowledge)

        // Build plan incorporating learned patterns
        val planTasks = mutableListOf<Task>()
        var estimatedComplexity = 5 // Base complexity

        when (task) {
            is Task.CodeChange -> {
                // If past knowledge shows test-first approaches succeeded frequently...
                if (insights.testFirstSuccessRate > 0.7) {
                    planTasks.add(
                        Task.CodeChange(
                            id = generateUUID("${task.id}-test-spec"),
                            status = link.socket.ampere.agents.core.status.TaskStatus.Pending,
                            description = "Define test specifications before implementation " +
                                "(Past knowledge shows ${(insights.testFirstSuccessRate * 100).toInt()}% " +
                                "success rate: ${insights.testFirstLearnings.take(100)})",
                            assignedTo = task.assignedTo
                        )
                    )
                }

                // If past knowledge identifies common failure points, add preventive steps
                insights.commonFailures.forEach { (failurePoint, learnings) ->
                    planTasks.add(
                        Task.CodeChange(
                            id = generateUUID("${task.id}-validate-${failurePoint.hashCode()}"),
                            status = link.socket.ampere.agents.core.status.TaskStatus.Pending,
                            description = "Validate against known failure pattern: $failurePoint " +
                                "(Past learnings: ${learnings.take(100)})",
                            assignedTo = task.assignedTo
                        )
                    )
                }

                // Use learned optimal task count for decomposition
                val optimalTasks = insights.optimalTaskCount ?: 5
                planTasks.add(
                    Task.CodeChange(
                        id = generateUUID("${task.id}-implement"),
                        status = link.socket.ampere.agents.core.status.TaskStatus.Pending,
                        description = "Implement ${task.description} in approximately $optimalTasks subtasks " +
                            "(Based on past success patterns: ${insights.decompositionLearnings.take(100)})",
                        assignedTo = task.assignedTo
                    )
                )

                // Adjust complexity based on insights
                estimatedComplexity = when {
                    insights.commonFailures.isNotEmpty() -> 8 // More failures = higher complexity
                    insights.testFirstSuccessRate > 0.7 -> 4 // Test-first reduces complexity
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
     * Extract actionable insights from past knowledge entries.
     *
     * This is where the agent "thinks about" what it's learned.
     */
    private fun analyzeKnowledge(knowledge: List<KnowledgeWithScore>): PlanningInsights {
        if (knowledge.isEmpty()) {
            return PlanningInsights() // No history, use defaults
        }

        // Filter to high-relevance knowledge (score > 0.5)
        val relevantKnowledge = knowledge.filter { it.relevanceScore > 0.5 }

        // Analyze learnings for test-first patterns
        val testFirstKnowledge = relevantKnowledge.filter { scored ->
            scored.knowledge.approach.contains("test", ignoreCase = true) ||
                scored.knowledge.learnings.contains("test", ignoreCase = true)
        }
        val testFirstSuccessRate = if (testFirstKnowledge.isNotEmpty()) {
            // Assuming higher relevance scores correlate with successful approaches
            testFirstKnowledge.map { it.relevanceScore }.average()
        } else 0.0

        // Extract common failure patterns from learnings
        val failures = relevantKnowledge
            .filter {
                it.knowledge.learnings.contains("failed", ignoreCase = true) ||
                    it.knowledge.learnings.contains("failure", ignoreCase = true)
            }
            .associate { scored ->
                // Extract failure point from learnings (simplified pattern matching)
                val failurePattern = scored.knowledge.learnings
                    .substringAfter("failure", "")
                    .substringAfter("failed", "")
                    .substringBefore(".")
                    .trim()
                    .take(50)
                failurePattern to scored.knowledge.learnings
            }

        // Determine optimal task count from successful decompositions
        val taskCountPattern = Regex("""(\d+)\s*tasks?""")
        val taskCounts = relevantKnowledge.mapNotNull { scored ->
            taskCountPattern.find(scored.knowledge.learnings)?.groupValues?.get(1)?.toIntOrNull()
        }
        val optimalTaskCount = if (taskCounts.isNotEmpty()) {
            taskCounts.average().toInt()
        } else null

        return PlanningInsights(
            testFirstSuccessRate = testFirstSuccessRate,
            testFirstLearnings = testFirstKnowledge.firstOrNull()?.knowledge?.learnings ?: "",
            commonFailures = failures,
            optimalTaskCount = optimalTaskCount,
            decompositionLearnings = relevantKnowledge
                .firstOrNull { it.knowledge.learnings.contains("task", ignoreCase = true) }
                ?.knowledge?.learnings ?: ""
        )
    }

    /**
     * Extract knowledge from completed task for future learning.
     *
     * This is where the agent reflects: "What did I learn that would help next time?"
     */
    override fun extractKnowledgeFromOutcome(
        outcome: Outcome,
        task: Task,
        plan: Plan
    ): Knowledge {
        val taskDescription = when (task) {
            is Task.CodeChange -> task.description
            else -> "Generic task ${task.id}"
        }

        val approachDescription = buildString {
            append("Decomposed '$taskDescription' into ${plan.tasks.size} tasks. ")
            if (plan.tasks.any { t ->
                    when (t) {
                        is Task.CodeChange -> t.description.contains("test", ignoreCase = true)
                        else -> false
                    }
                }
            ) {
                append("Used test-first approach. ")
            }
            append("Complexity: ${plan.estimatedComplexity}")
        }

        val learnings = buildString {
            when (outcome) {
                is Outcome.Success -> {
                    append("Success: ${plan.tasks.size}-task decomposition worked well. ")
                    if (plan.tasks.any { t ->
                            when (t) {
                                is Task.CodeChange -> t.description.contains("test", ignoreCase = true)
                                else -> false
                            }
                        }
                    ) {
                        append("Test-first approach prevented issues. ")
                    }
                    append("Recommend similar decomposition for future tasks.")
                }
                is Outcome.Failure -> {
                    append("Failure occurred during execution. ")
                    append("Recommend adjusting decomposition strategy or adding validation steps. ")
                    append("Consider breaking into ${plan.tasks.size + 2} tasks instead.")
                }
                else -> {
                    append("Partial completion with ${plan.tasks.size} tasks. ")
                    append("May need to refine task granularity.")
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
 * Structured insights extracted from past knowledge.
 *
 * This is the "lesson summary" that informs future planning.
 */
private data class PlanningInsights(
    val testFirstSuccessRate: Double = 0.0,
    val testFirstLearnings: String = "",
    val commonFailures: Map<String, String> = emptyMap(),
    val optimalTaskCount: Int? = null,
    val decompositionLearnings: String = ""
)
