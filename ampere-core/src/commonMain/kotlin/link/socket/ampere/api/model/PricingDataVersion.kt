package link.socket.ampere.api.model

import kotlinx.serialization.Serializable

/**
 * Version metadata for bundled pricing data plus any consumer overrides.
 */
@link.socket.ampere.api.AmpereStableApi
@Serializable
data class PricingDataVersion(
    val version: Int,
    val currency: String,
    val publishedAt: String? = null,
    val overridesApplied: Int = 0,
)
