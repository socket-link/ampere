package link.socket.ampere.agents.domain.cognition.sparks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.cognition.FileAccessScope
import link.socket.ampere.agents.domain.cognition.Spark
import link.socket.ampere.agents.domain.cognition.ToolId
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.domain.task.TaskId

/**
 * A Spark that provides context about the current task being worked on.
 *
 * TaskSpark wraps the existing Task type to add it to the cognitive context.
 * It doesn't narrow tools or file access—those constraints come from the role.
 * It purely adds context about what specific work the agent should accomplish.
 *
 * The task details are rendered as a "Current Task" section in the system prompt,
 * providing the agent with clear understanding of what to accomplish.
 */
@Serializable
@SerialName("TaskSpark")
data class TaskSpark(
    /**
     * The task ID for reference.
     */
    val taskId: TaskId,

    /**
     * Human-readable title for the task.
     */
    val title: String,

    /**
     * Detailed description of what needs to be done.
     */
    val description: String,

    /**
     * Optional acceptance criteria that define when the task is complete.
     */
    val acceptanceCriteria: List<String> = emptyList(),

    /**
     * Optional additional context or notes about the task.
     */
    val additionalContext: String = "",
) : Spark {

    override val name: String = "Task:$taskId"

    override val promptContribution: String = buildString {
        appendLine("## Current Task")
        appendLine()
        appendLine("**ID:** $taskId")
        appendLine("**Title:** $title")
        appendLine()
        appendLine("### Description")
        appendLine(description)

        if (acceptanceCriteria.isNotEmpty()) {
            appendLine()
            appendLine("### Acceptance Criteria")
            acceptanceCriteria.forEachIndexed { index, criterion ->
                appendLine("${index + 1}. $criterion")
            }
        }

        if (additionalContext.isNotBlank()) {
            appendLine()
            appendLine("### Additional Context")
            appendLine(additionalContext)
        }
    }

    override val allowedTools: Set<ToolId>? = null // Inherits from role

    override val fileAccessScope: FileAccessScope? = null // Inherits from role

    companion object {
        /**
         * Creates a TaskSpark from a Task domain object.
         *
         * Extracts relevant information from the Task sealed hierarchy
         * to populate the spark.
         */
        fun fromTask(task: Task): TaskSpark = when (task) {
            is Task.Blank -> TaskSpark(
                taskId = task.id.ifEmpty { "blank" },
                title = "No specific task",
                description = "No task is currently assigned.",
            )
            is Task.CodeChange -> TaskSpark(
                taskId = task.id,
                title = "Code Change: ${task.description.take(50)}",
                description = task.description,
            )
            else -> TaskSpark(
                taskId = task.id,
                title = "Task: ${task.id.take(30)}",
                description = "Task type: ${task::class.simpleName}",
            )
        }

        /**
         * Creates a simple TaskSpark with minimal information.
         */
        fun simple(
            taskId: String,
            title: String,
            description: String,
        ): TaskSpark = TaskSpark(
            taskId = taskId,
            title = title,
            description = description,
        )
    }
}
