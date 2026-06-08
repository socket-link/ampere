package link.socket.ampere.agents.domain.routing.capability

import kotlinx.serialization.Serializable
import link.socket.ampere.domain.ai.model.AIModelFeatures.RelativeReasoning
import link.socket.ampere.domain.ai.model.AIModelFeatures.SupportedInputs
import link.socket.ampere.domain.ai.provider.ProviderId

/**
 * A serializable, SDK-free description of what a provider can do and what it
 * costs. Lives parallel to [link.socket.ampere.domain.ai.provider.AIProvider]
 * (which is intentionally left untouched) so the relay can reason about
 * capability/cost/availability without touching a live client.
 *
 * Because no live client is held here, a descriptor round-trips cleanly through
 * serialization — the foundation for a future declarative-config story (no
 * loader is built yet).
 *
 * @property providerId Matches the corresponding `AIProvider.id`.
 * @property capabilities Discrete capabilities the provider advertises.
 * @property reasoning Reasoning level the provider's models offer.
 * @property maxContextTokens Largest context window the provider supports.
 * @property supportedInputs Input modalities the provider accepts.
 * @property cost How the provider charges (defaults to [CostPolicy.Metered]).
 * @property availabilityGated `true` for device-gated local providers (read in T4).
 */
@Serializable
data class ProviderDescriptor(
    val providerId: ProviderId,
    val capabilities: Set<ProviderCapability>,
    val reasoning: RelativeReasoning,
    val maxContextTokens: Int,
    val supportedInputs: SupportedInputs,
    val cost: CostPolicy = CostPolicy.Metered,
    val availabilityGated: Boolean = false,
)

/**
 * Whether this descriptor can serve the given [req]. A null/empty constraint
 * imposes nothing; every present constraint must hold.
 */
fun ProviderDescriptor.satisfies(req: CapabilityRequirement): Boolean =
    req.required.all { it in capabilities } &&
        (req.minReasoning == null || reasoning >= req.minReasoning) &&
        (req.minContextTokens == null || maxContextTokens >= req.minContextTokens) &&
        (req.inputs == null || supportedInputs.covers(req.inputs))

/**
 * Whether these inputs cover everything [required] asks for: for each modality
 * the requirement needs, this set must also support it.
 */
private fun SupportedInputs.covers(required: SupportedInputs): Boolean =
    (!required.audio || audio) &&
        (!required.image || image) &&
        (!required.pdf || pdf) &&
        (!required.text || text) &&
        (!required.video || video)
