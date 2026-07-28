package link.socket.ampere.agents.domain.emission

/**
 * Implements [Surface.Console]'s floor behaviour: print the [Emission] to
 * stdout and read the human's reply from stdin.
 *
 * This preserves `ToolAskHuman`'s historical console behaviour, which is
 * what [Surface.Console]'s floor-rule KDoc has always promised. It is a
 * synchronous, blocking operation — callers on the suspend/reply-bus path
 * (such as [EmissionScope.askHuman]) use it for the notification side of a
 * console delivery, not as a replacement for that path's reply mechanism.
 */
object ConsoleSurfaceIO {

    /** Renders [emission] to stdout as a human-readable prompt. */
    fun printPrompt(emission: Emission) {
        println(promptText(emission))
    }

    /** Prints [emission]'s prompt to stdout, then blocks reading a line of reply from stdin. */
    fun promptAndAwaitReply(emission: Emission): String {
        printPrompt(emission)
        return readlnOrNull().orEmpty()
    }

    private fun promptText(emission: Emission): String {
        val body = when (val payload = emission.payload) {
            is EmissionPayload.Prose -> payload.text
            is EmissionPayload.Decision -> listOfNotNull(payload.prompt, payload.context).joinToString("\n")
            is EmissionPayload.Confirmation -> listOfNotNull(payload.action, payload.preview).joinToString("\n")
            is EmissionPayload.Sensor -> "${payload.label}: ${payload.value}${payload.unit?.let { " $it" }.orEmpty()}"
        }
        if (emission.affordances.isEmpty()) {
            return body
        }
        val options = emission.affordances.joinToString("\n") { "  - ${it.label}" }
        return "$body\n$options"
    }
}
