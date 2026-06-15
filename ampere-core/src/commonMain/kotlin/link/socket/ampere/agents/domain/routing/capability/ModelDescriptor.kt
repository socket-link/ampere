package link.socket.ampere.agents.domain.routing.capability

import kotlinx.serialization.Serializable
import link.socket.ampere.domain.ai.model.AIModelFeatures.RelativeReasoning
import link.socket.ampere.domain.ai.model.AIModelFeatures.SupportedInputs
import link.socket.ampere.domain.ai.provider.ProviderId

/**
 * A serializable, SDK-free description of what a single **model** can do and
 * what it costs. Lives parallel to [link.socket.ampere.domain.ai.model.AIModel]
 * (which is intentionally left untouched) so the relay can reason about
 * capability/cost/availability without touching a live client.
 *
 * Selection is keyed by [modelName] (= [link.socket.ampere.domain.ai.model.AIModel.name])
 * because a tier is intrinsically a property of a model, not a provider: a
 * provider's cheapest model may be sub-tier. Re-keying to model granularity lets
 * a `CapabilityRequirement(minReasoning = HIGH)` resolve to a model that is
 * actually HIGH rather than any model from a provider that *offers* a HIGH model
 * (AMPR-214). [providerId] is retained as the owning provider — needed for the
 * local-availability gate, cost derivation, and execution dispatch — but is no
 * longer the selection key.
 *
 * Because no live client is held here, a descriptor round-trips cleanly through
 * serialization — the foundation for a future declarative-config story (no
 * loader is built yet).
 *
 * @property modelName Matches the corresponding `AIModel.name`; the selection key.
 * @property providerId The owning provider's `AIProvider.id`.
 * @property capabilities Discrete capabilities the model advertises.
 * @property reasoning Reasoning level this model offers.
 * @property maxContextTokens Largest context window this model supports.
 * @property supportedInputs Input modalities this model accepts.
 * @property cost How the model charges ([CostPolicy.Metered] vs [CostPolicy.Free]).
 * @property costPerWatt Representative metered generation cost in USD per
 *   normalized Watt (1 Watt = 1000 tokens), the sort key cost-aware routing
 *   minimises (AMPR-210). Ignored for [CostPolicy.Free] models, which always
 *   route at 0 — see [routingCostPerWatt].
 * @property availabilityGated `true` for device-gated local models (read in T4).
 */
@Serializable
data class ModelDescriptor(
    val modelName: String,
    val providerId: ProviderId,
    val capabilities: Set<ProviderCapability>,
    val reasoning: RelativeReasoning,
    val maxContextTokens: Int,
    val supportedInputs: SupportedInputs,
    val cost: CostPolicy = CostPolicy.Metered,
    val costPerWatt: Double = DEFAULT_COST_PER_WATT,
    val availabilityGated: Boolean = false,
    val rung: CapabilityRung = CapabilityRung.ONE,
) {
    companion object {
        /**
         * Neutral mid-tier cloud rate (USD per normalized Watt) used when a
         * descriptor does not specify one. Representative only; the registry
         * seeds real per-provider rates.
         */
        const val DEFAULT_COST_PER_WATT: Double = 0.014
    }
}

/**
 * The cost-per-Watt cost-aware routing ranks this model by: 0 for a
 * [CostPolicy.Free] model (local, on-device, 0W — always wins on price),
 * otherwise its metered [ModelDescriptor.costPerWatt]. This is the one place
 * the "prefer on-device == prefer cheapest" unification lives.
 */
val ModelDescriptor.routingCostPerWatt: Double
    get() = if (cost is CostPolicy.Free) 0.0 else costPerWatt

/**
 * Whether this descriptor can serve the given [req]. A null/empty constraint
 * imposes nothing; every present constraint must hold.
 */
fun ModelDescriptor.satisfies(req: CapabilityRequirement): Boolean =
    req.required.all { it in capabilities } &&
        (req.minReasoning == null || reasoning >= req.minReasoning) &&
        (req.minContextTokens == null || maxContextTokens >= req.minContextTokens) &&
        (req.inputs == null || supportedInputs.covers(req.inputs)) &&
        (req.minRung == null || rung >= req.minRung)

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
