package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands

fun main(args: Array<String>) = AmpereCommand()
    .subcommands(WatchCommand())
    .main(args)

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
