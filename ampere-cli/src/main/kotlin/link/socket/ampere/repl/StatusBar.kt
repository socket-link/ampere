package link.socket.ampere.repl

import org.jline.terminal.Terminal

/**
 * Renders a simple prompt for the REPL.
 */
class StatusBar(
    private val terminal: Terminal,
) {
    /**
     * Render the prompt.
     */
    fun render() {
        val prompt = TerminalColors.highlight("⚡") + " "
        with(terminal.writer()) {
            print(prompt)
            flush()
        }
    }
}
