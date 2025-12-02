package link.socket.ampere.repl

/**
 * Simple argument parser that supports both short and long flags.
 *
 * Examples:
 *   --filter TaskCreated  →  filter = "TaskCreated"
 *   -f TaskCreated        →  filter = "TaskCreated"
 *   -f TYPE1 -f TYPE2     →  filter = ["TYPE1", "TYPE2"]
 */
class ArgParser(args: List<String>) {
    private val parsed = mutableMapOf<String, MutableList<String>>()
    private val positional = mutableListOf<String>()

    init {
        parseArgs(args)
    }

    private fun parseArgs(args: List<String>) {
        var i = 0
        while (i < args.size) {
            val arg = args[i]

            when {
                arg.startsWith("--") -> {
                    // Long flag
                    val key = arg.substring(2)
                    val value = if (i + 1 < args.size && !args[i + 1].startsWith("-")) {
                        args[++i]
                    } else {
                        ""  // Boolean flag
                    }
                    parsed.getOrPut(key) { mutableListOf() }.add(value)
                }
                arg.startsWith("-") && arg.length == 2 -> {
                    // Short flag
                    val shortKey = arg.substring(1)
                    val longKey = SHORT_TO_LONG[shortKey] ?: shortKey

                    val value = if (i + 1 < args.size && !args[i + 1].startsWith("-")) {
                        args[++i]
                    } else {
                        ""  // Boolean flag
                    }
                    parsed.getOrPut(longKey) { mutableListOf() }.add(value)
                }
                else -> {
                    // Positional argument
                    positional.add(arg)
                }
            }
            i++
        }
    }

    /**
     * Get first value for a key.
     */
    fun get(key: String): String? = parsed[key]?.firstOrNull()

    /**
     * Get all values for a key (for repeatable flags like -f).
     */
    fun getAll(key: String): List<String> = parsed[key] ?: emptyList()

    /**
     * Get positional arguments.
     */
    fun getPositional(): List<String> = positional

    /**
     * Check if flag is present.
     */
    fun has(key: String): Boolean = parsed.containsKey(key)

    companion object {
        /**
         * Map short flags to long flags.
         */
        private val SHORT_TO_LONG = mapOf(
            "f" to "filter",
            "a" to "agent",
            "p" to "priority",
            "d" to "description",
            "s" to "sender",
            "t" to "title",
            "n" to "limit",
            "h" to "help"
        )

        /**
         * Map long flags to short flags (for help text).
         */
        val LONG_TO_SHORT = SHORT_TO_LONG.entries.associate { it.value to it.key }
    }
}
