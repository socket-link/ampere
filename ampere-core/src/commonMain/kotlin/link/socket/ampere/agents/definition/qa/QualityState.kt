package link.socket.ampere.agents.definition.qa

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
 * State for Quality Assurance Agent responsible for code validation,
 * test execution, and quality standards enforcement.
 *
 * This agent handles all quality-related operations in the AMPERE system,
 * tracking validation history, test coverage, known issue patterns, and findings.
 */
@Serializable
data class QualityState(
    /** Current task outcome */
    val outcome: Outcome,
    /** Current task being validated */
    val task: Task,
    /** Current validation plan */
    val plan: Plan,
    /** History of recent validations */
    val validationHistory: List<ValidationResult> = emptyList(),
    /** Pending code reviews */
    val pendingReviews: List<PendingReview> = emptyList(),
    /** Known patterns of issues to watch for */
    val knownIssuePatterns: List<IssuePattern> = emptyList(),
    /** Current test coverage information */
    val testCoverage: TestCoverageInfo? = null,
    /** Recent findings from validations */
    val recentFindings: List<Finding> = emptyList(),
) : AgentState() {

    /**
     * Converts the state into a structured perception for LLM consumption.
     */
    fun toPerception(): Perception<QualityState> = perception(this) {
        header("Quality Assurance Agent - State Perception")
        timestamp()

        setNewOutcome(outcome)
        setNewTask(task)
        setNewPlan(plan)
    }

    /**
     * Formats the perception state as structured text suitable for LLM consumption.
     */
    fun toPerceptionText(): String = perceptionText {
        header("Quality Agent - State Perception")
        timestamp()

        // Current task
        section("CURRENT TASK") {
            when (task) {
                is Task.CodeChange -> {
                    field("Type", "Code Validation")
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

        // Pending reviews
        sectionIf(pendingReviews.isNotEmpty(), "PENDING REVIEWS") {
            pendingReviews.take(5).forEach { review ->
                line("- ${review.filePath} (${review.priority})")
                review.reviewer?.let { line("  Reviewer: $it") }
            }
            if (pendingReviews.size > 5) {
                line("... and ${pendingReviews.size - 5} more")
            }
        }

        // Recent findings
        sectionIf(recentFindings.isNotEmpty(), "RECENT FINDINGS") {
            recentFindings.take(5).forEach { finding ->
                line("- [${finding.severity}] ${finding.message}")
                finding.location?.let { line("  Location: $it") }
            }
            if (recentFindings.size > 5) {
                line("... and ${recentFindings.size - 5} more findings")
            }
        }

        // Known issue patterns
        sectionIf(knownIssuePatterns.isNotEmpty(), "KNOWN ISSUE PATTERNS") {
            knownIssuePatterns.forEach { pattern ->
                line("- ${pattern.name}: ${pattern.description}")
            }
        }

        // Test coverage
        testCoverage?.let { coverage ->
            section("TEST COVERAGE") {
                field("Line Coverage", "${coverage.lineCoverage}%")
                field("Branch Coverage", "${coverage.branchCoverage}%")
                if (coverage.uncoveredAreas.isNotEmpty()) {
                    line("Uncovered areas:")
                    coverage.uncoveredAreas.take(3).forEach { area ->
                        line("  - $area")
                    }
                }
            }
        }

        // Validation history summary
        sectionIf(validationHistory.isNotEmpty(), "VALIDATION HISTORY") {
            val passed = validationHistory.count { it.passed }
            val failed = validationHistory.size - passed
            field("Recent Validations", validationHistory.size)
            field("Passed", passed)
            field("Failed", failed)
        }
    }

    companion object {
        val blank = QualityState(
            outcome = Outcome.blank,
            task = Task.Blank,
            plan = Plan.blank,
        )
    }
}

/**
 * Result of a validation run.
 */
@Serializable
data class ValidationResult(
    val id: String,
    val validationType: ValidationType,
    val passed: Boolean,
    val findings: List<Finding> = emptyList(),
    val timestamp: Instant,
)

/**
 * Types of validation checks.
 */
@Serializable
enum class ValidationType {
    SYNTAX,
    STYLE,
    LOGIC,
    SECURITY,
    PERFORMANCE,
    TESTING,
}

/**
 * A pending code review request.
 */
@Serializable
data class PendingReview(
    val id: String,
    val filePath: String,
    val reviewer: String? = null,
    val priority: ReviewPriority = ReviewPriority.NORMAL,
    val requestedAt: Instant,
)

/**
 * Priority levels for code reviews.
 */
@Serializable
enum class ReviewPriority {
    LOW,
    NORMAL,
    HIGH,
    CRITICAL,
}

/**
 * A known pattern of issues to watch for.
 */
@Serializable
data class IssuePattern(
    val id: String,
    val name: String,
    val description: String,
    val category: IssueCategory,
    val detectionRate: Double = 0.0,
)

/**
 * Categories of issues.
 */
@Serializable
enum class IssueCategory {
    NULL_SAFETY,
    CONCURRENCY,
    SECURITY,
    PERFORMANCE,
    MEMORY,
    BOUNDARY,
    LOGIC,
}

/**
 * Test coverage information.
 */
@Serializable
data class TestCoverageInfo(
    val lineCoverage: Double,
    val branchCoverage: Double,
    val uncoveredAreas: List<String> = emptyList(),
)

/**
 * A finding from validation.
 */
@Serializable
data class Finding(
    val id: String,
    val message: String,
    val severity: FindingSeverity,
    val location: String? = null,
    val suggestion: String? = null,
)

/**
 * Severity levels for findings.
 */
@Serializable
enum class FindingSeverity {
    INFO,
    WARNING,
    ERROR,
    CRITICAL,
}
