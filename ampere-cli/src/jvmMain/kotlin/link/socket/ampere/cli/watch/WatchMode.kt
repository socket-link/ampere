package link.socket.ampere.cli.watch

/**
 * The different viewing modes available in the interactive watch interface.
 *
 * Following the vim-inspired modal pattern, users can switch between these
 * modes using keyboard shortcuts to focus on different aspects of the system.
 */
enum class WatchMode {
    /** Live-updating dashboard showing system vitals, agent status, and recent events */
    DASHBOARD,

    /** Filtered event stream showing significant events */
    EVENT_STREAM,

    /** Focus on a specific agent's activity */
    AGENT_FOCUS,

    /** Memory operations view showing knowledge recall/storage patterns */
    MEMORY_OPS,

    /** Help overlay showing keyboard shortcuts and available commands */
    HELP,

    /** Command mode for issuing commands to the system */
    COMMAND
}

/**
 * Configuration for the current watch view.
 */
data class WatchViewConfig(
    val mode: WatchMode = WatchMode.DASHBOARD,
    val verboseMode: Boolean = false,
    val focusedAgentId: String? = null,
    val showHelp: Boolean = false,
    val commandInput: String = ""
)
