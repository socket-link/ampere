package link.socket.ampere.repl

/**
 * REPL interaction modes (vim-inspired).
 */
enum class Mode {
    /** Command mode - single-key shortcuts active */
    NORMAL,

    /** Insert mode - full command entry */
    INSERT,

    /** Observing mode - watching events or viewing output */
    OBSERVING
}

/**
 * Manages mode transitions and state.
 */
class ModeManager {
    private var currentMode = Mode.INSERT  // Start in insert mode
    private var lastEscapeTime = 0L
    private val doubleEscapeThresholdMs = 300L

    fun getCurrentMode() = currentMode

    fun setMode(mode: Mode) {
        currentMode = mode
    }

    /**
     * Handle Escape key press.
     * Returns true if should trigger emergency exit (double-tap).
     */
    fun handleEscape(): Boolean {
        val now = System.currentTimeMillis()
        val timeSinceLastEscape = now - lastEscapeTime

        return if (timeSinceLastEscape < doubleEscapeThresholdMs) {
            // Double-tap detected
            true
        } else {
            // Single escape - switch to normal mode
            lastEscapeTime = now
            currentMode = Mode.NORMAL
            false
        }
    }

    fun enterInsertMode() {
        currentMode = Mode.INSERT
        lastEscapeTime = 0L
    }
}
