package link.socket.ampere.agents.definition.pm

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.definition.perception
import link.socket.ampere.agents.definition.perceptionText
import link.socket.ampere.agents.domain.concept.Perception
import link.socket.ampere.agents.domain.concept.Plan
import link.socket.ampere.agents.domain.concept.outcome.Outcome
import link.socket.ampere.agents.domain.concept.task.Task
import link.socket.ampere.agents.domain.state.AgentState

/**
 * State for Project Manager agent responsible for goal decomposition,
 * work breakdown, task assignment, and coordination.
 *
 * This agent acts as the executive function of the AMPERE nervous system,
 * transforming high-level goals into structured execution plans and monitoring
 * progress across multiple agents.
 */
@Serializable
data class ProjectManagerState(
    /** Current task outcome */
    val outcome: Outcome,
    /** Current task being worked on */
    val task: Task,
    /** Current plan for task execution */
    val plan: Plan,
    /** Active goals being decomposed or monitored */
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
    fun toPerception(): Perception<ProjectManagerState> = perception(this) {
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
     */
    fun toPerceptionText(): String = perceptionText {
        header("Project Manager - State Perception")
        timestamp()

        // Active goals
        sectionIf(activeGoals.isNotEmpty(), "ACTIVE GOALS") {
            activeGoals.forEach { goal ->
                line("Goal: ${goal.description}")
                field("Status", goal.status)
                field("Priority", goal.priority)
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
        val blank = ProjectManagerState(
            outcome = Outcome.blank,
            task = Task.Blank,
            plan = Plan.blank,
            activeGoals = emptyList(),
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
 */
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
