package link.socket.ampere.repl

/**
 * Handles command alias expansion for the REPL.
 * Allows short-form commands to be expanded to their full forms.
 */
class AliasExpander(
    private val aliases: Map<String, String> = DEFAULT_ALIASES
) {
    companion object {
        val DEFAULT_ALIASES = mapOf(
            "w" to "watch",
            "s" to "status",
            "t" to "thread",
            "o" to "outcomes",
            "q" to "quit",
            "?" to "help"
        )
    }

    /**
     * Expand command aliases to their full forms.
     * Only expands the first word (command), preserving arguments.
     */
    fun expand(input: String): String {
        val parts = input.split(" ", limit = 2)
        val command = parts[0].lowercase()
        val rest = parts.getOrNull(1) ?: ""

        val expandedCommand = aliases[command] ?: command

        return if (rest.isEmpty()) {
            expandedCommand
        } else {
            "$expandedCommand $rest"
        }
    }

    /**
     * Check if a command is an alias.
     */
    fun isAlias(command: String): Boolean {
        return aliases.containsKey(command.lowercase())
    }

    /**
     * Get all registered aliases.
     */
    fun getAliases(): Map<String, String> = aliases
}
