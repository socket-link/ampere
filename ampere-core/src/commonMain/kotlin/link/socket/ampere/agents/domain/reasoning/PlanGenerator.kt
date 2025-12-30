package link.socket.ampere.agents.domain.reasoning

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import link.socket.ampere.agents.domain.expectation.Expectations
import link.socket.ampere.agents.domain.memory.KnowledgeWithScore
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.execution.tools.Tool

/**
 * Generates executable plans for accomplishing tasks.
 *
 * This component implements the "Plan" phase of the PROPEL cognitive loop,
 * transforming high-level understanding (task + ideas) into concrete,
 * sequential steps that can be executed by the agent.
 *
 * The planning process:
 * 1. Extract task description and synthesize ideas
 * 2. Build a planning prompt with task context and available tools
 * 3. Call LLM to generate structured plan steps
 * 4. Parse LLM response into Plan object with Task objects
 * 5. Fall back to simple plan if LLM call or parsing fails
 *
 * Usage:
 * ```kotlin
 * val generator = PlanGenerator(llmService)
 * val plan = generator.generate(
 *     task = currentTask,
 *     ideas = perceptionIdeas,
 *     agentRole = "Project Manager",
 *     availableTools = myTools,
 *     taskFactory = { id, description -> PMTask.SomeTask(id, description) },
 * )
 * ```
 *
 * @property llmService The LLM service for generating plans
 */
class PlanGenerator(
    private val llmService: AgentLLMService,
) {

    /**
     * Generates a plan for accomplishing the given task.
     *
     * @param task The task to plan for
     * @param ideas Insights from perception that inform planning
     * @param agentRole Description of the agent's role
     * @param availableTools Tools available to the agent
     * @param relevantKnowledge Past knowledge relevant to this task
     * @param taskFactory Factory function to create domain-specific task objects
     * @param customPromptBuilder Optional custom prompt builder for agent-specific planning
     * @return A Plan containing sequential steps to accomplish the goal
     */
    suspend fun generate(
        task: Task,
        ideas: List<Idea>,
        agentRole: String,
        availableTools: Set<Tool<*>> = emptySet(),
        relevantKnowledge: List<KnowledgeWithScore> = emptyList(),
        taskFactory: TaskFactory = DefaultTaskFactory,
        customPromptBuilder: ((Task, List<Idea>, Set<Tool<*>>, List<KnowledgeWithScore>) -> String)? = null,
    ): Plan {
        // Handle blank task
        if (task is Task.Blank) {
            return Plan.blank
        }

        val taskDescription = extractTaskDescription(task)
        val ideaSummary = synthesizeIdeas(ideas)
        val knowledgeSummary = synthesizeKnowledge(relevantKnowledge)

        val prompt = customPromptBuilder?.invoke(task, ideas, availableTools, relevantKnowledge)
            ?: buildPlanningPrompt(
                taskDescription = taskDescription,
                ideaSummary = ideaSummary,
                knowledgeSummary = knowledgeSummary,
                agentRole = agentRole,
                availableTools = availableTools,
            )

        return try {
            val jsonResponse = llmService.callForJson(
                prompt = prompt,
                systemMessage = PLANNING_SYSTEM_MESSAGE,
                maxTokens = 1000,
            )
            parsePlanFromResponse(jsonResponse.rawJson, task, taskFactory)
        } catch (e: Exception) {
            createFallbackPlan(task, taskFactory, "Plan generation failed: ${e.message}")
        }
    }

    /**
     * Extracts a description string from a task.
     */
    private fun extractTaskDescription(task: Task): String {
        return when (task) {
            is Task.CodeChange -> task.description
            is Task.Blank -> ""
            else -> "Task ${task.id}"
        }
    }

    /**
     * Synthesizes ideas into a summary for planning context.
     */
    private fun synthesizeIdeas(ideas: List<Idea>): String {
        return if (ideas.isNotEmpty()) {
            ideas.joinToString("\n\n") { idea ->
                "${idea.name}:\n${idea.description}"
            }
        } else {
            "No insights available from perception phase."
        }
    }

    /**
     * Synthesizes relevant knowledge into a summary for planning context.
     */
    private fun synthesizeKnowledge(knowledge: List<KnowledgeWithScore>): String {
        if (knowledge.isEmpty()) {
            return "No relevant past knowledge available."
        }

        return buildString {
            appendLine("Relevant past experiences (${knowledge.size} entries):")
            knowledge.take(5).forEach { scored ->
                appendLine("- Approach: ${scored.knowledge.approach}")
                appendLine("  Learnings: ${scored.knowledge.learnings.take(200)}...")
                appendLine("  Relevance: ${(scored.relevanceScore * 100).toInt()}%")
                appendLine()
            }
        }
    }

    /**
     * Builds the planning prompt.
     */
    private fun buildPlanningPrompt(
        taskDescription: String,
        ideaSummary: String,
        knowledgeSummary: String,
        agentRole: String,
        availableTools: Set<Tool<*>>,
    ): String = buildString {
        appendLine("You are the planning module of an autonomous $agentRole agent.")
        appendLine("Your task is to create a concrete, executable plan to accomplish the given task.")
        appendLine()
        appendLine("Task: $taskDescription")
        appendLine()
        appendLine("Insights from Perception:")
        appendLine(ideaSummary)
        appendLine()
        appendLine("Past Knowledge:")
        appendLine(knowledgeSummary)
        appendLine()

        if (availableTools.isNotEmpty()) {
            appendLine("Available Tools:")
            availableTools.forEach { tool ->
                appendLine("- ${tool.id}: ${tool.description}")
            }
            appendLine()
        }

        appendLine("Create a step-by-step plan where each step is a concrete task that can be executed.")
        appendLine("Each step should:")
        appendLine("1. Have a clear, actionable description")
        appendLine("2. Specify which tool to use (if applicable)")
        appendLine("3. Be sequentially ordered with clear dependencies")
        appendLine("4. Include validation/verification steps where appropriate")
        appendLine()
        appendLine("For simple tasks, create a 1-2 step plan.")
        appendLine("For complex tasks, break down into logical phases (3-5 steps typically).")
        appendLine("Avoid excessive granularity - focus on meaningful phases of work.")
        appendLine()
        appendLine("Format your response as a JSON object:")
        appendLine(
            """
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
            """.trimIndent(),
        )
        appendLine()
        appendLine("Respond ONLY with the JSON object, no other text.")
    }

    /**
     * Parses the LLM response into a Plan object.
     */
    private fun parsePlanFromResponse(
        jsonResponse: String,
        originalTask: Task,
        taskFactory: TaskFactory,
    ): Plan {
        val cleanedResponse = LLMResponseParser.cleanJsonResponse(jsonResponse)
        val planJson = LLMResponseParser.parseJsonObject(cleanedResponse)

        val stepsArray = planJson["steps"]?.jsonArray
            ?: throw IllegalStateException("No steps in plan")

        val complexity = LLMResponseParser.getInt(planJson, "estimatedComplexity", 5)

        // Validate steps
        if (stepsArray.isEmpty()) {
            throw IllegalStateException("Plan must contain at least one step")
        }

        // Convert steps into Task objects
        val planTasks = stepsArray.mapIndexed { index, stepElement ->
            val stepObj = stepElement.jsonObject
            val description = stepObj["description"]?.jsonPrimitive?.content
                ?: "Step ${index + 1}"
            val toolToUse = stepObj["toolToUse"]?.jsonPrimitive?.content

            taskFactory.create(
                id = "step-${index + 1}-${originalTask.id}",
                description = description,
                toolId = toolToUse,
                originalTask = originalTask,
            )
        }

        return Plan.ForTask(
            task = originalTask,
            tasks = planTasks,
            estimatedComplexity = complexity,
            expectations = Expectations.blank,
        )
    }

    /**
     * Creates a fallback plan when generation fails.
     */
    private fun createFallbackPlan(
        task: Task,
        taskFactory: TaskFactory,
        reason: String,
    ): Plan {
        if (task is Task.Blank) {
            return Plan.blank
        }

        val taskDescription = extractTaskDescription(task)

        val fallbackTask = taskFactory.create(
            id = "step-1-${task.id}",
            description = "Execute: $taskDescription (Note: Advanced planning unavailable - $reason)",
            toolId = null,
            originalTask = task,
        )

        return Plan.ForTask(
            task = task,
            tasks = listOf(fallbackTask),
            estimatedComplexity = 3,
            expectations = Expectations.blank,
        )
    }

    companion object {
        private const val PLANNING_SYSTEM_MESSAGE =
            "You are an autonomous agent planning system. Generate structured execution plans. Respond only with valid JSON."
    }
}

/**
 * Factory interface for creating domain-specific task objects.
 *
 * Each agent can provide its own implementation to create the appropriate
 * task types for its domain.
 */
fun interface TaskFactory {
    /**
     * Creates a task from plan step information.
     *
     * @param id Unique identifier for the task
     * @param description What the task accomplishes
     * @param toolId Optional tool to use for this task
     * @param originalTask The original task being planned
     * @return A Task object appropriate for the agent's domain
     */
    fun create(
        id: String,
        description: String,
        toolId: String?,
        originalTask: Task,
    ): Task
}

/**
 * Default task factory that creates Task.CodeChange objects.
 */
object DefaultTaskFactory : TaskFactory {
    override fun create(
        id: String,
        description: String,
        toolId: String?,
        originalTask: Task,
    ): Task {
        return Task.CodeChange(
            id = id,
            status = TaskStatus.Pending,
            description = description,
            assignedTo = if (originalTask is Task.CodeChange) originalTask.assignedTo else null,
        )
    }
}
