package link.socket.ampere.agents.domain.status

import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.environment.EnvironmentFileOperation
import link.socket.ampere.agents.execution.executor.ExecutorId

/**
 * Standardized progress updates during execution.
 * All executors emit these same event types for consistency.
 *
 * This allows the CLI to display execution progress uniformly
 * regardless of which tool is actually doing the work.
 */
sealed interface ExecutionStatus : Status {

    /** The executor that produced this status */
    val executorId: ExecutorId

    /** The time when this status was evaluated */
    val timestamp: Instant

    /**
     * Execution has started.
     */
    data class Started(
        override val executorId: String,
        override val timestamp: Instant,
    ) : ExecutionStatus {

        override val isClosed: Boolean = false
    }

    /**
     * Executor is analyzing the codebase to understand what needs to be done.
     */
    data class Analyzing(
        override val executorId: String,
        override val timestamp: Instant,
        val description: String,
        val filesAnalyzed: Int? = null,
    ) : ExecutionStatus {

        override val isClosed: Boolean = false
    }

    /**
     * Executor is planning its approach to the task.
     */
    data class Planning(
        override val executorId: ExecutorId,
        override val timestamp: Instant,
        val strategy: String,
    ) : ExecutionStatus {

        override val isClosed: Boolean = false
    }

    /**
     * Executor is writing or modifying a file.
     */
    data class Writing(
        override val executorId: ExecutorId,
        override val timestamp: Instant,
        val filePath: String,
        val operation: EnvironmentFileOperation,
    ) : ExecutionStatus {

        override val isClosed: Boolean = false
    }

    /**
     * Executor is running tests.
     */
    data class Testing(
        override val executorId: ExecutorId,
        override val timestamp: Instant,
        val testType: String,
        val detail: String,
    ) : ExecutionStatus {

        override val isClosed: Boolean = false
    }

    /**
     * Executor is running linting/formatting.
     */
    data class Linting(
        override val executorId: ExecutorId,
        override val timestamp: Instant,
        val detail: String,
    ) : ExecutionStatus {

        override val isClosed: Boolean = false
    }

    /**
     * Execution completed successfully.
     */
    data class Completed(
        override val executorId: ExecutorId,
        override val timestamp: Instant,
        val result: ExecutionOutcome.Success,
    ) : ExecutionStatus {

        override val isClosed: Boolean = true
    }

    /**
     * Execution ran to the end and had nothing to report.
     *
     * Neither [Completed] nor [Failed]: the tool did not fail, and it handed back no result
     * to complete with. It is what a tool that returns a blank outcome leaves
     * (`Outcome.blank` or `ExecutionOutcome.blank`), so a caller can tell a tool that had
     * nothing to say from one that went wrong. The
     * [ToolExecutionEngine][link.socket.ampere.agents.execution.ToolExecutionEngine] returns
     * it as an explicit [ExecutionOutcome.Blank]. Before AMPR-420 the executor protocol had
     * no way to say this, so a blank was reported as a failed dispatch.
     */
    data class Blank(
        override val executorId: ExecutorId,
        override val timestamp: Instant,
    ) : ExecutionStatus {

        override val isClosed: Boolean = true
    }

    /**
     * Execution failed.
     */
    data class Failed(
        override val executorId: ExecutorId,
        override val timestamp: Instant,
        val result: ExecutionOutcome.Failure,
    ) : ExecutionStatus {

        override val isClosed: Boolean = true
    }
}
