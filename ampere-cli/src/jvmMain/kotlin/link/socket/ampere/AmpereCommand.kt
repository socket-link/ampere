package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand

/**
 * Root command for the Ampere CLI.
 *
 * This is the entry point for all CLI operations. It doesn't perform any
 * actions itself, but serves as a container for subcommands like watch,
 * thread, and status.
 *
 * Command hierarchy:
 * - ampere watch: Watch live event stream
 * - ampere thread: Manage conversation threads
 *   - ampere thread list: List all threads
 *   - ampere thread show: Show thread details
 * - ampere status: View system-wide dashboard
 * - ampere outcomes: View execution outcomes and accumulated experience
 *   - ampere outcomes ticket: Show execution history for a specific ticket
 *   - ampere outcomes search: Find outcomes similar to a description
 *   - ampere outcomes executor: Show outcomes for a specific executor
 *   - ampere outcomes stats: Show aggregate outcome statistics
 */
class AmpereCommand : CliktCommand(
    name = "ampere",
    help = """
        Animated Multi-Agent (Prompting Technique) -> AniMA
        AniMA Model Protocol -> AMP
        AMP Example Runtime Environment -> AMPERE

        AMPERE is a tool for running AniMA simulations in a real-time, observable environment.
    """.trimIndent()
) {
    override fun run() = Unit
}
