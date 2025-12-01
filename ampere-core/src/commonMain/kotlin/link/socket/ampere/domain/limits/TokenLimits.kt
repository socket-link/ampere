package link.socket.ampere.domain.limits

import kotlinx.serialization.Serializable

@Serializable
data class TokenLimits(
    val contextWindow: TokenCount,
    val maxOutput: TokenCount,
)
