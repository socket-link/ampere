package link.socket.ampere.agents.definition

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.definition.code.CodeParams
import link.socket.ampere.agents.definition.code.CodePrompts
import link.socket.ampere.agents.definition.code.CodeState
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.reasoning.AgentReasoning
import link.socket.ampere.agents.domain.reasoning.DefaultTaskFactory
import link.socket.ampere.agents.domain.reasoning.Idea
import link.socket.ampere.agents.domain.reasoning.KnowledgeExtractor
import link.socket.ampere.agents.domain.reasoning.Perception
import link.socket.ampere.agents.domain.reasoning.PerceptionContextBuilder
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.reasoning.StepContext
import link.socket.ampere.agents.domain.reasoning.StepResult
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.domain.task.AssignedTo
import link.socket.ampere.agents.domain.task.MeetingTask
import link.socket.ampere.agents.domain.task.PMTask
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.domain.task.TicketTask
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.executor.Executor
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.request.ExecutionConstraints
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool

/**
 * Code Writer Agent - Autonomous code generation and file writing.
 *
 * This agent specializes in:
 * - Generating production-quality code from task descriptions
 * - Writing code files to the workspace
 * - Reading existing code for context
 * - Learning from execution outcomes to improve future code generation
 *
 * Uses the unified AgentReasoning infrastructure for all cognitive operations.
 */
open class CodeAgent(
    override val agentConfiguration: AgentConfiguration,
    private val toolWriteCodeFile: Tool<ExecutionContext.Code.WriteCode>,
    private val coroutineScope: CoroutineScope,
    override val initialState: CodeState = CodeState.blank,
    private val toolReadCodeFile: Tool<ExecutionContext.Code.ReadCode>? = null,
    private val executor: Executor = FunctionExecutor.create(),
    memoryServiceFactory: ((AgentId) -> link.socket.ampere.agents.domain.memory.AgentMemoryService)? = null,
    reasoningOverride: AgentReasoning? = null,
) : AutonomousAgent<CodeState>() {

    override val id: AgentId = generateUUID("CodeWriterAgent")

    override val memoryService: link.socket.ampere.agents.domain.memory.AgentMemoryService? =
        memoryServiceFactory?.invoke(id)

    override val requiredTools: Set<Tool<*>> = buildSet {
        add(toolWriteCodeFile)
        toolReadCodeFile?.let { add(it) }
    }

    // ========================================================================
    // Unified Reasoning - All cognitive logic in one place
    // ========================================================================

    private val reasoning: AgentReasoning = reasoningOverride ?: AgentReasoning.create(agentConfiguration, id) {
        agentRole = "Code Writer"
        availableTools = requiredTools
        this.executor = this@CodeAgent.executor

        perception {
            contextBuilder = { state -> buildPerceptionContext(state) }
        }

        planning {
            taskFactory = DefaultTaskFactory
            customPrompt = { task, ideas, tools, knowledge ->
                buildPlanningPrompt(task, ideas)
            }
        }

        execution {
            registerStrategy(toolWriteCodeFile.id, CodeParams.CodeWriting())
            toolReadCodeFile?.let { tool ->
                registerStrategy(tool.id, CodeParams.CodeReading())
            }
        }

        evaluation {
            contextBuilder = { outcomes -> buildOutcomeContext(outcomes) }
        }

        knowledge {
            extractor = { outcome, task, plan ->
                KnowledgeExtractor.extract(outcome, task, plan) {
                    approach {
                        prefix("Code Task")
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

    @Suppress("UNCHECKED_CAST")
    override val runLLMToEvaluatePerception: (perception: Perception<CodeState>) -> Idea =
        { perception ->
            runBlocking { reasoning.evaluatePerception(perception) }
        }

    override val runLLMToPlan: (task: Task, ideas: List<Idea>) -> Plan =
        { task, ideas ->
            runBlocking { reasoning.generatePlan(task, ideas) }
        }

    override val runLLMToExecuteTask: (task: Task) -> Outcome =
        { task ->
            runBlocking { executeTaskWithReasoning(task) }
        }

    override val runLLMToExecuteTool: (tool: Tool<*>, request: ExecutionRequest<*>) -> ExecutionOutcome =
        { tool, request ->
            runBlocking { reasoning.executeTool(tool, request) }
        }

    override val runLLMToEvaluateOutcomes: (outcomes: List<Outcome>) -> Idea =
        { outcomes ->
            runBlocking { reasoning.evaluateOutcomes(outcomes, memoryService).summaryIdea }
        }

    override fun extractKnowledgeFromOutcome(
        outcome: Outcome,
        task: Task,
        plan: Plan,
    ): Knowledge.FromOutcome = reasoning.extractKnowledge(outcome, task, plan)

    override fun callLLM(prompt: String): String =
        runBlocking { reasoning.callLLM(prompt) }

    // ========================================================================
    // Task Execution - Uses PlanExecutor for orchestration
    // ========================================================================

    private suspend fun executeTaskWithReasoning(task: Task): Outcome {
        if (task is Task.Blank) {
            return Outcome.blank
        }

        if (task !is Task.CodeChange) {
            return createTaskFailureOutcome(
                task,
                "Unsupported task type: ${task::class.simpleName}. " +
                    "CodeWriterAgent currently only supports Task.CodeChange",
            )
        }

        val plan = reasoning.generatePlan(task, emptyList())
        return reasoning.executePlan(plan) { step, context ->
            executeStep(step, context)
        }.outcome
    }

    private suspend fun executeStep(step: Task, context: StepContext): StepResult {
        return when (step) {
            is Task.CodeChange -> {
                val outcome = executeCodeChange(step)
                when (outcome) {
                    is ExecutionOutcome.CodeChanged.Success -> {
                        StepResult.success(
                            description = "Write code: ${step.description}",
                            details = "Modified ${outcome.changedFiles.size} files",
                            contextUpdates = mapOf(
                                "written_files" to outcome.changedFiles,
                            ),
                        )
                    }
                    is ExecutionOutcome.CodeChanged.Failure -> {
                        StepResult.failure(
                            description = "Write code: ${step.description}",
                            error = outcome.error.message,
                            isCritical = true,
                        )
                    }
                    is ExecutionOutcome.Failure -> {
                        StepResult.failure(
                            description = "Write code: ${step.description}",
                            error = "Execution failed",
                            isCritical = true,
                        )
                    }
                    else -> StepResult.success(
                        description = "Write code: ${step.description}",
                        details = "Completed",
                    )
                }
            }
            else -> StepResult.skip(
                description = "Unknown step type",
                reason = "Step type ${step::class.simpleName} not supported by CodeWriterAgent",
            )
        }
    }

    private suspend fun executeCodeChange(task: Task.CodeChange): ExecutionOutcome {
        val request = createExecutionRequest(task)
        return reasoning.executeTool(toolWriteCodeFile, request)
    }

    private fun createExecutionRequest(
        task: Task.CodeChange,
    ): ExecutionRequest<ExecutionContext.Code.WriteCode> {
        val ticket = createTicketForTask(task)
        val workspace = ExecutionWorkspace(baseDirectory = ".")

        return ExecutionRequest(
            context = ExecutionContext.Code.WriteCode(
                executorId = id,
                ticket = ticket,
                task = task,
                instructions = task.description,
                knowledgeFromPastMemory = emptyList(),
                workspace = workspace,
                instructionsPerFilePath = emptyList(), // Will be filled by strategy
            ),
            constraints = ExecutionConstraints(
                requireTests = false,
                requireLinting = false,
            ),
        )
    }

    private fun createTicketForTask(task: Task.CodeChange): Ticket {
        val now = Clock.System.now()
        return Ticket(
            id = "ticket-${task.id}",
            title = "Execute task: ${task.id}",
            description = task.description,
            type = TicketType.TASK,
            priority = TicketPriority.MEDIUM,
            status = TicketStatus.InProgress,
            assignedAgentId = when (val assignedTo = task.assignedTo) {
                is AssignedTo.Agent -> assignedTo.agentId
                else -> id
            },
            createdByAgentId = id,
            createdAt = now,
            updatedAt = now,
            dueDate = null,
        )
    }

    private fun createTaskFailureOutcome(task: Task, reason: String): Outcome {
        val now = Clock.System.now()
        return ExecutionOutcome.NoChanges.Failure(
            executorId = executor.id,
            ticketId = "ticket-${task.id}",
            taskId = task.id,
            executionStartTimestamp = now,
            executionEndTimestamp = now,
            message = reason,
        )
    }

    // ========================================================================
    // Context Builders - Agent-specific customizations
    // ========================================================================

    private fun buildPerceptionContext(state: AgentState): String {
        val codeState = state as? CodeState
        val currentMemory = state.getCurrentMemory()
        val pastMemory = state.getPastMemory()
        val currentTask = currentMemory.task
        val currentOutcome = currentMemory.outcome

        return PerceptionContextBuilder()
            .header("CodeWriterAgent State Analysis")
            .section("Current Task") {
                when (currentTask) {
                    is Task.CodeChange -> {
                        field("Type", "Code Change")
                        field("ID", currentTask.id)
                        field("Status", currentTask.status)
                        field("Description", currentTask.description)
                        currentTask.assignedTo?.let { field("Assigned To", it) }
                    }
                    is MeetingTask.AgendaItem -> {
                        field("Type", "Meeting Agenda Item")
                        field("ID", currentTask.id)
                        field("Status", currentTask.status)
                        field("Title", currentTask.title)
                        currentTask.description?.let { field("Description", it) }
                    }
                    is TicketTask.CompleteSubticket -> {
                        field("Type", "Complete Subticket")
                        field("ID", currentTask.id)
                        field("Status", currentTask.status)
                    }
                    is Task.Blank -> {
                        line("No active task")
                    }
                    else -> {
                        field("Type", currentTask::class.simpleName)
                        field("ID", currentTask.id)
                        field("Status", currentTask.status)
                    }
                }
            }
            .section("Current Outcome") {
                when (currentOutcome) {
                    is Outcome.Success -> line("Status: ✓ Success")
                    is Outcome.Failure -> line("Status: ✗ Failure")
                    is Outcome.Blank -> line("Status: No outcome yet")
                    else -> line("Status: ${currentOutcome::class.simpleName}")
                }
            }
            .sectionIf(pastMemory.tasks.isNotEmpty(), "Past Tasks") {
                line("${pastMemory.tasks.size} completed")
            }
            .sectionIf(pastMemory.outcomes.isNotEmpty(), "Past Outcomes") {
                line("${pastMemory.outcomes.size} recorded")
            }
            .sectionIf(pastMemory.knowledgeFromOutcomes.isNotEmpty(), "Learned Knowledge") {
                pastMemory.knowledgeFromOutcomes.takeLast(3).forEach { knowledge ->
                    line("- Approach: ${knowledge.approach}")
                    line("  Learnings: ${knowledge.learnings}")
                }
            }
            .section("Available Tools") {
                requiredTools.forEach { tool ->
                    line("- ${tool.id}: ${tool.description}")
                }
            }
            .build()
    }

    private fun buildPlanningPrompt(task: Task, ideas: List<Idea>): String = buildString {
        appendLine("You are the planning module of an autonomous code-writing agent.")
        appendLine()
        appendLine("Task: ${extractTaskDescription(task)}")
        appendLine()
        if (ideas.isNotEmpty()) {
            appendLine("Insights from Perception:")
            ideas.forEach { idea ->
                appendLine("${idea.name}:")
                appendLine(idea.description)
                appendLine()
            }
        }
        appendLine("Available Tools:")
        requiredTools.forEach { tool ->
            appendLine("- ${tool.id}: ${tool.description}")
        }
        appendLine()
        appendLine("Create a step-by-step plan where each step is a concrete task that can be executed.")
        appendLine("For simple tasks, create a 1-2 step plan.")
        appendLine("For complex tasks, break down into logical phases (3-5 steps typically).")
        appendLine()
        appendLine("Format your response as a JSON object:")
        appendLine("""{"steps": [{"description": "...",""")
        appendLine(""" "toolToUse": "write_code_file|read_code_file|null",""")
        appendLine(""" "requiresPreviousStep": true/false}],""")
        appendLine(""" "estimatedComplexity": 1-10}""")
    }

    private fun buildOutcomeContext(outcomes: List<Outcome>): String = buildString {
        appendLine("=== Code Execution Outcome Analysis ===")
        appendLine()
        val successCount = outcomes.count { it is Outcome.Success }
        val failedCount = outcomes.count { it is Outcome.Failure }
        appendLine("Total: ${outcomes.size}, Success: $successCount, Failed: $failedCount")
        appendLine()

        outcomes.forEachIndexed { i, outcome ->
            when (outcome) {
                is ExecutionOutcome.CodeChanged.Success -> {
                    appendLine("${i + 1}. ✓ Code Changed Successfully")
                    appendLine("   Files: ${outcome.changedFiles.size}")
                    outcome.changedFiles.take(3).forEach { file ->
                        appendLine("   - $file")
                    }
                    if (outcome.changedFiles.size > 3) {
                        appendLine("   ... and ${outcome.changedFiles.size - 3} more")
                    }
                }
                is ExecutionOutcome.CodeChanged.Failure -> {
                    appendLine("${i + 1}. ✗ Code Change Failed")
                    appendLine("   Error: ${outcome.error}")
                }
                is ExecutionOutcome.CodeReading.Success -> {
                    appendLine("${i + 1}. ✓ Code Read Successfully")
                    appendLine("   Files: ${outcome.readFiles.size}")
                }
                is ExecutionOutcome.CodeReading.Failure -> {
                    appendLine("${i + 1}. ✗ Code Reading Failed")
                    appendLine("   Error: ${outcome.error}")
                }
                else -> {
                    appendLine("${i + 1}. ${outcome::class.simpleName}")
                }
            }
        }
    }

    private fun extractTaskDescription(task: Task): String = when (task) {
        is Task.CodeChange -> task.description
        is MeetingTask.AgendaItem -> task.title
        is TicketTask.CompleteSubticket -> "Complete subticket ${task.id}"
        is PMTask -> "PM task ${task.id}"
        is Task.Blank -> ""
    }

    // ========================================================================
    // Utility Functions
    // ========================================================================

    /**
     * Helper for coroutine-based file writing with callback.
     */
    protected fun writeCodeFile(
        executionRequest: ExecutionRequest<ExecutionContext.Code.WriteCode>,
        onCodeSubmittedOutcome: (Outcome) -> Unit,
    ) {
        coroutineScope.launch {
            val outcome = toolWriteCodeFile.execute(executionRequest)
            onCodeSubmittedOutcome(outcome)
        }
    }

    companion object Companion {
        val SYSTEM_PROMPT = CodePrompts.SYSTEM_PROMPT
    }
}
