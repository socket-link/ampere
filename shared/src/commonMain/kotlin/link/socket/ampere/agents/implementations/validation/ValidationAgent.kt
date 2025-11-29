package link.socket.ampere.agents.implementations.validation

import kotlinx.datetime.Clock
import link.socket.ampere.agents.core.AgentConfiguration
import link.socket.ampere.agents.core.AgentId
import link.socket.ampere.agents.core.AutonomousAgent
import link.socket.ampere.agents.core.expectations.Expectations
import link.socket.ampere.agents.core.memory.AgentMemoryService
import link.socket.ampere.agents.core.memory.Knowledge
import link.socket.ampere.agents.core.outcomes.ExecutionOutcome
import link.socket.ampere.agents.core.outcomes.Outcome
import link.socket.ampere.agents.core.reasoning.Idea
import link.socket.ampere.agents.core.reasoning.Perception
import link.socket.ampere.agents.core.reasoning.Plan
import link.socket.ampere.agents.core.states.AgentState
import link.socket.ampere.agents.core.status.TaskStatus
import link.socket.ampere.agents.core.tasks.Task
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool

/**
 * Validation Agent responsible for validating code quality, test coverage,
 * and catching issues before deployment.
 *
 * Enhanced with episodic memory—learns which validation approaches catch
 * the most issues and which failure patterns recur frequently.
 */
class ValidationAgent(
    override val initialState: AgentState,
    override val agentConfiguration: AgentConfiguration,
    memoryServiceInstance: AgentMemoryService?,
) : AutonomousAgent<AgentState>() {

    override val id: AgentId = "ValidationAgent"

    override val memoryService: AgentMemoryService? = memoryServiceInstance

    /**
     * Planning with memory: learns which validation checks are most valuable.
     * The ValidationAgent learns patterns like:
     * - Which types of tests catch the most bugs
     * - Which code patterns lead to failures
     * - Which validation steps can be skipped safely
     */
    override val runLLMToPlan: (task: Task, ideas: List<Idea>, relevantKnowledge: List<Knowledge>) -> Plan =
        { task, ideas, relevantKnowledge ->
            // Analyze past knowledge for validation insights
            val insights = analyzeValidationKnowledge(relevantKnowledge)

            // Build validation plan based on learned priorities
            val validationTasks = mutableListOf<Task>()

            // Prioritize validation approaches that historically caught issues
            insights.mostEffectiveChecks.forEach { (checkType, effectiveness) ->
                validationTasks.add(
                    Task.CodeChange(
                        id = "validation-${checkType.lowercase()}-${Clock.System.now().toEpochMilliseconds()}",
                        status = TaskStatus.Pending,
                        description = "Run $checkType validation. " +
                            "Historical effectiveness: ${(effectiveness * 100).toInt()}% of issues caught by this check. " +
                            insights.checkLearnings[checkType].orEmpty()
                    )
                )
            }

            // Add checks for known recurring failure patterns
            insights.recurringFailurePatterns.forEach { (pattern, count) ->
                validationTasks.add(
                    Task.CodeChange(
                        id = "check-pattern-${pattern.hashCode()}-${Clock.System.now().toEpochMilliseconds()}",
                        status = TaskStatus.Pending,
                        description = "Validate against recurring failure pattern: $pattern. " +
                            "This pattern has appeared $count times in past validations."
                    )
                )
            }

            // If no knowledge available, use default comprehensive validation
            if (validationTasks.isEmpty()) {
                validationTasks.addAll(
                    listOf(
                        Task.CodeChange(
                            id = "validation-tests-${Clock.System.now().toEpochMilliseconds()}",
                            status = TaskStatus.Pending,
                            description = "Run unit tests. No past knowledge available, using default checks."
                        ),
                        Task.CodeChange(
                            id = "validation-lint-${Clock.System.now().toEpochMilliseconds()}",
                            status = TaskStatus.Pending,
                            description = "Run linting checks. No past knowledge available, using default checks."
                        ),
                        Task.CodeChange(
                            id = "validation-types-${Clock.System.now().toEpochMilliseconds()}",
                            status = TaskStatus.Pending,
                            description = "Run type checking. No past knowledge available, using default checks."
                        )
                    )
                )
            }

            // Complexity based on number of known failure patterns
            val complexity = when {
                insights.recurringFailurePatterns.size > 5 -> 9 // Many known issues
                insights.recurringFailurePatterns.size > 2 -> 6 // Some known issues
                validationTasks.size > 5 -> 5 // Many checks needed
                else -> 3 // Standard validation
            }

            Plan.ForTask(
                task = task,
                tasks = validationTasks,
                estimatedComplexity = complexity,
                expectations = Expectations.blank
            )
        }

    override val runLLMToEvaluatePerception: (perception: Perception<AgentState>) -> Idea =
        { perception ->
            Idea(
                id = "idea-${Clock.System.now().toEpochMilliseconds()}",
                description = "Evaluate code quality and test coverage",
                hypothesis = "Based on current state, we can identify optimal validation strategy",
                timestamp = Clock.System.now()
            )
        }

    override val runLLMToExecuteTask: (task: Task) -> Outcome =
        { task ->
            Outcome.Success(
                id = "outcome-${Clock.System.now().toEpochMilliseconds()}",
                result = "Validation completed for task: ${getTaskDescription(task)}",
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
     * Analyze past validation knowledge to extract actionable insights.
     */
    private fun analyzeValidationKnowledge(knowledge: List<Knowledge>): ValidationInsights {
        if (knowledge.isEmpty()) {
            return ValidationInsights()
        }

        // Track which types of checks were most effective
        val checkEffectiveness = mutableMapOf<String, Double>()
        val checkLearnings = mutableMapOf<String, String>()

        knowledge.forEach { k ->
            // Extract check types from approach descriptions
            listOf("unit test", "integration test", "lint", "type check", "security scan").forEach { checkType ->
                if (k.approach.contains(checkType, ignoreCase = true)) {
                    // Estimate effectiveness based on success mentions in learnings
                    val isEffective = k.learnings.contains("caught", ignoreCase = true) ||
                        k.learnings.contains("found", ignoreCase = true) ||
                        k.learnings.contains("detected", ignoreCase = true)

                    val currentScore = checkEffectiveness[checkType] ?: 0.0
                    checkEffectiveness[checkType] = currentScore + if (isEffective) 1.0 else 0.0

                    if (isEffective) {
                        checkLearnings[checkType] = k.learnings
                    }
                }
            }
        }

        // Normalize effectiveness scores
        val totalKnowledge = knowledge.size.toDouble()
        val normalizedEffectiveness = checkEffectiveness.mapValues { (_, score) ->
            score / totalKnowledge
        }.toList().sortedByDescending { it.second }.take(5)

        // Extract recurring failure patterns
        val failurePatterns = mutableMapOf<String, Int>()
        knowledge.forEach { k ->
            if (k.learnings.contains("failed", ignoreCase = true) ||
                k.learnings.contains("error", ignoreCase = true)
            ) {
                // Extract the failure description (simplified pattern extraction)
                val pattern = k.learnings.substringBefore(".").take(100)
                failurePatterns[pattern] = (failurePatterns[pattern] ?: 0) + 1
            }
        }

        return ValidationInsights(
            mostEffectiveChecks = normalizedEffectiveness.toMap(),
            checkLearnings = checkLearnings,
            recurringFailurePatterns = failurePatterns.filter { it.value > 1 }
        )
    }

    /**
     * Extract knowledge from validation outcomes.
     */
    fun extractKnowledgeFromValidation(
        outcome: Outcome,
        task: Task,
        issuesFound: List<String> = emptyList()
    ): Knowledge {
        val taskDescription = getTaskDescription(task)

        val approachDescription = buildString {
            append("Validated '$taskDescription'. ")
            if (issuesFound.isNotEmpty()) {
                append("Ran ${issuesFound.size} validation checks. ")
            }
        }

        val learnings = buildString {
            when (outcome) {
                is Outcome.Success -> {
                    if (issuesFound.isEmpty()) {
                        append("Success: All validations passed. No issues found.")
                    } else {
                        append("Success: Validation caught ${issuesFound.size} issues: ")
                        issuesFound.take(3).forEach { issue ->
                            append("$issue; ")
                        }
                    }
                }
                is Outcome.Failure -> {
                    append("Failure: Validation process failed: ${outcome.error}. ")
                    append("May need to update validation tooling or configuration.")
                }
                else -> {
                    append("Partial validation. Some checks may not have completed.")
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

    private fun getTaskDescription(task: Task): String = when (task) {
        is Task.CodeChange -> task.description
        is Task.Blank -> "blank task"
    }
}

/**
 * Insights extracted from past validation knowledge.
 */
private data class ValidationInsights(
    val mostEffectiveChecks: Map<String, Double> = emptyMap(),
    val checkLearnings: Map<String, String> = emptyMap(),
    val recurringFailurePatterns: Map<String, Int> = emptyMap()
)
