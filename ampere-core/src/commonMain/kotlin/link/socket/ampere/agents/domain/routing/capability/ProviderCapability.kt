package link.socket.ampere.agents.domain.routing.capability

import kotlinx.serialization.Serializable

/**
 * A discrete capability a provider/model can offer, used by routing to match a
 * call against models that can actually serve it.
 *
 * This is a *provider* capability axis and is intentionally distinct from the
 * agent-tool [link.socket.ampere.domain.capability.Capability] hierarchy — the
 * two describe different things (what a model can do vs. what an agent is
 * allowed to do) and must not be conflated.
 *
 * Matching logic, descriptors, and relay consumers land in later tasks; this
 * enum is pure vocabulary.
 */
@Serializable
enum class ProviderCapability {
    STRUCTURED_OUTPUT,
    TOOL_CALLING,
    WORLD_KNOWLEDGE,
    LONG_CONTEXT,
    MULTIMODAL_INPUT,
    STREAMING,
}
