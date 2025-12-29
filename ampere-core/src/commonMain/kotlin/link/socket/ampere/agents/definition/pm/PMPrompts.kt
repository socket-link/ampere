package link.socket.ampere.agents.definition.pm

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.definition.AgentId

/**
 * Agent capability information for task assignment decisions.
 *
 * @property agentId Unique identifier for the agent
 * @property capabilities List of capabilities this agent provides (e.g., "code-writing", "qa-testing", "documentation")
 * @property currentTaskCount Number of tasks currently assigned to this agent
 */
@Serializable
data class AgentCapability(
    val agentId: AgentId,
    val capabilities: List<String>,
    val currentTaskCount: Int,
)

/**
 * Prompt templates for Project Manager Agent's LLM-driven decision making.
 *
 * These prompts guide the PM agent to:
 * - Decompose goals into structured work breakdowns
 * - Assign tasks to appropriate agents
 * - Assess progress and identify blockers
 */
object PMPrompts {

    /**
     * Generates a prompt for decomposing a high-level goal into a structured work breakdown.
     *
     * The LLM response should be valid JSON matching the BatchIssueCreateRequest schema:
     * ```json
     * {
     *   "repository": "owner/repo",
     *   "issues": [
     *     {
     *       "localId": "epic-1",
     *       "type": "Feature",
     *       "title": "Epic title",
     *       "body": "Epic description in markdown",
     *       "labels": ["enhancement"],
     *       "assignees": [],
     *       "parent": null,
     *       "dependsOn": []
     *     },
     *     {
     *       "localId": "task-1",
     *       "type": "Task",
     *       "title": "Task title",
     *       "body": "Task description",
     *       "labels": ["task"],
     *       "assignees": [],
     *       "parent": "epic-1",
     *       "dependsOn": []
     *     }
     *   ]
     * }
     * ```
     *
     * @param goal The high-level goal to decompose (e.g., "Implement MCP server integration")
     * @param repository Repository identifier in "owner/repo" format
     * @param availableAgents List of agents that can be assigned tasks
     * @param existingIssues List of existing issues to avoid duplication
     * @return Prompt string that guides LLM to produce structured JSON output
     */
    fun goalDecompositionPrompt(
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
            appendLine("  - ${agent.agentId}: ${agent.capabilities.joinToString(", ")} (${agent.currentTaskCount} tasks)")
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
        appendLine("""
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
        """.trimIndent())
        appendLine()
        appendLine("## Important")
        appendLine("- Respond ONLY with the JSON")
        appendLine("- Ensure all `localId` values are unique")
        appendLine("- Ensure all `dependsOn` references point to valid `localId` values")
        appendLine("- Keep task descriptions concise but informative")
    }

    /**
     * Generates a prompt for assigning a task to the most appropriate agent.
     *
     * The LLM response should be valid JSON:
     * ```json
     * {
     *   "assignedAgent": "agent-id",
     *   "reasoning": "This agent has expertise in X and low current workload"
     * }
     * ```
     *
     * @param task The task description that needs assignment
     * @param availableAgents List of agents with their capabilities and current workload
     * @return Prompt string that guides LLM to select the best agent
     */
    fun taskAssignmentPrompt(
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
            appendLine("⚠️ No agents available for assignment!")
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
        appendLine("- Example: \"documentation\" agent for writing docs")
        appendLine()
        appendLine("### 2. Current Workload (Secondary)")
        appendLine("- Prefer agents with lower current task count")
        appendLine("- Avoid assigning to agents at capacity (>5 tasks)")
        appendLine("- Balance work distribution when capabilities overlap")
        appendLine()
        appendLine("### 3. Past Performance (Tertiary)")
        appendLine("- If you have knowledge of past successes, favor those agents")
        appendLine("- (This information may not always be available)")
        appendLine()
        appendLine("## Output Format")
        appendLine()
        appendLine("Respond with ONLY valid JSON (no markdown code blocks, no explanations):")
        appendLine()
        appendLine("""
{
  "assignedAgent": "agent-id-here",
  "reasoning": "Brief explanation of why this agent was selected based on capability match and workload"
}
        """.trimIndent())
        appendLine()
        appendLine("## Important")
        appendLine("- `assignedAgent` must be one of the agent IDs listed above")
        appendLine("- If no suitable agent exists, set `assignedAgent` to null")
        appendLine("- Keep reasoning concise (1-2 sentences)")
    }

    /**
     * Generates a prompt for assessing progress on an epic and its tasks.
     *
     * The LLM response should be valid JSON:
     * ```json
     * {
     *   "progressPercentage": 45,
     *   "blockedTasks": ["task-3", "task-5"],
     *   "risks": ["API design not finalized", "Dependencies on external service"],
     *   "recommendedActions": ["Finalize API design", "Contact external team"],
     *   "needsHumanInput": true,
     *   "humanInputReason": "Scope decision required for edge cases"
     * }
     * ```
     *
     * @param epicTitle The epic being tracked
     * @param tasks List of task summaries (localId, title, status)
     * @param recentEvents Recent events related to this epic (task completions, blockers, etc.)
     * @return Prompt string that guides LLM to assess progress and identify issues
     */
    fun progressAssessmentPrompt(
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
            appendLine("⚠️ No tasks defined for this epic!")
            appendLine()
            appendLine("Respond with:")
            appendLine("""{"progressPercentage": 0, "blockedTasks": [], "risks": ["No tasks defined"], "recommendedActions": ["Define tasks for this epic"], "needsHumanInput": true, "humanInputReason": "Epic needs task breakdown"}""")
            return@buildString
        }

        val completed = tasks.count { it.status == "completed" || it.status == "done" }
        val inProgress = tasks.count { it.status == "in_progress" || it.status == "in-progress" }
        val blocked = tasks.count { it.status == "blocked" }
        val pending = tasks.count { it.status == "pending" || it.status == "ready" }

        appendLine()
        appendLine("### Status Breakdown")
        appendLine("- ✅ Completed: $completed")
        appendLine("- 🔄 In Progress: $inProgress")
        appendLine("- 🚫 Blocked: $blocked")
        appendLine("- ⏸️ Pending: $pending")
        appendLine()
        appendLine("### Task Details")
        tasks.forEach { task ->
            val statusEmoji = when (task.status.lowercase()) {
                "completed", "done" -> "✅"
                "in_progress", "in-progress" -> "🔄"
                "blocked" -> "🚫"
                else -> "⏸️"
            }
            appendLine("$statusEmoji **${task.localId}**: ${task.title} (${task.status})")
            if (task.dependsOn.isNotEmpty()) {
                appendLine("  └─ Depends on: ${task.dependsOn.joinToString(", ")}")
            }
        }

        if (recentEvents.isNotEmpty()) {
            appendLine()
            appendLine("## Recent Events (last ${recentEvents.size})")
            recentEvents.take(10).forEach { event ->
                appendLine("- $event")
            }
            if (recentEvents.size > 10) {
                appendLine("- ... and ${recentEvents.size - 10} more events")
            }
        }

        appendLine()
        appendLine("## Assessment Guidelines")
        appendLine()
        appendLine("### Progress Percentage")
        appendLine("- Calculate based on completed tasks and partial progress on in-progress tasks")
        appendLine("- Completed task = 100% of its weight")
        appendLine("- In-progress task = ~50% of its weight")
        appendLine("- Blocked/Pending = 0%")
        appendLine("- Weight all tasks equally unless noted otherwise")
        appendLine()
        appendLine("### Blocked Tasks")
        appendLine("- List `localId` values for tasks that are blocked")
        appendLine("- Include tasks waiting on incomplete dependencies")
        appendLine("- Include tasks explicitly marked as \"blocked\" status")
        appendLine()
        appendLine("### Risks")
        appendLine("- Identify potential issues that could delay completion:")
        appendLine("  - Unresolved dependencies")
        appendLine("  - Tasks at capacity")
        appendLine("  - External dependencies")
        appendLine("  - Unclear requirements")
        appendLine()
        appendLine("### Recommended Actions")
        appendLine("- Suggest concrete next steps:")
        appendLine("  - Unblock specific tasks")
        appendLine("  - Reassign overloaded work")
        appendLine("  - Clarify requirements")
        appendLine("  - Escalate to humans")
        appendLine()
        appendLine("### Human Input Required")
        appendLine("- Set `needsHumanInput: true` if:")
        appendLine("  - Scope decisions needed")
        appendLine("  - Priority conflicts exist")
        appendLine("  - Requirements are unclear")
        appendLine("  - Major risks identified")
        appendLine("  - Architecture decisions needed")
        appendLine()
        appendLine("## Output Format")
        appendLine()
        appendLine("Respond with ONLY valid JSON (no markdown code blocks, no explanations):")
        appendLine()
        appendLine("""
{
  "progressPercentage": 0-100,
  "blockedTasks": ["task-id-1", "task-id-2"],
  "risks": ["Risk description 1", "Risk description 2"],
  "recommendedActions": ["Action 1", "Action 2"],
  "needsHumanInput": true/false,
  "humanInputReason": "Reason if needsHumanInput is true, or null"
}
        """.trimIndent())
        appendLine()
        appendLine("## Important")
        appendLine("- `progressPercentage` must be 0-100")
        appendLine("- `blockedTasks` should reference valid task `localId` values")
        appendLine("- Keep descriptions concise")
        appendLine("- If `needsHumanInput` is false, set `humanInputReason` to null")
    }
}

/**
 * Summary of a task for progress assessment.
 *
 * @property localId Task identifier
 * @property title Task title
 * @property status Current status (pending, in_progress, blocked, completed)
 * @property dependsOn List of task IDs this task depends on
 */
@Serializable
data class TaskSummary(
    val localId: String,
    val title: String,
    val status: String,
    val dependsOn: List<String> = emptyList(),
)
