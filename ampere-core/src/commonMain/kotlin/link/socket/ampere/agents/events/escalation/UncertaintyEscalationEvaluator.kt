package link.socket.ampere.agents.events.escalation

import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.event.CognitiveEvent
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.utils.generateUUID

/**
 * Evaluates uncertainty against an escalation threshold and publishes:
 * - [CognitiveEvent.EscalationConsidered] on every evaluation (high-volume telemetry)
 * - [CognitiveEvent.EscalationFired] when the uncertainty meets or exceeds the threshold
 *
 * When both events publish, `EscalationConsidered` is emitted first so consumers tracking
 * near-misses see the causal order at the publish site. Bus dispatch is concurrent, so
 * subscribers cannot rely on handler-level cross-event ordering.
 */
class UncertaintyEscalationEvaluator(
    private val agentId: AgentId,
    private val publishEscalationConsidered: suspend (CognitiveEvent.EscalationConsidered) -> Unit,
    private val publishEscalationFired: suspend (CognitiveEvent.EscalationFired) -> Unit,
    private val clock: Clock = Clock.System,
) {

    constructor(
        agentEventApi: AgentEventApi,
        clock: Clock = Clock.System,
    ) : this(
        agentId = agentEventApi.agentId,
        publishEscalationConsidered = { event -> agentEventApi.publish(event) },
        publishEscalationFired = { event -> agentEventApi.publish(event) },
        clock = clock,
    )

    /**
     * @return true when the threshold fired and an [CognitiveEvent.EscalationFired] was published.
     *   An [CognitiveEvent.EscalationConsidered] is always published, regardless of return value.
     */
    suspend fun evaluate(
        uncertaintyValue: Double,
        threshold: Double,
        prompt: String,
        cognitivePhase: CognitivePhase? = null,
        urgency: Urgency = Urgency.HIGH,
    ): Boolean {
        require(uncertaintyValue in UNCERTAINTY_RANGE) {
            "uncertaintyValue must be in [0.0, 1.0], was $uncertaintyValue"
        }
        require(threshold in UNCERTAINTY_RANGE) {
            "threshold must be in [0.0, 1.0], was $threshold"
        }

        val fired = uncertaintyValue >= threshold
        val source = EventSource.Agent(agentId)
        val consideredTimestamp = clock.now()

        publishEscalationConsidered(
            CognitiveEvent.EscalationConsidered(
                eventId = generateUUID(agentId),
                timestamp = consideredTimestamp,
                eventSource = source,
                agentId = agentId,
                uncertaintyValue = uncertaintyValue,
                threshold = threshold,
                fired = fired,
                cognitivePhase = cognitivePhase,
            ),
        )

        if (!fired) {
            return false
        }

        publishEscalationFired(
            CognitiveEvent.EscalationFired(
                eventId = generateUUID(agentId),
                timestamp = clock.now(),
                eventSource = source,
                urgency = urgency,
                agentId = agentId,
                uncertaintyValue = uncertaintyValue,
                threshold = threshold,
                prompt = prompt,
                cognitivePhase = cognitivePhase,
            ),
        )

        return true
    }

    private companion object {
        val UNCERTAINTY_RANGE = 0.0..1.0
    }
}
