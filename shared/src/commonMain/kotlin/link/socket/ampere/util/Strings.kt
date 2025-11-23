package link.socket.ampere.util

/**
 * Extension function to convert a string from "Agent Conversation" format to "agent_conversation" format.
 * This function transforms the string to lowercase and replaces spaces with underscores.
 *
 * Example:
 * ```
 * val originalString = "Agent Conversation"
 * val snakeCaseString = originalString.toSnakeCase()
 * println(snakeCaseString) // Output: agent_conversation
 * ```
 *
 * @receiver String The original string to be converted.
 * @return String The converted string in snake_case format.
 */
fun String.toSnakeCase(): String =
    lowercase().replace(" ", "_")
