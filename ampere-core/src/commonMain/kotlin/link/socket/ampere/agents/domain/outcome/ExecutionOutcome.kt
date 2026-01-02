package link.socket.ampere.agents.domain.outcome

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.error.ExecutionError
import link.socket.ampere.agents.domain.task.TaskId
import link.socket.ampere.agents.events.tickets.TicketId
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.executor.ExecutorId
import link.socket.ampere.agents.execution.results.ExecutionResult
import link.socket.ampere.agents.execution.tools.git.GitOperationResponse
import link.socket.ampere.agents.execution.tools.issue.BatchIssueCreateResponse

/**
 * Standardized execution outcomes.
 * Format is tool-agnostic to enable cross-executor learning.
 *
 * When an agent recalls "what worked last time", these results
 * should be comparable regardless of which executor produced them.
 */
@Serializable
sealed interface ExecutionOutcome : Outcome {

    /** The executor that produced this outcome */
    val executorId: ExecutorId

    /** The ticket that was executed */
    val ticketId: TicketId

    /** The task that was executed */
    val taskId: TaskId

    /** When the execution started */
    val executionStartTimestamp: Instant

    /** When the execution ended */
    val executionEndTimestamp: Instant

    /** Blank outcome, used for initialization */
    @Serializable
    data object Blank : ExecutionOutcome {
        override val id: OutcomeId = ""
        override val executorId: ExecutorId = ""
        override val ticketId: TicketId = ""
        override val taskId: TaskId = ""
        override val executionStartTimestamp: Instant = Instant.DISTANT_PAST
        override val executionEndTimestamp: Instant = Instant.DISTANT_PAST
    }

    /** Successful execution with all validation passing */
    @Serializable
    sealed interface Success : ExecutionOutcome, Outcome.Success

    /** Failed execution with error details */
    @Serializable
    sealed interface Failure : ExecutionOutcome, Outcome.Failure

    @Serializable
    sealed interface NoChanges : ExecutionOutcome {

        @Serializable
        data class Success(
            override val executorId: ExecutorId,
            override val ticketId: TicketId,
            override val taskId: TaskId,
            override val executionStartTimestamp: Instant,
            override val executionEndTimestamp: Instant,
            val message: String,
        ) : NoChanges, ExecutionOutcome.Success {

            override val id: OutcomeId =
                generateUUID(executorId)
        }

        @Serializable
        data class Failure(
            override val executorId: ExecutorId,
            override val ticketId: TicketId,
            override val taskId: TaskId,
            override val executionStartTimestamp: Instant,
            override val executionEndTimestamp: Instant,
            val message: String,
        ) : NoChanges, ExecutionOutcome.Failure {

            override val id: OutcomeId =
                generateUUID(executorId)
        }
    }

    @Serializable
    sealed interface CodeReading : ExecutionOutcome {

        @Serializable
        data class Success(
            override val executorId: ExecutorId,
            override val ticketId: TicketId,
            override val taskId: TaskId,
            override val executionStartTimestamp: Instant,
            override val executionEndTimestamp: Instant,
            val readFiles: List<Pair<String, String>>,
        ) : CodeReading, ExecutionOutcome.Success {

            override val id: OutcomeId =
                generateUUID(executorId)
        }

        @Serializable
        data class Failure(
            override val executorId: ExecutorId,
            override val ticketId: TicketId,
            override val taskId: TaskId,
            override val executionStartTimestamp: Instant,
            override val executionEndTimestamp: Instant,
            val error: ExecutionError,
            val partiallyReadFiles: List<Pair<String, String>>? = null,
        ) : CodeReading, ExecutionOutcome.Failure {

            override val id: OutcomeId =
                generateUUID(executorId)
        }
    }

    @Serializable
    sealed interface CodeChanged : ExecutionOutcome {

        @Serializable
        data class Success(
            override val executorId: ExecutorId,
            override val ticketId: TicketId,
            override val taskId: TaskId,
            override val executionStartTimestamp: Instant,
            override val executionEndTimestamp: Instant,
            val changedFiles: List<String>,
            val validation: ExecutionResult,
        ) : CodeChanged, ExecutionOutcome.Success {

            override val id: OutcomeId =
                generateUUID(executorId)
        }

        @Serializable
        data class Failure(
            override val executorId: ExecutorId,
            override val ticketId: TicketId,
            override val taskId: TaskId,
            override val executionStartTimestamp: Instant,
            override val executionEndTimestamp: Instant,
            val error: ExecutionError,
            val partiallyChangedFiles: List<String>? = null,
        ) : CodeChanged, ExecutionOutcome.Failure {

            override val id: OutcomeId =
                generateUUID(executorId)
        }
    }

    @Serializable
    sealed interface IssueManagement : ExecutionOutcome {

        @Serializable
        data class Success(
            override val executorId: ExecutorId,
            override val ticketId: TicketId,
            override val taskId: TaskId,
            override val executionStartTimestamp: Instant,
            override val executionEndTimestamp: Instant,
            val response: BatchIssueCreateResponse,
        ) : IssueManagement, ExecutionOutcome.Success {

            override val id: OutcomeId =
                generateUUID(executorId)
        }

        @Serializable
        data class Failure(
            override val executorId: ExecutorId,
            override val ticketId: TicketId,
            override val taskId: TaskId,
            override val executionStartTimestamp: Instant,
            override val executionEndTimestamp: Instant,
            val error: ExecutionError,
            val partialResponse: BatchIssueCreateResponse? = null,
        ) : IssueManagement, ExecutionOutcome.Failure {

            override val id: OutcomeId =
                generateUUID(executorId)
        }
    }

    /**
     * Outcomes from Git operations: branching, committing, pushing, PR creation.
     */
    @Serializable
    sealed interface GitOperation : ExecutionOutcome {

        @Serializable
        data class Success(
            override val executorId: ExecutorId,
            override val ticketId: TicketId,
            override val taskId: TaskId,
            override val executionStartTimestamp: Instant,
            override val executionEndTimestamp: Instant,
            val response: GitOperationResponse,
        ) : GitOperation, ExecutionOutcome.Success {

            override val id: OutcomeId =
                generateUUID(executorId)
        }

        @Serializable
        data class Failure(
            override val executorId: ExecutorId,
            override val ticketId: TicketId,
            override val taskId: TaskId,
            override val executionStartTimestamp: Instant,
            override val executionEndTimestamp: Instant,
            val error: ExecutionError,
            val partialResponse: GitOperationResponse? = null,
        ) : GitOperation, ExecutionOutcome.Failure {

            override val id: OutcomeId =
                generateUUID(executorId)
        }
    }

    companion object {
        val blank: ExecutionOutcome = Blank
    }
}
