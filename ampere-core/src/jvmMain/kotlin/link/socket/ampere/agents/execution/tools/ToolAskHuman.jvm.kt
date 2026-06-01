package link.socket.ampere.agents.execution.tools

import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.emission.EmissionReplyRegistry
import link.socket.ampere.agents.domain.emission.GlobalEmissionReplyRegistry
import link.socket.ampere.agents.domain.event.HumanInteractionEvent
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.execution.ParameterStrategy

/**
 * Creates a JVM / CLI-aware [ToolAskHuman] that prints a console banner when
 * human input is needed. This is the [Surface.Console] floor-surface implementation
 * preserved from the legacy `ToolAskHuman.jvm.kt` behaviour.
 *
 * The banner displays the emission ID (the key humans use with `ampere respond`),
 * ticket, task, and the question text. After the banner is printed the coroutine
 * suspends until [GlobalEmissionReplyRegistry] delivers a reply or the 30-minute
 * timeout fires.
 *
 * On non-JVM platforms the default [ToolAskHuman] factory (no console callback) is used.
 */
fun ToolAskHumanJvm(
    requiredAgentAutonomy: AgentActionAutonomy,
    eventSerialBus: EventSerialBus,
    replyRegistry: EmissionReplyRegistry = GlobalEmissionReplyRegistry.instance,
    parameterStrategy: ParameterStrategy? = null,
): FunctionTool<link.socket.ampere.agents.execution.request.ExecutionContext.NoChanges> = ToolAskHuman(
    requiredAgentAutonomy = requiredAgentAutonomy,
    eventSerialBus = eventSerialBus,
    replyRegistry = replyRegistry,
    parameterStrategy = parameterStrategy,
    onInputRequested = { event ->
        printConsoleHumanInputBanner(event)
    },
)

private fun printConsoleHumanInputBanner(event: HumanInteractionEvent.InputRequested) {
    val payload = event.emission.payload as? link.socket.ampere.agents.domain.emission.EmissionPayload.Decision
    val question = payload?.prompt ?: "(no prompt)"

    println(
        """
        ════════════════════════════════════════
        HUMAN INPUT REQUIRED
        ════════════════════════════════════════
        Emission ID : ${event.emission.id}
        Request ID  : ${event.requestId}
        Agent       : ${event.agentId}
        Ticket      : ${event.ticketId ?: "n/a"}
        Task        : ${event.taskId ?: "n/a"}

        Question: $question

        To respond, use:
          ./ampere-cli/ampere respond ${event.emission.id} "<your response>"
        ════════════════════════════════════════
        """.trimIndent(),
    )
}
