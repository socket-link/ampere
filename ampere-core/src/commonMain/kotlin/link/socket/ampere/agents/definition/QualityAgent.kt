package link.socket.ampere.agents.definition

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.definition.qa.QualityPrompts
import link.socket.ampere.agents.definition.qa.QualityState
import link.socket.ampere.agents.definition.qa.ValidationInsights
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.memory.AgentMemoryService
import link.socket.ampere.agents.domain.memory.KnowledgeWithScore
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.reasoning.AgentReasoning
import link.socket.ampere.agents.domain.reasoning.DefaultTaskFactory
import link.socket.ampere.agents.domain.reasoning.Idea
import link.socket.ampere.agents.domain.reasoning.Perception
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.reasoning.StepResult
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.executor.Executor
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool

/**
 * Quality Assurance Agent responsible for verifying code quality, correctness,
 * and adherence to standards.
 *
 * Enhanced with episodic memory—learns which validation approaches catch
 * the most issues and which types of problems are commonly missed.
 *
 * Uses the unified AgentReasoning infrastructure for all cognitive operations.
 */
class QualityAgent(
    override val agentConfiguration: AgentConfiguration,
    private val coroutineScope: CoroutineScope? = null,
    override val initialState: QualityState = QualityState.blank,
    private val executor: Executor = FunctionExecutor.create(),
    memoryServiceFactory: ((AgentId) -> AgentMemoryService)? = null,
) : AutonomousAgent<QualityState>() {

    override val id: AgentId = generateUUID("QualityAssuranceAgent")

    override val memoryService: AgentMemoryService? = memoryServiceFactory?.invoke(id)

    override val requiredTools: Set<Tool<*>> = emptySet()

    /**
     * QualityAgent uses ANALYTICAL cognitive affinity.
     *
     * This shapes the agent to break down problems systematically,
     * verify correctness, and trace logic - ideal for quality assurance,
     * testing, and code review.
     */
    override val affinity: CognitiveAffinity = CognitiveAffinity.ANALYTICAL

    // ========================================================================
    // Unified Reasoning - All cognitive logic in one place
    // ========================================================================

    private val reasoning = AgentReasoning.create(agentConfiguration, id) {
        agentRole = "Quality Assurance"
        availableTools = requiredTools
        this.executor = this@QualityAgent.executor

        perception {
            contextBuilder = { state -> QualityPrompts.perceptionContext(state as QualityState) }
        }

        planning {
            taskFactory = DefaultTaskFactory
            customPrompt = { task, ideas, tools, knowledge ->
                val insights = ValidationInsights.fromKnowledge(knowledge)
                QualityPrompts.planning(task, ideas, insights)
            }
        }

        execution {
            // No custom strategies yet - ready for QA-specific tools
        }

        evaluation {
            contextBuilder = { outcomes -> QualityPrompts.outcomeContext(outcomes) }
        }

        knowledge {
            extractor = { outcome, task, plan ->
                extractQualityKnowledge(outcome, task, plan)
            }
        }
    }

    // ========================================================================
    // PROPEL Cognitive Functions - Delegate to reasoning infrastructure
    // ========================================================================

    override val runLLMToEvaluatePerception: (perception: Perception<QualityState>) -> Idea =
        { perception ->
            runBlocking(Dispatchers.IO) {
                withTimeout(60000) {
                    reasoning.evaluatePerception(perception)
                }
            }
        }

    override val runLLMToPlan: (task: Task, ideas: List<Idea>) -> Plan =
        { task, ideas ->
            runBlocking(Dispatchers.IO) {
                withTimeout(60000) {
                    reasoning.generatePlan(task, ideas)
                }
            }
        }

    override val runLLMToExecuteTask: (task: Task) -> Outcome =
        { task ->
            runBlocking(Dispatchers.IO) {
                withTimeout(60000) {
                    executeTaskWithReasoning(task)
                }
            }
        }

    override val runLLMToExecuteTool: (tool: Tool<*>, request: ExecutionRequest<*>) -> ExecutionOutcome =
        { tool, request ->
            runBlocking(Dispatchers.IO) {
                withTimeout(60000) {
                    reasoning.executeTool(tool, request)
                }
            }
        }

    override val runLLMToEvaluateOutcomes: (outcomes: List<Outcome>) -> Idea =
        { outcomes ->
            runBlocking(Dispatchers.IO) {
                withTimeout(60000) {
                    reasoning.evaluateOutcomes(outcomes, memoryService).summaryIdea
                }
            }
        }

    override fun extractKnowledgeFromOutcome(
        outcome: Outcome,
        task: Task,
        plan: Plan,
    ): Knowledge.FromOutcome = extractQualityKnowledge(outcome, task, plan)

    override fun callLLM(prompt: String): String =
        runBlocking(Dispatchers.IO) {
            withTimeout(60000) {
                reasoning.callLLM(prompt)
            }
        }

    // ========================================================================
    // Custom Planning with Learned Validation Insights
    // ========================================================================

    /**
     * Custom planning that incorporates learned insights from past knowledge.
     *
     * The QualityAgent learns patterns like:
     * - Which validation checks catch the most issues
     * - Which types of bugs are frequently missed
     * - Effective validation order and prioritization
     */
    override suspend fun determinePlanForTask(
        task: Task,
        vararg ideas: Idea,
        relevantKnowledge: List<KnowledgeWithScore>,
    ): Plan {
        val insights = ValidationInsights.fromKnowledge(relevantKnowledge)
        val planTasks = mutableListOf<Task>()
        var estimatedComplexity = 5

        when (task) {
            is Task.CodeChange -> {
                // Prioritize validation checks based on past effectiveness
                insights.effectiveChecks.toList()
                    .sortedByDescending { it.second }
                    .forEach { (checkType, effectiveness) ->
                        planTasks.add(
                            Task.CodeChange(
                                id = generateUUID("${task.id}-validate-$checkType"),
                                status = TaskStatus.Pending,
                                description = "Run $checkType validation (${(effectiveness * 100).toInt()}% " +
                                    "effectiveness from past experience)",
                                assignedTo = task.assignedTo,
                            ),
                        )
                    }

                // Add extra validation for commonly missed issues
                insights.commonlyMissedIssues.forEach { issueType ->
                    planTasks.add(
                        Task.CodeChange(
                            id = generateUUID("${task.id}-check-${issueType.hashCode()}"),
                            status = TaskStatus.Pending,
                            description = "Extra validation for commonly missed: $issueType",
                            assignedTo = task.assignedTo,
                        ),
                    )
                }

                // If no specific insights, add standard validation tasks
                if (planTasks.isEmpty()) {
                    planTasks.add(
                        Task.CodeChange(
                            id = generateUUID("${task.id}-syntax"),
                            status = TaskStatus.Pending,
                            description = "Syntax and compilation validation for ${task.description}",
                            assignedTo = task.assignedTo,
                        ),
                    )
                    planTasks.add(
                        Task.CodeChange(
                            id = generateUUID("${task.id}-style"),
                            status = TaskStatus.Pending,
                            description = "Code style and standards validation",
                            assignedTo = task.assignedTo,
                        ),
                    )
                    planTasks.add(
                        Task.CodeChange(
                            id = generateUUID("${task.id}-logic"),
                            status = TaskStatus.Pending,
                            description = "Logic and correctness validation",
                            assignedTo = task.assignedTo,
                        ),
                    )
                }

                // Adjust complexity based on insights
                estimatedComplexity = when {
                    insights.commonlyMissedIssues.size > 3 -> 8
                    insights.effectiveChecks.values.average() > 0.8 -> 3
                    else -> 5
                }
            }
            else -> {
                planTasks.add(task)
            }
        }

        return Plan.ForTask(
            task = task,
            tasks = planTasks,
            estimatedComplexity = estimatedComplexity,
        )
    }

    // ========================================================================
    // Task Execution
    // ========================================================================

    private suspend fun executeTaskWithReasoning(task: Task): Outcome {
        if (task is Task.Blank) {
            return Outcome.blank
        }

        val plan = reasoning.generatePlan(task, emptyList())
        return reasoning.executePlan(plan) { step, _ ->
            StepResult.success(
                description = "Execute validation: ${step.id}",
                details = "Validation step completed",
            )
        }.outcome
    }

    // ========================================================================
    // Knowledge Extraction
    // ========================================================================

    private fun extractQualityKnowledge(
        outcome: Outcome,
        task: Task,
        plan: Plan,
    ): Knowledge.FromOutcome {
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
            timestamp = Clock.System.now(),
        )
    }

    companion object Companion {
        val SYSTEM_PROMPT = QualityPrompts.SYSTEM_PROMPT
    }
}
