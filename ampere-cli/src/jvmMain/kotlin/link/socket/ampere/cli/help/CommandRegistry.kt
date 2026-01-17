package link.socket.ampere.cli.help

/**
 * Single source of truth for all CLI commands and keyboard shortcuts.
 *
 * This registry drives:
 * - Help overlay (press 'h')
 * - Command mode help (:help)
 * - Status bar shortcuts
 *
 * To add a new command or shortcut, add it here and it will automatically
 * appear in all help displays.
 */
object CommandRegistry {

    /**
     * Definition of a command available in command mode (:).
     */
    data class CommandDefinition(
        val name: String,
        val aliases: List<String>,
        val description: String,
        val usage: String?,
        val category: CommandCategory = CommandCategory.GENERAL,
    ) {
        /**
         * Format the command for help display.
         * e.g., ":help, :h, :?" or ":goal <description>"
         */
        fun formatForHelp(): String {
            val names = listOf(name) + aliases
            val namesPart = names.joinToString(", ") { ":$it" }
            return if (usage != null) "$namesPart $usage" else namesPart
        }
    }

    /**
     * Category for grouping commands in help output.
     */
    enum class CommandCategory {
        GENERAL,
        NAVIGATION,
        GITHUB,
    }

    /**
     * Definition of a keyboard shortcut.
     */
    data class ShortcutDefinition(
        val key: Char,
        val label: String,
        val description: String,
        val alternateKey: Char? = null,
        val category: ShortcutCategory = ShortcutCategory.OPTIONS,
    ) {
        /**
         * Format the key for help display.
         * e.g., "h  or  ?" or just "d"
         */
        fun formatKeyForHelp(): String {
            return if (alternateKey != null) {
                "$key  or  $alternateKey"
            } else {
                key.toString()
            }
        }
    }

    /**
     * Category for grouping shortcuts in help output.
     */
    enum class ShortcutCategory {
        VIEWING_MODES,
        OPTIONS,
    }

    /**
     * All available commands.
     */
    val commands: List<CommandDefinition> = listOf(
        CommandDefinition(
            name = "help",
            aliases = listOf("h", "?"),
            description = "Show available commands",
            usage = null,
        ),
        CommandDefinition(
            name = "agents",
            aliases = emptyList(),
            description = "List all active agents",
            usage = null,
        ),
        CommandDefinition(
            name = "goal",
            aliases = emptyList(),
            description = "Set an autonomous goal for the agent",
            usage = "<description>",
        ),
        CommandDefinition(
            name = "issues",
            aliases = emptyList(),
            description = "Create GitHub issues from .ampere/issues/",
            usage = "create <filename>",
            category = CommandCategory.GITHUB,
        ),
        CommandDefinition(
            name = "ticket",
            aliases = emptyList(),
            description = "Show ticket details",
            usage = "<id>",
        ),
        CommandDefinition(
            name = "thread",
            aliases = emptyList(),
            description = "Show conversation thread",
            usage = "<id>",
        ),
        CommandDefinition(
            name = "sparks",
            aliases = listOf("spark", "cognitive"),
            description = "Inspect agent's Spark stack and cognitive context",
            usage = "[agent-name]",
        ),
        CommandDefinition(
            name = "quit",
            aliases = listOf("q", "exit"),
            description = "Exit dashboard",
            usage = null,
        ),
    )

    /**
     * All keyboard shortcuts.
     */
    val shortcuts: List<ShortcutDefinition> = listOf(
        // Viewing modes
        ShortcutDefinition(
            key = 'd',
            label = "dashboard",
            description = "Dashboard - System vitals, agent status, recent events",
            category = ShortcutCategory.VIEWING_MODES,
        ),
        ShortcutDefinition(
            key = 'e',
            label = "events",
            description = "Event Stream - Filtered stream of significant events",
            category = ShortcutCategory.VIEWING_MODES,
        ),
        ShortcutDefinition(
            key = 'm',
            label = "memory",
            description = "Memory Ops - Knowledge recall/storage patterns",
            category = ShortcutCategory.VIEWING_MODES,
        ),
        ShortcutDefinition(
            key = '1',
            label = "1-9",
            description = "Agent Focus - Detailed view of specific agent",
            category = ShortcutCategory.VIEWING_MODES,
        ),
        // Options
        ShortcutDefinition(
            key = 'a',
            label = "agent",
            description = "Agent selection mode (then press 1-9)",
            category = ShortcutCategory.OPTIONS,
        ),
        ShortcutDefinition(
            key = 'v',
            label = "verbose",
            description = "Toggle verbose mode (show/hide routine events)",
            category = ShortcutCategory.OPTIONS,
        ),
        ShortcutDefinition(
            key = ':',
            label = "command",
            description = "Command mode - Issue commands to the system",
            category = ShortcutCategory.OPTIONS,
        ),
        ShortcutDefinition(
            key = 'h',
            label = "help",
            description = "Toggle this help screen",
            alternateKey = '?',
            category = ShortcutCategory.OPTIONS,
        ),
        ShortcutDefinition(
            key = 'q',
            label = "quit",
            description = "Exit AMPERE dashboard",
            category = ShortcutCategory.OPTIONS,
        ),
    )

    /**
     * Get shortcuts for a specific category.
     */
    fun shortcutsByCategory(category: ShortcutCategory): List<ShortcutDefinition> {
        return shortcuts.filter { it.category == category }
    }

    /**
     * Get commands for a specific category.
     */
    fun commandsByCategory(category: CommandCategory): List<CommandDefinition> {
        return commands.filter { it.category == category }
    }

    /**
     * Find a command by name or alias.
     */
    fun findCommand(nameOrAlias: String): CommandDefinition? {
        val lower = nameOrAlias.lowercase()
        return commands.find { cmd ->
            cmd.name == lower || cmd.aliases.any { it == lower }
        }
    }

    /**
     * Get shortcuts suitable for status bar display.
     * Returns only single-key shortcuts (not ranges like 1-9).
     */
    fun statusBarShortcuts(): List<ShortcutDefinition> {
        return shortcuts.filter { it.key != '1' && it.key != ':' }
    }
}
