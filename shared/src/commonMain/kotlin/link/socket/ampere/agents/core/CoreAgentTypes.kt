package link.socket.ampere.agents.core

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.events.tasks.Task

/**
 * Core foundational types for autonomous agents. All types are immutable and
 * defined in commonMain to support Kotlin Multiplatform targets.
 */

@Serializable
data class Perception(
    val ideas: List<Idea>,
    val currentState: State,
    val timestamp: Instant,
)

@Serializable
data class Idea(
    val name: String,
    val description: String = "",
) {
    companion object {
        val blank: Idea
            get() = Idea(name = "", description = "")
    }
}

/**
 * Represents a high-level plan produced by an agent for a given task.
 *
 * @param tasks Ordered list of steps the agent intends to take.
 * @param estimatedComplexity A rough, relative measure of complexity (e.g., 1–10).
 * @param requiresHumanApproval Whether the plan should be approved by a human before execution.
 */
@Serializable
data class Plan(
    val estimatedComplexity: Int,
    val tasks: List<Task>,
) {
    companion object {
        val blank: Plan
            get() = Plan(estimatedComplexity = 0, tasks = emptyList())
    }
}

@Serializable
sealed class Outcome(
    val task: Task,
) {

    @Serializable
    data object Blank : Outcome(Task.blank)

    @Serializable
    sealed class Success(
        private val _task: Task,
    ) : Outcome(_task) {

        @Serializable
        data class Partial(
            private val __task: Task,
            val unfinishedTasks: List<Task>? = null,
        ) : Success(__task)

        @Serializable
        data class Full(
            private val __task: Task,
            val value: String,
        ) : Success(__task)
    }

    @Serializable
    data class Failure(
        private val _task: Task,
        val errorMessage: String,
    ) : Outcome(_task)

    companion object {
        val blank = Blank
    }
}

/**
 * A message that an agent may emit for logging, user communication, or escalation.
 *
 * @param content The human-readable message content.
 * @param severity The severity level of the message.
 * @param requiresResponse Whether a human response is requested/required.
 */
@Serializable
data class Signal(
    val value: String,
)
