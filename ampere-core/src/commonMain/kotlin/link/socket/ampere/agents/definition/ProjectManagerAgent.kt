package link.socket.ampere.agents.definition

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.definition.pm.ProjectManagerState
import link.socket.ampere.agents.domain.concept.Idea
import link.socket.ampere.agents.domain.concept.Perception
import link.socket.ampere.agents.domain.concept.Plan
import link.socket.ampere.agents.domain.concept.knowledge.Knowledge
import link.socket.ampere.agents.domain.concept.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.concept.outcome.Outcome
import link.socket.ampere.agents.domain.concept.outcome.StepOutcome
import link.socket.ampere.agents.domain.concept.task.PMTask
import link.socket.ampere.agents.domain.concept.task.Task
import link.socket.ampere.agents.domain.config.AgentConfiguration
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.executor.Executor
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool

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
 * This agent transforms vague intentions into structured execution, maintaining
 * working memory of project state and flexibly switching between planning and
 * monitoring modes based on system state.
 *
 * Personality Characteristics:
 * - Directness: 0.8 (Clear, concise communication)
 * - Creativity: 0.5 (Balanced between structured and innovative)
 * - Verbosity: 0.6 (Moderately detailed)
 * - Formality: 0.7 (Professional but approachable)
 */
open class ProjectManagerAgent(
    override val agentConfiguration: AgentConfiguration,
    private val toolCreateIssues: Tool<ExecutionContext.IssueManagement>,
    private val toolAskHuman: Tool<ExecutionContext.NoChanges>,
    private val coroutineScope: CoroutineScope,
    override val initialState: AgentState = ProjectManagerState.blank,
    private val executor: Executor = FunctionExecutor.create(),
    memoryServiceFactory: ((AgentId) -> link.socket.ampere.agents.domain.memory.AgentMemoryService)? = null,
) : AutonomousAgent<AgentState>() {

    override val id: AgentId = generateUUID("ProjectManagerAgent")

    override val memoryService: link.socket.ampere.agents.domain.memory.AgentMemoryService? =
        memoryServiceFactory?.invoke(id)

    override val requiredTools: Set<Tool<*>> =
        setOf(toolCreateIssues, toolAskHuman)

    // ========================================================================
    // PROPEL Cognitive Functions - Neural Agent Lambda Properties
    // ========================================================================

    /**
     * Evaluates perception to generate ideas for next actions.
     *
     * In the PM context, this involves:
     * - Analyzing current project state
     * - Identifying goals needing decomposition
     * - Detecting blocked tasks
     * - Recognizing coordination needs
     */
    override val runLLMToEvaluatePerception: (perception: Perception<AgentState>) -> Idea =
        { perception ->
            runPerceptionEvaluation(perception)
        }

    /**
     * Creates a plan for accomplishing a task.
     *
     * For PM, this involves:
     * - Breaking down goals into epics and tasks
     * - Identifying dependencies
     * - Determining agent assignments
     * - Planning coordination meetings
     */
    override val runLLMToPlan: (task: Task, ideas: List<Idea>) -> Plan =
        { task, ideas ->
            generatePlan(task, ideas)
        }

    /**
     * Executes a single task.
     *
     * PM tasks include:
     * - Creating issue hierarchies
     * - Assigning work to agents
     * - Updating issue status
     * - Initiating meetings
     */
    override val runLLMToExecuteTask: (task: Task) -> Outcome =
        { task ->
            runBlocking {
                executeTask(task)
            }
        }

    /**
     * Executes a tool with LLM-generated parameters.
     *
     * PM uses this for:
     * - ToolCreateIssues: Generate issue structure from goals
     * - ToolAskHuman: Escalate decisions beyond authority
     */
    override val runLLMToExecuteTool: (tool: Tool<*>, request: ExecutionRequest<*>) -> ExecutionOutcome =
        { tool, request ->
            runBlocking {
                executeToolWithLLMGeneratedParameters(tool, request)
            }
        }

    /**
     * Evaluates execution outcomes to generate learnings.
     *
     * PM learns from:
     * - Effective work breakdown structures
     * - Successful agent assignments
     * - Coordination patterns that worked
     * - Escalation decisions
     */
    override val runLLMToEvaluateOutcomes: (outcomes: List<Outcome>) -> Idea =
        { outcomes ->
            evaluateOutcomesAndGenerateLearnings(outcomes)
        }

    // ========================================================================
    // Knowledge Extraction
    // ========================================================================

    /**
     * Extracts knowledge from completed task outcomes.
     *
     * Captures learnings about:
     * - Optimal task granularity for different goal types
     * - Agent capability matching effectiveness
     * - Coordination overhead vs. parallelization benefits
     * - When to escalate vs. proceed autonomously
     */
    override fun extractKnowledgeFromOutcome(
        outcome: Outcome,
        task: Task,
        plan: Plan,
    ): Knowledge.FromOutcome {
        // Build approach description from task and plan
        val approach = buildString {
            append("PM Task: ${task}")

            if (plan is Plan.ForTask && plan.tasks.isNotEmpty()) {
                append(" (${plan.tasks.size} steps)")
            }
        }

        // Extract learnings based on outcome type
        val learnings = when (outcome) {
            is ExecutionOutcome.IssueManagement.Success -> {
                buildString {
                    appendLine("✓ Issue management succeeded")
                    appendLine("Issues created: ${outcome.response.created.size}")
                    outcome.response.created.take(3).forEach { issue ->
                        appendLine("  - Issue #${issue.issueNumber}")
                    }
                    if (outcome.response.created.size > 3) {
                        appendLine("  ... and ${outcome.response.created.size - 3} more")
                    }

                    val duration = outcome.executionEndTimestamp - outcome.executionStartTimestamp
                    appendLine("Duration: $duration")

                    appendLine()
                    appendLine("This work breakdown structure was effective for this goal type.")
                }
            }
            is ExecutionOutcome.IssueManagement.Failure -> {
                buildString {
                    appendLine("✗ Issue management failed")
                    appendLine("Error: ${outcome.error.message}")

                    val duration = outcome.executionEndTimestamp - outcome.executionStartTimestamp
                    appendLine("Duration before failure: $duration")

                    appendLine()
                    appendLine("Review issue structure or external system configuration.")
                }
            }
            is ExecutionOutcome.NoChanges.Success -> {
                buildString {
                    appendLine("✓ Human escalation completed")
                    appendLine("Response: ${outcome.message}")

                    val duration = outcome.executionEndTimestamp - outcome.executionStartTimestamp
                    appendLine("Duration: $duration")

                    appendLine()
                    appendLine("Escalation provided necessary guidance for decision-making.")
                }
            }
            is ExecutionOutcome.NoChanges.Failure -> {
                buildString {
                    appendLine("✗ Escalation failed")
                    appendLine("Error: ${outcome.message}")

                    appendLine()
                    appendLine("Consider alternative escalation mechanisms or proceed with best judgment.")
                }
            }
            else -> {
                "Outcome: $outcome"
            }
        }

        return Knowledge.FromOutcome(
            outcomeId = outcome.id,
            approach = approach,
            learnings = learnings,
            timestamp = kotlinx.datetime.Clock.System.now(),
        )
    }

    // ========================================================================
    // Private Implementation Methods (Stubs)
    // ========================================================================

    /**
     * Stub: Run perception evaluation using LLM.
     *
     * TODO: Implement full perception analysis:
     * - Analyze active goals and their status
     * - Identify goals needing decomposition
     * - Detect blocked tasks requiring attention
     * - Recognize coordination opportunities
     * - Generate ideas for next actions
     */
    private fun runPerceptionEvaluation(perception: Perception<AgentState>): Idea {
        // Stub implementation - return a placeholder idea
        return Idea(
            name = "Evaluate project state",
            description = "Stub: Evaluate project state and determine next actions. Full LLM-based perception evaluation pending.",
        )
    }

    /**
     * Stub: Generate a plan for the given task using LLM.
     *
     * TODO: Implement full planning:
     * - Break down goals into epics and tasks
     * - Identify task dependencies
     * - Determine optimal agent assignments
     * - Plan coordination meetings if needed
     * - Recall relevant past knowledge about similar breakdowns
     */
    private fun generatePlan(task: Task, ideas: List<Idea>): Plan {
        // Stub implementation - return a minimal plan
        return Plan.ForTask(
            task = task,
            tasks = listOf(task),
            estimatedComplexity = 5,
        )
    }

    /**
     * Execute a task by routing to executePlan if it's a plan-based task.
     *
     * This delegates to executePlan for structured execution with plan tracking.
     */
    private suspend fun executeTask(task: Task): Outcome {
        // For now, just call executePlan with a simple plan containing the task
        val plan = Plan.ForTask(
            task = task,
            tasks = listOf(task),
            estimatedComplexity = 3,
        )
        return executePlan(plan)
    }

    /**
     * Execute a plan by iterating through steps, tracking outcomes, and handling failures.
     *
     * This is the core execution engine for the PM agent. It:
     * 1. Iterates through plan steps sequentially
     * 2. Emits events for each step start/completion
     * 3. Routes to appropriate execution handlers (tools vs actions)
     * 4. Tracks created issue numbers for subsequent steps
     * 5. Stops on critical failures
     * 6. Returns overall outcome with summary
     *
     * @param plan The plan to execute
     * @return Outcome summarizing the plan execution
     */
    override suspend fun executePlan(plan: Plan): Outcome {
        val planId = generateUUID("plan")
        val startTime = kotlinx.datetime.Clock.System.now()
        val stepOutcomes = mutableListOf<StepOutcome>()
        val createdIssueMap = mutableMapOf<String, Int>() // localId → issueNumber

        if (plan !is Plan.ForTask || plan.tasks.isEmpty()) {
            // No steps to execute
            val taskId = if (plan is Plan.ForTask) plan.task.id else ""
            return ExecutionOutcome.NoChanges.Success(
                executorId = id,
                ticketId = "",
                taskId = taskId,
                message = "Plan has no steps to execute",
                executionStartTimestamp = startTime,
                executionEndTimestamp = kotlinx.datetime.Clock.System.now(),
            )
        }

        // Iterate through plan steps
        for ((index, step) in plan.tasks.withIndex()) {
            val stepStartTime = kotlinx.datetime.Clock.System.now()

            // Emit step started event
            // TODO: Emit via event bus when integrated
            // eventBus.emit(PlanEvent.PlanStepStarted(...))

            // Execute the step based on its type
            val stepOutcome = try {
                when (step) {
                    is PMTask.CreateIssues -> executeToolStep(step, createdIssueMap)
                    is PMTask.AssignTask -> executeActionStep(step, createdIssueMap)
                    is PMTask.StartMonitoring -> executeActionStep(step, createdIssueMap)
                    else -> {
                        // Unknown step type - skip
                        StepOutcome.Skipped(
                            id = step.id,
                            stepDescription = "Unknown step type: ${step::class.simpleName}",
                            timestamp = stepStartTime,
                            reason = "Step type not yet implemented",
                        )
                    }
                }
            } catch (e: Exception) {
                // Step execution threw exception - create failure outcome
                StepOutcome.Failure(
                    id = step.id,
                    stepDescription = "Execute step ${step.id}",
                    startTimestamp = stepStartTime,
                    endTimestamp = kotlinx.datetime.Clock.System.now(),
                    error = "Exception during step execution: ${e.message}",
                    isCritical = true,
                )
            }

            stepOutcomes.add(stepOutcome)

            // Emit step completed event
            // TODO: Emit via event bus when integrated
            // eventBus.emit(PlanEvent.PlanStepCompleted(...))

            // Stop on critical failure
            if (stepOutcome is StepOutcome.Failure && stepOutcome.isCritical) {
                // Mark remaining steps as skipped
                for (remainingIndex in (index + 1) until plan.tasks.size) {
                    val remainingStep = plan.tasks[remainingIndex]
                    stepOutcomes.add(
                        StepOutcome.Skipped(
                            id = remainingStep.id,
                            stepDescription = "Execute step ${remainingStep.id}",
                            timestamp = kotlinx.datetime.Clock.System.now(),
                            reason = "Skipped due to critical failure in step ${index + 1}",
                        ),
                    )
                }
                break
            }
        }

        // Build overall outcome
        val endTime = kotlinx.datetime.Clock.System.now()
        val successCount = stepOutcomes.count { it is StepOutcome.Success }
        val partialCount = stepOutcomes.count { it is StepOutcome.PartialSuccess }
        val failureCount = stepOutcomes.count { it is StepOutcome.Failure }
        val skippedCount = stepOutcomes.count { it is StepOutcome.Skipped }

        val summary = buildString {
            appendLine("Plan execution complete:")
            appendLine("  ✓ Success: $successCount")
            if (partialCount > 0) {
                appendLine("  ⚠ Partial: $partialCount")
            }
            if (failureCount > 0) {
                appendLine("  ✗ Failure: $failureCount")
            }
            if (skippedCount > 0) {
                appendLine("  ⊘ Skipped: $skippedCount")
            }
            if (createdIssueMap.isNotEmpty()) {
                appendLine()
                appendLine("Created ${createdIssueMap.size} issues")
            }
        }

        return if (failureCount > 0) {
            ExecutionOutcome.NoChanges.Failure(
                executorId = id,
                ticketId = "",
                taskId = plan.task.id,
                executionStartTimestamp = startTime,
                executionEndTimestamp = endTime,
                message = summary,
            )
        } else {
            ExecutionOutcome.NoChanges.Success(
                executorId = id,
                ticketId = "",
                taskId = plan.task.id,
                executionStartTimestamp = startTime,
                executionEndTimestamp = endTime,
                message = summary,
            )
        }
    }

    /**
     * Execute a tool step (e.g., ToolCreateIssues).
     *
     * Invokes the tool through the executor, processes the outcome, and updates
     * the issue mapping.
     *
     * @param step The CreateIssues step to execute
     * @param createdIssueMap Map to store localId → issueNumber mappings
     * @return StepOutcome indicating success, partial success, or failure
     */
    private suspend fun executeToolStep(
        step: PMTask.CreateIssues,
        createdIssueMap: MutableMap<String, Int>,
    ): StepOutcome {
        val startTime = kotlinx.datetime.Clock.System.now()

        // Build execution request for ToolCreateIssues
        val executionRequest = ExecutionRequest(
            context = ExecutionContext.IssueManagement(
                executorId = id,
                ticket = createPlaceholderTicket(step),
                task = step,
                instructions = "Create issues for work breakdown",
                knowledgeFromPastMemory = emptyList(),
                issueRequest = step.issueRequest,
            ),
            constraints = link.socket.ampere.agents.execution.request.ExecutionConstraints(
                requireTests = false,
                requireLinting = false,
            ),
        )

        // Execute via executor
        val executionOutcome = try {
            val statusFlow = executor.execute(executionRequest, toolCreateIssues)
            statusFlow.last().let { finalStatus ->
                when (finalStatus) {
                    is link.socket.ampere.agents.domain.concept.status.ExecutionStatus.Completed -> finalStatus.result
                    is link.socket.ampere.agents.domain.concept.status.ExecutionStatus.Failed -> finalStatus.result
                    else -> {
                        // Unexpected status
                        return StepOutcome.Failure(
                            id = step.id,
                            stepDescription = "Create issues",
                            startTimestamp = startTime,
                            endTimestamp = kotlinx.datetime.Clock.System.now(),
                            error = "Unexpected execution status: ${finalStatus::class.simpleName}",
                            isCritical = true,
                        )
                    }
                }
            }
        } catch (e: Exception) {
            return StepOutcome.Failure(
                id = step.id,
                stepDescription = "Create issues",
                startTimestamp = startTime,
                endTimestamp = kotlinx.datetime.Clock.System.now(),
                error = "Exception during tool execution: ${e.message}",
                isCritical = true,
            )
        }

        // Process outcome
        return when (executionOutcome) {
            is ExecutionOutcome.IssueManagement.Success -> {
                // Store localId → issueNumber mappings
                executionOutcome.response.created.forEach { createdIssue ->
                    createdIssueMap[createdIssue.localId] = createdIssue.issueNumber
                }

                if (executionOutcome.response.errors.isEmpty()) {
                    // Full success
                    StepOutcome.Success(
                        id = step.id,
                        stepDescription = "Create ${executionOutcome.response.created.size} issues",
                        startTimestamp = startTime,
                        endTimestamp = kotlinx.datetime.Clock.System.now(),
                        details = "Created ${executionOutcome.response.created.size} issues: ${executionOutcome.response.created.joinToString(", ") { "#${it.issueNumber}" }}",
                    )
                } else {
                    // Partial success
                    StepOutcome.PartialSuccess(
                        id = step.id,
                        stepDescription = "Create issues",
                        startTimestamp = startTime,
                        endTimestamp = kotlinx.datetime.Clock.System.now(),
                        successCount = executionOutcome.response.created.size,
                        failureCount = executionOutcome.response.errors.size,
                        details = "Created ${executionOutcome.response.created.size} issues, ${executionOutcome.response.errors.size} failed",
                    )
                }
            }
            is ExecutionOutcome.IssueManagement.Failure -> {
                StepOutcome.Failure(
                    id = step.id,
                    stepDescription = "Create issues",
                    startTimestamp = startTime,
                    endTimestamp = kotlinx.datetime.Clock.System.now(),
                    error = executionOutcome.error.message,
                    isCritical = true,
                )
            }
            else -> {
                StepOutcome.Failure(
                    id = step.id,
                    stepDescription = "Create issues",
                    startTimestamp = startTime,
                    endTimestamp = kotlinx.datetime.Clock.System.now(),
                    error = "Unexpected outcome type: ${executionOutcome::class.simpleName}",
                    isCritical = true,
                )
            }
        }
    }

    /**
     * Execute an action step (e.g., AssignTask, StartMonitoring).
     *
     * Actions don't invoke tools but perform PM-internal operations like emitting events
     * or updating tracking state.
     *
     * @param step The action step to execute
     * @param createdIssueMap Map of localId → issueNumber to resolve issue references
     * @return StepOutcome indicating success or failure
     */
    private suspend fun executeActionStep(
        step: Task,
        createdIssueMap: Map<String, Int>,
    ): StepOutcome {
        val startTime = kotlinx.datetime.Clock.System.now()

        return when (step) {
            is PMTask.AssignTask -> {
                // Resolve issue number from map
                val issueNumber = createdIssueMap[step.taskLocalId]

                // TODO: Emit TaskAssigned event when event bus is integrated
                // eventBus.emit(PlanEvent.TaskAssigned(...))

                StepOutcome.Success(
                    id = step.id,
                    stepDescription = "Assign task ${step.taskLocalId} to ${step.agentId}",
                    startTimestamp = startTime,
                    endTimestamp = kotlinx.datetime.Clock.System.now(),
                    details = buildString {
                        append("Assigned task ${step.taskLocalId}")
                        if (issueNumber != null) {
                            append(" (#$issueNumber)")
                        }
                        append(" to ${step.agentId}: ${step.reasoning}")
                    },
                )
            }
            is PMTask.StartMonitoring -> {
                // Resolve epic issue number from map
                val epicIssueNumber = createdIssueMap[step.epicLocalId]

                // TODO: Register epic for progress tracking
                // TODO: Emit MonitoringStarted event when event bus is integrated
                // eventBus.emit(PlanEvent.MonitoringStarted(...))

                StepOutcome.Success(
                    id = step.id,
                    stepDescription = "Start monitoring epic ${step.epicLocalId}",
                    startTimestamp = startTime,
                    endTimestamp = kotlinx.datetime.Clock.System.now(),
                    details = buildString {
                        append("Started monitoring epic ${step.epicLocalId}")
                        if (epicIssueNumber != null) {
                            append(" (#$epicIssueNumber)")
                        }
                        append(" with ${step.tasks.size} tasks")
                    },
                )
            }
            else -> {
                StepOutcome.Failure(
                    id = step.id,
                    stepDescription = "Unknown action step",
                    startTimestamp = startTime,
                    endTimestamp = kotlinx.datetime.Clock.System.now(),
                    error = "Unknown action step type: ${step::class.simpleName}",
                    isCritical = false, // Non-critical - can continue with other steps
                )
            }
        }
    }

    /**
     * Create a placeholder ticket for execution context.
     *
     * The PM agent doesn't work with traditional tickets but the ExecutionContext requires one.
     */
    private fun createPlaceholderTicket(task: Task): link.socket.ampere.agents.events.tickets.Ticket {
        val now = kotlinx.datetime.Clock.System.now()
        return link.socket.ampere.agents.events.tickets.Ticket(
            id = "pm-ticket-${task.id}",
            title = "PM Task: ${task.id}",
            description = "Project Manager task execution",
            type = link.socket.ampere.agents.events.tickets.TicketType.TASK,
            priority = link.socket.ampere.agents.events.tickets.TicketPriority.MEDIUM,
            status = link.socket.ampere.agents.domain.concept.status.TicketStatus.InProgress,
            assignedAgentId = id,
            createdByAgentId = id,
            createdAt = now,
            updatedAt = now,
            dueDate = null,
        )
    }

    /**
     * Stub: Execute a tool with LLM-generated parameters.
     *
     * TODO: Implement tool execution with LLM parameter generation:
     * - Use LLM to generate issue structures from goals
     * - Use LLM to formulate escalation questions
     * - Execute via executor
     * - Handle outcomes
     */
    private suspend fun executeToolWithLLMGeneratedParameters(
        tool: Tool<*>,
        request: ExecutionRequest<*>,
    ): ExecutionOutcome {
        // Stub implementation - return a placeholder outcome
        return ExecutionOutcome.NoChanges.Success(
            executorId = "ProjectManagerAgent",
            ticketId = "",
            taskId = "",
            message = "Stub tool execution - tool: ${tool.id}",
            executionStartTimestamp = kotlinx.datetime.Clock.System.now(),
            executionEndTimestamp = kotlinx.datetime.Clock.System.now(),
        )
    }

    /**
     * Stub: Evaluate outcomes and generate learnings using LLM.
     *
     * TODO: Implement outcome evaluation:
     * - Analyze success/failure patterns
     * - Identify effective work breakdown structures
     * - Learn optimal task granularity
     * - Recognize when to escalate vs. proceed
     */
    private fun evaluateOutcomesAndGenerateLearnings(outcomes: List<Outcome>): Idea {
        // Stub implementation - return a placeholder idea
        return Idea(
            name = "Learn from outcomes",
            description = "Stub: Learn from outcomes and adjust approach. Full LLM-based outcome evaluation pending.",
        )
    }

    /**
     * Stub: Call LLM with a prompt.
     *
     * TODO: Implement actual LLM integration:
     * - Use agentConfiguration to get AI client
     * - Format prompt with PM-specific context
     * - Handle retries and fallbacks
     * - Parse and validate responses
     */
    override fun callLLM(prompt: String): String {
        // Stub implementation
        TODO("LLM integration pending - requires AI client from agentConfiguration")
    }

    // ========================================================================
    // Planning: Work Breakdown and Task Assignment
    // ========================================================================

    /**
     * Overrides determinePlanForTask to generate PM-specific plans.
     *
     * Routes to different planning strategies based on task type:
     * - DecomposeGoal tasks → createGoalDecompositionPlan
     * - AssessProgress tasks → createProgressMonitoringPlan
     * - Other tasks → fallback to simple plan
     *
     * @param task The task to plan for
     * @param ideas Current ideas informing the plan
     * @param relevantKnowledge Past knowledge entries relevant to this task
     * @return Plan with steps to accomplish the task
     */
    override suspend fun determinePlanForTask(
        task: link.socket.ampere.agents.domain.concept.task.Task,
        vararg ideas: Idea,
        relevantKnowledge: List<link.socket.ampere.agents.domain.memory.KnowledgeWithScore>,
    ): Plan {
        return when (task) {
            is PMTask.DecomposeGoal -> {
                createGoalDecompositionPlan(task, relevantKnowledge)
            }
            is PMTask.AssessProgress -> {
                createProgressMonitoringPlan(task, relevantKnowledge)
            }
            else -> {
                // Fallback: create simple plan with the task itself
                Plan.ForTask(
                    task = task,
                    tasks = listOf(task),
                    estimatedComplexity = 3,
                )
            }
        }
    }

    /**
     * Create a plan for decomposing a goal into structured work.
     *
     * This plan includes:
     * 1. Generate work breakdown using LLM + goalDecompositionPrompt
     * 2. Create issues via ToolCreateIssues
     * 3. Assign each task to an appropriate agent
     * 4. Start monitoring the epic
     *
     * @param goalTask The goal decomposition task
     * @param relevantKnowledge Past knowledge about similar goals
     * @return Plan with decomposition, creation, assignment, and monitoring steps
     */
    private suspend fun createGoalDecompositionPlan(
        goalTask: PMTask.DecomposeGoal,
        relevantKnowledge: List<link.socket.ampere.agents.domain.memory.KnowledgeWithScore>,
    ): Plan {
        // Gather context for decomposition
        val availableAgents = gatherAvailableAgents()
        val existingIssues = gatherExistingIssues(goalTask.repository)

        // Generate work breakdown using LLM
        val workBreakdown = generateWorkBreakdownWithLLM(
            goal = goalTask.goal,
            repository = goalTask.repository,
            availableAgents = availableAgents,
            existingIssues = existingIssues,
            relevantKnowledge = relevantKnowledge,
        )

        // Generate agent assignments for each task
        val assignments = generateTaskAssignmentsWithLLM(
            tasks = workBreakdown.issues.filter { it.type == link.socket.ampere.agents.execution.tools.issue.IssueType.Task },
            availableAgents = availableAgents,
        )

        // Build plan steps
        val planTasks = mutableListOf<link.socket.ampere.agents.domain.concept.task.Task>()

        // Step 1: Create issues
        planTasks.add(
            PMTask.CreateIssues(
                id = link.socket.ampere.agents.events.utils.generateUUID("${goalTask.id}-create-issues"),
                status = link.socket.ampere.agents.domain.concept.status.TaskStatus.Pending,
                issueRequest = workBreakdown,
            ),
        )

        // Step 2+: Assign each task to an agent
        assignments.forEach { (taskLocalId, assignment) ->
            planTasks.add(
                PMTask.AssignTask(
                    id = link.socket.ampere.agents.events.utils.generateUUID("${goalTask.id}-assign-$taskLocalId"),
                    status = link.socket.ampere.agents.domain.concept.status.TaskStatus.Pending,
                    taskLocalId = taskLocalId,
                    agentId = assignment.agentId,
                    reasoning = assignment.reasoning,
                ),
            )
        }

        // Final step: Start monitoring the epic
        val epicLocalId = workBreakdown.issues.find {
            it.type == link.socket.ampere.agents.execution.tools.issue.IssueType.Feature
        }?.localId ?: "epic-1"

        planTasks.add(
            PMTask.StartMonitoring(
                id = link.socket.ampere.agents.events.utils.generateUUID("${goalTask.id}-monitor"),
                status = link.socket.ampere.agents.domain.concept.status.TaskStatus.Pending,
                epicLocalId = epicLocalId,
                tasks = workBreakdown.issues
                    .filter { it.type == link.socket.ampere.agents.execution.tools.issue.IssueType.Task }
                    .map { it.localId },
            ),
        )

        return Plan.ForTask(
            task = goalTask,
            tasks = planTasks,
            estimatedComplexity = 7, // Decomposition is complex
        )
    }

    /**
     * Create a plan for assessing and acting on epic progress.
     *
     * This plan includes:
     * 1. Assess current progress using LLM + progressAssessmentPrompt
     * 2. Take recommended actions (unblock, escalate, reassign, etc.)
     *
     * @param progressTask The progress assessment task
     * @param relevantKnowledge Past knowledge about similar situations
     * @return Plan with assessment and action steps
     */
    private suspend fun createProgressMonitoringPlan(
        progressTask: PMTask.AssessProgress,
        relevantKnowledge: List<link.socket.ampere.agents.domain.memory.KnowledgeWithScore>,
    ): Plan {
        // TODO: Implement progress monitoring plan
        // 1. Query current task states
        // 2. Use PMPrompts.progressAssessmentPrompt with LLM
        // 3. Generate action tasks based on recommendations

        // Stub implementation for now
        return Plan.ForTask(
            task = progressTask,
            tasks = listOf(progressTask),
            estimatedComplexity = 4,
        )
    }

    /**
     * Generate work breakdown structure using LLM and goal decomposition prompt.
     *
     * @return BatchIssueCreateRequest with epic and tasks
     */
    private suspend fun generateWorkBreakdownWithLLM(
        goal: String,
        repository: String,
        availableAgents: List<link.socket.ampere.agents.definition.pm.AgentCapability>,
        existingIssues: List<String>,
        relevantKnowledge: List<link.socket.ampere.agents.domain.memory.KnowledgeWithScore>,
    ): link.socket.ampere.agents.execution.tools.issue.BatchIssueCreateRequest {
        // Generate prompt
        val prompt = link.socket.ampere.agents.definition.pm.PMPrompts.goalDecompositionPrompt(
            goal = goal,
            repository = repository,
            availableAgents = availableAgents,
            existingIssues = existingIssues,
        )

        // TODO: Call LLM with prompt and parse JSON response
        // val llmResponse = callLLM(prompt)
        // val json = Json { ignoreUnknownKeys = true }
        // return json.decodeFromString<BatchIssueCreateRequest>(llmResponse)

        // Stub implementation - return minimal work breakdown
        return link.socket.ampere.agents.execution.tools.issue.BatchIssueCreateRequest(
            repository = repository,
            issues = listOf(
                link.socket.ampere.agents.execution.tools.issue.IssueCreateRequest(
                    localId = "epic-1",
                    type = link.socket.ampere.agents.execution.tools.issue.IssueType.Feature,
                    title = "Epic: $goal",
                    body = "Decomposed goal: $goal",
                    labels = listOf("epic"),
                ),
                link.socket.ampere.agents.execution.tools.issue.IssueCreateRequest(
                    localId = "task-1",
                    type = link.socket.ampere.agents.execution.tools.issue.IssueType.Task,
                    title = "Implement core functionality for: $goal",
                    body = "Main implementation task",
                    labels = listOf("task"),
                    parent = "epic-1",
                ),
            ),
        )
    }

    /**
     * Generate agent assignments for tasks using LLM and task assignment prompt.
     *
     * @return Map of task localId to assignment decision
     */
    private suspend fun generateTaskAssignmentsWithLLM(
        tasks: List<link.socket.ampere.agents.execution.tools.issue.IssueCreateRequest>,
        availableAgents: List<link.socket.ampere.agents.definition.pm.AgentCapability>,
    ): Map<String, TaskAssignment> {
        val assignments = mutableMapOf<String, TaskAssignment>()

        // Generate assignment for each task
        tasks.forEach { task ->
            val prompt = link.socket.ampere.agents.definition.pm.PMPrompts.taskAssignmentPrompt(
                task = task.title,
                availableAgents = availableAgents,
            )

            // TODO: Call LLM with prompt and parse JSON response
            // val llmResponse = callLLM(prompt)
            // val json = Json { ignoreUnknownKeys = true }
            // val assignmentJson = json.parseToJsonElement(llmResponse).jsonObject
            // assignments[task.localId] = TaskAssignment(
            //     agentId = assignmentJson["assignedAgent"]?.jsonPrimitive?.content ?: "default-agent",
            //     reasoning = assignmentJson["reasoning"]?.jsonPrimitive?.content ?: "Default assignment"
            // )

            // Stub implementation - assign to first available agent
            val agent = availableAgents.firstOrNull()
            if (agent != null) {
                assignments[task.localId] = TaskAssignment(
                    agentId = agent.agentId,
                    reasoning = "Stub: Assigned to first available agent",
                )
            }
        }

        return assignments
    }

    /**
     * Gather available agents and their current capabilities/workload.
     *
     * TODO: Integrate with agent registry and event system
     */
    private suspend fun gatherAvailableAgents(): List<link.socket.ampere.agents.definition.pm.AgentCapability> {
        // Stub implementation
        return listOf(
            link.socket.ampere.agents.definition.pm.AgentCapability(
                agentId = "code-writer-1",
                capabilities = listOf("code-writing", "kotlin", "testing"),
                currentTaskCount = 0,
            ),
        )
    }

    /**
     * Gather existing issues from the repository.
     *
     * TODO: Integrate with ToolQueryIssues when available
     */
    private suspend fun gatherExistingIssues(repository: String): List<String> {
        // Stub implementation
        return emptyList()
    }

    /**
     * Task assignment decision from LLM.
     */
    private data class TaskAssignment(
        val agentId: AgentId,
        val reasoning: String,
    )

    // ========================================================================
    // Perception: Context Gathering for Decision Making
    // ========================================================================

    /**
     * Overrides perceiveState to gather comprehensive project context.
     *
     * This method orchestrates gathering information from multiple sources:
     * - Active goals and epics from issue tracker
     * - Current agent availability and workload
     * - Recent significant events
     * - Pending human escalations
     * - Relevant past knowledge
     *
     * @param currentState The current PM agent state
     * @param newIdeas New ideas to incorporate into perception
     * @return Perception containing fresh project context and ideas
     */
    override suspend fun perceiveState(
        currentState: AgentState,
        vararg newIdeas: Idea,
    ): Perception<AgentState> {
        // Cast to ProjectManagerState for type safety
        val pmState = currentState as? ProjectManagerState ?: ProjectManagerState.blank

        // Gather fresh context from multiple sources
        val activeGoals = extractActiveGoals()
        val projectMetrics = assessProjectState()
        val agentStates = gatherAgentStates()
        val blockers = identifyBlockers()
        val escalations = findPendingEscalations()

        // Create updated state with fresh context
        val freshState = pmState.copy(
            activeGoals = activeGoals,
            taskAssignments = agentStates.mapValues { it.key }, // Simplified for now
            blockedTasks = blockers,
            pendingEscalations = escalations,
        )

        // Create ideas from gathered context
        val ideas = mutableListOf<Idea>()
        ideas.add(Idea(name = "Project Manager Perception"))

        // Add goal-related ideas
        if (activeGoals.isNotEmpty()) {
            ideas.add(Idea(
                name = "Active Goals",
                description = "${activeGoals.size} active goals: ${activeGoals.joinToString { it.description }}",
            ))
        }

        // Add blocker ideas
        if (blockers.isNotEmpty()) {
            ideas.add(Idea(
                name = "Blocked Tasks",
                description = "${blockers.size} tasks are blocked: ${blockers.joinToString()}",
            ))
        }

        // Add escalation ideas
        if (escalations.isNotEmpty()) {
            ideas.add(Idea(
                name = "Pending Escalations",
                description = "${escalations.size} decisions need human input",
            ))
        }

        // Add project metrics ideas
        ideas.add(Idea(
            name = "Project Status",
            description = "Open issues: ${projectMetrics["openIssues"]}, " +
                "In Progress: ${projectMetrics["inProgress"]}, " +
                "Completed: ${projectMetrics["completed"]}",
        ))

        // Include any new ideas passed in
        ideas.addAll(newIdeas)

        // Return perception with fresh state and ideas
        return Perception(
            id = link.socket.ampere.agents.events.utils.generateUUID(id),
            ideas = ideas,
            currentState = freshState,
            timestamp = kotlinx.datetime.Clock.System.now(),
        )
    }

    /**
     * Extract active goals from the issue tracker.
     *
     * Finds open Feature/Epic issues that represent current goals.
     *
     * TODO: Integrate with ToolQueryIssues when available
     * For now, returns stub data
     *
     * @return List of active goals
     */
    private suspend fun extractActiveGoals(): List<link.socket.ampere.agents.definition.pm.Goal> {
        // TODO: Query GitHub issues via ToolQueryIssues
        // val issues = queryIssues(type = "Feature", status = "open")
        // return issues.map { issue ->
        //     Goal(
        //         id = issue.id,
        //         description = issue.title,
        //         priority = issue.priority,
        //         status = issue.status
        //     )
        // }

        // Stub implementation - return empty list for now
        return emptyList()
    }

    /**
     * Assess overall project state and calculate metrics.
     *
     * Calculates metrics like:
     * - Total open issues
     * - In-progress vs pending vs completed
     * - Average completion time
     *
     * TODO: Integrate with ToolQueryIssues when available
     *
     * @return Map of metric name to value
     */
    private suspend fun assessProjectState(): Map<String, Int> {
        // TODO: Query all issues and calculate metrics
        // val allIssues = queryIssues()
        // return mapOf(
        //     "openIssues" to allIssues.count { it.status == "open" },
        //     "inProgress" to allIssues.count { it.status == "in_progress" },
        //     "completed" to allIssues.count { it.status == "completed" }
        // )

        // Stub implementation
        return mapOf(
            "openIssues" to 0,
            "inProgress" to 0,
            "completed" to 0,
        )
    }

    /**
     * Gather current agent states from the event system.
     *
     * Queries the event bus to determine:
     * - Which agents are active
     * - Current workload per agent
     * - Agent availability
     *
     * TODO: Integrate with event bus API
     *
     * @return Map of agent ID to agent state/workload
     */
    private suspend fun gatherAgentStates(): Map<AgentId, Int> {
        // TODO: Query event bus for agent states
        // val agentEvents = eventBus.getRecentEvents(type = "AgentStatus")
        // return agentEvents.groupBy { it.agentId }
        //     .mapValues { (_, events) ->
        //         events.count { it.status == "working" }
        //     }

        // Stub implementation
        return emptyMap()
    }

    /**
     * Identify tasks that are currently blocked.
     *
     * A task is considered blocked if:
     * - It has unmet dependencies
     * - It's explicitly marked as blocked
     * - It's been in "in_progress" for too long without updates
     *
     * TODO: Integrate with issue tracker and event system
     *
     * @return List of blocked task IDs
     */
    private suspend fun identifyBlockers(): List<String> {
        // TODO: Query issues and events to find blockers
        // val issues = queryIssues(status = "blocked")
        // val stalledTasks = queryIssues(status = "in_progress")
        //     .filter { it.lastUpdated < Clock.System.now() - 2.days }
        // return (issues + stalledTasks).map { it.id }

        // Stub implementation
        return emptyList()
    }

    /**
     * Find escalations pending human response.
     *
     * Identifies decisions that have been escalated to humans
     * but haven't received a response yet.
     *
     * TODO: Integrate with human escalation tracking system
     *
     * @return List of pending escalations
     */
    private suspend fun findPendingEscalations(): List<link.socket.ampere.agents.definition.pm.Escalation> {
        // TODO: Query escalation tracking system
        // val escalationEvents = eventBus.getEvents(type = "HumanEscalation")
        // return escalationEvents
        //     .filter { !it.hasResponse }
        //     .map { event ->
        //         Escalation(
        //             id = event.id,
        //             decision = event.decision,
        //             reason = event.reason,
        //             context = event.context
        //         )
        //     }

        // Stub implementation
        return emptyList()
    }

    companion object {
        /**
         * System prompt defining the PM agent's role, capabilities, and guidelines.
         */
        const val SYSTEM_PROMPT = """
You are a Project Manager Agent, the executive function of the AMPERE multi-agent system.

## Your Role

You transform high-level goals into structured execution by:
1. Decomposing goals into epics and tasks following a clear work breakdown structure
2. Creating issues in external project management systems
3. Assigning tasks to appropriate specialized agents (CodeWriter, QA, etc.)
4. Monitoring task progress through the event stream
5. Facilitating coordination through meetings when agents are blocked
6. Escalating decisions to humans when they exceed your authority

## Your Capabilities

Available Tools:
- **ToolCreateIssues**: Create hierarchical issues (epics with tasks) in external systems
- **ToolAskHuman**: Escalate uncertainty or decisions to human stakeholders

You can:
- Access the event stream to monitor task progress
- Initiate meetings for multi-agent coordination
- Track dependencies between tasks
- Update issue status as work progresses

## Work Breakdown Structure Guidelines

When decomposing goals:
1. Create ONE Feature epic as the parent
2. Create multiple Task issues as children of the epic
3. Define clear dependencies between tasks
4. Ensure each task is:
   - Specific and actionable
   - Scoped for a single agent
   - Estimable (can be completed in reasonable time)
   - Independent of blocked dependencies

## Decision Authority Boundaries

You can decide autonomously:
- How to break down well-defined goals into tasks
- Which specialized agent to assign to each task
- When to initiate coordination meetings
- How to sequence dependent tasks

You MUST escalate to humans:
- Scope decisions (what's in/out of scope)
- Priority conflicts (when multiple goals compete)
- Unclear requirements (when goal lacks sufficient detail)
- Architecture decisions (when multiple valid approaches exist)
- Resource constraints (when agent capacity is exceeded)

## Communication Style

- Be direct and concise (directness: 0.8)
- Balance structure with innovation (creativity: 0.5)
- Provide moderate detail (verbosity: 0.6)
- Maintain professional tone (formality: 0.7)

## Cognitive Loop

You operate in a continuous PROPEL cycle:
1. **Perceive**: Analyze project state, goals, and blockers
2. **Recall**: Access relevant past knowledge about similar projects
3. **Operate**: Execute plans (create issues, assign tasks)
4. **Plan**: Generate work breakdown structures
5. **Evaluate**: Learn from outcomes and adjust approach
6. **Learn**: Store successful patterns for future use

Focus on coordination efficiency - parallel work when possible, sequential when dependencies require it.
"""
    }
}
