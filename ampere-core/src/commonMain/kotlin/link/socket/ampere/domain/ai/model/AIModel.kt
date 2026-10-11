@file:Suppress("ClassName")

package link.socket.ampere.domain.ai.model

import kotlinx.serialization.Serializable
import link.socket.ampere.domain.ai.provider.ProviderId
import link.socket.ampere.domain.limits.ModelLimits

@Serializable
sealed class AIModel(
    open val name: String,
    open val displayName: String,
    open val description: String,
    open val features: AIModelFeatures,
    open val limits: ModelLimits,
) {

    /**
     * The id of the provider that makes this model — Anthropic for Claude — which is
     * not necessarily the route a call to it travels: a configuration's provider may
     * be a gateway that reaches the same model through another key (AMPR-434).
     *
     * Overridden once per family as a getter, so it has no backing field and never
     * enters the serialized form.
     */
    abstract val makerId: ProviderId
}
