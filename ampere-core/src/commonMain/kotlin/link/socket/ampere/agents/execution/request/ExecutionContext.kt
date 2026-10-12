package link.socket.ampere.agents.execution.request

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.execution.executor.ExecutorId
import link.socket.ampere.agents.execution.tools.ToolId
import link.socket.ampere.agents.execution.tools.git.GitOperationRequest
import link.socket.ampere.agents.execution.tools.issue.BatchIssueCreateRequest

/**
 * Additional context used to help the executor perform a task.
 *
 * Names two parties, and they are never the same string (AMPR-443, F17): [actorId] is the
 * agent that decided the work should happen, and [executorId] is the
 * [Executor][link.socket.ampere.agents.execution.executor.Executor] that performs it. Until
 * F17 the agent's id rode in [executorId], so "who did this" had no answer — a reader that
 * wanted the agent and a reader that wanted the executor read the same field.
 */
@Serializable
sealed interface ExecutionContext {

    /**
     * The agent that decided this work should happen — the Subject a tool acts on behalf of.
     * Read this, not [executorId], wherever "who" means the agent: an event's source, a
     * routing decision, an escalation to a person.
     */
    val actorId: AgentId

    /**
     * The [Executor][link.socket.ampere.agents.execution.executor.Executor] that performs this
     * work — its `id`, e.g. `FunctionExecutor.DEFAULT_ID`. [NO_EXECUTOR_ID] when the agent has
     * none bound; never the agent's own id. `ToolExecutionEngine` re-stamps it at dispatch with
     * the executor that actually runs the tool.
     */
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
        override val actorId: AgentId,
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
            override val actorId: AgentId,
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
            override val actorId: AgentId,
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
        override val actorId: AgentId,
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
        override val actorId: AgentId,
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
        override val actorId: AgentId,
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

    companion object {
        /**
         * The [executorId] of a context whose agent has no
         * [Executor][link.socket.ampere.agents.execution.executor.Executor] bound (AMPR-405).
         * Names that fact rather than falling back to the agent's id, which would put the
         * decider back in the performer's field.
         */
        const val NO_EXECUTOR_ID: ExecutorId = "none"
    }

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

/**
 * This context with [executorId] as its performer, every other field unchanged — the same
 * subtype back. `ToolExecutionEngine` stamps the executor that actually runs a tool through
 * this, so a tool reading [ExecutionContext.executorId] and the tool-event pair agree.
 */
fun <C : ExecutionContext> C.withExecutorId(executorId: ExecutorId): C {
    val stamped: ExecutionContext = when (val context: ExecutionContext = this) {
        is ExecutionContext.NoChanges -> context.copy(executorId = executorId)
        is ExecutionContext.Code.ReadCode -> context.copy(executorId = executorId)
        is ExecutionContext.Code.WriteCode -> context.copy(executorId = executorId)
        is ExecutionContext.IssueManagement -> context.copy(executorId = executorId)
        is ExecutionContext.GitOperation -> context.copy(executorId = executorId)
        is ExecutionContext.Planning -> context.copy(executorId = executorId)
    }
    @Suppress("UNCHECKED_CAST")
    return stamped as C
}
