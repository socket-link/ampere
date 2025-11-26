package link.socket.ampere.agents.execution.outcomes

import kotlinx.serialization.Serializable

/** Results from running tests */
@Serializable
data class ExecutionTestValidation(
    /** Number of tests that passed */
    val passed: Int,
    /** Number of tests that failed */
    val failed: Int,
    /** Number of tests that were skipped */
    val skipped: Int,
    /** How long the tests took to run in milliseconds */
    val duration: Long,
    /** Details of test failures */
    val failures: List<TestFailure>,
)

/** Details about a single test failure */
@Serializable
data class TestFailure(
    /** Fully qualified name of the failed test */
    val testName: String,
    /** The error message */
    val errorMessage: String,
    /** Stack trace if available */
    val stackTrace: String?,
)
