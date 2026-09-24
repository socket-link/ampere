package link.socket.ampere.agents.execution.tools

import kotlin.time.Duration.Companion.minutes
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.Principal
import link.socket.ampere.agents.domain.emission.EmissionReplyRegistry
import link.socket.ampere.agents.domain.emission.EmissionTimeout
import link.socket.ampere.agents.domain.emission.GlobalEmissionReplyRegistry
import link.socket.ampere.agents.domain.emission.emission
import link.socket.ampere.agents.domain.emission.extractFreeText
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.HumanInteractionEvent
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.execution.ParameterStrategy
import link.socket.ampere.agents.execution.request.ExecutionContext

const val ASK_HUMAN_TOOL_ID: String = "ask_human"

/**
 * Creates a FunctionTool that asks a human for guidance via the Emission DSL.
 *
 * Publishes a [HumanInteractionEvent.InputRequested] through the door [eventApi]
 * — persisted, then dispatched — and suspends the calling coroutine until the
 * human replies (or the 30-minute default timeout fires). The [onInputRequested]
 * callback runs synchronously after the event is published, before suspension —
 * use it for surface-specific side effects such as printing a console banner.
 *
 * The Emission is attributed to the run named by [ExecutionRequest.runId]
 * (AMPR-351), so the human reply it collects can be traced back to the Arc run
 * that asked for it. The tool is built once per agent and reused across runs, so
 * the run is read per call off the request rather than captured here.
 *
 * @param requiredAgentAutonomy Minimum autonomy level required to use this tool.
 * @param eventApi The door emission events are published through; replies are
 *   awaited on its bus. Its clock stamps the outcome timestamps.
 * @param replyRegistry Registry that correlates pending emissions with replies;
 *   defaults to the process-wide [GlobalEmissionReplyRegistry].
 * @param onInputRequested Callback invoked with the published event before the
 *   coroutine suspends; defaults to a no-op (surfaces wire their own handlers).
 * @param parameterStrategy Optional tool-owned strategy for parameter generation.
 */
fun ToolAskHuman(
    requiredAgentAutonomy: AgentActionAutonomy,
    eventApi: AgentEventApi,
    replyRegistry: EmissionReplyRegistry = GlobalEmissionReplyRegistry.instance,
    onInputRequested: suspend (HumanInteractionEvent.InputRequested) -> Unit = {},
    parameterStrategy: ParameterStrategy? = null,
): FunctionTool<ExecutionContext.NoChanges> = FunctionTool(
    id = ASK_HUMAN_TOOL_ID,
    name = NAME,
    description = DESCRIPTION,
    requiredAgentAutonomy = requiredAgentAutonomy,
    parameterStrategy = parameterStrategy,
    executionFunction = { executionRequest ->
        val context = executionRequest.context
        val startTimestamp = eventApi.clock.now()
        val eventSource = EventSource.Agent(context.executorId)

        try {
            // A root: the tool asks on behalf of a task, not of another Emission. No principal
            // reaches tool dispatch yet, so this is ambient authority until D5 decides otherwise.
            // The run comes off the request (AMPR-351) — it is the only thing the dispatch path
            // hands this function — so an ask made inside an Arc run is linked back to it, and one
            // made outside a run stays honestly unattributed.
            val reply = emission(
                eventSource = eventSource,
                eventApi = eventApi,
                replyRegistry = replyRegistry,
                runId = executionRequest.runId,
                principal = Principal.Ambient,
                parentEmissionId = null,
            ) {
                askHuman(
                    prompt = context.instructions,
                    agentId = context.executorId,
                    ticketId = context.ticket.id,
                    taskId = context.task.id,
                    timeout = 30.minutes,
                    onProduced = onInputRequested,
                )
            }

            val text = extractFreeText(reply.replyContext)
                ?: reply.replyContext?.toString()
                ?: "no response"

            ExecutionOutcome.NoChanges.Success(
                executorId = context.executorId,
                ticketId = context.ticket.id,
                taskId = context.task.id,
                executionStartTimestamp = startTimestamp,
                executionEndTimestamp = eventApi.clock.now(),
                message = "Human response: $text",
            )
        } catch (e: EmissionTimeout) {
            ExecutionOutcome.NoChanges.Failure(
                executorId = context.executorId,
                ticketId = context.ticket.id,
                taskId = context.task.id,
                executionStartTimestamp = startTimestamp,
                executionEndTimestamp = eventApi.clock.now(),
                message = "Human input request timed out after ${e.timeout}",
            )
        }
    },
)

private const val NAME = "Ask a Human"
private const val DESCRIPTION = "Escalates uncertainty to human for guidance."
