package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand
import link.socket.ampere.repl.ReplSession

/**
 * Launches an interactive REPL session for AMPERE.
 *
 * It keeps the environment running while allowing multiple commands
 * to be executed in sequence.
 */
class InteractiveCommand(
    private val context: AmpereContext
) : CliktCommand(
    name = "interactive",
    help = "Start an interactive REPL session"
) {
    override fun run() {
        val session = ReplSession(context)
        try {
            session.start()
        } finally {
            session.close()
        }
    }
}
