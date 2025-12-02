package link.socket.ampere.agents.implementations.code

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import link.socket.ampere.agents.core.AgentConfiguration
import link.socket.ampere.agents.core.AgentId
import link.socket.ampere.agents.core.AssignedTo
import link.socket.ampere.agents.core.AutonomousAgent
import link.socket.ampere.agents.core.expectations.Expectations
import link.socket.ampere.agents.core.memory.Knowledge
import link.socket.ampere.agents.core.outcomes.ExecutionOutcome
import link.socket.ampere.agents.core.outcomes.Outcome
import link.socket.ampere.agents.core.reasoning.Idea
import link.socket.ampere.agents.core.reasoning.Perception
import link.socket.ampere.agents.core.reasoning.Plan
import link.socket.ampere.agents.core.states.AgentState
import link.socket.ampere.agents.core.status.TaskStatus
import link.socket.ampere.agents.core.tasks.Task
import link.socket.ampere.agents.core.tasks.MeetingTask
import link.socket.ampere.agents.core.tasks.TicketTask
import link.socket.ampere.agents.core.status.ExecutionStatus
import link.socket.ampere.agents.core.status.TicketStatus
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.execution.request.ExecutionConstraints
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
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
    private val executor: link.socket.ampere.agents.execution.executor.Executor =
        link.socket.ampere.agents.execution.executor.FunctionExecutor.create(),
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
            // TODO: execute tool with parameters
            ExecutionOutcome.Blank
        }

    override val runLLMToEvaluateOutcomes: (outcomes: List<Outcome>) -> Idea =
        { outcomes ->
            // TODO: evaluate outcomes and generate the idea to start the next runtime loop
            Idea.blank
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
     * TODO: Implement meaningful knowledge extraction for code writing tasks.
     * This stub implementation provides minimal knowledge capture until the agent
     * is enhanced with memory capabilities.
     */
    override fun extractKnowledgeFromOutcome(
        outcome: Outcome,
        task: Task,
        plan: Plan
    ): Knowledge {
        return Knowledge.FromOutcome(
            outcomeId = outcome.id,
            approach = "Code writing task executed",
            learnings = "Task completed - knowledge extraction not yet implemented",
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

    /**
     * Calls the LLM with the given prompt and returns the response.
     *
     * This uses the agent's configured AI provider and model to generate a response.
     *
     * @param prompt The prompt to send to the LLM
     * @return The LLM's response text
     */
    private fun callLLM(prompt: String): String = runBlocking {
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
            return Plan.blank
        }

        // Extract task description
        val taskDescription = when (task) {
            is Task.CodeChange -> task.description
            is MeetingTask.AgendaItem -> task.title
            is TicketTask.CompleteSubticket -> "Complete subticket ${task.id}"
            is Task.Blank -> return Plan.blank
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
            expectations = Expectations.blank
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
    private fun validatePlanSteps(steps: kotlinx.serialization.json.JsonArray): kotlinx.serialization.json.JsonArray {
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
            is Task.Blank -> return Plan.blank
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
            expectations = Expectations.blank
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
            return Outcome.blank
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
        startTime: kotlinx.datetime.Instant,
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
}
