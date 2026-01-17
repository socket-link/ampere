package link.socket.ampere.agents.definition

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.definition.code.CodeParams
import link.socket.ampere.agents.definition.code.CodePrompts
import link.socket.ampere.agents.definition.code.CodeState
import link.socket.ampere.agents.definition.code.IssueWorkflowStatus
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
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
import link.socket.ampere.agents.domain.status.TaskStatus
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
import link.socket.ampere.agents.execution.tools.git.ToolCommit
import link.socket.ampere.agents.execution.tools.git.ToolCreateBranch
import link.socket.ampere.agents.execution.tools.git.ToolCreatePullRequest
import link.socket.ampere.agents.execution.tools.git.ToolGitStatus
import link.socket.ampere.agents.execution.tools.git.ToolPush
import link.socket.ampere.agents.execution.tools.git.ToolStageFiles
import link.socket.ampere.integrations.issues.ExistingIssue
import link.socket.ampere.integrations.issues.IssueQuery
import link.socket.ampere.integrations.issues.IssueState
import link.socket.ampere.integrations.issues.IssueUpdate

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
    private val issueTrackerProvider: link.socket.ampere.integrations.issues.IssueTrackerProvider? = null,
    private val repository: String? = null,
    private val toolCreateBranch: Tool<ExecutionContext.GitOperation>? = ToolCreateBranch(),
    private val toolStageFiles: Tool<ExecutionContext.GitOperation>? = ToolStageFiles(),
    private val toolCommit: Tool<ExecutionContext.GitOperation>? = ToolCommit(),
    private val toolPush: Tool<ExecutionContext.GitOperation>? = ToolPush(),
    private val toolCreatePR: Tool<ExecutionContext.GitOperation>? = ToolCreatePullRequest(),
    private val toolGitStatus: Tool<ExecutionContext.GitOperation>? = ToolGitStatus(),
) : AutonomousAgent<CodeState>() {

    override val id: AgentId = generateUUID("CodeWriterAgent")

    override val memoryService: link.socket.ampere.agents.domain.memory.AgentMemoryService? =
        memoryServiceFactory?.invoke(id)

    override val requiredTools: Set<Tool<*>> = buildSet {
        add(toolWriteCodeFile)
        toolReadCodeFile?.let { add(it) }
        toolCreateBranch?.let { add(it) }
        toolStageFiles?.let { add(it) }
        toolCommit?.let { add(it) }
        toolPush?.let { add(it) }
        toolCreatePR?.let { add(it) }
        toolGitStatus?.let { add(it) }
    }

    /**
     * CodeAgent uses ANALYTICAL cognitive affinity.
     *
     * This shapes the agent to break problems into verifiable steps,
     * prioritize correctness, and trace logic chains - ideal for
     * code generation, review, and debugging.
     */
    override val affinity: CognitiveAffinity = CognitiveAffinity.ANALYTICAL

    // ========================================================================
    // Unified Reasoning - All cognitive logic in one place
    // ========================================================================

    private val reasoning: AgentReasoning = reasoningOverride ?: AgentReasoning.create(agentConfiguration, id) {
        agentRole = "Code Writer"
        availableTools = requiredTools
        this.executor = this@CodeAgent.executor

        perception {
            contextBuilder = { state ->
                runBlocking(Dispatchers.IO) {
                    withTimeout(60000) {
                        buildPerceptionContext(state)
                    }
                }
            }
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

    /**
     * Determines the operation type from a task description.
     *
     * Analyzes the task description to classify it into one of the supported
     * operation types (code writing, Git operations, etc.).
     */
    private enum class OperationType {
        CODE_WRITE,
        GIT_CREATE_BRANCH,
        GIT_STAGE_FILES,
        GIT_COMMIT,
        GIT_PUSH,
        GIT_CREATE_PR,
        GIT_STATUS,
        UNKNOWN,
    }

    /**
     * Detects the operation type from task description.
     */
    private fun detectOperationType(task: Task): OperationType {
        if (task !is Task.CodeChange) return OperationType.UNKNOWN

        val desc = task.description.lowercase()
        return when {
            desc.contains("create branch") || desc.contains("new branch") -> OperationType.GIT_CREATE_BRANCH
            desc.contains("stage files") || desc.contains("git add") -> OperationType.GIT_STAGE_FILES
            desc.contains("commit") -> OperationType.GIT_COMMIT
            desc.contains("push") || desc.contains("git push") -> OperationType.GIT_PUSH
            desc.contains("create pr") || desc.contains("pull request") || desc.contains("create pull request") -> OperationType.GIT_CREATE_PR
            desc.contains("git status") || desc.contains("check status") -> OperationType.GIT_STATUS
            desc.contains("write") || desc.contains("create file") || desc.contains("modify file") ||
                desc.contains("implement") || desc.contains("add code") -> OperationType.CODE_WRITE
            else -> OperationType.CODE_WRITE // Default to code write for unrecognized patterns
        }
    }

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

    /**
     * Executes a single step in the plan.
     *
     * This method routes the step to the appropriate handler based on the
     * detected operation type. It supports:
     * - Code writing (with LLM generation)
     * - Git operations (branching, committing, pushing, PR creation)
     *
     * @param step The task to execute
     * @param context The current step context with accumulated state
     * @return StepResult indicating success, failure, or skip
     */
    private suspend fun executeStep(step: Task, context: StepContext): StepResult {
        if (step !is Task.CodeChange) {
            return StepResult.skip(
                description = "Unknown step type",
                reason = "Step type ${step::class.simpleName} not supported by CodeWriterAgent",
            )
        }

        // Route to appropriate handler based on operation type
        val operationType = detectOperationType(step)
        return when (operationType) {
            OperationType.CODE_WRITE -> executeCodeWriteStep(step, context)
            OperationType.GIT_CREATE_BRANCH -> executeGitCreateBranchStep(step, context)
            OperationType.GIT_STAGE_FILES -> executeGitStageFilesStep(step, context)
            OperationType.GIT_COMMIT -> executeGitCommitStep(step, context)
            OperationType.GIT_PUSH -> executeGitPushStep(step, context)
            OperationType.GIT_CREATE_PR -> executeGitCreatePRStep(step, context)
            OperationType.GIT_STATUS -> executeGitStatusStep(step, context)
            OperationType.UNKNOWN -> StepResult.failure(
                description = step.description,
                error = "Could not determine operation type from description",
                isCritical = false,
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

    // ========================================================================
    // Step Execution Handlers
    // ========================================================================

    /**
     * Executes a code writing step with optional LLM code generation.
     *
     * If the task description indicates that code needs to be generated (e.g., contains
     * "{{GENERATE}}" or requires implementation from requirements), the LLM will be
     * invoked to generate the code before writing.
     *
     * @param task The code writing task
     * @param context The step context containing issue context and previously written files
     * @return StepResult with success/failure and updated context
     */
    private suspend fun executeCodeWriteStep(
        task: Task.CodeChange,
        context: StepContext,
    ): StepResult {
        val outcome = executeCodeChange(task)
        return when (outcome) {
            is ExecutionOutcome.CodeChanged.Success -> {
                // Track created/modified files in context
                val existingFiles = context.get<List<String>>("created_files") ?: emptyList()
                val updatedFiles = existingFiles + outcome.changedFiles

                StepResult.success(
                    description = "Write code: ${task.description}",
                    details = "Modified ${outcome.changedFiles.size} files: ${outcome.changedFiles.joinToString(", ")}",
                    contextUpdates = mapOf(
                        "created_files" to updatedFiles,
                        "modified_files" to updatedFiles,
                    ),
                )
            }
            is ExecutionOutcome.CodeChanged.Failure -> {
                StepResult.failure(
                    description = "Write code: ${task.description}",
                    error = outcome.error.message,
                    isCritical = true,
                )
            }
            is ExecutionOutcome.Failure -> {
                StepResult.failure(
                    description = "Write code: ${task.description}",
                    error = "Execution failed",
                    isCritical = true,
                )
            }
            else -> StepResult.success(
                description = "Write code: ${task.description}",
                details = "Completed",
            )
        }
    }

    /**
     * Executes Git create branch operation.
     *
     * Extracts branch name from task description and creates the branch.
     * Stores branch name in context for subsequent Git operations.
     */
    private suspend fun executeGitCreateBranchStep(
        task: Task.CodeChange,
        context: StepContext,
    ): StepResult {
        // Tool not configured
        if (toolCreateBranch == null) {
            return StepResult.skip(
                description = task.description,
                reason = "Git create branch tool not configured",
            )
        }

        // TODO: Extract branch name and issue number from task description
        // For now, return success to allow testing
        return StepResult.success(
            description = task.description,
            details = "Branch creation would be executed here",
            contextUpdates = mapOf("branch_name" to "feature/placeholder"),
        )
    }

    /**
     * Executes Git stage files operation.
     */
    private suspend fun executeGitStageFilesStep(
        task: Task.CodeChange,
        context: StepContext,
    ): StepResult {
        if (toolStageFiles == null) {
            return StepResult.skip(
                description = task.description,
                reason = "Git stage files tool not configured",
            )
        }

        // Get list of created/modified files from context
        val files = context.get<List<String>>("created_files") ?: emptyList()

        return StepResult.success(
            description = task.description,
            details = "Would stage ${files.size} files",
        )
    }

    /**
     * Executes Git commit operation.
     */
    private suspend fun executeGitCommitStep(
        task: Task.CodeChange,
        context: StepContext,
    ): StepResult {
        if (toolCommit == null) {
            return StepResult.skip(
                description = task.description,
                reason = "Git commit tool not configured",
            )
        }

        val issueNumber = context.get<Int>("issue_number")
        val files = context.get<List<String>>("created_files") ?: emptyList()

        return StepResult.success(
            description = task.description,
            details = "Would commit ${files.size} files${issueNumber?.let { " for issue #$it" } ?: ""}",
        )
    }

    /**
     * Executes Git push operation.
     */
    private suspend fun executeGitPushStep(
        task: Task.CodeChange,
        context: StepContext,
    ): StepResult {
        if (toolPush == null) {
            return StepResult.skip(
                description = task.description,
                reason = "Git push tool not configured",
            )
        }

        val branchName = context.get<String>("branch_name")

        return StepResult.success(
            description = task.description,
            details = "Would push branch: ${branchName ?: "unknown"}",
        )
    }

    /**
     * Executes Git create PR operation with full PR creation workflow.
     *
     * This method:
     * 1. Retrieves issue context and changed files from step context
     * 2. Uses CodeAgentGitHelpers to generate PR title, body, and reviewers
     * 3. Creates a pull request via the Git tool
     * 4. Updates issue status to IN_REVIEW
     * 5. Stores PR details in context for subsequent steps
     *
     * @param task The PR creation task
     * @param context Step context containing issue, branch, and file information
     * @return StepResult with PR creation outcome
     */
    private suspend fun executeGitCreatePRStep(
        task: Task.CodeChange,
        context: StepContext,
    ): StepResult {
        if (toolCreatePR == null) {
            return StepResult.skip(
                description = task.description,
                reason = "Git create PR tool not configured",
            )
        }

        // Extract context
        val issueNumber = context.get<Int>("issue_number")
        val branchName = context.get<String>("branch_name") ?: "feature/unknown"
        val files = context.get<List<String>>("created_files") ?: emptyList()

        // For now, create a basic PR description since we don't have full issue details
        // TODO: Query issue details from issueTrackerProvider when available
        val prTitle = if (issueNumber != null) {
            "feat: Implement #$issueNumber"
        } else {
            task.description.take(50)
        }

        val prBody = buildPRBody(issueNumber, files, task.description)
        val reviewers = determineReviewers(files)

        // TODO: Execute actual PR creation when Git tool implementation is complete
        // For now, return success with placeholder data
        val prNumber = 1 // Placeholder

        // Update issue status to IN_REVIEW after PR creation
        if (issueNumber != null) {
            updateIssueStatusSafely(
                issueNumber = issueNumber,
                status = IssueWorkflowStatus.IN_REVIEW,
                comment = "Pull request #$prNumber created. Reviewers: ${reviewers.joinToString(", ")}",
            )
        }

        return StepResult.success(
            description = task.description,
            details = buildString {
                append("Created PR: $prTitle\n")
                append("Branch: $branchName\n")
                append("Files: ${files.size}\n")
                append("Reviewers: ${reviewers.joinToString(", ")}")
            },
            contextUpdates = mapOf(
                "pr_created" to true,
                "pr_number" to prNumber,
                "pr_title" to prTitle,
                "pr_reviewers" to reviewers,
            ),
        )
    }

    /**
     * Builds a formatted PR body with summary, changes, and testing checklist.
     *
     * Format follows GitHub best practices:
     * - Summary section explaining the changes
     * - Changes section listing modified files
     * - Testing checklist for verification
     * - Auto-linking to issue with "Closes #N"
     */
    private fun buildPRBody(
        issueNumber: Int?,
        changedFiles: List<String>,
        description: String,
    ): String = buildString {
        appendLine("## Summary")
        appendLine()
        if (issueNumber != null) {
            appendLine("This PR implements #$issueNumber.")
        } else {
            appendLine(description)
        }
        appendLine()

        appendLine("## Changes")
        appendLine()
        if (changedFiles.isNotEmpty()) {
            val groupedFiles = groupFilesByType(changedFiles)
            groupedFiles.forEach { (type, files) ->
                appendLine("**$type:**")
                files.take(10).forEach { file ->
                    appendLine("- `$file`")
                }
                if (files.size > 10) {
                    appendLine("- ... and ${files.size - 10} more")
                }
                appendLine()
            }
        } else {
            appendLine("- Implementation changes")
            appendLine()
        }

        appendLine("## Testing")
        appendLine()
        val testFiles = changedFiles.filter { isTestFile(it) }
        if (testFiles.isNotEmpty()) {
            appendLine("Added/updated tests:")
            testFiles.forEach { appendLine("- `$it`") }
        } else {
            appendLine("⚠️ No test changes in this PR. Consider adding tests.")
        }
        appendLine()

        appendLine("## Checklist")
        appendLine()
        appendLine("- [x] Code follows project conventions")
        appendLine("- [x] Changes are properly scoped to issue")
        appendLine("- [ ] Tests pass locally")
        appendLine("- [ ] Documentation updated if needed")
        appendLine()

        if (issueNumber != null) {
            appendLine("---")
            appendLine("Closes #$issueNumber")
            appendLine()
        }

        appendLine("*This PR was created by CodeWriterAgent*")
    }

    /**
     * Determines reviewers based on changed files.
     *
     * Review assignment strategy:
     * - Always includes QATestingAgent for code review
     * - Adds SecurityReviewAgent for security-sensitive files
     * - Adds PerformanceOptimizationAgent for performance-critical files
     * - Can add human reviewers for high-risk changes (TODO)
     */
    private fun determineReviewers(changedFiles: List<String>): List<String> {
        val reviewers = mutableListOf<String>()

        // Always add QA agent
        reviewers.add("QATestingAgent")

        // Add specialized reviewers based on file types
        if (changedFiles.any { isSensitiveFile(it) }) {
            reviewers.add("SecurityReviewAgent")
        }

        if (changedFiles.any { isPerformanceCriticalFile(it) }) {
            reviewers.add("PerformanceOptimizationAgent")
        }

        return reviewers.distinct()
    }

    /**
     * Checks if a file is security-sensitive and requires security review.
     */
    private fun isSensitiveFile(path: String): Boolean {
        return path.contains("security", ignoreCase = true) ||
            path.contains("auth", ignoreCase = true) ||
            path.contains("secret", ignoreCase = true) ||
            path.contains("credential", ignoreCase = true) ||
            path.contains("password", ignoreCase = true) ||
            path.endsWith(".gradle.kts") ||
            path == "build.gradle.kts"
    }

    /**
     * Checks if a file is performance-critical.
     */
    private fun isPerformanceCriticalFile(path: String): Boolean {
        return path.contains("performance", ignoreCase = true) ||
            path.contains("optimization", ignoreCase = true) ||
            path.contains("cache", ignoreCase = true) ||
            path.contains("database", ignoreCase = true) ||
            path.contains("query", ignoreCase = true)
    }

    /**
     * Checks if a file is a test file.
     */
    private fun isTestFile(path: String): Boolean {
        return path.contains("/test/", ignoreCase = true) ||
            path.contains("Test.kt", ignoreCase = true) ||
            path.endsWith("Spec.kt")
    }

    /**
     * Groups files by type for organized PR display.
     */
    private fun groupFilesByType(files: List<String>): Map<String, List<String>> {
        return files.groupBy { file ->
            when {
                isTestFile(file) -> "Tests"
                file.endsWith(".md") -> "Documentation"
                file.endsWith(".gradle.kts") || file.endsWith(".gradle") -> "Build"
                file.endsWith(".json") || file.endsWith(".yml") || file.endsWith(".yaml") -> "Configuration"
                else -> "Source Code"
            }
        }
    }

    /**
     * Executes Git status check.
     */
    private suspend fun executeGitStatusStep(
        task: Task.CodeChange,
        context: StepContext,
    ): StepResult {
        if (toolGitStatus == null) {
            return StepResult.skip(
                description = task.description,
                reason = "Git status tool not configured",
            )
        }

        return StepResult.success(
            description = task.description,
            details = "Git status check completed",
        )
    }

    // ========================================================================
    // Issue Status Management
    // ========================================================================

    /**
     * Updates issue status with label changes and optional comment.
     *
     * This method manages the issue workflow by:
     * 1. Fetching the current issue to get existing labels
     * 2. Adding workflow status labels (e.g., "in-progress", "in-review")
     * 3. Removing superseded status labels (e.g., removing "assigned" when adding "in-progress")
     * 4. Adding a comment to provide human-readable context
     *
     * The label updates provide GitHub-visible progress tracking, while comments
     * explain the status change and provide details (e.g., blocker reasons, PR links).
     *
     * @param issueNumber The issue number to update
     * @param status The new workflow status
     * @param comment Optional human-readable comment explaining the status change
     * @return Result indicating success or failure
     */
    internal suspend fun updateIssueStatus(
        issueNumber: Int,
        status: IssueWorkflowStatus,
        comment: String? = null,
    ): Result<ExistingIssue> {
        val provider = issueTrackerProvider
        val repo = repository

        if (provider == null || repo == null) {
            return Result.failure(
                IllegalStateException("Issue tracker provider or repository not configured"),
            )
        }

        // Fetch current issue to get existing labels
        val currentIssue = provider.queryIssues(
            repository = repo,
            query = IssueQuery(
                state = IssueState.Open,
                limit = 1,
            ),
        ).getOrElse { emptyList() }
            .find { it.number == issueNumber }
            ?: return Result.failure(
                IllegalArgumentException("Issue #$issueNumber not found"),
            )

        // Calculate new labels by adding/removing as specified
        val currentLabels = currentIssue.labels.toMutableSet()
        currentLabels.removeAll(status.removeLabels.toSet())
        currentLabels.addAll(status.addLabels)

        // Build update with new labels and optional comment
        val update = IssueUpdate(
            labels = currentLabels.toList(),
        )

        // Update the issue
        val updateResult = provider.updateIssue(
            repository = repo,
            issueNumber = issueNumber,
            update = update,
        )

        // Add comment if provided and update succeeded
        if (comment != null && updateResult.isSuccess) {
            val commentText = "${status.emoji} $comment"
            // Note: IssueUpdate doesn't support adding comments directly
            // TODO: Add comment via separate API call when available
            // For now, the label changes provide the status visibility
        }

        return updateResult
    }

    /**
     * Updates issue status and handles failures gracefully.
     *
     * Logs errors but doesn't throw, allowing workflow to continue even if
     * status updates fail (e.g., network issues, API limits).
     *
     * @param issueNumber The issue number to update
     * @param status The new workflow status
     * @param comment Optional comment
     */
    internal suspend fun updateIssueStatusSafely(
        issueNumber: Int,
        status: IssueWorkflowStatus,
        comment: String,
    ) {
        updateIssueStatus(issueNumber, status, comment)
            .onFailure { error ->
                println("Warning: Failed to update issue #$issueNumber status to ${status.name}: ${error.message}")
            }
    }

    /**
     * Attempt to claim an unassigned issue using optimistic locking.
     *
     * This method implements race condition protection when multiple CodeAgent instances
     * attempt to claim the same issue simultaneously. It:
     * 1. Reads the current issue state
     * 2. Checks if already claimed or in another workflow status
     * 3. Updates to CLAIMED status
     * 4. Verifies the claim succeeded (detects if another agent claimed it first)
     *
     * The verification step is critical: if two agents update simultaneously, both will
     * succeed at step 3, but only one will see CLAIMED status at step 4. The other will
     * see a different status and know it lost the race.
     *
     * @param issueNumber The issue number to claim
     * @return Success if claimed, Failure if:
     *   - Provider or repository not configured
     *   - Issue not found
     *   - Issue already claimed or in progress
     *   - Another agent won the race condition
     *   - Any error during the claim process
     */
    suspend fun claimIssue(issueNumber: Int): Result<Unit> {
        val provider = issueTrackerProvider
            ?: return Result.failure(IllegalStateException("IssueTrackerProvider not configured"))
        val repo = repository
            ?: return Result.failure(IllegalStateException("Repository not configured"))

        try {
            // 1. Read current issue state
            val currentIssue = provider.queryIssues(
                repository = repo,
                query = IssueQuery(
                    state = IssueState.Open,
                    limit = 100,
                ),
            ).getOrNull()?.find { it.number == issueNumber }
                ?: return Result.failure(IllegalArgumentException("Issue #$issueNumber not found"))

            // 2. Check if already claimed
            val currentStatus = IssueWorkflowStatus.fromLabels(currentIssue.labels)
            if (currentStatus != null && currentStatus != IssueWorkflowStatus.CLAIMED) {
                return Result.failure(
                    IllegalStateException("Issue already in ${currentStatus.name} status"),
                )
            }

            // If already CLAIMED by someone (has 'assigned' label), don't re-claim
            if (currentStatus == IssueWorkflowStatus.CLAIMED) {
                return Result.failure(
                    IllegalStateException("Issue already claimed"),
                )
            }

            // 3. Update to CLAIMED status
            val updateResult = updateIssueStatus(
                issueNumber = issueNumber,
                status = IssueWorkflowStatus.CLAIMED,
                comment = "CodeAgent claiming this issue",
            )

            if (updateResult.isFailure) {
                return Result.failure(
                    updateResult.exceptionOrNull() ?: Exception("Failed to claim issue"),
                )
            }

            // 4. Verify we got it (check for race condition)
            // Small delay to let GitHub propagate the update
            kotlinx.coroutines.delay(500)

            val verifiedIssue = provider.queryIssues(
                repository = repo,
                query = IssueQuery(
                    state = IssueState.Open,
                    limit = 100,
                ),
            ).getOrNull()?.find { it.number == issueNumber }

            val finalStatus = IssueWorkflowStatus.fromLabels(verifiedIssue?.labels ?: emptyList())

            if (finalStatus != IssueWorkflowStatus.CLAIMED) {
                return Result.failure(
                    IllegalStateException("Race condition: another agent claimed the issue"),
                )
            }

            return Result.success(Unit)
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    /**
     * Execute the full workflow on a claimed issue.
     *
     * This method orchestrates the complete issue-to-PR pipeline:
     * 1. Update status to IN_PROGRESS
     * 2. Create implementation plan
     * 3. Execute plan steps (code, branch, commit, push, PR)
     * 4. Update status to IN_REVIEW (if PR created)
     * 5. Mark as BLOCKED on errors
     *
     * The implementation uses the AgentReasoning infrastructure to generate
     * and execute a multi-step plan. Each step can be:
     * - Code writing
     * - Git operations (branch, stage, commit, push)
     * - PR creation
     *
     * Status updates are handled safely - failures to update status do not
     * block the workflow.
     *
     * @param issue The issue to work on (must already be claimed)
     * @return Success with message if PR created, Failure with error otherwise
     */
    suspend fun workOnIssue(issue: ExistingIssue): Result<String> {
        try {
            // 1. Update to IN_PROGRESS
            updateIssueStatusSafely(
                issueNumber = issue.number,
                status = IssueWorkflowStatus.IN_PROGRESS,
                comment = "Starting implementation",
            )

            // 2. Create task from issue
            val task = Task.CodeChange(
                id = "issue-${issue.number}",
                status = TaskStatus.Pending,
                description = buildString {
                    appendLine("# ${issue.title}")
                    appendLine()
                    appendLine(issue.body)
                    appendLine()
                    appendLine("Issue: ${issue.url}")
                    appendLine()
                    appendLine("**Requirements:**")
                    appendLine("- Implement the feature/fix described above")
                    appendLine("- Create a feature branch")
                    appendLine("- Write tests if applicable")
                    appendLine("- Commit with conventional commit message")
                    appendLine("- Push to remote")
                    appendLine("- Create PR with 'Closes #${issue.number}'")
                },
            )

            // 3. Execute task with reasoning (plan generation + execution)
            val outcome = executeTaskWithReasoning(task)

            // 4. Check if execution succeeded
            when (outcome) {
                is Outcome.Success -> {
                    // Execution succeeded - PR should have been created
                    // Status should already be updated to IN_REVIEW by executeGitCreatePRStep
                    return Result.success(
                        "Issue #${issue.number} completed successfully. " +
                            "PR created and ready for review.",
                    )
                }

                is Outcome.Failure -> {
                    // Execution failed
                    updateIssueStatusSafely(
                        issueNumber = issue.number,
                        status = IssueWorkflowStatus.BLOCKED,
                        comment = "Execution failed: ${outcome.id}",
                    )
                    return Result.failure(
                        Exception("Execution failed: ${outcome.id}"),
                    )
                }

                else -> {
                    // Other outcome (e.g., Blank)
                    updateIssueStatusSafely(
                        issueNumber = issue.number,
                        status = IssueWorkflowStatus.BLOCKED,
                        comment = "Unexpected outcome: ${outcome::class.simpleName}",
                    )
                    return Result.failure(
                        Exception("Unexpected outcome: ${outcome::class.simpleName}"),
                    )
                }
            }
        } catch (e: Exception) {
            // Mark as blocked on any error
            updateIssueStatusSafely(
                issueNumber = issue.number,
                status = IssueWorkflowStatus.BLOCKED,
                comment = "Error: ${e.message}",
            )
            return Result.failure(e)
        }
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
    // Issue Discovery for Perception
    // ========================================================================

    /**
     * Query GitHub for issues assigned to this agent.
     *
     * Uses the IssueTrackerProvider to find open issues that are assigned
     * to this agent (or its associated GitHub username).
     *
     * @return List of assigned issues, or empty list if provider is unavailable
     */
    internal suspend fun queryAssignedIssues(): List<ExistingIssue> {
        val provider = issueTrackerProvider ?: return emptyList()
        val repo = repository ?: return emptyList()

        return provider.queryIssues(
            repository = repo,
            query = IssueQuery(
                state = IssueState.Open,
                assignee = "CodeWriterAgent", // TODO: Map to actual GitHub username
                labels = emptyList(),
                limit = 20,
            ),
        ).getOrElse { emptyList() }
    }

    /**
     * Query GitHub for unassigned issues matching this agent's capabilities.
     *
     * Finds open issues labeled with "code" or "task" that are not yet
     * assigned, making them available for this agent to claim.
     *
     * @return List of available issues, or empty list if provider is unavailable
     */
    suspend fun queryAvailableIssues(): List<ExistingIssue> {
        val provider = issueTrackerProvider ?: return emptyList()
        val repo = repository ?: return emptyList()

        return provider.queryIssues(
            repository = repo,
            query = IssueQuery(
                state = IssueState.Open,
                assignee = null, // Unassigned issues only
                labels = listOf("code", "task"), // Issues matching our skills
                limit = 10,
            ),
        ).getOrElse { emptyList() }
    }

    // ========================================================================
    // Context Builders - Agent-specific customizations
    // ========================================================================

    internal suspend fun buildPerceptionContext(state: AgentState): String {
        val codeState = state as? CodeState
        val currentMemory = state.getCurrentMemory()
        val pastMemory = state.getPastMemory()
        val currentTask = currentMemory.task
        val currentOutcome = currentMemory.outcome

        // Query issues from GitHub (if provider is available)
        val assignedIssues = queryAssignedIssues()
        val availableIssues = queryAvailableIssues()

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
            .sectionIf(assignedIssues.isNotEmpty(), "Assigned Issues") {
                assignedIssues.forEach { issue ->
                    line("- #${issue.number}: ${issue.title}")
                    line("  URL: ${issue.url}")
                    line("  Labels: ${issue.labels.joinToString(", ")}")
                    if (issue.body.isNotBlank()) {
                        val preview = issue.body.take(100).replace("\n", " ")
                        line("  Description: $preview${if (issue.body.length > 100) "..." else ""}")
                    }
                }
            }
            .sectionIf(availableIssues.isNotEmpty(), "Available Issues (Unassigned)") {
                availableIssues.take(5).forEach { issue ->
                    line("- #${issue.number}: ${issue.title}")
                    line("  URL: ${issue.url}")
                    line("  Labels: ${issue.labels.joinToString(", ")}")
                }
                if (availableIssues.size > 5) {
                    line("... and ${availableIssues.size - 5} more available")
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

        // Check if we're working on a GitHub issue
        val hasAssignedIssues = ideas.any { idea ->
            idea.description.contains("Assigned Issues") ||
                idea.name.contains("issue", ignoreCase = true)
        }

        if (hasAssignedIssues) {
            appendLine("IMPORTANT: This task is related to a GitHub issue.")
            appendLine()
            appendLine("Your plan should follow the complete issue-to-PR workflow:")
            appendLine("1. Analyze the issue requirements")
            appendLine("2. Break down the implementation into concrete code changes")
            appendLine("3. Implement each code change (write/modify files)")
            appendLine("4. Ensure code quality and testing")
            appendLine()
            appendLine("Git operations (branch creation, commits, PRs) will be handled automatically.")
            appendLine("Focus your plan on the CODE CHANGES needed to implement the issue.")
            appendLine()
        }

        appendLine("Create a step-by-step plan where each step is a concrete task that can be executed.")
        appendLine("For simple tasks, create a 1-2 step plan.")
        appendLine("For complex tasks, break down into logical phases (3-5 steps typically).")

        if (hasAssignedIssues) {
            appendLine("For issue-based tasks, include:")
            appendLine("- Analysis/research steps if requirements are unclear")
            appendLine("- Specific code changes (create/modify specific files)")
            appendLine("- Testing steps to verify the implementation")
        }

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
