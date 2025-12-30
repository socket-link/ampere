package link.socket.ampere.agents.definition.code

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.reasoning.Perception
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.reasoning.perception
import link.socket.ampere.agents.domain.reasoning.perceptionText
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.domain.task.Task

/**
 * State for Code Agent responsible for code generation, file writing, and code reading.
 *
 * This agent handles all code-related operations in the AMPERE system,
 * tracking workspace context, file modifications, test results, and codebase understanding.
 */
@Serializable
data class CodeState(
    /** Current task outcome */
    val outcome: Outcome,
    /** Current task being worked on */
    val task: Task,
    /** Current plan for task execution */
    val plan: Plan,
    /** Current workspace information */
    val currentWorkspace: WorkspaceInfo? = null,
    /** Recently modified files */
    val recentlyModifiedFiles: List<FileChange> = emptyList(),
    /** Pending code reviews */
    val pendingCodeReviews: List<CodeReviewRequest> = emptyList(),
    /** Latest test execution results */
    val testResults: TestSummary? = null,
    /** Codebase context for informed code generation */
    val codebaseContext: CodebaseContext? = null,
) : AgentState() {

    /**
     * Converts the state into a structured perception for LLM consumption.
     */
    fun toPerception(): Perception<CodeState> = perception(this) {
        header("Code Writer Perception State")
        timestamp()

        setNewOutcome(outcome)
        setNewTask(task)
        setNewPlan(plan)
    }

    /**
     * Formats the perception state as structured text suitable for LLM consumption.
     */
    fun toPerceptionText(): String = perceptionText {
        header("Code Agent - State Perception")
        timestamp()

        // Current task
        section("CURRENT TASK") {
            when (task) {
                is Task.CodeChange -> {
                    field("Type", "Code Change")
                    field("ID", task.id)
                    field("Status", task.status)
                    field("Description", task.description)
                }
                is Task.Blank -> line("No active task")
                else -> {
                    field("Type", task::class.simpleName)
                    field("ID", task.id)
                }
            }
        }

        // Workspace info
        currentWorkspace?.let { ws ->
            section("WORKSPACE") {
                field("Base Directory", ws.baseDirectory)
                ws.projectType?.let { field("Project Type", it) }
            }
        }

        // Recent file changes
        sectionIf(recentlyModifiedFiles.isNotEmpty(), "RECENTLY MODIFIED FILES") {
            recentlyModifiedFiles.take(10).forEach { change ->
                line("${change.changeType}: ${change.path}")
            }
        }

        // Pending reviews
        sectionIf(pendingCodeReviews.isNotEmpty(), "PENDING CODE REVIEWS") {
            pendingCodeReviews.forEach { review ->
                line("- ${review.filePath}")
                review.reviewer?.let { line("  Reviewer: $it") }
            }
        }

        // Test results
        testResults?.let { tests ->
            section("TEST RESULTS") {
                field("Passed", tests.passed)
                field("Failed", tests.failed)
                field("Skipped", tests.skipped)
                if (tests.failedTests.isNotEmpty()) {
                    line("Failed tests:")
                    tests.failedTests.take(5).forEach { test ->
                        line("  - $test")
                    }
                }
            }
        }

        // Codebase context
        codebaseContext?.let { ctx ->
            section("CODEBASE CONTEXT") {
                field("Main Language", ctx.mainLanguage)
                if (ctx.frameworks.isNotEmpty()) {
                    field("Frameworks", ctx.frameworks.joinToString(", "))
                }
                if (ctx.keyPatterns.isNotEmpty()) {
                    line("Key Patterns:")
                    ctx.keyPatterns.forEach { pattern ->
                        line("  - $pattern")
                    }
                }
            }
        }
    }

    companion object {
        val blank = CodeState(
            outcome = Outcome.blank,
            task = Task.Blank,
            plan = Plan.blank,
        )
    }
}

/**
 * Information about the current workspace.
 */
@Serializable
data class WorkspaceInfo(
    val baseDirectory: String,
    val projectType: String? = null,
)

/**
 * Represents a file modification.
 */
@Serializable
data class FileChange(
    val path: String,
    val changeType: ChangeType,
    val timestamp: Instant,
)

/**
 * Type of file change.
 */
@Serializable
enum class ChangeType {
    CREATED,
    MODIFIED,
    DELETED,
}

/**
 * A pending code review request.
 */
@Serializable
data class CodeReviewRequest(
    val filePath: String,
    val reviewer: String? = null,
    val priority: ReviewPriority = ReviewPriority.NORMAL,
)

/**
 * Priority levels for code reviews.
 */
@Serializable
enum class ReviewPriority {
    LOW,
    NORMAL,
    HIGH,
    URGENT,
}

/**
 * Summary of test execution results.
 */
@Serializable
data class TestSummary(
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val failedTests: List<String> = emptyList(),
)

/**
 * Context about the codebase being worked on.
 */
@Serializable
data class CodebaseContext(
    val mainLanguage: String,
    val frameworks: List<String> = emptyList(),
    val keyPatterns: List<String> = emptyList(),
)
