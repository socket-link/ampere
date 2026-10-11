package link.socket.ampere.agents.definition.project

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.reasoning.Perception
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.reasoning.perception
import link.socket.ampere.agents.domain.reasoning.perceptionText
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.goal.GoalRecord

/**
 * State for Project Manager agent responsible for goal decomposition,
 * work breakdown, task assignment, and coordination.
 *
 * This agent acts as the executive function of the AMPERE nervous system,
 * transforming high-level goals into structured execution plans and monitoring
 * progress across multiple agents.
 */
@Serializable
data class ProjectState(
    /** Current task outcome */
    val outcome: Outcome,
    /** Current task being worked on */
    val task: Task,
    /** Current plan for task execution */
    val plan: Plan,
    /** Active goals being decomposed or monitored */
    @Deprecated(
        message = "String-typed goals are retired (AMPR-419, F8). The agent's goals live on the " +
            "Goal branch: read them with GoalBranch.open() and pass them to toPerceptionText(goals). " +
            "Nothing reads this field; it stays one release so stored states still decode.",
    )
    val activeGoals: List<Goal> = emptyList(),
    /** Current work breakdown structures */
    val workBreakdowns: List<WorkBreakdown> = emptyList(),
    /** Task assignments to agents */
    val taskAssignments: Map<String, AgentId> = emptyMap(),
    /** Issues created and their status */
    val createdIssues: List<CreatedIssue> = emptyList(),
    /** Blocked tasks requiring attention */
    val blockedTasks: List<String> = emptyList(),
    /** Decisions pending human escalation */
    val pendingEscalations: List<Escalation> = emptyList(),
) : AgentState() {

    /**
     * Converts the state into a structured perception for LLM consumption.
     */
    fun toPerception(): Perception<ProjectState> = perception(this) {
        header("Project Manager Perception State")
        timestamp()

        setNewOutcome(outcome)
        setNewTask(task)
        setNewPlan(plan)
    }

    /**
     * Formats the perception state as structured text suitable for LLM consumption.
     *
     * Highlights active goals, work breakdowns, assignments, and blockers to
     * enable the PM agent to make informed coordination decisions.
     *
     * @param goals The agent's goals as the Goal branch holds them (AMPR-419) — typically
     *   `GoalBranch.open()`. The string-typed [activeGoals] is not read: a goal's weight is
     *   derived, never stored (F28), so no priority is rendered either.
     */
    fun toPerceptionText(goals: List<GoalRecord> = emptyList()): String = perceptionText {
        header("Project Manager - State Perception")
        timestamp()

        // Active goals, from the Goal branch
        sectionIf(goals.isNotEmpty(), "ACTIVE GOALS") {
            goals.forEach { record ->
                line("Goal: ${record.goal.description}")
                field("Status", record.status.name)
                record.goal.parent?.let { parent -> field("Part of", parent.value) }
                line("")
            }
        }

        // Work breakdowns
        sectionIf(workBreakdowns.isNotEmpty(), "WORK BREAKDOWNS") {
            workBreakdowns.forEach { breakdown ->
                line("Epic: ${breakdown.epicTitle}")
                field("Tasks", breakdown.tasks.size)
                field("Completed", breakdown.tasks.count { it.status == "completed" })
                line("")
            }
        }

        // Blocked tasks requiring attention
        sectionIf(blockedTasks.isNotEmpty(), "BLOCKED TASKS (Requires Attention)") {
            blockedTasks.forEach { taskId ->
                line("- $taskId")
            }
        }

        // Pending escalations
        sectionIf(pendingEscalations.isNotEmpty(), "PENDING ESCALATIONS") {
            pendingEscalations.forEach { escalation ->
                line("Decision: ${escalation.decision}")
                field("Reason", escalation.reason)
                line("")
            }
        }

        // Task assignments
        sectionIf(taskAssignments.isNotEmpty(), "TASK ASSIGNMENTS") {
            taskAssignments.forEach { (taskId, agentId) ->
                line("$taskId -> $agentId")
            }
        }
    }

    companion object {
        /**
         * Returns an empty state for initialization.
         */
        val blank: ProjectState
            get() = ProjectState(
                outcome = Outcome.blank,
                task = Task.Blank,
                plan = Plan.blank,
                workBreakdowns = emptyList(),
                taskAssignments = emptyMap(),
                createdIssues = emptyList(),
                blockedTasks = emptyList(),
                pendingEscalations = emptyList(),
            )
    }
}

/**
 * Represents a high-level goal to be decomposed.
 *
 * String-typed throughout (recon C28): a priority with no scale and a status with no states. The
 * agent's goals are the Field's Goal branch now — `link.socket.ampere.field.Goal`, persisted as a
 * `GoalRecord` by `GoalBranch` (AMPR-419, F8).
 */
@Deprecated(
    message = "String-typed goals are retired (AMPR-419, F8). Use the Goal branch: " +
        "link.socket.ampere.goal.GoalRecord, read through GoalBranch.",
    replaceWith = ReplaceWith("GoalRecord", "link.socket.ampere.goal.GoalRecord"),
)
@Serializable
data class Goal(
    val id: String,
    val description: String,
    val priority: String,
    val status: String,
)

/**
 * Represents a structured work breakdown (epic + tasks).
 */
@Serializable
data class WorkBreakdown(
    val epicId: String,
    val epicTitle: String,
    val tasks: List<WorkTask>,
)

/**
 * Represents a task within a work breakdown.
 */
@Serializable
data class WorkTask(
    val id: String,
    val title: String,
    val status: String,
    val dependencies: List<String> = emptyList(),
)

/**
 * Represents an issue created in external system.
 */
@Serializable
data class CreatedIssue(
    val issueId: String,
    val title: String,
    val issueType: String,
    val status: String,
)

/**
 * Represents a decision escalated to human.
 */
@Serializable
data class Escalation(
    val id: String,
    val decision: String,
    val reason: String,
    val context: String,
)
