package link.socket.ampere.agents.execution.request

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.execution.executor.ExecutorId
import link.socket.ampere.agents.execution.tools.ToolId
import link.socket.ampere.agents.execution.tools.git.GitOperationRequest
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

    /**
     * Context for Git operations: branching, committing, pushing, and PR creation.
     *
     * @property gitRequest The Git operation to perform
     */
    @Serializable
    data class GitOperation(
        override val executorId: ExecutorId,
        override val ticket: Ticket,
        override val task: Task,
        override val instructions: String,
        val gitRequest: GitOperationRequest,
        override val knowledgeFromPastMemory: List<Knowledge> = emptyList(),
    ) : ExecutionContext

    /**
     * Context for the `plan_steps` tool — agent-neutral plan generation.
     *
     * Carries everything the tool's strategy needs to produce a structured plan:
     * the task being planned for, the perceived ideas, recalled knowledge, the
     * agent's role label, and a minimal description of the tools available so
     * the LLM can populate `toolToUse` per step.
     *
     * The strategy fills [parsedPlan] from its LLM response; the tool's
     * `execute()` reads it back out and wraps it in an outcome.
     */
    @Serializable
    data class Planning(
        override val executorId: ExecutorId,
        override val ticket: Ticket,
        override val task: Task,
        override val instructions: String,
        val agentRole: String,
        val ideaSummary: String,
        val knowledgeSummary: String,
        val availableToolDescriptors: List<ToolDescriptor>,
        val parsedPlan: Plan? = null,
        override val knowledgeFromPastMemory: List<Knowledge> = emptyList(),
    ) : ExecutionContext

    /**
     * Serializable summary of a tool surfaced to the planning LLM.
     *
     * Avoids dragging the full `Tool<*>` hierarchy through the planning context,
     * which would entangle execution-time tool registration with planning-time
     * intent generation.
     */
    @Serializable
    data class ToolDescriptor(
        val id: ToolId,
        val name: String,
        val description: String,
    )
}
