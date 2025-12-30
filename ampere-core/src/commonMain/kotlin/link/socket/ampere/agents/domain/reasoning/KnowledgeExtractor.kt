package link.socket.ampere.agents.domain.reasoning

import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.task.Task

/**
 * Extracts knowledge from task execution outcomes.
 *
 * This component handles the "Learn" phase of the PROPEL cognitive loop,
 * creating structured knowledge from execution results that can be stored
 * in agent memory for future reference.
 *
 * Uses a builder pattern for constructing knowledge entries with consistent
 * formatting across all agent types.
 *
 * Usage:
 * ```kotlin
 * val knowledge = KnowledgeExtractor.extract(outcome, task, plan) {
 *     // Agent-specific approach description
 *     approach {
 *         taskType(task)  // "Code change: ..." or "PM Task: ..."
 *         planSize(plan)  // "(3 steps)"
 *     }
 *
 *     // Agent-specific learning extraction
 *     learnings {
 *         fromOutcome(outcome)  // Auto-handles all ExecutionOutcome types
 *         line("Additional context specific to this agent")
 *     }
 * }
 * ```
 */
object KnowledgeExtractor {

    /**
     * Extracts knowledge from an outcome using a builder.
     *
     * @param outcome The execution outcome
     * @param task The task that was executed
     * @param plan The plan that was followed
     * @param configure Builder configuration lambda
     * @return Knowledge entry ready to be stored
     */
    fun extract(
        outcome: Outcome,
        task: Task,
        plan: Plan,
        configure: KnowledgeBuilder.() -> Unit,
    ): Knowledge.FromOutcome {
        val builder = KnowledgeBuilder(outcome, task, plan)
        builder.configure()
        return builder.build()
    }

    /**
     * Extracts knowledge using default extraction for all outcome types.
     *
     * This provides sensible defaults without requiring agent-specific configuration.
     *
     * @param outcome The execution outcome
     * @param task The task that was executed
     * @param plan The plan that was followed
     * @param agentRole Optional role description for context
     * @return Knowledge entry ready to be stored
     */
    fun extractDefault(
        outcome: Outcome,
        task: Task,
        plan: Plan,
        agentRole: String = "Agent",
    ): Knowledge.FromOutcome {
        return extract(outcome, task, plan) {
            approach {
                prefix("$agentRole Task")
                taskType(task)
                planSize(plan)
            }
            learnings {
                fromOutcome(outcome)
            }
        }
    }
}

/**
 * Builder for constructing Knowledge.FromOutcome entries.
 */
class KnowledgeBuilder(
    private val outcome: Outcome,
    private val task: Task,
    private val plan: Plan,
) {
    private var approachDescription: String = ""
    private var learningsDescription: String = ""

    /**
     * Configure the approach description.
     */
    fun approach(configure: ApproachBuilder.() -> Unit) {
        val builder = ApproachBuilder(task, plan)
        builder.configure()
        approachDescription = builder.build()
    }

    /**
     * Configure the learnings description.
     */
    fun learnings(configure: LearningsBuilder.() -> Unit) {
        val builder = LearningsBuilder(outcome)
        builder.configure()
        learningsDescription = builder.build()
    }

    /**
     * Build the final Knowledge.FromOutcome.
     */
    fun build(): Knowledge.FromOutcome {
        return Knowledge.FromOutcome(
            outcomeId = outcome.id,
            approach = approachDescription.ifEmpty { "Task execution" },
            learnings = learningsDescription.ifEmpty { "No learnings recorded" },
            timestamp = Clock.System.now(),
        )
    }
}

/**
 * Builder for the approach description.
 */
class ApproachBuilder(
    private val task: Task,
    private val plan: Plan,
) {
    private val parts = mutableListOf<String>()

    /**
     * Add a prefix to the approach description.
     */
    fun prefix(text: String) {
        parts.add(0, text)
    }

    /**
     * Add task type description based on task type.
     */
    fun taskType(task: Task) {
        val description = when (task) {
            is Task.CodeChange -> "Code change: ${task.description}"
            is Task.Blank -> "No specific task"
            else -> "Task: ${task.id}"
        }
        parts.add(description)
    }

    /**
     * Add custom task description.
     */
    fun custom(description: String) {
        parts.add(description)
    }

    /**
     * Add plan size information if plan has steps.
     */
    fun planSize(plan: Plan) {
        if (plan is Plan.ForTask && plan.tasks.isNotEmpty()) {
            parts.add("(${plan.tasks.size} steps)")
        }
    }

    fun build(): String = parts.joinToString(" ")
}

/**
 * Builder for the learnings description.
 */
class LearningsBuilder(private val outcome: Outcome) {
    private val content = StringBuilder()

    /**
     * Add a line to the learnings.
     */
    fun line(text: String) {
        content.appendLine(text)
    }

    /**
     * Add a blank line.
     */
    fun blank() {
        content.appendLine()
    }

    /**
     * Add a success indicator line.
     */
    fun success(message: String) {
        content.appendLine("✓ $message")
    }

    /**
     * Add a failure indicator line.
     */
    fun failure(message: String) {
        content.appendLine("✗ $message")
    }

    /**
     * Add a field with label and value.
     */
    fun field(label: String, value: Any?) {
        content.appendLine("$label: $value")
    }

    /**
     * Add files list (truncated if too many).
     */
    fun files(label: String, files: List<String>, maxShow: Int = 3) {
        field(label, files.size)
        files.take(maxShow).forEach { file ->
            content.appendLine("  - $file")
        }
        if (files.size > maxShow) {
            content.appendLine("  ... and ${files.size - maxShow} more")
        }
    }

    /**
     * Add file pairs list (path -> content) truncated if too many.
     */
    fun filePairs(label: String, files: List<Pair<String, String>>, maxShow: Int = 3) {
        field(label, files.size)
        files.take(maxShow).forEach { (path, _) ->
            content.appendLine("  - $path")
        }
        if (files.size > maxShow) {
            content.appendLine("  ... and ${files.size - maxShow} more")
        }
    }

    /**
     * Add duration information.
     */
    fun duration(
        startTimestamp: kotlinx.datetime.Instant,
        endTimestamp: kotlinx.datetime.Instant,
    ) {
        val duration = endTimestamp - startTimestamp
        content.appendLine("Duration: $duration")
    }

    /**
     * Add recommendation line.
     */
    fun recommendation(text: String) {
        blank()
        content.appendLine(text)
    }

    /**
     * Auto-extract learnings from any ExecutionOutcome type.
     *
     * This handles all known outcome types with sensible defaults.
     */
    fun fromOutcome(outcome: Outcome) {
        when (outcome) {
            is ExecutionOutcome.CodeChanged.Success -> {
                success("Code changes succeeded")
                files("Files modified", outcome.changedFiles)
                field("Validation", outcome.validation)
                duration(outcome.executionStartTimestamp, outcome.executionEndTimestamp)
                recommendation("This approach was successful for this type of code change task.")
            }
            is ExecutionOutcome.CodeChanged.Failure -> {
                failure("Code changes failed")
                field("Error", outcome.error)
                outcome.partiallyChangedFiles?.let { files ->
                    if (files.isNotEmpty()) {
                        files("Partially changed", files)
                    }
                }
                duration(outcome.executionStartTimestamp, outcome.executionEndTimestamp)
                recommendation("Future tasks should avoid this approach or address the error cause.")
            }
            is ExecutionOutcome.CodeReading.Success -> {
                success("Code reading succeeded")
                filePairs("Files read", outcome.readFiles)
                duration(outcome.executionStartTimestamp, outcome.executionEndTimestamp)
                recommendation("Code reading was successful for gathering context.")
            }
            is ExecutionOutcome.CodeReading.Failure -> {
                failure("Code reading failed")
                field("Error", outcome.error)
                recommendation("Consider checking file paths or permissions for future reads.")
            }
            is ExecutionOutcome.IssueManagement.Success -> {
                success("Issue management succeeded")
                field("Issues created", outcome.response.created.size)
                outcome.response.created.take(3).forEach { issue ->
                    content.appendLine("  - Issue #${issue.issueNumber}")
                }
                if (outcome.response.created.size > 3) {
                    content.appendLine("  ... and ${outcome.response.created.size - 3} more")
                }
                duration(outcome.executionStartTimestamp, outcome.executionEndTimestamp)
                recommendation("This work breakdown structure was effective for this goal type.")
            }
            is ExecutionOutcome.IssueManagement.Failure -> {
                failure("Issue management failed")
                field("Error", outcome.error.message)
                duration(outcome.executionStartTimestamp, outcome.executionEndTimestamp)
                recommendation("Review issue structure or external system configuration.")
            }
            is ExecutionOutcome.NoChanges.Success -> {
                success(outcome.message)
                duration(outcome.executionStartTimestamp, outcome.executionEndTimestamp)
            }
            is ExecutionOutcome.NoChanges.Failure -> {
                failure(outcome.message)
                duration(outcome.executionStartTimestamp, outcome.executionEndTimestamp)
            }
            is Outcome.Success -> {
                success("Task succeeded (details not available)")
            }
            is Outcome.Failure -> {
                failure("Task failed (details not available)")
            }
            is Outcome.Blank -> {
                line("No outcome recorded")
            }
            else -> {
                line("Outcome type: ${outcome::class.simpleName}")
            }
        }
    }

    fun build(): String = content.toString().trimEnd()
}
