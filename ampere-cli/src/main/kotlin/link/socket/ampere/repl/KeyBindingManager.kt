package link.socket.ampere.repl

import org.jline.reader.LineReader
import org.jline.reader.Widget

/**
 * Manages custom key bindings for the REPL.
 * Provides a clean interface for registering and managing keyboard shortcuts.
 */
class KeyBindingManager(
    private val reader: LineReader
) {
    /**
     * Register a widget with the given name and action.
     */
    fun registerWidget(name: String, action: () -> Boolean) {
        val widget = Widget { action() }
        reader.getWidgets()[name] = widget
    }

    /**
     * Bind a key sequence to a widget.
     * @param widgetName The name of the widget to bind
     * @param keySequence The key sequence (e.g., "\u000C" for Ctrl+L)
     * @param keymap The keymap to bind in (default: "main")
     */
    fun bindKey(widgetName: String, keySequence: String, keymap: String = "main") {
        reader.getKeyMaps()[keymap]?.bind(
            org.jline.reader.Reference(widgetName),
            keySequence
        )
    }

    /**
     * Install default clear screen bindings (Ctrl+L and Ctrl+Space).
     */
    fun installClearScreenBindings(clearScreenAction: () -> Unit) {
        registerWidget("clear-screen-custom") {
            clearScreenAction()
            true
        }

        // Bind Ctrl+L (already bound by default in JLine, but we override it)
        bindKey("clear-screen-custom", "\u000C")

        // Bind Ctrl+Space
        bindKey("clear-screen-custom", "\u0000")
    }

    /**
     * Unbind a key sequence from a keymap.
     */
    fun unbindKey(keySequence: String, keymap: String = "main") {
        reader.getKeyMaps()[keymap]?.unbind(keySequence)
    }

    companion object {
        // Common key sequences
        const val CTRL_L = "\u000C"
        const val CTRL_SPACE = "\u0000"
        const val CTRL_E = "\u0005"
        const val CTRL_D = "\u0004"
        const val CTRL_C = "\u0003"
        const val ESC = "\u001B"
        const val ENTER = "\n"
        const val CARRIAGE_RETURN = "\r"
    }
}
