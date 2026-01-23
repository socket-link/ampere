package link.socket.ampere.domain.llm

/**
 * Type alias for a custom LLM provider function.
 *
 * This allows external systems to inject their own LLM implementation
 * at runtime, enabling:
 * - Testing with mock responses
 * - Integration with custom LLM backends
 * - Prompt interception and transformation
 *
 * The prompt parameter contains the complete context including any
 * system message, formatted as:
 * ```
 * System: <system message>
 *
 * User: <user prompt>
 * ```
 *
 * @param prompt The complete prompt including system context and user message
 * @return The LLM response as a string
 */
typealias LlmProvider = suspend (prompt: String) -> String
