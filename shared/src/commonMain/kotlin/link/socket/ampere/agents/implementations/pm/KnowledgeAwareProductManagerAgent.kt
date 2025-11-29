package link.socket.ampere.agents.implementations.pm

import kotlinx.datetime.Clock
import link.socket.ampere.agents.core.Agent
import link.socket.ampere.agents.core.AgentConfiguration
import link.socket.ampere.agents.core.AgentId
import link.socket.ampere.agents.core.AutonomousAgent
import link.socket.ampere.agents.core.memory.AgentMemoryService
import link.socket.ampere.agents.core.memory.Knowledge
import link.socket.ampere.agents.core.memory.KnowledgeWithScore
import link.socket.ampere.agents.core.memory.MemoryContext
import link.socket.ampere.agents.core.outcomes.ExecutionOutcome
import link.socket.ampere.agents.core.outcomes.Outcome
import link.socket.ampere.agents.core.expectations.Expectations
import link.socket.ampere.agents.core.reasoning.Idea
import link.socket.ampere.agents.core.reasoning.Perception
import link.socket.ampere.agents.core.reasoning.Plan
import link.socket.ampere.agents.core.states.AgentState
import link.socket.ampere.agents.core.tasks.Task
import link.socket.ampere.agents.core.tasks.TaskPriority
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool

/**
 * Product Manager Agent responsible for breaking down features into tasks
 * and coordinating implementation efforts.
 *
 * Now enhanced with episodic memory—learns which decomposition strategies
 * lead to successful implementations versus which create confusion or rework.
 */
class KnowledgeAwareProductManagerAgent(
    override val initialState: AgentState,
    override val agentConfiguration: AgentConfiguration,
    memoryServiceInstance: AgentMemoryService?,
) : AutonomousAgent<AgentState>() {

    override val id: AgentId = "KnowledgeAwareProductManager"

    override val memoryService: AgentMemoryService? = memoryServiceInstance

    /**
     * UPDATED: Now accepts past knowledge to inform planning.
     * The ProductManager learns patterns like:
     * - Features with comprehensive test plans succeed more often
     * - Breaking features into fewer than 5 tasks reduces overhead
     * - Similar features that worked well (reusable decomposition patterns)
     */
    override val runLLMToPlan: (task: Task, ideas: List<Idea>, relevantKnowledge: List<Knowledge>) -> Plan =
        { task, ideas, relevantKnowledge ->
            // Analyze past knowledge for actionable patterns
            val insights = analyzeKnowledge(relevantKnowledge)

            // Build plan tasks incorporating learned patterns
            val planTasks = mutableListOf<Task>()

            // If past knowledge shows test-first approaches succeeded frequently...
            if (insights.testFirstSuccessRate > 0.7) {
                planTasks.add(
                    Task.CodeChange(
                        id = "task-test-first-${Clock.System.now().toEpochMilliseconds()}",
                        status = link.socket.ampere.agents.core.status.TaskStatus.Pending,
                        description = "Define test specifications before implementation. " +
                            "Past knowledge shows ${(insights.testFirstSuccessRate * 100).toInt()}% " +
                            "success rate when tests are defined first: ${insights.testFirstLearnings}"
                    )
                )
            }

            // If past knowledge identifies common failure points, add preventive steps
            insights.commonFailures.forEach { (failurePoint, learnings) ->
                planTasks.add(
                    Task.CodeChange(
                        id = "task-validate-${failurePoint.hashCode()}-${Clock.System.now().toEpochMilliseconds()}",
                        status = link.socket.ampere.agents.core.status.TaskStatus.Pending,
                        description = "Validate against known failure pattern: $failurePoint. " +
                            "Past attempts failed here with learnings: $learnings"
                    )
                )
            }

            // Use learned optimal task count for decomposition
            val optimalTasks = insights.optimalTaskCount ?: 5
            planTasks.add(
                Task.CodeChange(
                    id = "task-decompose-${Clock.System.now().toEpochMilliseconds()}",
                    status = link.socket.ampere.agents.core.status.TaskStatus.Pending,
                    description = "Break feature into approximately $optimalTasks tasks. " +
                        "Past knowledge indicates $optimalTasks-task breakdowns had highest success rate: " +
                        insights.decompositionLearnings
                )
            )

            // Calculate complexity based on insights
            val complexity = when {
                insights.commonFailures.isNotEmpty() -> 8 // High complexity if known failures exist
                insights.testFirstSuccessRate > 0.7 -> 4 // Moderate complexity with good test patterns
                else -> 6 // Default moderate-high complexity
            }

            // Build final plan with complexity calibrated by past knowledge
            Plan.ForTask(
                task = task,
                tasks = planTasks,
                estimatedComplexity = complexity,
                expectations = Expectations.blank
            )
        }

    override val runLLMToEvaluatePerception: (perception: Perception<AgentState>) -> Idea =
        { perception ->
            // Simple perception evaluation
            Idea(
                id = "idea-${Clock.System.now().toEpochMilliseconds()}",
                description = "Evaluate current project state",
                hypothesis = "Based on current state, we can identify optimal decomposition strategy",
                timestamp = Clock.System.now()
            )
        }

    override val runLLMToExecuteTask: (task: Task) -> Outcome =
        { task ->
            // Simple task execution
            Outcome.Success(
                id = "outcome-${Clock.System.now().toEpochMilliseconds()}",
                result = "Executed task: ${task.description}",
                timestamp = Clock.System.now()
            )
        }

    override val runLLMToExecuteTool: (tool: Tool<*>, request: ExecutionRequest<*>) -> ExecutionOutcome =
        { tool, request ->
            ExecutionOutcome.Blank
        }

    override val runLLMToEvaluateOutcomes: (outcomes: List<Outcome>) -> Idea =
        { outcomes ->
            Idea.blank
        }

    /**
     * Extract actionable insights from past knowledge entries.
     * This is where the agent "thinks about" what it's learned.
     */
    private fun analyzeKnowledge(knowledge: List<Knowledge>): PlanningInsights {
        if (knowledge.isEmpty()) {
            return PlanningInsights() // No history, use defaults
        }

        // Analyze learnings for test-first patterns
        val testFirstKnowledge = knowledge.filter { k ->
            k.approach.contains("test", ignoreCase = true) ||
                k.learnings.contains("test", ignoreCase = true)
        }

        // Estimate success rate based on presence of positive learnings
        val testFirstSuccessRate = if (testFirstKnowledge.isNotEmpty()) {
            testFirstKnowledge.count { k ->
                k.learnings.contains("success", ignoreCase = true) ||
                    k.learnings.contains("worked", ignoreCase = true) ||
                    k.learnings.contains("prevented", ignoreCase = true)
            }.toDouble() / testFirstKnowledge.size
        } else 0.0

        // Extract common failure patterns from learnings
        val failures = knowledge
            .filter { it.learnings.contains("failed", ignoreCase = true) }
            .associate { k ->
                // Extract failure point from learnings (simplified—could parse more sophisticatedly)
                val failurePattern = k.learnings.substringBefore(".")
                failurePattern to k.learnings
            }

        // Determine optimal task count from successful decompositions
        val taskCountPattern = Regex("""(\d+)\s*tasks?""")
        val taskCounts = knowledge.mapNotNull { k ->
            taskCountPattern.find(k.learnings)?.groupValues?.get(1)?.toIntOrNull()
        }
        val optimalTaskCount = if (taskCounts.isNotEmpty()) {
            taskCounts.average().toInt()
        } else null

        return PlanningInsights(
            testFirstSuccessRate = testFirstSuccessRate,
            testFirstLearnings = testFirstKnowledge.firstOrNull()?.learnings ?: "",
            commonFailures = failures,
            optimalTaskCount = optimalTaskCount,
            decompositionLearnings = knowledge
                .firstOrNull { it.learnings.contains("task", ignoreCase = true) }
                ?.learnings ?: ""
        )
    }

    /**
     * Extract knowledge from completed task for future learning.
     * This is where the agent reflects: "What did I learn that would help next time?"
     */
    fun extractKnowledgeFromOutcome(
        outcome: Outcome,
        task: Task,
        plan: Plan
    ): Knowledge {
        val taskDescription = when (task) {
            is Task.CodeChange -> task.description
            is Task.Blank -> "blank task"
        }

        val approachDescription = buildString {
            append("Decomposed '$taskDescription' into ${plan.tasks.size} tasks. ")
            if (plan.tasks.any { t ->
                when (t) {
                    is Task.CodeChange -> t.description.contains("test", ignoreCase = true)
                    else -> false
                }
            }) {
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
                    }) {
                        append("Test-first approach prevented issues. ")
                    }
                }
                is Outcome.Failure -> {
                    append("Failure: ${outcome.error}. ")
                    append("Recommend adjusting decomposition strategy or adding validation steps. ")
                }
                else -> {
                    append("Partial completion. ")
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
     * Helper to extract ID from Knowledge for tracking.
     */
    private fun extractKnowledgeId(knowledge: Knowledge): String = when (knowledge) {
        is Knowledge.FromIdea -> knowledge.ideaId
        is Knowledge.FromOutcome -> knowledge.outcomeId
        is Knowledge.FromPerception -> knowledge.perceptionId
        is Knowledge.FromPlan -> knowledge.planId
        is Knowledge.FromTask -> knowledge.taskId
    }
}

/**
 * Structured insights extracted from past knowledge.
 * This is the "lesson summary" that informs future planning.
 */
private data class PlanningInsights(
    val testFirstSuccessRate: Double = 0.0,
    val testFirstLearnings: String = "",
    val commonFailures: Map<String, String> = emptyMap(),
    val optimalTaskCount: Int? = null,
    val decompositionLearnings: String = ""
)
