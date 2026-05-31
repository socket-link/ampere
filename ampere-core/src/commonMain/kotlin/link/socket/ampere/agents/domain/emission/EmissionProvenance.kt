package link.socket.ampere.agents.domain.emission

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.WorkflowId
import link.socket.ampere.agents.domain.event.EventId

/**
 * Where this [Emission] came from. Every Emission carries provenance — that
 * is what makes it auditable downstream.
 *
 *  - `runId` / `workflowId` link the Emission to the Arc and reasoning unit
 *    that produced it.
 *  - `sourceEventId` records the event that motivated the Emission (for
 *    example, the tool result that prompted a Confirmation).
 *  - `toolInvocationId`, `pluginId`, `modelId` are populated when the
 *    Emission can be attributed to a specific tool call, plugin, or model.
 *  - `inputDigest` is the deterministic SHA-256 hash of the payload (see
 *    [inputDigest]) and lets consumers reason about content identity
 *    without re-hashing.
 */
@Serializable
data class EmissionProvenance(
    val runId: RunId? = null,
    val workflowId: WorkflowId? = null,
    val sourceEventId: EventId? = null,
    val toolInvocationId: String? = null,
    val pluginId: String? = null,
    val modelId: String? = null,
    val inputDigest: String,
)
