package link.socket.ampere.domain.limits

import kotlinx.serialization.Serializable

@Serializable
data class RateLimits(
    val tierFree: Tier.FreeTier? = null,
    val tier1: Tier.Tier1? = null,
    val tier2: Tier.Tier2? = null,
    val tier3: Tier.Tier3? = null,
    val tier4: Tier.Tier4? = null,
    val tier5: Tier.Tier5? = null,
)
