package link.socket.ampere.agents.domain.cognition

/**
 * Type alias for tool identifiers.
 */
typealias ToolId = String

/**
 * A Spark is a specialization layer that narrows an agent's cognitive focus.
 *
 * Rather than discrete agent types, the Spark system uses a cellular differentiation model
 * where a single Agent class accumulates specialization through Spark layers. Each Spark
 * is a filter that:
 * - Adds context to the system prompt
 * - Potentially constrains available tools
 * - Potentially constrains file access patterns
 *
 * Sparks can only narrow capabilities, never expand them. This is an architectural
 * invariant that ensures child contexts can never exceed parent permissions.
 *
 * The system prompt is dynamically rebuilt from the accumulated Spark stack before
 * each LLM interaction, allowing agents to specialize their behavior without
 * requiring separate class implementations.
 *
 * Note: This is not a sealed interface to allow implementations in subpackages.
 * Concrete implementations should be serializable with appropriate @SerialName annotations.
 */
interface Spark {
    /**
     * Human-readable identifier for observability and debugging.
     * Format: "Type:Subtype" (e.g., "Role:Code", "Project:ampere")
     */
    val name: String

    /**
     * The markdown content this Spark adds to the system prompt.
     * This should provide context and instructions relevant to this
     * specialization layer.
     */
    val promptContribution: String

    /**
     * Optional set of tool IDs this Spark permits.
     *
     * - If null: inherits from parent context (no change to available tools)
     * - If specified: narrows the available tools to this set
     *
     * When multiple Sparks specify tools, the effective set is the intersection
     * of all specified sets.
     */
    val allowedTools: Set<ToolId>?

    /**
     * Optional file access scope this Spark permits.
     *
     * - If null: inherits from parent context (no change to file access)
     * - If specified: narrows file access to these patterns
     *
     * Uses intersection semantics for read/write and union for forbidden.
     */
    val fileAccessScope: FileAccessScope?
}
