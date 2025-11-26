package link.socket.ampere.agents.execution.outcomes

import kotlinx.serialization.Serializable

/** Results from running linting/formatting */
@Serializable
data class ExecutionLintValidation(
    /** Number of errors found */
    val errors: Int,
    /** Number of warnings found */
    val warnings: Int,
    /** Individual lint issues */
    val issues: List<LintIssue>,
)

/** A single lint issue */
@Serializable
data class LintIssue(
    /** File where the issue was found */
    val file: String,
    /** Line number of the issue */
    val line: Int,
    /** Severity level (error, warning, info) */
    val severity: String,
    /** Description of the issue */
    val message: String,
)
