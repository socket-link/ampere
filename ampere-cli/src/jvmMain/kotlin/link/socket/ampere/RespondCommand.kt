package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.help
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.emission.GlobalEmissionReplyRegistry
import link.socket.ampere.agents.domain.event.EmissionEvent
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.util.randomUUID
import kotlinx.serialization.json.JsonPrimitive

/**
 * CLI command to respond to human input requests from agents.
 *
 * When an agent invokes [ToolAskHuman], it publishes a
 * [link.socket.ampere.agents.domain.event.HumanInteractionEvent.InputRequested] and
 * displays the Emission ID (labelled "Emission ID" in the console banner). This
 * command delivers the human's response by constructing an
 * [EmissionEvent.BaseResolved] and delivering it directly to
 * [GlobalEmissionReplyRegistry], resuming the suspended agent coroutine.
 *
 * Usage:
 *   ampere respond <emission-id> "<response text>"
 */
class RespondCommand : CliktCommand(
    name = "respond",
    help = """
        Provide a response to an agent's human input request.

        When an agent asks for human input it prints an Emission ID.
        Use this command to provide your response and unblock the agent.

        Examples:
          ampere respond <emission-id> "Approved"
          ampere respond <emission-id> "Use the staging environment instead"
    """.trimIndent(),
) {

    private val emissionId by argument()
        .help("The Emission ID displayed by the agent")

    private val response by argument()
        .help("Your response text (quote if it contains spaces)")

    override fun run() {
        val registry = GlobalEmissionReplyRegistry.instance

        val pending = registry.getPendingEmissionIds()
        if (emissionId !in pending) {
            echo("Error: Emission ID '$emissionId' not found", err = true)
            echo("")
            echo("Pending emissions:")
            if (pending.isEmpty()) {
                echo("  (none)")
            } else {
                pending.forEach { id -> echo("  - $id") }
            }
            return
        }

        val freeTextPayload = kotlinx.serialization.json.JsonObject(
            mapOf(
                "type" to JsonPrimitive("free-text"),
                "text" to JsonPrimitive(response),
            ),
        )

        val resolved = EmissionEvent.BaseResolved(
            eventId = randomUUID(),
            timestamp = Clock.System.now(),
            eventSource = EventSource.Human,
            urgency = Urgency.HIGH,
            emissionId = emissionId,
            affordanceId = "free-text-response",
            replyContext = freeTextPayload,
        )

        val delivered = registry.deliver(resolved)
        if (delivered) {
            echo("Response delivered successfully!")
            echo("")
            echo("Emission ID : $emissionId")
            echo("Response    : $response")
            echo("")
            echo("The agent will now continue execution with your input.")
        } else {
            echo("Error: Failed to deliver response (emission may have timed out)", err = true)
        }
    }
}
