package link.socket.ampere.agents.execution.request

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.concept.knowledge.Knowledge
import link.socket.ampere.agents.domain.concept.task.Task
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.execution.executor.ExecutorId
import link.socket.ampere.agents.execution.tools.issue.BatchIssueCreateRequest

/** Additional context used to help the executor perform a task */
@Serializable
sealed interface ExecutionContext {

    /** The executor that will use this context */
    val executorId: ExecutorId

    /** The ticket describing the overall work to be done */
    val ticket: Ticket

    /** The current task from the ticket to be performed */
    val task: Task

    /** More instructions for the executor on how the work should be done */
    val instructions: String

    /** Previous attempts and their outcomes, to help the executor avoid repeating mistakes */
    val knowledgeFromPastMemory: List<Knowledge>

    @Serializable
    data class NoChanges(
        override val executorId: ExecutorId,
        override val ticket: Ticket,
        override val task: Task,
        override val instructions: String,
        override val knowledgeFromPastMemory: List<Knowledge> = emptyList(),
    ) : ExecutionContext

    @Serializable
    sealed interface Code : ExecutionContext {

        val workspace: ExecutionWorkspace

        @Serializable
        data class ReadCode(
            override val executorId: ExecutorId,
            override val ticket: Ticket,
            override val task: Task,
            override val instructions: String,
            override val workspace: ExecutionWorkspace,
            val filePathsToRead: List<String>,
            override val knowledgeFromPastMemory: List<Knowledge> = emptyList(),
        ) : Code

        @Serializable
        data class WriteCode(
            override val executorId: ExecutorId,
            override val ticket: Ticket,
            override val task: Task,
            override val instructions: String,
            override val workspace: ExecutionWorkspace,
            val instructionsPerFilePath: List<Pair<String, String>>,
            override val knowledgeFromPastMemory: List<Knowledge> = emptyList(),
        ) : Code
    }

    @Serializable
    data class IssueManagement(
        override val executorId: ExecutorId,
        override val ticket: Ticket,
        override val task: Task,
        override val instructions: String,
        val issueRequest: BatchIssueCreateRequest,
        override val knowledgeFromPastMemory: List<Knowledge> = emptyList(),
    ) : ExecutionContext
}
