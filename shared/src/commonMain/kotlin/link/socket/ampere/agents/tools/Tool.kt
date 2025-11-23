package link.socket.ampere.agents.tools

import link.socket.ampere.agents.core.AutonomyLevel
import link.socket.ampere.agents.core.Outcome
import link.socket.ampere.agents.events.tasks.Task

typealias ToolId = String

/**
 * Base contract for executable tools used by autonomous agents.
 *
 * Implementations should be deterministic and side‑effect aware, returning
 * immutable results via [Outcome]. Tools must also declare the minimum
 * [requiredAutonomyLevel] an agent should have to use them safely.
 */
interface Tool {

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
    val requiredAutonomyLevel: AutonomyLevel

    /**
     * Execute the tool with the given parameters.
     *
     * Implementations should handle errors gracefully and return an [Outcome]
     * indicating success or failure, avoiding exceptions for expected error
     * conditions. The returned [Outcome.result] should be an immutable value
     * or data structure.
     *
     * @param parameters Arbitrary key-value pairs required for execution.
     * @return [Outcome] describing success/failure and any resulting payload.
     */
    suspend fun execute(
        sourceTask: Task,
        parameters: Map<String, Any?>,
    ): Outcome

    /**
     * Validate input parameters prior to execution.
     *
     * Implementations should perform lightweight checks only (presence, type/
     * shape validation) and avoid heavy I/O. Detailed validation errors should
     * be surfaced via [execute] in the returned [Outcome] when appropriate.
     *
     * @param parameters Arbitrary key-value pairs required for execution.
     * @return true if parameters appear valid for execution; false otherwise.
     */
    fun validateParameters(parameters: Map<String, Any>): Boolean
}
