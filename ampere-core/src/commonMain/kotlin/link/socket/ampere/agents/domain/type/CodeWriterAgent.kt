package link.socket.ampere.agents.domain.type

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import link.socket.ampere.agents.domain.concept.Idea
import link.socket.ampere.agents.domain.concept.Perception
import link.socket.ampere.agents.domain.concept.Plan
import link.socket.ampere.agents.domain.concept.expectation.Expectations
import link.socket.ampere.agents.domain.concept.knowledge.Knowledge
import link.socket.ampere.agents.domain.concept.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.concept.outcome.Outcome
import link.socket.ampere.agents.domain.concept.status.ExecutionStatus
import link.socket.ampere.agents.domain.concept.status.TaskStatus
import link.socket.ampere.agents.domain.concept.status.TicketStatus
import link.socket.ampere.agents.domain.concept.task.AssignedTo
import link.socket.ampere.agents.domain.concept.task.MeetingTask
import link.socket.ampere.agents.domain.concept.task.Task
import link.socket.ampere.agents.domain.concept.task.TicketTask
import link.socket.ampere.agents.domain.config.AgentConfiguration
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.execution.executor.Executor
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.request.ExecutionConstraints
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.McpTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.domain.util.toClientModelId

/**
 * First concrete agent that can read tickets, generate code, and validate results (scaffold).
 *
 * This implementation intentionally keeps logic simple and deterministic to
 * satisfy the initial milestone requirements. It is multiplatform-friendly as
 * it only relies on commonMain types and contracts.
 */
open class CodeWriterAgent(
    override val initialState: AgentState,
    override val agentConfiguration: AgentConfiguration,
    private val toolWriteCodeFile: Tool<ExecutionContext.Code.WriteCode>,
    private val coroutineScope: CoroutineScope,
    private val executor: Executor =
        FunctionExecutor.create(),
) : AutonomousAgent<AgentState>() {

    override val id: AgentId = "CodeWriterAgent"

    override val requiredTools: Set<Tool<*>> =
        setOf(toolWriteCodeFile)

    override val runLLMToEvaluatePerception: (perception: Perception<AgentState>) -> Idea =
        { perception ->
            runPerceptionEvaluation(perception)
        }

    override val runLLMToPlan: (task: Task, ideas: List<Idea>) -> Plan =
        { task, ideas ->
            generatePlan(task, ideas)
        }

    override val runLLMToExecuteTask: (task: Task) -> Outcome =
        { task ->
            runBlocking {
                executeTask(task)
            }
        }

    override val runLLMToExecuteTool: (tool: Tool<*>, request: ExecutionRequest<*>) -> ExecutionOutcome =
        { tool, request ->
            runBlocking {
                executeToolWithLLMGeneratedParameters(tool, request)
            }
        }

    override val runLLMToEvaluateOutcomes: (outcomes: List<Outcome>) -> Idea =
        { outcomes ->
            evaluateOutcomesAndGenerateLearnings(outcomes)
        }

    private fun writeCodeFile(
        executionRequest: ExecutionRequest<ExecutionContext.Code.WriteCode>,
        onCodeSubmittedOutcome: (Outcome) -> Unit,
    ) {
        coroutineScope.launch {
            val outcome = toolWriteCodeFile.execute(executionRequest)
            onCodeSubmittedOutcome(outcome)
        }
    }

    /**
     * Extract knowledge from completed task outcome.
     *
     * This creates immediate knowledge from a single task execution. For deeper
     * pattern analysis across multiple outcomes, use runLLMToEvaluateOutcomes.
     *
     * Knowledge extracted here captures:
     * - What approach was used (task description and plan)
     * - Whether it succeeded or failed
     * - Context that might be useful for similar future tasks
     */
    override fun extractKnowledgeFromOutcome(
        outcome: Outcome,
        task: Task,
        plan: Plan
    ): Knowledge.FromOutcome {
        // Build approach description from task and plan
        val approach = buildString {
            when (task) {
                is Task.CodeChange -> append("Code change: ${task.description}")
                is MeetingTask.AgendaItem -> append("Meeting item: ${task.title}")
                is TicketTask.CompleteSubticket -> append("Subticket: ${task.id}")
                is Task.Blank -> append("No specific task")
            }

            if (plan is Plan.ForTask && plan.tasks.isNotEmpty()) {
                append(" (${plan.tasks.size} steps)")
            }
        }

        // Extract learnings based on outcome type
        val learnings = when (outcome) {
            is ExecutionOutcome.CodeChanged.Success -> {
                buildString {
                    appendLine("✓ Code changes succeeded")
                    appendLine("Files modified: ${outcome.changedFiles.size}")
                    outcome.changedFiles.take(3).forEach { file ->
                        appendLine("  - $file")
                    }
                    if (outcome.changedFiles.size > 3) {
                        appendLine("  ... and ${outcome.changedFiles.size - 3} more")
                    }
                    appendLine("Validation: ${outcome.validation}")

                    val duration = outcome.executionEndTimestamp - outcome.executionStartTimestamp
                    appendLine("Duration: $duration")

                    appendLine()
                    appendLine("This approach was successful for this type of code change task.")
                }
            }
            is ExecutionOutcome.CodeChanged.Failure -> {
                buildString {
                    appendLine("✗ Code changes failed")
                    appendLine("Error: ${outcome.error}")
                    outcome.partiallyChangedFiles?.let { files ->
                        if (files.isNotEmpty()) {
                            appendLine("Partially changed ${files.size} files")
                        }
                    }

                    val duration = outcome.executionEndTimestamp - outcome.executionStartTimestamp
                    appendLine("Duration before failure: $duration")

                    appendLine()
                    appendLine("Future tasks should avoid this approach or address the error cause.")
                }
            }
            is ExecutionOutcome.CodeReading.Success -> {
                buildString {
                    appendLine("✓ Code reading succeeded")
                    appendLine("Files read: ${outcome.readFiles.size}")

                    val duration = outcome.executionEndTimestamp - outcome.executionStartTimestamp
                    appendLine("Duration: $duration")

                    appendLine()
                    appendLine("Code reading was successful for gathering context.")
                }
            }
            is ExecutionOutcome.CodeReading.Failure -> {
                buildString {
                    appendLine("✗ Code reading failed")
                    appendLine("Error: ${outcome.error}")

                    appendLine()
                    appendLine("Consider checking file paths or permissions for future reads.")
                }
            }
            is ExecutionOutcome.NoChanges.Success -> {
                "Task completed without changes: ${(outcome as ExecutionOutcome.NoChanges.Success).message}"
            }
            is ExecutionOutcome.NoChanges.Failure -> {
                "Task failed without changes: ${(outcome as ExecutionOutcome.NoChanges.Failure).message}"
            }
            is Outcome.Success -> "Task succeeded (details not available)"
            is Outcome.Failure -> "Task failed (details not available)"
            is Outcome.Blank -> "No outcome recorded"
            else -> "Outcome type: ${outcome::class.simpleName}"
        }

        return Knowledge.FromOutcome(
            outcomeId = outcome.id,
            approach = approach,
            learnings = learnings,
            timestamp = Clock.System.now()
        )
    }

    /**
     * Evaluates the agent's current perception and generates insights.
     *
     * This function analyzes the agent's state including current task, past outcomes,
     * and available tools, then calls an LLM to generate structured insights about
     * what the agent should focus on or be aware of.
     *
     * @param perception The current perception containing agent state and context
     * @return An Idea containing insights about the current situation
     */
    private fun runPerceptionEvaluation(perception: Perception<AgentState>): Idea {
        val state = perception.currentState

        // Extract relevant context from the agent's state
        val currentMemory = state.getCurrentMemory()
        val pastMemory = state.getPastMemory()

        val currentTask = currentMemory.task
        val currentOutcome = currentMemory.outcome

        // Build a context description for the LLM
        val contextDescription = buildString {
            appendLine("=== CodeWriterAgent State Analysis ===")
            appendLine()

            // Current task information
            appendLine("Current Task:")
            when (currentTask) {
                is Task.CodeChange -> {
                    appendLine("  Type: Code Change")
                    appendLine("  ID: ${currentTask.id}")
                    appendLine("  Status: ${currentTask.status}")
                    appendLine("  Description: ${currentTask.description}")
                    currentTask.assignedTo?.let { assignedTo ->
                        appendLine("  Assigned To: $assignedTo")
                    }
                }
                is MeetingTask.AgendaItem -> {
                    appendLine("  Type: Meeting Agenda Item")
                    appendLine("  ID: ${currentTask.id}")
                    appendLine("  Status: ${currentTask.status}")
                    appendLine("  Title: ${currentTask.title}")
                    currentTask.description?.let { desc ->
                        appendLine("  Description: $desc")
                    }
                }
                is TicketTask.CompleteSubticket -> {
                    appendLine("  Type: Complete Subticket")
                    appendLine("  ID: ${currentTask.id}")
                    appendLine("  Status: ${currentTask.status}")
                }
                is Task.Blank -> {
                    appendLine("  No active task")
                }
            }
            appendLine()

            // Current outcome information
            appendLine("Current Outcome:")
            when (currentOutcome) {
                is Outcome.Success -> appendLine("  Status: ✓ Success")
                is Outcome.Failure -> appendLine("  Status: ✗ Failure")
                is Outcome.Blank -> appendLine("  Status: No outcome yet")
                else -> appendLine("  Status: ${currentOutcome::class.simpleName}")
            }
            appendLine()

            // Past memory information
            if (pastMemory.tasks.isNotEmpty()) {
                appendLine("Past Tasks: ${pastMemory.tasks.size} completed")
            }
            if (pastMemory.outcomes.isNotEmpty()) {
                appendLine("Past Outcomes: ${pastMemory.outcomes.size} recorded")
            }

            // Knowledge from past outcomes
            if (pastMemory.knowledgeFromOutcomes.isNotEmpty()) {
                appendLine()
                appendLine("Learned Knowledge from Past Outcomes:")
                pastMemory.knowledgeFromOutcomes.takeLast(3).forEach { knowledge ->
                    appendLine("  - Approach: ${knowledge.approach}")
                    appendLine("    Learnings: ${knowledge.learnings}")
                }
            }

            appendLine()
            appendLine("Available Tools:")
            requiredTools.forEach { tool ->
                appendLine("  - ${tool.id}: ${tool.description}")
            }
        }

        // Craft a prompt for the LLM
        val perceptionPrompt = """
            You are the perception module of an autonomous code-writing agent.
            Analyze the current state and generate insights that will inform planning and execution.

            Consider:
            - Is there a task that needs attention?
            - Are there patterns in recent successes or failures?
            - Are the necessary tools available for the current task?
            - What context from past outcomes should inform the current approach?
            - Are there warning signs (e.g., blank state, missing task details)?

            Current State:
            $contextDescription

            Generate 1-3 specific, actionable insights about this situation.
            Each insight should identify something important and suggest why it matters.

            Format your response as a JSON array of insight objects:
            [
              {
                "observation": "what you noticed",
                "implication": "why it matters",
                "confidence": "high|medium|low"
              }
            ]

            Respond ONLY with the JSON array, no other text.
        """.trimIndent()

        // Call the LLM
        val llmResponse = try {
            callLLM(perceptionPrompt)
        } catch (e: Exception) {
            // Fallback if LLM call fails
            return createFallbackIdea(currentTask, "LLM call failed: ${e.message}")
        }

        // Parse the response into structured insights
        val idea = try {
            parseInsightsIntoIdea(llmResponse, currentTask)
        } catch (e: Exception) {
            // Fallback if parsing fails
            createFallbackIdea(currentTask, "Failed to parse LLM response: ${e.message}")
        }

        return idea
    }


    // TODO: MMOOVOOVEEEEE
    /**
     * Calls the LLM with the given prompt and returns the response.
     *
     * This uses the agent's configured AI provider and model to generate a response.
     *
     * @param prompt The prompt to send to the LLM
     * @return The LLM's response text
     */
    override fun callLLM(prompt: String): String = runBlocking {
        val client = agentConfiguration.aiConfiguration.provider.client
        val model = agentConfiguration.aiConfiguration.model

        val messages = listOf(
            ChatMessage(
                role = ChatRole.System,
                content = "You are an analytical agent perception system. Respond only with valid JSON."
            ),
            ChatMessage(
                role = ChatRole.User,
                content = prompt
            )
        )

        val request = ChatCompletionRequest(
            model = model.toClientModelId(),
            messages = messages,
            temperature = 0.3, // Low temperature for analytical, consistent responses
            maxTokens = 500
        )

        val completion = client.chatCompletion(request)
        completion.choices.firstOrNull()?.message?.content
            ?: throw IllegalStateException("No response from LLM")
    }

    /**
     * Parses the LLM's JSON response into an Idea object.
     *
     * Expected format:
     * [
     *   {
     *     "observation": "...",
     *     "implication": "...",
     *     "confidence": "high|medium|low"
     *   }
     * ]
     *
     * @param jsonResponse The JSON response from the LLM
     * @param task The current task being analyzed
     * @return An Idea containing the parsed insights
     */
    private fun parseInsightsIntoIdea(jsonResponse: String, task: Task): Idea {
        val json = Json { ignoreUnknownKeys = true }

        // Clean up response (remove markdown code blocks if present)
        val cleanedResponse = jsonResponse
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val insightsArray = json.parseToJsonElement(cleanedResponse).jsonArray

        if (insightsArray.isEmpty()) {
            return createFallbackIdea(task, "No insights generated")
        }

        // Extract insights
        val insights = insightsArray.map { element ->
            val obj = element.jsonObject
            val observation = obj["observation"]?.jsonPrimitive?.content ?: "No observation"
            val implication = obj["implication"]?.jsonPrimitive?.content ?: "No implication"
            val confidence = obj["confidence"]?.jsonPrimitive?.content ?: "medium"

            "$observation → $implication (confidence: $confidence)"
        }

        // Create an Idea from the insights
        val taskDescription = when (task) {
            is Task.CodeChange -> task.description
            is MeetingTask.AgendaItem -> task.title
            is TicketTask.CompleteSubticket -> "subticket ${task.id}"
            is Task.Blank -> "current task"
        }

        return Idea(
            name = "Perception analysis for $taskDescription",
            description = insights.joinToString("\n\n")
        )
    }

    /**
     * Creates a fallback Idea when LLM call or parsing fails.
     *
     * This ensures the agent can continue operating even if perception evaluation
     * encounters errors.
     *
     * @param task The current task
     * @param reason Why the fallback was needed
     * @return A basic Idea describing the current state
     */
    private fun createFallbackIdea(task: Task, reason: String): Idea {
        val taskStatus = when (task) {
            is Task.CodeChange -> "Code change task: ${task.description} (Status: ${task.status})"
            is MeetingTask.AgendaItem -> "Meeting agenda item: ${task.title} (Status: ${task.status})"
            is TicketTask.CompleteSubticket -> "Complete subticket: ${task.id} (Status: ${task.status})"
            is Task.Blank -> "No active task"
        }

        return Idea(
            name = "Basic perception (fallback)",
            description = """
                Current state: $taskStatus

                Note: Advanced perception analysis unavailable - $reason

                Available tools: ${requiredTools.joinToString(", ") { it.id }}
            """.trimIndent()
        )
    }

    /**
     * Generates an executable plan for accomplishing a given task.
     *
     * This function transforms high-level understanding (task + ideas) into concrete,
     * sequential steps that can be executed by the agent. It uses an LLM to reason
     * about how to decompose the task into actionable sub-tasks.
     *
     * The planning process:
     * 1. Extracts task description and synthesizes insights from ideas
     * 2. Builds a planning prompt with task context and available tools
     * 3. Calls LLM to generate structured plan steps
     * 4. Parses LLM response into Plan object with Task objects
     * 5. Falls back to simple plan if LLM call or parsing fails
     *
     * @param task The task to plan for
     * @param ideas Insights from perception that inform planning
     * @return A Plan containing sequential steps (as Tasks) to accomplish the goal
     */
    private fun generatePlan(task: Task, ideas: List<Idea>): Plan {
        // Handle blank task
        if (task is Task.Blank) {
            return Plan.Companion.blank
        }

        // Extract task description
        val taskDescription = when (task) {
            is Task.CodeChange -> task.description
            is MeetingTask.AgendaItem -> task.title
            is TicketTask.CompleteSubticket -> "Complete subticket ${task.id}"
            is Task.Blank -> return Plan.Companion.blank
        }

        // Synthesize ideas into planning context
        val ideaSummary = if (ideas.isNotEmpty()) {
            ideas.joinToString("\n\n") { idea ->
                "${idea.name}:\n${idea.description}"
            }
        } else {
            "No insights available from perception phase."
        }

        // Build planning prompt
        val planningPrompt = """
            You are the planning module of an autonomous code-writing agent.
            Your task is to create a concrete, executable plan to accomplish the given task.

            Task: $taskDescription

            Insights from Perception:
            $ideaSummary

            Available Tools:
            ${requiredTools.joinToString("\n") { "- ${it.id}: ${it.description}" }}

            Create a step-by-step plan where each step is a concrete task that can be executed.
            Each step should:
            1. Have a clear, actionable description
            2. Specify which tool to use (if applicable)
            3. Be sequentially ordered with clear dependencies
            4. Include validation/verification steps where appropriate

            For simple tasks, create a 1-2 step plan.
            For complex tasks, break down into logical phases (3-5 steps typically).
            Avoid excessive granularity - focus on meaningful phases of work.

            Format your response as a JSON object:
            {
              "steps": [
                {
                  "description": "what this step accomplishes",
                  "toolToUse": "tool ID or null if no specific tool",
                  "requiresPreviousStep": true/false
                }
              ],
              "estimatedComplexity": 1-10,
              "requiresHumanInput": true/false
            }

            Respond ONLY with the JSON object, no other text.
        """.trimIndent()

        // Call LLM
        val llmResponse = try {
            callLLM(planningPrompt)
        } catch (e: Exception) {
            // Fallback: create a simple single-step plan
            return createFallbackPlan(task, "LLM call failed: ${e.message}")
        }

        // Parse response into Plan
        val plan = try {
            parseLLMResponseIntoPlan(llmResponse, task)
        } catch (e: Exception) {
            // Fallback if parsing fails
            createFallbackPlan(task, "Failed to parse LLM response: ${e.message}")
        }

        return plan
    }

    /**
     * Parses LLM JSON response into a structured Plan object.
     *
     * Expected JSON format:
     * {
     *   "steps": [
     *     {
     *       "description": "...",
     *       "toolToUse": "...",
     *       "requiresPreviousStep": true/false
     *     }
     *   ],
     *   "estimatedComplexity": 1-10,
     *   "requiresHumanInput": true/false
     * }
     *
     * @param jsonResponse The JSON response from the LLM
     * @param task The task being planned
     * @return A Plan containing the parsed steps as Task objects
     */
    private fun parseLLMResponseIntoPlan(jsonResponse: String, task: Task): Plan {
        val json = Json { ignoreUnknownKeys = true }

        // Clean up response (remove markdown code blocks if present)
        val cleanedResponse = jsonResponse
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val planJson = json.parseToJsonElement(cleanedResponse).jsonObject

        val stepsArray = planJson["steps"]?.jsonArray
            ?: throw IllegalStateException("No steps in plan")

        val complexity = planJson["estimatedComplexity"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5

        // Validate steps are logically ordered
        val validatedSteps = validatePlanSteps(stepsArray)

        // Convert steps into Task objects
        val planTasks = validatedSteps.mapIndexed { index, stepElement ->
            val stepObj = stepElement.jsonObject
            val description = stepObj["description"]?.jsonPrimitive?.content
                ?: "Step ${index + 1}"

            Task.CodeChange(
                id = "step-${index + 1}-${task.id}",
                status = TaskStatus.Pending,
                description = description,
                assignedTo = when (task) {
                    is Task.CodeChange -> task.assignedTo
                    else -> null
                }
            )
        }

        return Plan.ForTask(
            task = task,
            tasks = planTasks,
            estimatedComplexity = complexity,
            expectations = Expectations.Companion.blank
        )
    }

    /**
     * Validates that plan steps are logically sequenced and executable.
     *
     * Checks for:
     * - At least one step exists
     * - Steps have descriptions
     * - Dependencies are valid (if we add dependency tracking in the future)
     *
     * @param steps The array of step JSON objects
     * @return The validated steps (same as input if valid)
     * @throws IllegalStateException if validation fails
     */
    private fun validatePlanSteps(steps: JsonArray): JsonArray {
        if (steps.isEmpty()) {
            throw IllegalStateException("Plan must contain at least one step")
        }

        // Verify each step has a description
        steps.forEachIndexed { index, stepElement ->
            val stepObj = stepElement.jsonObject
            val description = stepObj["description"]?.jsonPrimitive?.content
            if (description.isNullOrBlank()) {
                throw IllegalStateException("Step ${index + 1} is missing a description")
            }
        }

        return steps
    }

    /**
     * Creates a fallback plan when LLM call or parsing fails.
     *
     * This ensures the agent can continue operating even if planning encounters errors.
     * The fallback is a simple single-step plan that attempts to execute the task directly.
     *
     * @param task The task to create a plan for
     * @param reason Why the fallback was needed
     * @return A simple Plan with one step
     */
    private fun createFallbackPlan(task: Task, reason: String): Plan {
        val taskDescription = when (task) {
            is Task.CodeChange -> task.description
            is MeetingTask.AgendaItem -> task.title
            is TicketTask.CompleteSubticket -> "Complete subticket ${task.id}"
            is Task.Blank -> return Plan.Companion.blank
        }

        // Create a simple single-step plan as fallback
        val fallbackTask = Task.CodeChange(
            id = "step-1-${task.id}",
            status = TaskStatus.Pending,
            description = "Execute: $taskDescription (Note: Advanced planning unavailable - $reason)",
            assignedTo = when (task) {
                is Task.CodeChange -> task.assignedTo
                else -> null
            }
        )

        return Plan.ForTask(
            task = task,
            tasks = listOf(fallbackTask),
            estimatedComplexity = 3,
            expectations = Expectations.Companion.blank
        )
    }

    /**
     * Executes a task by using LLM to determine code changes and executing them via the executor.
     *
     * This function:
     * 1. Analyzes the task to determine what code needs to be written
     * 2. Uses LLM to generate file paths and code content
     * 3. Creates execution requests for each file
     * 4. Executes them through the executor (not directly calling tools)
     * 5. Collects outcomes and returns overall result
     *
     * @param task The task to execute
     * @return Outcome indicating success or failure
     */
    private suspend fun executeTask(task: Task): Outcome {
        // Handle blank task
        if (task is Task.Blank) {
            return Outcome.Companion.blank
        }

        // Only handle CodeChange tasks for now
        if (task !is Task.CodeChange) {
            return createTaskFailureOutcome(
                task,
                "Unsupported task type: ${task::class.simpleName}. " +
                    "CodeWriterAgent currently only supports Task.CodeChange"
            )
        }

        val startTime = Clock.System.now()

        // Use LLM to determine what code changes to make
        val codeGenPrompt = buildCodeGenerationPrompt(task)
        val llmResponse = try {
            callLLM(codeGenPrompt)
        } catch (e: Exception) {
            return createTaskFailureOutcome(task, "LLM call failed: ${e.message}")
        }

        // Parse the response into file specifications
        val fileSpecs = try {
            parseCodeGenerationResponse(llmResponse)
        } catch (e: Exception) {
            return createTaskFailureOutcome(task, "Failed to parse LLM response: ${e.message}")
        }

        if (fileSpecs.isEmpty()) {
            return createTaskFailureOutcome(task, "No files generated by LLM")
        }

        // Execute each file write operation
        val executionOutcomes = mutableListOf<ExecutionOutcome>()

        for (fileSpec in fileSpecs) {
            // Create execution request
            val executionRequest = createExecutionRequest(task, fileSpec)

            // Execute via executor (not calling tool directly!)
            val outcome = try {
                executeViaExecutor(executionRequest)
            } catch (e: Exception) {
                // If execution fails, create a failure outcome
                ExecutionOutcome.NoChanges.Failure(
                    executorId = executor.id,
                    ticketId = executionRequest.context.ticket.id,
                    taskId = task.id,
                    executionStartTimestamp = startTime,
                    executionEndTimestamp = Clock.System.now(),
                    message = "Execution failed for file ${fileSpec.path}: ${e.message}",
                )
            }

            executionOutcomes.add(outcome)

            // Stop on first failure
            if (outcome is ExecutionOutcome.Failure) {
                break
            }
        }

        // Convert execution outcomes to overall task outcome
        return convertExecutionOutcomesToTaskOutcome(executionOutcomes, task, startTime)
    }

    /**
     * Builds the LLM prompt for code generation.
     *
     * This prompt asks the LLM to analyze the task and determine what code changes are needed.
     *
     * @param task The task to generate code for
     * @return The prompt string
     */
    private fun buildCodeGenerationPrompt(task: Task.CodeChange): String {
        return """
            You are the execution module of an autonomous code-writing agent.
            Your task is to generate the actual code changes needed to complete this task.

            Task Description: ${task.description}

            Available Tools:
            ${requiredTools.joinToString("\n") { "- ${it.id}: ${it.description}" }}

            Analyze the task and determine:
            1. What file(s) need to be created or modified
            2. What content should be written to each file
            3. The order in which files should be written (if multiple)

            Generate COMPLETE, WORKING code files. Do not use placeholders or TODOs.
            Each file should be production-ready and follow best practices.

            Format your response as a JSON object:
            {
              "files": [
                {
                  "path": "relative/path/to/file.kt",
                  "content": "the complete file content here",
                  "reason": "brief explanation of why this file is needed"
                }
              ]
            }

            Important:
            - Use relative file paths (e.g., "src/main/kotlin/MyClass.kt")
            - Include the COMPLETE file content, not snippets
            - Ensure code is syntactically correct and follows Kotlin conventions
            - If multiple files are needed, order them logically

            Respond ONLY with the JSON object, no other text.
        """.trimIndent()
    }

    /**
     * Parses the LLM's JSON response into file specifications.
     *
     * Expected format:
     * {
     *   "files": [
     *     {
     *       "path": "...",
     *       "content": "...",
     *       "reason": "..."
     *     }
     *   ]
     * }
     *
     * @param jsonResponse The JSON response from the LLM
     * @return List of file specifications
     */
    private fun parseCodeGenerationResponse(jsonResponse: String): List<FileSpec> {
        val json = Json { ignoreUnknownKeys = true }

        // Clean up response (remove markdown code blocks if present)
        val cleanedResponse = jsonResponse
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val responseJson = json.parseToJsonElement(cleanedResponse).jsonObject

        val filesArray = responseJson["files"]?.jsonArray
            ?: throw IllegalStateException("No 'files' array in response")

        if (filesArray.isEmpty()) {
            throw IllegalStateException("Empty 'files' array in response")
        }

        return filesArray.map { element ->
            val fileObj = element.jsonObject
            val path = fileObj["path"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("File specification missing 'path'")
            val content = fileObj["content"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("File specification missing 'content'")
            val reason = fileObj["reason"]?.jsonPrimitive?.content
                ?: "No reason provided"

            FileSpec(path, content, reason)
        }
    }

    /**
     * Creates an execution request for a file specification.
     *
     * Builds the proper ExecutionRequest with ExecutionContext.Code.WriteCode.
     *
     * @param task The task being executed
     * @param fileSpec The file specification to execute
     * @return ExecutionRequest ready for executor
     */
    private fun createExecutionRequest(
        task: Task.CodeChange,
        fileSpec: FileSpec,
    ): ExecutionRequest<ExecutionContext.Code.WriteCode> {
        // Create a ticket for this task (or use existing if available)
        val ticket = createTicketForTask(task)

        // Create workspace (use current directory for now)
        val workspace = ExecutionWorkspace(baseDirectory = ".")

        return ExecutionRequest(
            context = ExecutionContext.Code.WriteCode(
                executorId = executor.id,
                ticket = ticket,
                task = task,
                instructions = "Write file ${fileSpec.path}: ${fileSpec.reason}",
                knowledgeFromPastMemory = emptyList(),
                workspace = workspace,
                instructionsPerFilePath = listOf(fileSpec.path to fileSpec.content),
            ),
            constraints = ExecutionConstraints(
                requireTests = false,  // Don't require tests for now
                requireLinting = false,  // Don't require linting for now
            ),
        )
    }

    /**
     * Creates a ticket for a task (if it doesn't have one already).
     *
     * This is needed because ExecutionContext requires a ticket.
     *
     * @param task The task to create a ticket for
     * @return A ticket
     */
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

    /**
     * Executes a request via the executor, collecting the flow and extracting the final outcome.
     *
     * This is the key architectural decision: agents invoke tools through executors,
     * not by calling tool.execute() directly. This provides:
     * - Observability through ExecutionStatus events
     * - Consistent error handling
     * - Separation of concerns
     *
     * @param request The execution request
     * @return The final execution outcome
     */
    private suspend fun executeViaExecutor(
        request: ExecutionRequest<ExecutionContext.Code.WriteCode>,
    ): ExecutionOutcome {
        // Execute via executor (returns Flow<ExecutionStatus>)
        val statusFlow = executor.execute(request, toolWriteCodeFile)

        // Collect the flow to get the final status
        // The last emitted status will be either Completed or Failed
        val finalStatus = statusFlow.last()

        // Extract the outcome from the final status
        return when (finalStatus) {
            is ExecutionStatus.Completed -> finalStatus.result
            is ExecutionStatus.Failed -> finalStatus.result
            else -> {
                // This shouldn't happen (flow should always end with Completed or Failed)
                // but handle it gracefully
                val now = Clock.System.now()
                ExecutionOutcome.NoChanges.Failure(
                    executorId = executor.id,
                    ticketId = request.context.ticket.id,
                    taskId = request.context.task.id,
                    executionStartTimestamp = now,
                    executionEndTimestamp = now,
                    message = "Unexpected final status: ${finalStatus::class.simpleName}",
                )
            }
        }
    }

    /**
     * Converts execution outcomes to a task-level outcome.
     *
     * This aggregates multiple file write outcomes into a single task outcome.
     *
     * @param outcomes The execution outcomes from file writes
     * @param task The task that was executed
     * @param startTime When task execution started
     * @return Overall task outcome
     */
    private fun convertExecutionOutcomesToTaskOutcome(
        outcomes: List<ExecutionOutcome>,
        task: Task,
        startTime: Instant,
    ): Outcome {
        if (outcomes.isEmpty()) {
            return createTaskFailureOutcome(task, "No execution outcomes")
        }

        // Check if all succeeded
        val allSucceeded = outcomes.all { it is ExecutionOutcome.Success }

        if (allSucceeded) {
            // All files written successfully
            val filesWritten = outcomes.filterIsInstance<ExecutionOutcome.CodeChanged.Success>()
                .flatMap { it.changedFiles }

            // Since ExecutionOutcome extends Outcome, we can return it directly
            // But for task-level outcome, we want the last successful outcome
            return outcomes.last()
        } else {
            // Some failed - return the first failure
            val firstFailure = outcomes.firstOrNull { it is ExecutionOutcome.Failure }
                ?: return createTaskFailureOutcome(task, "Unknown failure")

            return firstFailure
        }
    }

    /**
     * Creates a failure outcome for a task that couldn't be executed.
     *
     * This is used when the task itself fails before we can even try executing files
     * (e.g., LLM call fails, parsing fails, etc.)
     *
     * @param task The task that failed
     * @param reason Why it failed
     * @return Failure outcome
     */
    private fun createTaskFailureOutcome(task: Task, reason: String): Outcome {
        val now = Clock.System.now()

        // Create a minimal ticket for the failure outcome
        val ticket = when (task) {
            is Task.CodeChange -> createTicketForTask(task)
            else -> Ticket(
                id = "ticket-${task.id}",
                title = "Task ${task.id}",
                description = "Task execution",
                type = TicketType.TASK,
                priority = TicketPriority.MEDIUM,
                status = TicketStatus.Blocked,
                assignedAgentId = id,
                createdByAgentId = id,
                createdAt = now,
                updatedAt = now,
                dueDate = null,
            )
        }

        return ExecutionOutcome.NoChanges.Failure(
            executorId = executor.id,
            ticketId = ticket.id,
            taskId = task.id,
            executionStartTimestamp = now,
            executionEndTimestamp = now,
            message = reason,
        )
    }

    /**
     * Evaluates execution outcomes and generates learnings that feed back into the cognitive loop.
     *
     * This function closes the learning loop by:
     * 1. Analyzing execution outcomes to identify patterns of success and failure
     * 2. Using an LLM to extract actionable insights from these patterns
     * 3. Creating Knowledge.FromOutcome entries for each learning
     * 4. Storing learnings in agent memory for future reference
     * 5. Returning an Idea that summarizes the learnings
     *
     * The insights generated here inform future perception, planning, and execution,
     * enabling the agent to improve over time by learning from experience.
     *
     * @param outcomes List of outcomes from recent executions
     * @return An Idea containing synthesized learnings from the outcomes
     */
    protected open fun evaluateOutcomesAndGenerateLearnings(outcomes: List<Outcome>): Idea {
        // Handle empty outcomes
        if (outcomes.isEmpty()) {
            return Idea(
                name = "No outcomes to evaluate",
                description = "No execution outcomes were provided for evaluation."
            )
        }

        // Handle blank outcomes
        if (outcomes.all { it is Outcome.Blank }) {
            return Idea(
                name = "Only blank outcomes",
                description = "All provided outcomes were blank - no learnings can be extracted."
            )
        }

        // Build outcome analysis context
        val analysisContext = buildOutcomeAnalysisContext(outcomes)

        // Craft evaluation prompt
        val evaluationPrompt = buildOutcomeEvaluationPrompt(analysisContext)

        // Call LLM to generate insights
        val llmResponse = try {
            callLLM(evaluationPrompt)
        } catch (e: Exception) {
            return createFallbackLearningIdea(outcomes, "LLM call failed: ${e.message}")
        }

        // Parse insights into Knowledge objects
        val knowledgeEntries = try {
            parseLearningsFromResponse(llmResponse, outcomes)
        } catch (e: Exception) {
            return createFallbackLearningIdea(outcomes, "Failed to parse learnings: ${e.message}")
        }

        // Store learnings in agent memory for future reference
        if (knowledgeEntries.isNotEmpty()) {
            initialState.addToPastKnowledge(
                rememberedKnowledgeFromOutcomes = knowledgeEntries
            )
        }

        // Create an Idea summarizing the learnings
        return createLearningIdea(knowledgeEntries, outcomes)
    }

    /**
     * Builds a context description from execution outcomes for LLM analysis.
     *
     * This extracts key information from outcomes including:
     * - Success vs failure counts
     * - Details about successful executions
     * - Details about failed executions
     * - Execution timing and performance data
     *
     * @param outcomes The outcomes to analyze
     * @return A structured context description for the LLM
     */
    private fun buildOutcomeAnalysisContext(outcomes: List<Outcome>): String {
        val successfulOutcomes = outcomes.filterIsInstance<Outcome.Success>()
        val failedOutcomes = outcomes.filterIsInstance<Outcome.Failure>()

        return buildString {
            appendLine("=== Execution Outcome Analysis ===")
            appendLine()
            appendLine("Total Outcomes: ${outcomes.size}")
            appendLine("Successful: ${successfulOutcomes.size}")
            appendLine("Failed: ${failedOutcomes.size}")
            appendLine()

            // Analyze successful outcomes
            if (successfulOutcomes.isNotEmpty()) {
                appendLine("Successful Executions:")
                appendLine()

                successfulOutcomes.forEach { outcome ->
                    when (outcome) {
                        is ExecutionOutcome.CodeChanged.Success -> {
                            val duration = outcome.executionEndTimestamp - outcome.executionStartTimestamp
                            appendLine("✓ Code Changed Successfully")
                            appendLine("  Task: ${outcome.taskId}")
                            appendLine("  Files Changed: ${outcome.changedFiles.size}")
                            outcome.changedFiles.take(5).forEach { file ->
                                appendLine("    - $file")
                            }
                            if (outcome.changedFiles.size > 5) {
                                appendLine("    ... and ${outcome.changedFiles.size - 5} more")
                            }
                            appendLine("  Duration: $duration")
                            appendLine("  Validation: ${outcome.validation}")
                            appendLine()
                        }
                        is ExecutionOutcome.CodeReading.Success -> {
                            val duration = outcome.executionEndTimestamp - outcome.executionStartTimestamp
                            appendLine("✓ Code Read Successfully")
                            appendLine("  Task: ${outcome.taskId}")
                            appendLine("  Files Read: ${outcome.readFiles.size}")
                            outcome.readFiles.take(3).forEach { (path, _) ->
                                appendLine("    - $path")
                            }
                            if (outcome.readFiles.size > 3) {
                                appendLine("    ... and ${outcome.readFiles.size - 3} more")
                            }
                            appendLine("  Duration: $duration")
                            appendLine()
                        }
                        is ExecutionOutcome.NoChanges.Success -> {
                            val duration = outcome.executionEndTimestamp - outcome.executionStartTimestamp
                            appendLine("✓ No Changes (Success)")
                            appendLine("  Task: ${outcome.taskId}")
                            appendLine("  Message: ${outcome.message}")
                            appendLine("  Duration: $duration")
                            appendLine()
                        }
                        else -> {
                            appendLine("✓ Success: ${outcome::class.simpleName}")
                            if (outcome is ExecutionOutcome) {
                                appendLine("  Task: ${outcome.taskId}")
                            }
                            appendLine()
                        }
                    }
                }
            }

            // Analyze failed outcomes
            if (failedOutcomes.isNotEmpty()) {
                appendLine("Failed Executions:")
                appendLine()

                failedOutcomes.forEach { outcome ->
                    when (outcome) {
                        is ExecutionOutcome.CodeChanged.Failure -> {
                            val duration = outcome.executionEndTimestamp - outcome.executionStartTimestamp
                            appendLine("✗ Code Change Failed")
                            appendLine("  Task: ${outcome.taskId}")
                            appendLine("  Error: ${outcome.error}")
                            outcome.partiallyChangedFiles?.let { files ->
                                if (files.isNotEmpty()) {
                                    appendLine("  Partially Changed Files: ${files.size}")
                                    files.take(3).forEach { file ->
                                        appendLine("    - $file")
                                    }
                                }
                            }
                            appendLine("  Duration: $duration")
                            appendLine()
                        }
                        is ExecutionOutcome.CodeReading.Failure -> {
                            val duration = outcome.executionEndTimestamp - outcome.executionStartTimestamp
                            appendLine("✗ Code Reading Failed")
                            appendLine("  Task: ${outcome.taskId}")
                            appendLine("  Error: ${outcome.error}")
                            outcome.partiallyReadFiles?.let { files ->
                                if (files.isNotEmpty()) {
                                    appendLine("  Partially Read Files: ${files.size}")
                                }
                            }
                            appendLine("  Duration: $duration")
                            appendLine()
                        }
                        is ExecutionOutcome.NoChanges.Failure -> {
                            val duration = outcome.executionEndTimestamp - outcome.executionStartTimestamp
                            appendLine("✗ Execution Failed (No Changes)")
                            appendLine("  Task: ${outcome.taskId}")
                            appendLine("  Message: ${outcome.message}")
                            appendLine("  Duration: $duration")
                            appendLine()
                        }
                        else -> {
                            appendLine("✗ Failure: ${outcome::class.simpleName}")
                            if (outcome is ExecutionOutcome) {
                                appendLine("  Task: ${outcome.taskId}")
                            }
                            appendLine()
                        }
                    }
                }
            }

            // Add summary statistics
            appendLine("Summary:")
            val successRate = if (outcomes.isNotEmpty()) {
                (successfulOutcomes.size.toDouble() / outcomes.size.toDouble() * 100).toInt()
            } else {
                0
            }
            appendLine("  Success Rate: $successRate%")

            // Calculate average duration for execution outcomes
            val executionOutcomes = outcomes.filterIsInstance<ExecutionOutcome>()
            if (executionOutcomes.isNotEmpty()) {
                val totalDuration = executionOutcomes.sumOf { outcome ->
                    (outcome.executionEndTimestamp - outcome.executionStartTimestamp).inWholeMilliseconds
                }
                val avgDuration = totalDuration / executionOutcomes.size
                appendLine("  Average Execution Duration: ${avgDuration}ms")
            }
        }
    }

    /**
     * Builds the LLM prompt for outcome evaluation.
     *
     * This prompt instructs the LLM to:
     * - Identify patterns in successful and failed executions
     * - Extract actionable insights from these patterns
     * - Generate learnings with confidence levels based on evidence
     * - Focus on insights that will improve future performance
     *
     * @param analysisContext The outcome analysis context
     * @return The prompt string for the LLM
     */
    private fun buildOutcomeEvaluationPrompt(analysisContext: String): String {
        return """
            You are the learning module of an autonomous code-writing agent.
            Analyze execution outcomes to generate insights that will improve future performance.

            Your goal is to identify:
            1. What patterns distinguish successful executions from failures
            2. Which approaches, tools, or strategies correlate with success
            3. What common failure modes exist and how to avoid them
            4. What meta-patterns exist (e.g., "simple tasks succeed more than complex ones")

            Execution Data:
            $analysisContext

            Generate 2-4 specific, actionable insights. Each insight should:
            - Identify a clear pattern observed in the data
            - Explain why this pattern matters for future executions
            - Suggest a concrete change to behavior based on this pattern
            - Include confidence level (high/medium/low) based on evidence strength

            Focus on insights that will actually improve performance, not just observations.
            "File operations sometimes fail" is an observation.
            "File operations fail when paths are relative; use absolute paths" is actionable.

            Format your response as a JSON array:
            [
              {
                "pattern": "what pattern you identified",
                "reasoning": "why this pattern emerged and why it matters",
                "actionableAdvice": "what to do differently based on this",
                "confidence": "high|medium|low",
                "evidenceCount": number_of_supporting_examples
              }
            ]

            Important:
            - Base insights on actual patterns in the data, not generic advice
            - Higher evidenceCount should correlate with higher confidence
            - Focus on learnings specific to code generation and file operations
            - Avoid generic advice that would apply to any task

            Respond ONLY with the JSON array, no other text.
        """.trimIndent()
    }

    /**
     * Parses the LLM response into Knowledge.FromOutcome objects.
     *
     * Expected JSON format:
     * [
     *   {
     *     "pattern": "...",
     *     "reasoning": "...",
     *     "actionableAdvice": "...",
     *     "confidence": "high|medium|low",
     *     "evidenceCount": N
     *   }
     * ]
     *
     * @param llmResponse The LLM's JSON response
     * @param outcomes The original outcomes being analyzed
     * @return List of Knowledge.FromOutcome entries
     */
    private fun parseLearningsFromResponse(
        llmResponse: String,
        outcomes: List<Outcome>,
    ): List<Knowledge.FromOutcome> {
        val json = Json { ignoreUnknownKeys = true }

        // Clean up response (remove markdown code blocks if present)
        val cleanedResponse = llmResponse
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val learningsArray = json.parseToJsonElement(cleanedResponse).jsonArray

        if (learningsArray.isEmpty()) {
            return emptyList()
        }

        val now = Clock.System.now()

        // Convert each learning into a Knowledge.FromOutcome entry
        return learningsArray.mapNotNull { element ->
            try {
                val obj = element.jsonObject
                val pattern = obj["pattern"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val reasoning = obj["reasoning"]?.jsonPrimitive?.content ?: ""
                val actionableAdvice = obj["actionableAdvice"]?.jsonPrimitive?.content ?: ""
                val confidence = obj["confidence"]?.jsonPrimitive?.content ?: "medium"
                val evidenceCount = obj["evidenceCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1

                // Use the first non-blank outcome ID, or generate a composite ID
                val outcomeId = outcomes.firstOrNull { it.id.isNotBlank() }?.id
                    ?: "composite-${outcomes.hashCode()}"

                Knowledge.FromOutcome(
                    outcomeId = outcomeId,
                    approach = pattern,
                    learnings = buildString {
                        appendLine("Reasoning: $reasoning")
                        appendLine()
                        appendLine("Actionable Advice: $actionableAdvice")
                        appendLine()
                        appendLine("Confidence: $confidence")
                        appendLine("Evidence Count: $evidenceCount")
                    },
                    timestamp = now
                )
            } catch (e: Exception) {
                // Skip malformed learning entries
                null
            }
        }
    }

    /**
     * Creates an Idea from Knowledge.FromOutcome entries.
     *
     * This synthesizes the learnings into a cohesive description that can be
     * used by future perception to inform planning and execution.
     *
     * @param knowledgeEntries The knowledge entries extracted from outcomes
     * @param outcomes The original outcomes
     * @return An Idea summarizing the learnings
     */
    private fun createLearningIdea(
        knowledgeEntries: List<Knowledge.FromOutcome>,
        outcomes: List<Outcome>,
    ): Idea {
        val successCount = outcomes.count { it is Outcome.Success }
        val failureCount = outcomes.count { it is Outcome.Failure }

        val description = buildString {
            appendLine("Learnings from ${outcomes.size} execution outcomes " +
                    "($successCount successful, $failureCount failed):")
            appendLine()

            if (knowledgeEntries.isEmpty()) {
                appendLine("No specific learnings extracted - outcomes may be too similar or data insufficient.")
            } else {
                knowledgeEntries.forEachIndexed { index, knowledge ->
                    appendLine("${index + 1}. ${knowledge.approach}")
                    appendLine()
                    knowledge.learnings.lines().forEach { line ->
                        if (line.isNotBlank()) {
                            appendLine("   $line")
                        }
                    }
                    appendLine()
                }
            }
        }

        return Idea(
            name = "Outcome evaluation: ${outcomes.size} executions analyzed",
            description = description
        )
    }

    /**
     * Creates a fallback learning Idea when LLM analysis fails.
     *
     * This ensures the agent can continue operating even when outcome evaluation
     * encounters errors. The fallback provides basic statistics without deep insights.
     *
     * @param outcomes The outcomes that were being analyzed
     * @param reason Why the fallback was needed
     * @return A basic Idea with outcome statistics
     */
    protected open fun createFallbackLearningIdea(outcomes: List<Outcome>, reason: String): Idea {
        val successCount = outcomes.count { it is Outcome.Success }
        val failureCount = outcomes.count { it is Outcome.Failure }
        val successRate = if (outcomes.isNotEmpty()) {
            (successCount.toDouble() / outcomes.size.toDouble() * 100).toInt()
        } else {
            0
        }

        val description = buildString {
            appendLine("Basic outcome statistics (advanced analysis unavailable - $reason):")
            appendLine()
            appendLine("Total Outcomes: ${outcomes.size}")
            appendLine("Successful: $successCount")
            appendLine("Failed: $failureCount")
            appendLine("Success Rate: $successRate%")
            appendLine()

            if (failureCount > successCount) {
                appendLine("⚠ High failure rate suggests tasks may be too complex or tools inadequate.")
                appendLine("Consider: Breaking tasks into smaller steps, validating inputs, or using different approaches.")
            } else {
                appendLine("✓ Reasonable success rate - continue with current approach.")
            }
        }

        // Create a basic Knowledge.FromOutcome for the fallback
        val now = Clock.System.now()
        val outcomeId = outcomes.firstOrNull { it.id.isNotBlank() }?.id
            ?: "fallback-${outcomes.hashCode()}"

        val fallbackKnowledge = Knowledge.FromOutcome(
            outcomeId = outcomeId,
            approach = "Completed ${outcomes.size} executions with $successRate% success rate",
            learnings = if (failureCount > successCount) {
                "High failure rate indicates need for simpler tasks or better tools"
            } else {
                "Continue with current approach"
            },
            timestamp = now
        )

        // Store the fallback learning
        initialState.addToPastKnowledge(
            rememberedKnowledgeFromOutcomes = listOf(fallbackKnowledge)
        )

        return Idea(
            name = "Outcome evaluation (basic statistics)",
            description = description
        )
    }

    /**
     * Data class representing a file to be written.
     *
     * @property path The relative file path
     * @property content The complete file content
     * @property reason Why this file is needed
     */
    private data class FileSpec(
        val path: String,
        val content: String,
        val reason: String,
    )

    /**
     * Executes a tool with LLM-generated parameters.
     *
     * This function acts as the parameter synthesizer - it takes a high-level intent
     * and a specific tool, then uses an LLM to generate the exact parameters that tool needs.
     *
     * For code generation tools, this means generating actual code content, file paths,
     * and implementation details from a description like "implement user validation".
     *
     * The function:
     * 1. Extracts the intent from the execution request
     * 2. Builds a parameter generation prompt specific to the tool type
     * 3. Calls the LLM to generate parameters
     * 4. Parses and validates the generated parameters
     * 5. Executes the tool through the executor
     * 6. Returns the execution outcome
     *
     * @param tool The tool to execute
     * @param request The execution request containing context and high-level intent
     * @return ExecutionOutcome indicating success or failure
     */
    private suspend fun executeToolWithLLMGeneratedParameters(
        tool: Tool<*>,
        request: ExecutionRequest<*>,
    ): ExecutionOutcome {
        val startTime = Clock.System.now()

        // Extract the intent from the request
        val intent = extractIntentFromRequest(request)
        if (intent.isBlank()) {
            return ExecutionOutcome.NoChanges.Failure(
                executorId = executor.id,
                ticketId = request.context.ticket.id,
                taskId = request.context.task.id,
                executionStartTimestamp = startTime,
                executionEndTimestamp = Clock.System.now(),
                message = "Cannot execute tool: no intent found in request",
            )
        }

        // Check if this is an MCP tool - not yet supported
        if (tool is McpTool) {
            return ExecutionOutcome.NoChanges.Failure(
                executorId = executor.id,
                ticketId = request.context.ticket.id,
                taskId = request.context.task.id,
                executionStartTimestamp = startTime,
                executionEndTimestamp = Clock.System.now(),
                message = "MCP tool execution not yet supported",
            )
        }

        // Generate parameters using LLM based on the tool type
        val parametersPrompt = buildParameterGenerationPrompt(tool, request, intent)
        val llmResponse = try {
            callLLM(parametersPrompt)
        } catch (e: Exception) {
            return ExecutionOutcome.NoChanges.Failure(
                executorId = executor.id,
                ticketId = request.context.ticket.id,
                taskId = request.context.task.id,
                executionStartTimestamp = startTime,
                executionEndTimestamp = Clock.System.now(),
                message = "LLM call failed during parameter generation: ${e.message}",
            )
        }

        // Parse the LLM response into tool-specific parameters
        val enrichedRequest = try {
            parseAndEnrichRequest(llmResponse, tool, request)
        } catch (e: Exception) {
            return ExecutionOutcome.NoChanges.Failure(
                executorId = executor.id,
                ticketId = request.context.ticket.id,
                taskId = request.context.task.id,
                executionStartTimestamp = startTime,
                executionEndTimestamp = Clock.System.now(),
                message = "Failed to parse LLM response into parameters: ${e.message}",
            )
        }

        // Execute the tool through the executor with the enriched request
        return try {
            when (tool) {
                is FunctionTool<*> -> {
                    // For FunctionTools, we need to cast appropriately
                    @Suppress("UNCHECKED_CAST")
                    val typedTool = tool as Tool<ExecutionContext>
                    @Suppress("UNCHECKED_CAST")
                    val typedRequest = enrichedRequest as ExecutionRequest<ExecutionContext>

                    val statusFlow = executor.execute(typedRequest, typedTool)
                    val finalStatus = statusFlow.last()

                    when (finalStatus) {
                        is ExecutionStatus.Completed -> finalStatus.result
                        is ExecutionStatus.Failed -> finalStatus.result
                        else -> ExecutionOutcome.NoChanges.Failure(
                            executorId = executor.id,
                            ticketId = request.context.ticket.id,
                            taskId = request.context.task.id,
                            executionStartTimestamp = startTime,
                            executionEndTimestamp = Clock.System.now(),
                            message = "Unexpected execution status: ${finalStatus::class.simpleName}",
                        )
                    }
                }
                is McpTool -> {
                    // MCP tools not yet fully implemented
                    ExecutionOutcome.NoChanges.Failure(
                        executorId = executor.id,
                        ticketId = request.context.ticket.id,
                        taskId = request.context.task.id,
                        executionStartTimestamp = startTime,
                        executionEndTimestamp = Clock.System.now(),
                        message = "MCP tool execution not yet supported",
                    )
                }
            }
        } catch (e: Exception) {
            ExecutionOutcome.NoChanges.Failure(
                executorId = executor.id,
                ticketId = request.context.ticket.id,
                taskId = request.context.task.id,
                executionStartTimestamp = startTime,
                executionEndTimestamp = Clock.System.now(),
                message = "Tool execution failed: ${e.message}",
            )
        }
    }

    /**
     * Extracts the high-level intent from an execution request.
     *
     * The intent describes what the agent wants to accomplish (e.g., "implement user validation").
     * This is extracted from the request's instructions field.
     *
     * @param request The execution request
     * @return The extracted intent string
     */
    private fun extractIntentFromRequest(request: ExecutionRequest<*>): String {
        return request.context.instructions
    }

    /**
     * Builds a prompt for the LLM to generate tool-specific parameters.
     *
     * The prompt is tailored to the tool type:
     * - For write_code_file: generates complete, working code
     * - For read_code_file: determines which files to read
     * - For other tools: generates appropriate parameters in JSON format
     *
     * @param tool The tool that will be executed
     * @param request The original execution request
     * @param intent The high-level intent to accomplish
     * @return The prompt string for the LLM
     */
    private fun buildParameterGenerationPrompt(
        tool: Tool<*>,
        request: ExecutionRequest<*>,
        intent: String,
    ): String {
        return when (tool.id) {
            "write_code_file" -> buildCodeWritingPrompt(request, intent)
            "read_code_file" -> buildCodeReadingPrompt(request, intent)
            else -> buildGenericToolPrompt(tool, request, intent)
        }
    }

    /**
     * Builds a prompt for generating code to write to a file.
     *
     * This prompt instructs the LLM to generate production-quality code that:
     * - Is syntactically correct
     * - Follows Kotlin idioms and best practices
     * - Includes proper package declarations and imports
     * - Has complete implementations (no TODOs or placeholders)
     *
     * @param request The execution request with context
     * @param intent The high-level intent (what to implement)
     * @return The prompt string
     */
    private fun buildCodeWritingPrompt(
        request: ExecutionRequest<*>,
        intent: String,
    ): String {
        val context = request.context
        val workspace = if (context is ExecutionContext.Code) {
            context.workspace.baseDirectory
        } else {
            "."
        }

        return """
            You are a precise code generation system for the CodeWriterAgent.
            Your task is to generate production-quality Kotlin code based on the given intent.

            Intent: $intent

            Workspace: $workspace

            Generate COMPLETE, WORKING code that:
            1. Is syntactically correct and follows Kotlin conventions
            2. Uses idiomatic Kotlin patterns (data classes, sealed classes, extension functions where appropriate)
            3. Includes proper package declarations inferred from file paths
            4. Includes all necessary imports
            5. Has no TODOs, placeholders, or incomplete implementations
            6. Includes appropriate documentation comments for public APIs

            Package naming convention:
            - For file path "src/commonMain/kotlin/link/socket/ampere/User.kt" → package link.socket.ampere
            - For file path "src/main/kotlin/com/example/Foo.kt" → package com.example

            Format your response as a JSON object:
            {
              "files": [
                {
                  "path": "relative/path/to/file.kt",
                  "content": "the complete file content",
                  "reason": "brief explanation of what this file does"
                }
              ]
            }

            Important:
            - Generate at least one file
            - Use relative paths from the workspace root
            - Include COMPLETE file content, not snippets
            - Start file content with package declaration (if applicable)

            Respond ONLY with the JSON object, no other text.
        """.trimIndent()
    }

    /**
     * Builds a prompt for determining which code files to read.
     *
     * This prompt instructs the LLM to analyze the intent and determine which
     * files need to be read to understand the context.
     *
     * @param request The execution request with context
     * @param intent The high-level intent
     * @return The prompt string
     */
    private fun buildCodeReadingPrompt(
        request: ExecutionRequest<*>,
        intent: String,
    ): String {
        val context = request.context
        val workspace = if (context is ExecutionContext.Code) {
            context.workspace.baseDirectory
        } else {
            "."
        }

        return """
            You are a code analysis system for the CodeWriterAgent.
            Your task is to determine which files need to be read to accomplish the given intent.

            Intent: $intent

            Workspace: $workspace

            Analyze the intent and determine which files should be read to:
            1. Understand existing code structure
            2. Find relevant classes, functions, or modules
            3. Gather context for code modifications

            Format your response as a JSON object:
            {
              "filePaths": [
                "relative/path/to/file1.kt",
                "relative/path/to/file2.kt"
              ],
              "reason": "brief explanation of why these files are needed"
            }

            Respond ONLY with the JSON object, no other text.
        """.trimIndent()
    }

    /**
     * Builds a generic prompt for tools that don't have specific handling.
     *
     * This prompt asks the LLM to generate parameters in JSON format based on
     * the tool's description and the given intent.
     *
     * @param tool The tool to generate parameters for
     * @param request The execution request
     * @param intent The high-level intent
     * @return The prompt string
     */
    private fun buildGenericToolPrompt(
        tool: Tool<*>,
        request: ExecutionRequest<*>,
        intent: String,
    ): String {
        return """
            You are a parameter generation system for the CodeWriterAgent.
            Your task is to generate appropriate parameters for executing a tool.

            Tool: ${tool.name}
            Tool Description: ${tool.description}

            Intent: $intent

            Generate the parameters needed to execute this tool to accomplish the intent.

            Format your response as a JSON object with parameter names as keys:
            {
              "parameterName1": "value1",
              "parameterName2": "value2"
            }

            Respond ONLY with the JSON object, no other text.
        """.trimIndent()
    }

    /**
     * Parses the LLM response and enriches the execution request with generated parameters.
     *
     * This function:
     * 1. Cleans up the LLM response (removes markdown formatting)
     * 2. Parses the JSON response
     * 3. Extracts tool-specific parameters
     * 4. Creates a new execution request with the enriched context
     *
     * @param llmResponse The raw response from the LLM
     * @param tool The tool being executed
     * @param originalRequest The original execution request
     * @return An enriched execution request with generated parameters
     */
    private fun parseAndEnrichRequest(
        llmResponse: String,
        tool: Tool<*>,
        originalRequest: ExecutionRequest<*>,
    ): ExecutionRequest<*> {
        val json = Json { ignoreUnknownKeys = true }

        // Clean up response (remove markdown code blocks if present)
        val cleanedResponse = llmResponse
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val responseJson = json.parseToJsonElement(cleanedResponse).jsonObject

        return when (tool.id) {
            "write_code_file" -> {
                // Parse file specifications
                val filesArray = responseJson["files"]?.jsonArray
                    ?: throw IllegalStateException("No 'files' array in LLM response")

                if (filesArray.isEmpty()) {
                    throw IllegalStateException("Empty 'files' array in LLM response")
                }

                // Extract file paths and content
                val instructionsPerFilePath = filesArray.map { element ->
                    val fileObj = element.jsonObject
                    val path = fileObj["path"]?.jsonPrimitive?.content
                        ?: throw IllegalStateException("File missing 'path'")
                    val content = fileObj["content"]?.jsonPrimitive?.content
                        ?: throw IllegalStateException("File missing 'content'")

                    path to content
                }

                // Create enriched context for write_code_file
                val originalContext = originalRequest.context
                val workspace = if (originalContext is ExecutionContext.Code) {
                    originalContext.workspace
                } else {
                    ExecutionWorkspace(baseDirectory = ".")
                }

                val enrichedContext = ExecutionContext.Code.WriteCode(
                    executorId = originalContext.executorId,
                    ticket = originalContext.ticket,
                    task = originalContext.task,
                    instructions = originalContext.instructions,
                    workspace = workspace,
                    instructionsPerFilePath = instructionsPerFilePath,
                    knowledgeFromPastMemory = originalContext.knowledgeFromPastMemory,
                )

                ExecutionRequest(
                    context = enrichedContext,
                    constraints = originalRequest.constraints,
                )
            }

            "read_code_file" -> {
                // Parse file paths
                val filePathsArray = responseJson["filePaths"]?.jsonArray
                    ?: throw IllegalStateException("No 'filePaths' array in LLM response")

                val filePaths = filePathsArray.map { it.jsonPrimitive.content }

                // Create enriched context for read_code_file
                val originalContext = originalRequest.context
                val workspace = if (originalContext is ExecutionContext.Code) {
                    originalContext.workspace
                } else {
                    ExecutionWorkspace(baseDirectory = ".")
                }

                val enrichedContext = ExecutionContext.Code.ReadCode(
                    executorId = originalContext.executorId,
                    ticket = originalContext.ticket,
                    task = originalContext.task,
                    instructions = originalContext.instructions,
                    workspace = workspace,
                    filePathsToRead = filePaths,
                    knowledgeFromPastMemory = originalContext.knowledgeFromPastMemory,
                )

                ExecutionRequest(
                    context = enrichedContext,
                    constraints = originalRequest.constraints,
                )
            }

            else -> {
                // For generic tools, return the original request
                // (parameters are in the JSON but we don't know how to apply them)
                originalRequest
            }
        }
    }
}
