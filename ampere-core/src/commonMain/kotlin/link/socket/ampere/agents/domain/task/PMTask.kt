package link.socket.ampere.agents.domain.task

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.execution.tools.issue.BatchIssueCreateRequest

/**
 * Tasks specific to Project Manager Agent.
 *
 * These represent the PM's coordination and planning activities.
 */
@Serializable
sealed interface PMTask : Task {

    /**
     * Decompose a high-level goal into structured work breakdown (epic + tasks).
     *
     * This task involves:
     * 1. Using LLM to analyze the goal and create work breakdown
     * 2. Generating BatchIssueCreateRequest JSON
     * 3. Determining agent assignments for each task
     *
     * @property goal The high-level goal to decompose
     * @property repository Repository identifier in "owner/repo" format
     */
    @Serializable
    data class DecomposeGoal(
        override val id: TaskId,
        override val status: TaskStatus,
        val goal: String,
        val repository: String,
    ) : PMTask

    /**
     * Create issues in external system (GitHub, JIRA, etc.).
     *
     * @property issueRequest The batch issue creation request
     */
    @Serializable
    data class CreateIssues(
        override val id: TaskId,
        override val status: TaskStatus,
        val issueRequest: BatchIssueCreateRequest,
    ) : PMTask

    /**
     * Assign a task to an appropriate agent.
     *
     * @property taskLocalId The local ID of the task within the work breakdown
     * @property agentId The agent to assign the task to
     * @property reasoning Why this agent was selected
     */
    @Serializable
    data class AssignTask(
        override val id: TaskId,
        override val status: TaskStatus,
        val taskLocalId: String,
        val agentId: AgentId,
        val reasoning: String,
    ) : PMTask

    /**
     * Start monitoring an epic's progress.
     *
     * @property epicLocalId The local ID of the epic to monitor
     * @property tasks List of task IDs within the epic
     */
    @Serializable
    data class StartMonitoring(
        override val id: TaskId,
        override val status: TaskStatus,
        val epicLocalId: String,
        val tasks: List<String>,
    ) : PMTask

    /**
     * Assess progress on an epic and identify blockers.
     *
     * @property epicId The epic being assessed
     * @property epicTitle Title of the epic
     */
    @Serializable
    data class AssessProgress(
        override val id: TaskId,
        override val status: TaskStatus,
        val epicId: String,
        val epicTitle: String,
    ) : PMTask

    /**
     * Escalate a decision to human stakeholders.
     *
     * @property decision The decision requiring human input
     * @property reason Why this needs escalation
     * @property context Additional context for the decision
     */
    @Serializable
    data class EscalateDecision(
        override val id: TaskId,
        override val status: TaskStatus,
        val decision: String,
        val reason: String,
        val context: String,
    ) : PMTask
}
