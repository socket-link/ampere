package link.socket.ampere.domain.limits

import kotlinx.serialization.Serializable

@Serializable
data class ModelLimits(
    val rate: RateLimits,
    val token: TokenLimits,
)
