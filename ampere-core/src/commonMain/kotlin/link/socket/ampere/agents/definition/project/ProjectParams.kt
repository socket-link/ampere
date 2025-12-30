package link.socket.ampere.agents.definition.project

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.reasoning.LLMResponseParser
import link.socket.ampere.agents.execution.ParameterStrategy
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.agents.execution.tools.issue.BatchIssueCreateRequest

/**
 * Agent capability information for task assignment decisions.
 */
@Serializable
data class AgentCapability(
    val agentId: AgentId,
    val capabilities: List<String>,
    val currentTaskCount: Int,
)

/**
 * Summary of a task for progress assessment.
 */
@Serializable
data class TaskSummary(
    val localId: String,
    val title: String,
    val status: String,
    val dependsOn: List<String> = emptyList(),
)

/**
 * Parameter strategies for Project Manager Agent tools.
 *
 * Each strategy handles:
 * - Prompt generation for the LLM
 * - Response parsing and request enrichment
 */
sealed class ProjectParams {

    /**
     * Strategy for issue creation tools.
     *
     * Uses ProjectPrompts.goalDecomposition to generate a structured
     * work breakdown that becomes a BatchIssueCreateRequest.
     */
    class IssueCreation(
        private val repository: String,
        private val availableAgents: List<AgentCapability> = emptyList(),
        private val existingIssues: List<String> = emptyList(),
    ) : ParameterStrategy {

        override val systemMessage: String =
            "You are a project manager agent responsible for breaking down goals into structured work. " +
                "Respond only with valid JSON matching the BatchIssueCreateRequest schema."

        override val maxTokens: Int = 3000

        override fun buildPrompt(
            tool: Tool<*>,
            request: ExecutionRequest<*>,
            intent: String,
        ): String = ProjectPrompts.goalDecomposition(
            goal = intent,
            repository = repository,
            availableAgents = availableAgents,
            existingIssues = existingIssues,
        )

        override fun parseAndEnrichRequest(
            jsonResponse: String,
            originalRequest: ExecutionRequest<*>,
        ): ExecutionRequest<*> {
            val cleanedResponse = LLMResponseParser.cleanJsonResponse(jsonResponse)
            val json = Json { ignoreUnknownKeys = true }
            val issueRequest = json.decodeFromString<BatchIssueCreateRequest>(cleanedResponse)

            val context = originalRequest.context
            val enrichedContext = ExecutionContext.IssueManagement(
                executorId = context.executorId,
                ticket = context.ticket,
                task = context.task,
                instructions = context.instructions,
                issueRequest = issueRequest,
                knowledgeFromPastMemory = context.knowledgeFromPastMemory,
            )

            return ExecutionRequest(
                context = enrichedContext,
                constraints = originalRequest.constraints,
            )
        }
    }

    /**
     * Strategy for human escalation tools.
     *
     * Generates structured escalation requests that include:
     * - The decision that needs human input
     * - Context and reasoning
     * - Options being considered
     * - Recommended action (if any)
     */
    class HumanEscalation(
        private val agentRole: String = "Project Manager",
    ) : ParameterStrategy {

        override val systemMessage: String =
            "You are an agent preparing a decision for human escalation. " +
                "Format the escalation clearly and concisely. Respond only with valid JSON."

        override val maxTokens: Int = 1000

        override fun buildPrompt(
            tool: Tool<*>,
            request: ExecutionRequest<*>,
            intent: String,
        ): String = ProjectPrompts.humanEscalation(
            agentRole = agentRole,
            intent = intent,
            taskId = request.context.task.id,
            ticketTitle = request.context.ticket.title,
        )

        override fun parseAndEnrichRequest(
            jsonResponse: String,
            originalRequest: ExecutionRequest<*>,
        ): ExecutionRequest<*> {
            val cleanedResponse = LLMResponseParser.cleanJsonResponse(jsonResponse)
            val escalationJson = LLMResponseParser.parseJsonObject(cleanedResponse)

            val question = LLMResponseParser.getString(escalationJson, "question", "Decision needed")
            val context = LLMResponseParser.getString(escalationJson, "context", "")
            val recommendation = LLMResponseParser.getString(escalationJson, "recommendation", "")
            val urgency = LLMResponseParser.getString(escalationJson, "urgency", "medium")

            val enrichedInstructions = buildString {
                appendLine("ESCALATION REQUEST")
                appendLine("==================")
                appendLine()
                appendLine("Question: $question")
                appendLine()
                if (context.isNotBlank()) {
                    appendLine("Context: $context")
                    appendLine()
                }
                if (recommendation.isNotBlank()) {
                    appendLine("Agent Recommendation: $recommendation")
                    appendLine()
                }
                appendLine("Urgency: $urgency")
            }

            val originalContext = originalRequest.context
            val enrichedContext = ExecutionContext.NoChanges(
                executorId = originalContext.executorId,
                ticket = originalContext.ticket,
                task = originalContext.task,
                instructions = enrichedInstructions,
                knowledgeFromPastMemory = originalContext.knowledgeFromPastMemory,
            )

            return ExecutionRequest(
                context = enrichedContext,
                constraints = originalRequest.constraints,
            )
        }
    }

    /**
     * Strategy for task assignment.
     *
     * Uses ProjectPrompts.taskAssignment to determine the best agent
     * for a given task based on capabilities and workload.
     */
    class TaskAssignment(
        private val availableAgents: List<AgentCapability>,
    ) : ParameterStrategy {

        override val systemMessage: String =
            "You are a task assignment system. Select the best agent for the task. " +
                "Respond only with valid JSON."

        override val maxTokens: Int = 500

        override fun buildPrompt(
            tool: Tool<*>,
            request: ExecutionRequest<*>,
            intent: String,
        ): String = ProjectPrompts.taskAssignment(
            task = intent,
            availableAgents = availableAgents,
        )

        override fun parseAndEnrichRequest(
            jsonResponse: String,
            originalRequest: ExecutionRequest<*>,
        ): ExecutionRequest<*> {
            val cleanedResponse = LLMResponseParser.cleanJsonResponse(jsonResponse)
            val assignmentJson = LLMResponseParser.parseJsonObject(cleanedResponse)

            val assignedAgent = LLMResponseParser.getString(assignmentJson, "assignedAgent", "")
            val reasoning = LLMResponseParser.getString(assignmentJson, "reasoning", "")

            val enrichedInstructions = buildString {
                append(originalRequest.context.instructions)
                appendLine()
                appendLine()
                appendLine("Assignment Decision:")
                appendLine("  Agent: $assignedAgent")
                appendLine("  Reasoning: $reasoning")
            }

            val originalContext = originalRequest.context
            val enrichedContext = ExecutionContext.NoChanges(
                executorId = originalContext.executorId,
                ticket = originalContext.ticket,
                task = originalContext.task,
                instructions = enrichedInstructions,
                knowledgeFromPastMemory = originalContext.knowledgeFromPastMemory,
            )

            return ExecutionRequest(
                context = enrichedContext,
                constraints = originalRequest.constraints,
            )
        }
    }
}

/**
 * Prompt templates for Project Manager Agent's LLM-driven decision making.
 */
object ProjectPrompts {

    const val SYSTEM_PROMPT = """You are a Project Manager Agent responsible for:
- Decomposing goals into structured work breakdowns
- Creating issues in external systems
- Assigning tasks to specialized agents
- Monitoring progress and facilitating coordination
- Escalating decisions that exceed agent authority"""

    /**
     * Generates a prompt for decomposing a high-level goal into a structured work breakdown.
     */
    fun goalDecomposition(
        goal: String,
        repository: String,
        availableAgents: List<AgentCapability>,
        existingIssues: List<String> = emptyList(),
    ): String = buildString {
        appendLine("# Goal Decomposition Task")
        appendLine()
        appendLine("You are a Project Manager Agent responsible for breaking down goals into actionable work.")
        appendLine()
        appendLine("## Goal to Decompose")
        appendLine("```")
        appendLine(goal)
        appendLine("```")
        appendLine()
        appendLine("## Context")
        appendLine("- **Repository**: $repository")
        appendLine("- **Available Agents**: ${availableAgents.size}")
        availableAgents.forEach { agent ->
            appendLine(
                "  - ${agent.agentId}: ${agent.capabilities.joinToString(", ")} (${agent.currentTaskCount} tasks)",
            )
        }

        if (existingIssues.isNotEmpty()) {
            appendLine()
            appendLine("## Existing Issues (Avoid Duplication)")
            existingIssues.take(10).forEach { issue ->
                appendLine("  - $issue")
            }
            if (existingIssues.size > 10) {
                appendLine("  ... and ${existingIssues.size - 10} more")
            }
        }

        appendLine()
        appendLine("## Work Breakdown Structure Guidelines")
        appendLine()
        appendLine("### Epic Structure")
        appendLine("- Create **ONE** Feature epic as the parent issue")
        appendLine("- Epic title should clearly state what will be built")
        appendLine("- Epic body should include:")
        appendLine("  - Context and motivation")
        appendLine("  - Success criteria")
        appendLine("  - Technical constraints")
        appendLine("  - Link to relevant documentation")
        appendLine()
        appendLine("### Task Granularity")
        appendLine("- Create **3-8 Task issues** as children of the epic")
        appendLine("- Each task should be:")
        appendLine("  - **Specific**: Clear, actionable objective")
        appendLine("  - **Scoped**: Completable by a single agent")
        appendLine("  - **Estimable**: Can be done in reasonable time (hours to days, not weeks)")
        appendLine("  - **Independent**: Not blocked by other tasks unless explicitly marked")
        appendLine()
        appendLine("### Dependency Identification")
        appendLine("- Use `dependsOn` field to specify task dependencies")
        appendLine("- Dependencies should form a DAG (no circular dependencies)")
        appendLine("- Prefer parallel work when possible")
        appendLine("- Common dependency patterns:")
        appendLine("  - Implementation before tests")
        appendLine("  - Core functionality before extensions")
        appendLine("  - API design before implementation")
        appendLine()
        appendLine("### Duplicate Avoidance")
        if (existingIssues.isNotEmpty()) {
            appendLine("- Review the existing issues listed above")
            appendLine("- Do NOT create tasks that duplicate existing work")
            appendLine("- If the goal overlaps with existing issues, reference them in the epic body")
        } else {
            appendLine("- No existing issues found - you're starting fresh")
        }
        appendLine()
        appendLine("### Labels and Metadata")
        appendLine("- Epic: Use label \"epic\" or \"feature\"")
        appendLine("- Tasks: Use label \"task\"")
        appendLine("- Add descriptive labels like \"backend\", \"frontend\", \"testing\", \"docs\"")
        appendLine("- Leave `assignees` empty (assignment will be done separately)")
        appendLine()
        appendLine("## Output Format")
        appendLine()
        appendLine("Respond with ONLY valid JSON matching this schema (no markdown code blocks, no explanations):")
        appendLine()
        appendLine("**IMPORTANT:** Output ONLY the raw JSON, with no markdown code blocks or surrounding text.")
        appendLine()
        appendLine(
            """
{
  "repository": "$repository",
  "issues": [
    {
      "localId": "epic-1",
      "type": "Feature",
      "title": "Epic: [Brief description of the feature]",
      "body": "## Context\n[Why this feature is needed]\n\n## Success Criteria\n- [ ] Criterion 1\n- [ ] Criterion 2\n\n## Technical Constraints\n[Any limitations or requirements]",
      "labels": ["epic", "enhancement"],
      "assignees": [],
      "parent": null,
      "dependsOn": []
    },
    {
      "localId": "task-1",
      "type": "Task",
      "title": "[Specific actionable task]",
      "body": "## Objective\n[What needs to be done]\n\n## Implementation Notes\n[Technical details or guidance]",
      "labels": ["task"],
      "assignees": [],
      "parent": "epic-1",
      "dependsOn": []
    }
  ]
}
            """.trimIndent(),
        )
        appendLine()
        appendLine("## Important")
        appendLine("- Respond ONLY with the JSON")
        appendLine("- Ensure all `localId` values are unique")
        appendLine("- Ensure all `dependsOn` references point to valid `localId` values")
        appendLine("- Keep task descriptions concise but informative")
    }

    /**
     * Generates a prompt for assigning a task to the most appropriate agent.
     */
    fun taskAssignment(
        task: String,
        availableAgents: List<AgentCapability>,
    ): String = buildString {
        appendLine("# Task Assignment Decision")
        appendLine()
        appendLine("You are a Project Manager Agent responsible for assigning tasks to the most appropriate agent.")
        appendLine()
        appendLine("## Task to Assign")
        appendLine("```")
        appendLine(task)
        appendLine("```")
        appendLine()
        appendLine("## Available Agents")
        if (availableAgents.isEmpty()) {
            appendLine("No agents available for assignment!")
            appendLine()
            appendLine("Respond with:")
            appendLine("""{"assignedAgent": null, "reasoning": "No agents available"}""")
            return@buildString
        }

        availableAgents.forEach { agent ->
            appendLine()
            appendLine("### ${agent.agentId}")
            appendLine("- **Capabilities**: ${agent.capabilities.joinToString(", ")}")
            appendLine("- **Current workload**: ${agent.currentTaskCount} tasks")
            appendLine("- **Availability**: ${if (agent.currentTaskCount < 5) "Available" else "At capacity"}")
        }

        appendLine()
        appendLine("## Assignment Criteria")
        appendLine()
        appendLine("### 1. Capability Match (Most Important)")
        appendLine("- Select agent whose capabilities best match the task requirements")
        appendLine("- Example: \"code-writing\" agent for implementation tasks")
        appendLine("- Example: \"qa-testing\" agent for validation tasks")
        appendLine()
        appendLine("### 2. Current Workload (Secondary)")
        appendLine("- Prefer agents with lower current task count")
        appendLine("- Avoid assigning to agents at capacity (>5 tasks)")
        appendLine()
        appendLine("### 3. Past Performance")
        appendLine("- Consider past success rates for similar tasks")
        appendLine("- Agents with proven track record should be preferred")
        appendLine()
        appendLine("## Output Format")
        appendLine()
        appendLine("Respond with ONLY valid JSON (no markdown code blocks, no explanations):")
        appendLine(
            """
{
  "assignedAgent": "agent-id-here",
  "reasoning": "Brief explanation of why this agent was selected"
}
            """.trimIndent(),
        )
    }

    /**
     * Generates a prompt for human escalation.
     */
    fun humanEscalation(
        agentRole: String,
        intent: String,
        taskId: String,
        ticketTitle: String,
    ): String = buildString {
        appendLine("# Human Escalation Request")
        appendLine()
        appendLine("You are a $agentRole agent that needs human input to proceed.")
        appendLine()
        appendLine("## Situation Requiring Escalation")
        appendLine(intent)
        appendLine()
        appendLine("## Context")
        appendLine("Task: $taskId")
        appendLine("Ticket: $ticketTitle")
        appendLine()
        appendLine("## Instructions")
        appendLine("Format this escalation as a clear question for a human to answer.")
        appendLine("Include:")
        appendLine("- What decision is needed")
        appendLine("- Why this exceeds agent authority")
        appendLine("- Available options (if known)")
        appendLine("- Your recommendation (if any)")
        appendLine()
        appendLine("## Output Format")
        appendLine()
        appendLine("Respond with ONLY valid JSON:")
        appendLine(
            """
{
  "question": "The specific question for the human",
  "context": "Brief context about the situation",
  "options": ["Option A", "Option B", "Option C"],
  "recommendation": "Your recommended option, or null if no recommendation",
  "urgency": "high|medium|low"
}
            """.trimIndent(),
        )
    }

    /**
     * Generates a prompt for assessing progress on an epic and its tasks.
     */
    fun progressAssessment(
        epicTitle: String,
        tasks: List<TaskSummary>,
        recentEvents: List<String> = emptyList(),
    ): String = buildString {
        appendLine("# Progress Assessment Task")
        appendLine()
        appendLine("You are a Project Manager Agent responsible for monitoring project progress.")
        appendLine()
        appendLine("## Epic")
        appendLine("**$epicTitle**")
        appendLine()
        appendLine("## Tasks (${tasks.size} total)")

        if (tasks.isEmpty()) {
            appendLine("No tasks defined for this epic!")
            appendLine()
            appendLine("Respond with:")
            appendLine(
                """{"progressPercentage": 0, "blockedTasks": [], "risks": ["No tasks defined"], "recommendedActions": ["Define tasks for this epic"], "needsHumanInput": true, "humanInputReason": "Epic needs task breakdown"}""",
            )
            return@buildString
        }

        val completed = tasks.count { it.status == "completed" || it.status == "done" }
        val inProgress = tasks.count { it.status == "in_progress" || it.status == "in-progress" }
        val blocked = tasks.count { it.status == "blocked" }
        val pending = tasks.count { it.status == "pending" || it.status == "ready" }

        appendLine()
        appendLine("### Status Breakdown")
        appendLine("- Completed: $completed")
        appendLine("- In Progress: $inProgress")
        appendLine("- Blocked: $blocked")
        appendLine("- Pending: $pending")
        appendLine()
        appendLine("### Task Details")
        tasks.forEach { task ->
            val statusMarker = when (task.status.lowercase()) {
                "completed", "done" -> "[x]"
                "in_progress", "in-progress" -> "[~]"
                "blocked" -> "[!]"
                else -> "[ ]"
            }
            appendLine("$statusMarker **${task.localId}**: ${task.title} (${task.status})")
            if (task.dependsOn.isNotEmpty()) {
                appendLine("    Depends on: ${task.dependsOn.joinToString(", ")}")
            }
        }

        if (recentEvents.isNotEmpty()) {
            appendLine()
            appendLine("## Recent Events (last ${minOf(recentEvents.size, 10)} of ${recentEvents.size})")
            recentEvents.take(10).forEach { event ->
                appendLine("- $event")
            }
            if (recentEvents.size > 10) {
                appendLine("... and ${recentEvents.size - 10} more")
            }
        }

        appendLine()
        appendLine("## Assessment Guidelines")
        appendLine()
        appendLine("### Progress Percentage")
        appendLine("- Calculate based on completed vs total tasks")
        appendLine("- Account for in-progress tasks as partial completion")
        appendLine()
        appendLine("### Blocked Tasks")
        appendLine("- Identify tasks that cannot proceed")
        appendLine("- Note the blocking reason if apparent")
        appendLine()
        appendLine("### Risks")
        appendLine("- Identify potential issues that could delay completion")
        appendLine("- Consider dependencies and blockers")
        appendLine()
        appendLine("### Recommended Actions")
        appendLine("- Suggest next steps to move the epic forward")
        appendLine("- Prioritize unblocking blocked tasks")
        appendLine()
        appendLine("### Human Input Required")
        appendLine("- Set to true if decisions exceed agent authority")
        appendLine("- Explain what human decision is needed")
        appendLine()
        appendLine("## Output Format")
        appendLine()
        appendLine("Respond with ONLY valid JSON (no markdown code blocks, no explanations):")
        appendLine(
            """
{
  "progressPercentage": 0-100,
  "blockedTasks": ["task-id-1", "task-id-2"],
  "risks": ["Risk description 1", "Risk description 2"],
  "recommendedActions": ["Action 1", "Action 2"],
  "needsHumanInput": true/false,
  "humanInputReason": "Reason if needsHumanInput is true, or null"
}
            """.trimIndent(),
        )
    }
}
