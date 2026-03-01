package link.socket.ampere.agents.domain.routing

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.domain.ai.model.AIModelFeatures.RelativeReasoning
import link.socket.ampere.domain.ai.model.AIModelFeatures.RelativeSpeed

/**
 * Context passed to the [CognitiveRelay] for each LLM invocation.
 *
 * Captures what phase the agent is in, who the agent is, and optional
 * task-level hints about desired model characteristics. The relay reads
 * this context to determine where to route the call.
 *
 * @property phase The cognitive phase active during this call, if any.
 * @property agentId The agent making the call.
 * @property agentRole The agent definition name (e.g., "CodeAgent", "ProductAgent").
 * @property workflowId Optional correlation ID for the broader reasoning unit being executed.
 * @property preferredReasoning Hint for desired reasoning level.
 * @property preferredSpeed Hint for desired speed.
 * @property tags Free-form tags for task-based routing (e.g., "code-generation", "summarization").
 */
@Serializable
data class RoutingContext(
    val phase: CognitivePhase? = null,
    val agentId: AgentId? = null,
    val agentRole: String? = null,
    val workflowId: String? = null,
    val preferredReasoning: RelativeReasoning? = null,
    val preferredSpeed: RelativeSpeed? = null,
    val tags: Set<String> = emptySet(),
)
