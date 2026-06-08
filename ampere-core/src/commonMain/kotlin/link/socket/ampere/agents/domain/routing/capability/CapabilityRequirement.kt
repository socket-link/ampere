package link.socket.ampere.agents.domain.routing.capability

import kotlinx.serialization.Serializable
import link.socket.ampere.domain.ai.model.AIModelFeatures.RelativeReasoning
import link.socket.ampere.domain.ai.model.AIModelFeatures.SupportedInputs

/**
 * A declarative description of what a particular call needs from a provider/model.
 *
 * Routing matches this against the capabilities a candidate model advertises.
 * The quality axes ([minReasoning], [inputs]) reuse the existing model-feature
 * types rather than duplicating them, so a requirement speaks the same vocabulary
 * the model itself does.
 *
 * Every field is optional/empty by default: an empty [CapabilityRequirement]
 * imposes no constraints and matches any model.
 *
 * @property required Capabilities the model must support.
 * @property minReasoning Minimum reasoning level the model must offer, if any.
 * @property minContextTokens Minimum context window the model must provide, if any.
 * @property inputs Input modalities the model must accept, if any.
 */
@Serializable
data class CapabilityRequirement(
    val required: Set<ProviderCapability> = emptySet(),
    val minReasoning: RelativeReasoning? = null,
    val minContextTokens: Int? = null,
    val inputs: SupportedInputs? = null,
)
