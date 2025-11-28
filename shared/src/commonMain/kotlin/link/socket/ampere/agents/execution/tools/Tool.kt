package link.socket.ampere.agents.execution.tools

import link.socket.ampere.agents.core.actions.AgentActionAutonomy
import link.socket.ampere.agents.core.outcomes.Outcome
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest

typealias ToolId = String

/**
 * Base contract for executable tools used by autonomous agents.
 *
 * Implementations should be deterministic and side‑effect aware, returning
 * immutable results via [Outcome].
 */
interface Tool <Context : ExecutionContext> {

    /** Unique identifier for this tool instance. */
    val id: ToolId

    /**
     * Unique title for this tool.
     * Should be stable across versions to allow referencing and auditing.
     */
    val name: String

    /**
     * Human-readable description of what this tool does.
     * Keep concise but specific enough to support selection and auditing.
     */
    val description: String

    /**
     * The minimum autonomy level an agent must have to use this tool without
     * human approval. Agents below this level should request human oversight
     * before execution.
     */
    val requiredAgentAutonomy: AgentActionAutonomy

    /**
     * Executes the tool with the given request parameters.
     *
     * @param executionRequest parameters, context, and instructions for the execution.
     * @return [Outcome] describing success/failure and any resulting payload.
     */
    suspend fun execute(executionRequest: ExecutionRequest<Context>): Outcome
}
