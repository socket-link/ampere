package link.socket.ampere.agents.definition

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.definition.project.ProjectAgentState
import link.socket.ampere.agents.definition.project.ProjectParams
import link.socket.ampere.agents.definition.project.ProjectPrompts
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.reasoning.AgentReasoning
import link.socket.ampere.agents.domain.reasoning.Idea
import link.socket.ampere.agents.domain.reasoning.KnowledgeExtractor
import link.socket.ampere.agents.domain.reasoning.Perception
import link.socket.ampere.agents.domain.reasoning.PerceptionContextBuilder
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.reasoning.StepContext
import link.socket.ampere.agents.domain.reasoning.StepResult
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.domain.task.PMTask
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.executor.Executor
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.agents.execution.tools.issue.BatchIssueCreateRequest

/**
 * Project Manager Agent - The Executive Function of AMPERE
 *
 * Acts as the "prefrontal cortex" of the multi-agent system, responsible for:
 * - Decomposing high-level goals into structured work breakdowns
 * - Creating issues in external systems (GitHub, JIRA, etc.)
 * - Assigning tasks to appropriate agents based on capabilities
 * - Monitoring task progress through the event stream
 * - Facilitating coordination when agents are blocked
 * - Escalating to humans when decisions exceed agent authority
 *
 * Uses the unified AgentReasoning infrastructure for all cognitive operations.
 */
open class ProjectAgent(
    override val agentConfiguration: AgentConfiguration,
    private val toolCreateIssues: Tool<ExecutionContext.IssueManagement>,
    private val toolAskHuman: Tool<ExecutionContext.NoChanges>,
    private val coroutineScope: CoroutineScope,
    override val initialState: AgentState = ProjectAgentState.blank,
    private val executor: Executor = FunctionExecutor.create(),
    memoryServiceFactory: ((AgentId) -> link.socket.ampere.agents.domain.memory.AgentMemoryService)? = null,
    reasoningOverride: AgentReasoning? = null,
    private val eventApiOverride: AgentEventApi? = null,
    private val observabilityScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val agentId: AgentId = generateUUID("ProjectManagerAgent"),
) : ObservableAgent<AgentState>(eventApiOverride, observabilityScope) {

    override val id: AgentId = agentId

    override val memoryService: link.socket.ampere.agents.domain.memory.AgentMemoryService? =
        memoryServiceFactory?.invoke(id)

    override val requiredTools: Set<Tool<*>> = setOf(toolCreateIssues, toolAskHuman)

    /**
     * ProjectAgent uses INTEGRATIVE cognitive affinity.
     *
     * This shapes the agent to understand the whole system, connect parts,
     * and bridge perspectives - ideal for project management, coordination,
     * and planning.
     */
    override val affinity: CognitiveAffinity = CognitiveAffinity.INTEGRATIVE

    // ========================================================================
    // Unified Reasoning - All cognitive logic in one place
    // ========================================================================

    private val reasoning: AgentReasoning = reasoningOverride ?: AgentReasoning.create(agentConfiguration, id) {
        agentRole = "Project Manager"
        availableTools = requiredTools
        this.executor = this@ProjectAgent.executor

        perception {
            contextBuilder = { state -> buildPerceptionContext(state) }
        }

        planning {
            taskFactory = PMTaskFactory
            customPrompt = { task, ideas, tools, knowledge ->
                buildPlanningPrompt(task, ideas)
            }
        }

        execution {
            registerStrategy(
                toolCreateIssues.id,
                ProjectParams.IssueCreation(
                    repository = "owner/repo",
                    availableAgents = emptyList(),
                    existingIssues = emptyList(),
                ),
            )
            registerStrategy(toolAskHuman.id, ProjectParams.HumanEscalation("Project Manager"))
        }

        evaluation {
            contextBuilder = { outcomes -> buildOutcomeContext(outcomes) }
        }

        knowledge {
            extractor = { outcome, task, plan ->
                KnowledgeExtractor.extract(outcome, task, plan) {
                    approach {
                        prefix("PM Task")
                        taskType(task)
                        planSize(plan)
                    }
                    learnings {
                        fromOutcome(outcome)
                    }
                }
            }
        }
    }

    // ========================================================================
    // PROPEL Cognitive Functions - Delegate to reasoning infrastructure
    // ========================================================================

    override val runLLMToEvaluatePerception: (perception: Perception<AgentState>) -> Idea =
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
    ): Knowledge.FromOutcome = reasoning.extractKnowledge(outcome, task, plan)

    override fun callLLM(prompt: String): String =
        runBlocking(Dispatchers.IO) {
            withTimeout(60000) {
                reasoning.callLLM(prompt)
            }
        }

    // ========================================================================
    // Task Execution - Uses PlanExecutor for orchestration
    // ========================================================================

    private suspend fun executeTaskWithReasoning(task: Task): Outcome {
        val plan = reasoning.generatePlan(task, emptyList())
        return reasoning.executePlan(plan) { step, context ->
            executeStep(step, context)
        }.outcome
    }

    private suspend fun executeStep(step: Task, context: StepContext): StepResult {
        return when (step) {
            is PMTask.CreateIssues -> {
                val outcome = reasoning.executeTool(
                    toolCreateIssues,
                    createExecutionRequest(step),
                )
                when (outcome) {
                    is ExecutionOutcome.IssueManagement.Success -> {
                        StepResult.success(
                            description = "Create ${outcome.response.created.size} issues",
                            details = outcome.response.created.joinToString { "#${it.issueNumber}" },
                            contextUpdates = mapOf(
                                "created_issues" to outcome.response.created.associate {
                                    it.localId to it.issueNumber
                                },
                            ),
                        )
                    }
                    is ExecutionOutcome.IssueManagement.Failure -> {
                        StepResult.failure(
                            description = "Create issues",
                            error = outcome.error.message,
                            isCritical = true,
                        )
                    }
                    else -> StepResult.failure(
                        description = "Create issues",
                        error = "Unexpected outcome: ${outcome::class.simpleName}",
                        isCritical = true,
                    )
                }
            }
            is PMTask.AssignTask -> {
                val issueMap: Map<String, Int>? = context.get("created_issues")
                val issueNumber = issueMap?.get(step.taskLocalId)
                StepResult.success(
                    description = "Assign task ${step.taskLocalId} to ${step.agentId}",
                    details = buildString {
                        append("Assigned task ${step.taskLocalId}")
                        issueNumber?.let { append(" (#$it)") }
                        append(" to ${step.agentId}: ${step.reasoning}")
                    },
                )
            }
            is PMTask.StartMonitoring -> {
                val issueMap: Map<String, Int>? = context.get("created_issues")
                val epicNumber = issueMap?.get(step.epicLocalId)
                StepResult.success(
                    description = "Start monitoring epic ${step.epicLocalId}",
                    details = buildString {
                        append("Started monitoring epic ${step.epicLocalId}")
                        epicNumber?.let { append(" (#$it)") }
                        append(" with ${step.tasks.size} tasks")
                    },
                )
            }
            else -> StepResult.skip(
                description = "Unknown step",
                reason = "Step type ${step::class.simpleName} not implemented",
            )
        }
    }

    private fun createExecutionRequest(step: PMTask.CreateIssues): ExecutionRequest<ExecutionContext.IssueManagement> {
        val now = kotlinx.datetime.Clock.System.now()
        val ticket = link.socket.ampere.agents.events.tickets.Ticket(
            id = "pm-ticket-${step.id}",
            title = "PM Task: ${step.id}",
            description = "Project Manager task execution",
            type = link.socket.ampere.agents.events.tickets.TicketType.TASK,
            priority = link.socket.ampere.agents.events.tickets.TicketPriority.MEDIUM,
            status = TicketStatus.InProgress,
            assignedAgentId = id,
            createdByAgentId = id,
            createdAt = now,
            updatedAt = now,
            dueDate = null,
        )
        return ExecutionRequest(
            context = ExecutionContext.IssueManagement(
                executorId = id,
                ticket = ticket,
                task = step,
                instructions = "Create issues for work breakdown",
                issueRequest = step.issueRequest,
                knowledgeFromPastMemory = emptyList(),
            ),
            constraints = link.socket.ampere.agents.execution.request.ExecutionConstraints(
                requireTests = false,
                requireLinting = false,
            ),
        )
    }

    // ========================================================================
    // Context Builders - Agent-specific customizations
    // ========================================================================

    private fun buildPerceptionContext(state: AgentState): String {
        val pmState = state as? ProjectAgentState ?: return "No PM state available"
        return PerceptionContextBuilder()
            .header("Project Manager State Analysis")
            .sectionIf(pmState.activeGoals.isNotEmpty(), "Active Goals") {
                pmState.activeGoals.forEach { goal ->
                    line("Goal: ${goal.description}")
                    field("Status", goal.status)
                    field("Priority", goal.priority)
                }
            }
            .sectionIf(pmState.workBreakdowns.isNotEmpty(), "Work Breakdowns") {
                pmState.workBreakdowns.forEach { breakdown ->
                    line("Epic: ${breakdown.epicTitle}")
                    field("Tasks", breakdown.tasks.size)
                    field("Completed", breakdown.tasks.count { it.status == "completed" })
                }
            }
            .sectionIf(pmState.blockedTasks.isNotEmpty(), "Blocked Tasks") {
                pmState.blockedTasks.forEach { taskId -> line("- $taskId") }
            }
            .sectionIf(pmState.pendingEscalations.isNotEmpty(), "Pending Escalations") {
                pmState.pendingEscalations.forEach { escalation ->
                    line("Decision: ${escalation.decision}")
                    field("Reason", escalation.reason)
                }
            }
            .build()
    }

    private fun buildPlanningPrompt(task: Task, ideas: List<Idea>): String = buildString {
        appendLine("You are the planning module of an autonomous Project Manager agent.")
        appendLine()
        appendLine("Goal: ${extractTaskDescription(task)}")
        appendLine()
        if (ideas.isNotEmpty()) {
            appendLine("Insights:")
            ideas.forEach { appendLine("- ${it.name}: ${it.description}") }
            appendLine()
        }
        appendLine("Available Actions: Create Issues, Assign Tasks, Start Monitoring, Escalate to Human")
        appendLine()
        appendLine("Create a JSON plan with steps to accomplish the goal.")
        appendLine(
            """{"steps": [{"description": "...", "toolToUse": "create_issues|assign_task|start_monitoring|ask_human", "requiresPreviousStep": true/false}], "estimatedComplexity": 1-10}""",
        )
    }

    private fun buildOutcomeContext(outcomes: List<Outcome>): String = buildString {
        appendLine("=== PM Outcome Analysis ===")
        appendLine(
            "Total: ${outcomes.size}, Success: ${outcomes.count { it is Outcome.Success }}, Failed: ${outcomes.count { it is Outcome.Failure }}",
        )
        outcomes.forEachIndexed { i, outcome ->
            when (outcome) {
                is ExecutionOutcome.IssueManagement.Success ->
                    appendLine("${i + 1}. ✓ Created ${outcome.response.created.size} issues")
                is ExecutionOutcome.IssueManagement.Failure ->
                    appendLine("${i + 1}. ✗ ${outcome.error.message}")
                else ->
                    appendLine("${i + 1}. ${outcome::class.simpleName}")
            }
        }
    }

    private fun extractTaskDescription(task: Task): String = when (task) {
        is PMTask.DecomposeGoal -> task.goal
        is PMTask.AssessProgress -> "Assess progress on epic ${task.epicId}"
        is PMTask.CreateIssues -> "Create ${task.issueRequest.issues.size} issues"
        is PMTask.AssignTask -> "Assign task ${task.taskLocalId}"
        is PMTask.StartMonitoring -> "Monitor epic ${task.epicLocalId}"
        is Task.CodeChange -> task.description
        is Task.Blank -> ""
        else -> "Task ${task.id}"
    }

    companion object Companion {
        val SYSTEM_PROMPT = ProjectPrompts.SYSTEM_PROMPT
    }
}

/**
 * Task factory for PM-specific task types.
 */
private object PMTaskFactory : link.socket.ampere.agents.domain.reasoning.TaskFactory {
    override fun create(id: String, description: String, toolId: String?, originalTask: Task): Task {
        return when (toolId) {
            "create_issues" -> PMTask.CreateIssues(
                id = id,
                status = TaskStatus.Pending,
                issueRequest = BatchIssueCreateRequest(
                    repository = "owner/repo",
                    issues = emptyList(),
                ),
            )
            "assign_task" -> PMTask.AssignTask(
                id = id,
                status = TaskStatus.Pending,
                taskLocalId = "pending",
                agentId = "pending",
                reasoning = description,
            )
            "start_monitoring" -> PMTask.StartMonitoring(
                id = id,
                status = TaskStatus.Pending,
                epicLocalId = "pending",
                tasks = emptyList(),
            )
            else -> Task.CodeChange(
                id = id,
                status = TaskStatus.Pending,
                description = description,
                assignedTo = null,
            )
        }
    }
}
